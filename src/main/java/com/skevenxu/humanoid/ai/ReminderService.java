package com.skevenxu.humanoid.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.skevenxu.humanoid.entity.BotConversation;
import com.skevenxu.humanoid.entity.BotReminder;
import com.skevenxu.humanoid.llbot.LlbotApiClient;
import com.skevenxu.humanoid.mapper.BotConversationMapper;
import com.skevenxu.humanoid.mapper.BotReminderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/** 扫描到期的定时提醒并通过 llbot 发送。 */
@Service
public class ReminderService {

    private static final Logger log = LoggerFactory.getLogger(ReminderService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final BotReminderMapper reminderMapper;
    private final BotConversationMapper conversationMapper;
    private final ContextStore contextStore;
    private final LlbotApiClient llbotApiClient;
    private final int maxAttempts;

    public ReminderService(BotReminderMapper reminderMapper,
                           BotConversationMapper conversationMapper,
                           ContextStore contextStore,
                           LlbotApiClient llbotApiClient,
                           @Value("${humanoid.reminder.max-attempts:3}") int maxAttempts) {
        this.reminderMapper = reminderMapper;
        this.conversationMapper = conversationMapper;
        this.contextStore = contextStore;
        this.llbotApiClient = llbotApiClient;
        this.maxAttempts = maxAttempts;
    }

    @Scheduled(cron = "${humanoid.reminder.cron:*/30 * * * * *}")
    public void dispatch() {
        LocalDateTime now = LocalDateTime.now(ZONE);
        List<BotReminder> due = reminderMapper.selectList(new LambdaQueryWrapper<BotReminder>()
                .eq(BotReminder::getStatus, BotReminder.STATUS_PENDING)
                .le(BotReminder::getRemindAt, now)
                .orderByAsc(BotReminder::getRemindAt)
                .last("LIMIT 20"));
        log.info("提醒扫描触发, 到期任务={}", due.size());
        for (BotReminder reminder : due) {
            send(reminder, now);
        }
    }

    private void send(BotReminder reminder, LocalDateTime now) {
        Optional<Long> messageId = "group".equals(reminder.getMessageType())
                ? llbotApiClient.sendGroup(reminder.getGroupId(), reminder.getContent())
                : llbotApiClient.sendPrivate(reminder.getUserId(), reminder.getContent());

        int attempts = (reminder.getAttempts() == null ? 0 : reminder.getAttempts()) + 1;
        reminder.setAttempts(attempts);
        if (messageId.isEmpty()) {
            if (attempts >= maxAttempts) {
                reminder.setStatus(BotReminder.STATUS_FAILED);
            }
            reminderMapper.updateById(reminder);
            log.warn("提醒发送失败 id={} attempts={}", reminder.getId(), attempts);
            return;
        }

        reminder.setStatus(BotReminder.STATUS_SENT);
        reminder.setSentAt(now);
        reminderMapper.updateById(reminder);
        saveBotMessage(reminder, messageId.get());
        log.info("提醒已发送 id={} user_id={} group_id={} message_id={}",
                reminder.getId(), reminder.getUserId(), reminder.getGroupId(), messageId.get());
    }

    private void saveBotMessage(BotReminder reminder, Long messageId) {
        BotConversation bot = new BotConversation();
        bot.setUserId(reminder.getUserId());
        bot.setRole("bot");
        bot.setMessageType(reminder.getMessageType());
        if ("group".equals(reminder.getMessageType())) {
            bot.setGroupId(reminder.getGroupId());
        }
        bot.setOnebotMessageId(messageId);
        bot.setContent(reminder.getContent());
        bot.setEventTime(LocalDateTime.now(ZONE));
        bot.setCreatedAt(LocalDateTime.now(ZONE));
        conversationMapper.insert(bot);
        contextStore.appendAssistant(reminder.getUserId(), reminder.getMessageType(),
                reminder.getGroupId(), reminder.getContent(), java.util.List.of());
    }
}
