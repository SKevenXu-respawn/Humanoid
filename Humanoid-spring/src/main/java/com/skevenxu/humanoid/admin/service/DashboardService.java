package com.skevenxu.humanoid.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.google.gson.JsonObject;
import com.skevenxu.humanoid.entity.BotContext;
import com.skevenxu.humanoid.entity.BotConversation;
import com.skevenxu.humanoid.entity.BotGroup;
import com.skevenxu.humanoid.entity.BotImage;
import com.skevenxu.humanoid.entity.BotReminder;
import com.skevenxu.humanoid.entity.BotTask;
import com.skevenxu.humanoid.entity.BotToolCall;
import com.skevenxu.humanoid.entity.BotUser;
import com.skevenxu.humanoid.llbot.LlbotApiClient;
import com.skevenxu.humanoid.mapper.BotContextMapper;
import com.skevenxu.humanoid.mapper.BotConversationMapper;
import com.skevenxu.humanoid.mapper.BotGroupMapper;
import com.skevenxu.humanoid.mapper.BotImageMapper;
import com.skevenxu.humanoid.mapper.BotReminderMapper;
import com.skevenxu.humanoid.mapper.BotTaskMapper;
import com.skevenxu.humanoid.mapper.BotToolCallMapper;
import com.skevenxu.humanoid.mapper.BotUserMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** 管理端概览统计。 */
@Service
public class DashboardService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final BotUserMapper userMapper;
    private final BotGroupMapper groupMapper;
    private final BotConversationMapper conversationMapper;
    private final BotContextMapper contextMapper;
    private final BotTaskMapper taskMapper;
    private final BotReminderMapper reminderMapper;
    private final BotImageMapper imageMapper;
    private final BotToolCallMapper toolCallMapper;
    private final LlbotApiClient llbotApiClient;

    public DashboardService(BotUserMapper userMapper,
                            BotGroupMapper groupMapper,
                            BotConversationMapper conversationMapper,
                            BotContextMapper contextMapper,
                            BotTaskMapper taskMapper,
                            BotReminderMapper reminderMapper,
                            BotImageMapper imageMapper,
                            BotToolCallMapper toolCallMapper,
                            LlbotApiClient llbotApiClient) {
        this.userMapper = userMapper;
        this.groupMapper = groupMapper;
        this.conversationMapper = conversationMapper;
        this.contextMapper = contextMapper;
        this.taskMapper = taskMapper;
        this.reminderMapper = reminderMapper;
        this.imageMapper = imageMapper;
        this.toolCallMapper = toolCallMapper;
        this.llbotApiClient = llbotApiClient;
    }

    public Map<String, Object> stats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("users", Map.of(
                "total", userMapper.selectCount(null),
                "enabled", userMapper.selectCount(new LambdaQueryWrapper<BotUser>()
                        .eq(BotUser::getEnabled, true))));
        stats.put("groups", Map.of(
                "total", groupMapper.selectCount(null),
                "enabled", groupMapper.selectCount(new LambdaQueryWrapper<BotGroup>()
                        .eq(BotGroup::getEnabled, true))));

        LocalDateTime todayStart = LocalDate.now(ZONE).atStartOfDay();
        stats.put("conversations", Map.of(
                "total", conversationMapper.selectCount(null),
                "today", conversationMapper.selectCount(new LambdaQueryWrapper<BotConversation>()
                        .ge(BotConversation::getCreatedAt, todayStart))));

        QueryWrapper<BotContext> scopeQuery = new QueryWrapper<>();
        scopeQuery.select("user_id", "message_type", "group_id")
                .groupBy("user_id", "message_type", "group_id");
        stats.put("contexts", Map.of(
                "messages", contextMapper.selectCount(null),
                "scopes", (long) contextMapper.selectMaps(scopeQuery).size()));

        stats.put("tasks", Map.of(
                "total", taskMapper.selectCount(null),
                "pending", taskCount(BotTask.STATUS_PENDING),
                "inProgress", taskCount(BotTask.STATUS_IN_PROGRESS),
                "paused", taskCount(BotTask.STATUS_PAUSED),
                "completed", taskCount(BotTask.STATUS_COMPLETED)));

        stats.put("reminders", Map.of(
                "total", reminderMapper.selectCount(null),
                "pending", reminderCount(BotReminder.STATUS_PENDING),
                "sent", reminderCount(BotReminder.STATUS_SENT),
                "failed", reminderCount(BotReminder.STATUS_FAILED),
                "cancelled", reminderCount(BotReminder.STATUS_CANCELLED)));

        stats.put("images", imageMapper.selectCount(null));
        stats.put("toolCalls", toolCallMapper.selectCount(null));
        return stats;
    }

    public Map<String, Object> llbotStatus() {
        Map<String, Object> result = new LinkedHashMap<>();
        Optional<JsonObject> status = llbotApiClient.getStatus();
        result.put("reachable", status.isPresent());
        result.put("online", status.map(data -> bool(data, "online")).orElse(false));
        result.put("good", status.map(data -> bool(data, "good")).orElse(false));

        Optional<JsonObject> version = llbotApiClient.getVersion();
        result.put("appName", version.map(data -> text(data, "app_name")).orElse(""));
        result.put("appVersion", version.map(data -> text(data, "app_version")).orElse(""));
        return result;
    }

    private long taskCount(String status) {
        return taskMapper.selectCount(new LambdaQueryWrapper<BotTask>()
                .eq(BotTask::getStatus, status));
    }

    private long reminderCount(String status) {
        return reminderMapper.selectCount(new LambdaQueryWrapper<BotReminder>()
                .eq(BotReminder::getStatus, status));
    }

    private static boolean bool(JsonObject data, String key) {
        return data.has(key) && !data.get(key).isJsonNull() && data.get(key).getAsBoolean();
    }

    private static String text(JsonObject data, String key) {
        return data.has(key) && !data.get(key).isJsonNull() ? data.get(key).getAsString() : "";
    }
}
