package com.skevenxu.humanoid.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.skevenxu.humanoid.entity.BotConversation;
import com.skevenxu.humanoid.entity.BotReminder;
import com.skevenxu.humanoid.mapper.BotConversationMapper;
import com.skevenxu.humanoid.mapper.BotReminderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** 扫描到期的定时提醒，把预设提示词交给主 AI 执行并回复。 */
@Service
public class ReminderService {

    private static final Logger log = LoggerFactory.getLogger(ReminderService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final BotReminderMapper reminderMapper;
    private final BotConversationMapper conversationMapper;
    private final ContextStore contextStore;
    private final AiChatService aiChatService;
    private final int maxAttempts;

    public ReminderService(BotReminderMapper reminderMapper,
                           BotConversationMapper conversationMapper,
                           ContextStore contextStore,
                           AiChatService aiChatService,
                           @Value("${humanoid.reminder.max-attempts:3}") int maxAttempts) {
        this.reminderMapper = reminderMapper;
        this.conversationMapper = conversationMapper;
        this.contextStore = contextStore;
        this.aiChatService = aiChatService;
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
            if (!claim(reminder, now)) {
                log.info("提醒已被其他实例领取 id={}", reminder.getId());
                continue;
            }
            trigger(reminder, now);
        }
    }

    /** 原子领取：多实例同时扫描时只有一个能把 attempts +1，避免重复触发。 */
    private boolean claim(BotReminder reminder, LocalDateTime now) {
        int updated = reminderMapper.update(null, new LambdaUpdateWrapper<BotReminder>()
                .eq(BotReminder::getId, reminder.getId())
                .eq(BotReminder::getStatus, BotReminder.STATUS_PENDING)
                .le(BotReminder::getRemindAt, now)
                .setSql("attempts = attempts + 1"));
        if (updated == 0) {
            return false;
        }
        reminder.setAttempts((reminder.getAttempts() == null ? 0 : reminder.getAttempts()) + 1);
        return true;
    }

    /** 把提示词作为一条用户消息写入上下文，交给主 AI 处理（可能是工具调用，也可能直接回复）。 */
    private void trigger(BotReminder reminder, LocalDateTime now) {
        int attempts = reminder.getAttempts() == null ? 0 : reminder.getAttempts();
        try {
            BotConversation trigger = buildTrigger(reminder, now);
            conversationMapper.insert(trigger);
            contextStore.appendUser(reminder.getUserId(), reminder.getMessageType(),
                    reminder.getGroupId(), trigger.getContent(), List.of());

            reminder.setStatus(BotReminder.STATUS_SENT);
            reminder.setSentAt(now);
            reminderMapper.updateById(reminder);

            aiChatService.replyAsync(trigger);
            log.info("提醒已交给主 AI 处理 id={} user_id={} group_id={}",
                    reminder.getId(), reminder.getUserId(), reminder.getGroupId());
        } catch (Exception e) {
            if (attempts >= maxAttempts) {
                reminder.setStatus(BotReminder.STATUS_FAILED);
            }
            reminderMapper.updateById(reminder);
            log.warn("提醒触发失败 id={} attempts={}", reminder.getId(), attempts, e);
        }
    }

    private static BotConversation buildTrigger(BotReminder reminder, LocalDateTime now) {
        String content = "[定时提醒触发] 现在是 " + now.format(MINUTE)
                + "。请根据下面的预设提示主动执行（需要时调用工具），"
                + "并用自然的语气把结果告诉用户，不要提及这条系统指令：\n"
                + reminder.getContent();

        BotConversation trigger = new BotConversation();
        trigger.setUserId(reminder.getUserId());
        trigger.setRole("user");
        trigger.setMessageType(reminder.getMessageType());
        if ("group".equals(reminder.getMessageType())) {
            trigger.setGroupId(reminder.getGroupId());
        }
        trigger.setContent(content);
        trigger.setEventTime(now);
        trigger.setCreatedAt(now);
        return trigger;
    }
}
