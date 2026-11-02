package com.skevenxu.humanoid.ai.tools;

import com.skevenxu.humanoid.entity.BotReminder;
import com.skevenxu.humanoid.mapper.BotReminderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/** 定时提醒工具：AI 设置时间与预设提示词，到点后由 ReminderService 交给主 AI 执行。 */
@Component
public class ReminderTool {

    private static final Logger log = LoggerFactory.getLogger(ReminderTool.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter SECOND = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_CONTENT = 1000;

    private final BotReminderMapper reminderMapper;

    public ReminderTool(BotReminderMapper reminderMapper) {
        this.reminderMapper = reminderMapper;
    }

    @Tool(description = """
            为用户设置定时提醒。remindAt 格式为 yyyy-MM-dd HH:mm（北京时间）。
            如果用户说的是相对时间（如「十分钟后」「明天早上」），请先调用 currentTime 工具获取当前时间再计算。
            message 是预设提示词：到点后会把这条提示交给主 AI 执行（可以调用工具），由 AI 决定最终发给用户的回复。
            所以请写清楚到点后要做什么，而不是只写一句要发出去的提醒语。""")
    public String scheduleReminder(String remindAt, String message) {
        ToolContext.ChatScope scope = ToolContext.get();
        if (scope == null) {
            return "错误：无法确定当前会话";
        }
        if (message == null || message.isBlank()) {
            return "错误：提醒内容不能为空";
        }
        if (message.length() > MAX_CONTENT) {
            return "错误：提醒内容过长，请控制在 " + MAX_CONTENT + " 字以内";
        }

        LocalDateTime time = parse(remindAt);
        if (time == null) {
            return "错误：时间格式不正确，请使用 yyyy-MM-dd HH:mm 格式";
        }
        LocalDateTime now = LocalDateTime.now(ZONE);
        if (!time.isAfter(now)) {
            return "错误：提醒时间已经过去，请设置一个将来的时间";
        }

        BotReminder reminder = new BotReminder();
        reminder.setUserId(scope.userId());
        reminder.setMessageType(scope.messageType());
        reminder.setGroupId("group".equals(scope.messageType()) && scope.groupId() != null
                ? scope.groupId() : 0L);
        reminder.setContent(message.trim());
        reminder.setRemindAt(time);
        reminder.setStatus(BotReminder.STATUS_PENDING);
        reminder.setAttempts(0);
        reminder.setCreatedAt(now);
        reminderMapper.insert(reminder);

        log.info("工具调用 scheduleReminder user_id={} id={} remind_at={}",
                scope.userId(), reminder.getId(), time);
        return "已设置提醒，将在 " + time.format(MINUTE) + " 交给主 AI 处理：" + reminder.getContent();
    }

    private static LocalDateTime parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String value = text.trim();
        try {
            return LocalDateTime.parse(value, MINUTE);
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.parse(value, SECOND);
            } catch (DateTimeParseException e) {
                return null;
            }
        }
    }
}
