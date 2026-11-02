package com.skevenxu.humanoid.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.skevenxu.humanoid.admin.dto.AdminDtos.RuntimeAppView;
import com.skevenxu.humanoid.admin.dto.AdminDtos.RuntimeDbView;
import com.skevenxu.humanoid.admin.dto.AdminDtos.RuntimeLlbotView;
import com.skevenxu.humanoid.admin.dto.AdminDtos.RuntimeQueueView;
import com.skevenxu.humanoid.admin.dto.AdminDtos.RuntimeSchedulerView;
import com.skevenxu.humanoid.admin.dto.AdminDtos.RuntimeSecretView;
import com.skevenxu.humanoid.admin.dto.AdminDtos.RuntimeView;
import com.skevenxu.humanoid.admin.dto.AdminDtos.RuntimeVoiceView;
import com.skevenxu.humanoid.ai.AiChatService;
import com.skevenxu.humanoid.ai.monitor.SchedulerStatus;
import com.skevenxu.humanoid.ai.secret.SecretService;
import com.skevenxu.humanoid.ai.voice.DoubaoAsrClient;
import com.skevenxu.humanoid.ai.voice.DoubaoVoiceClient;
import com.skevenxu.humanoid.ai.voice.VoiceConfigService;
import com.skevenxu.humanoid.entity.BotFriendRequest;
import com.skevenxu.humanoid.entity.BotReminder;
import com.skevenxu.humanoid.entity.BotTask;
import com.skevenxu.humanoid.entity.BotVideoQueue;
import com.skevenxu.humanoid.llbot.LlbotApiClient;
import com.skevenxu.humanoid.mapper.BotFriendRequestMapper;
import com.skevenxu.humanoid.mapper.BotReminderMapper;
import com.skevenxu.humanoid.mapper.BotTaskMapper;
import com.skevenxu.humanoid.mapper.BotVideoQueueMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 运行状态汇总：应用、数据库、LLBot、队列、调度器、密钥、语音。 */
@Service
public class AdminRuntimeService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final List<String> TABLES = List.of(
            "bot_user", "bot_group", "bot_conversation", "bot_context",
            "bot_task", "bot_reminder", "bot_tool_call", "bot_image",
            "bot_video_queue", "bot_memory", "bot_memory_summary",
            "bot_prompt", "bot_secret", "bot_friend_request");

    private final JdbcTemplate jdbc;
    private final LlbotApiClient llbotApiClient;
    private final DashboardService dashboardService;
    private final AiChatService aiChatService;
    private final SecretService secretService;
    private final VoiceConfigService voiceConfigService;
    private final DoubaoVoiceClient voiceClient;
    private final DoubaoAsrClient asrClient;
    private final BotReminderMapper reminderMapper;
    private final BotTaskMapper taskMapper;
    private final BotVideoQueueMapper videoQueueMapper;
    private final BotFriendRequestMapper friendRequestMapper;

    public AdminRuntimeService(JdbcTemplate jdbc,
                               LlbotApiClient llbotApiClient,
                               DashboardService dashboardService,
                               AiChatService aiChatService,
                               SecretService secretService,
                               VoiceConfigService voiceConfigService,
                               DoubaoVoiceClient voiceClient,
                               DoubaoAsrClient asrClient,
                               BotReminderMapper reminderMapper,
                               BotTaskMapper taskMapper,
                               BotVideoQueueMapper videoQueueMapper,
                               BotFriendRequestMapper friendRequestMapper) {
        this.jdbc = jdbc;
        this.llbotApiClient = llbotApiClient;
        this.dashboardService = dashboardService;
        this.aiChatService = aiChatService;
        this.secretService = secretService;
        this.voiceConfigService = voiceConfigService;
        this.voiceClient = voiceClient;
        this.asrClient = asrClient;
        this.reminderMapper = reminderMapper;
        this.taskMapper = taskMapper;
        this.videoQueueMapper = videoQueueMapper;
        this.friendRequestMapper = friendRequestMapper;
    }

    public RuntimeView snapshot() {
        RuntimeAppView app = app();
        RuntimeDbView database = database();

        Map<String, Object> llbot = dashboardService.llbotStatus();
        Optional<JsonObject> login = llbotApiClient.getLoginInfo();
        RuntimeLlbotView llbotView = new RuntimeLlbotView(
                bool(llbot, "reachable"), bool(llbot, "online"), bool(llbot, "good"),
                string(llbot, "appName"), string(llbot, "appVersion"),
                login.map(data -> longValue(data, "user_id")).orElse(null),
                login.map(data -> text(data, "nickname")).orElse(null));

        RuntimeQueueView queues = new RuntimeQueueView(
                reminderMapper.selectCount(new LambdaQueryWrapper<BotReminder>()
                        .eq(BotReminder::getStatus, BotReminder.STATUS_PENDING)),
                taskMapper.selectCount(new LambdaQueryWrapper<BotTask>()
                        .eq(BotTask::getStatus, BotTask.STATUS_IN_PROGRESS)),
                videoQueueMapper.selectCount(new LambdaQueryWrapper<BotVideoQueue>()
                        .eq(BotVideoQueue::getStatus, BotVideoQueue.STATUS_PENDING)),
                friendRequestMapper.selectCount(new LambdaQueryWrapper<BotFriendRequest>()
                        .eq(BotFriendRequest::getStatus, BotFriendRequest.STATUS_PENDING)));

        List<RuntimeSchedulerView> schedulers = SchedulerStatus.snapshot().stream()
                .map(entry -> new RuntimeSchedulerView(entry.name(), entry.label(),
                        entry.detail(), entry.lastRunAt()))
                .toList();

        List<RuntimeSecretView> secrets = secretService.list().stream()
                .map(info -> new RuntimeSecretView(info.key(), info.label(),
                        info.configured(), info.masked(), info.source(), info.required()))
                .toList();

        RuntimeVoiceView voice = new RuntimeVoiceView(voiceConfigService.isEnabled(),
                voiceClient.isConfigured(), asrClient.isEnabled(), voiceConfigService.speaker());

        return new RuntimeView(app, database, llbotView, queues, schedulers, secrets, voice,
                warnings(database, llbotView, secrets, voice));
    }

    private RuntimeAppView app() {
        long startMillis = ManagementFactory.getRuntimeMXBean().getStartTime();
        LocalDateTime start = LocalDateTime.ofInstant(Instant.ofEpochMilli(startMillis), ZONE);
        long uptime = Math.max(0, (System.currentTimeMillis() - startMillis) / 1000);
        Runtime runtime = Runtime.getRuntime();
        long usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        long maxMb = runtime.maxMemory() / (1024 * 1024);
        int threads = ManagementFactory.getThreadMXBean().getThreadCount();
        return new RuntimeAppView(start, uptime, usedMb, maxMb, threads,
                aiChatService.registeredToolCount(), aiChatService.registeredBrowserToolCount());
    }

    private RuntimeDbView database() {
        try {
            Map<String, Long> counts = new LinkedHashMap<>();
            for (String table : TABLES) {
                Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
                counts.put(table, count == null ? 0 : count);
            }
            return new RuntimeDbView(true, counts);
        } catch (Exception e) {
            return new RuntimeDbView(false, Map.of());
        }
    }

    private static List<String> warnings(RuntimeDbView database, RuntimeLlbotView llbot,
                                         List<RuntimeSecretView> secrets, RuntimeVoiceView voice) {
        List<String> warnings = new ArrayList<>();
        if (!database.reachable()) {
            warnings.add("数据库不可达");
        }
        if (!llbot.reachable()) {
            warnings.add("LLBot 不可达，请确认 llbot 容器已启动");
        } else if (!llbot.online()) {
            warnings.add("LLBot 不在线，请打开 LLBot WebUI 检查 QQ 登录");
        }
        if (voice.configured() && !voice.asrConfigured()) {
            warnings.add("语音合成已配置，但语音识别未启用");
        }
        if (!voice.configured()) {
            warnings.add("未配置语音合成 Key");
        }
        for (RuntimeSecretView secret : secrets) {
            if (secret.required() && !secret.configured()) {
                warnings.add("密钥未配置：" + secret.label());
            }
        }
        return warnings;
    }

    private static boolean bool(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value instanceof Boolean b && b;
    }

    private static String string(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : value.toString();
    }

    private static Long longValue(JsonObject obj, String key) {
        JsonElement element = obj.get(key);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        try {
            return element.getAsLong();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String text(JsonObject obj, String key) {
        JsonElement element = obj.get(key);
        return element == null || element.isJsonNull() ? null : element.getAsString();
    }
}
