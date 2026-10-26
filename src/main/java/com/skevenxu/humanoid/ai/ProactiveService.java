package com.skevenxu.humanoid.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.skevenxu.humanoid.ai.tools.TaskTool;
import com.skevenxu.humanoid.ai.tools.ToolContext;
import com.skevenxu.humanoid.entity.BotContext;
import com.skevenxu.humanoid.entity.BotConversation;
import com.skevenxu.humanoid.entity.BotProactiveState;
import com.skevenxu.humanoid.entity.BotToolCall;
import com.skevenxu.humanoid.entity.BotUser;
import com.skevenxu.humanoid.llbot.LlbotApiClient;
import com.skevenxu.humanoid.mapper.BotConversationMapper;
import com.skevenxu.humanoid.mapper.BotProactiveStateMapper;
import com.skevenxu.humanoid.mapper.BotToolCallMapper;
import com.skevenxu.humanoid.mapper.BotUserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 主动行为：每 30 分钟醒来一次，自主决定「什么都不做 / 找用户聊天 / 刷视频并评论」。
 * 浏览器操作通过 Playwright MCP 工具完成，只开放安全的浏览类工具。
 */
@Service
public class ProactiveService {

    private static final Logger log = LoggerFactory.getLogger(ProactiveService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final int MAX_CHAT_CHARS = 500;

    /** 主动行为允许调用的浏览器工具白名单（排除 evaluate/upload/unsafe 等高风险工具）。 */
    private static final Set<String> BROWSER_TOOLS = Set.of(
            "browser_navigate", "browser_navigate_back", "browser_click", "browser_type",
            "browser_press_key", "browser_wait_for", "browser_snapshot", "browser_take_screenshot",
            "browser_tabs", "browser_select_option", "browser_hover", "browser_find",
            "browser_console_messages", "browser_network_requests", "browser_resize",
            "browser_close", "browser_drag");

    private final boolean enabled;
    private final int checkIntervalMinutes;
    private final int maxBrowseSessionsPerTick;
    private final Set<Long> userIds;
    private final String proactivePrompt;
    private final ChatClient chatClient;
    private final BotUserMapper userMapper;
    private final BotConversationMapper conversationMapper;
    private final BotProactiveStateMapper stateMapper;
    private final BotToolCallMapper toolCallMapper;
    private final TaskTool taskTool;
    private final SummaryService summaryService;
    private final LlbotApiClient llbotApiClient;
    private final ContextStore contextStore;

    public ProactiveService(@Value("${humanoid.proactive.enabled:true}") boolean enabled,
                            @Value("${humanoid.proactive.check-interval-minutes:25}") int checkIntervalMinutes,
                            @Value("${humanoid.proactive.max-browse-sessions-per-tick:2}") int maxBrowseSessionsPerTick,
                            @Value("${humanoid.proactive.user-ids:}") String userIds,
                            @Value("classpath:prompts/proactive-prompt.txt") Resource proactivePrompt,
                            ChatClient.Builder chatClientBuilder,
                            HumanoidTools humanoidTools,
                            ObjectProvider<ToolCallbackProvider> toolCallbackProviders,
                            ToolCallLogger toolCallLogger,
                            BotUserMapper userMapper,
                            BotConversationMapper conversationMapper,
                            BotProactiveStateMapper stateMapper,
                            BotToolCallMapper toolCallMapper,
                            TaskTool taskTool,
                            SummaryService summaryService,
                            LlbotApiClient llbotApiClient,
                            ContextStore contextStore) {
        this.enabled = enabled;
        this.checkIntervalMinutes = Math.max(1, checkIntervalMinutes);
        this.maxBrowseSessionsPerTick = Math.max(0, Math.min(5, maxBrowseSessionsPerTick));
        this.userIds = userIds.isBlank() ? Set.of() : Arrays.stream(userIds.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Long::parseLong)
                .collect(Collectors.toSet());
        this.proactivePrompt = loadPrompt(proactivePrompt);
        this.userMapper = userMapper;
        this.conversationMapper = conversationMapper;
        this.stateMapper = stateMapper;
        this.toolCallMapper = toolCallMapper;
        this.taskTool = taskTool;
        this.summaryService = summaryService;
        this.llbotApiClient = llbotApiClient;
        this.contextStore = contextStore;
        this.chatClient = buildProactiveClient(chatClientBuilder, humanoidTools, toolCallbackProviders, toolCallLogger);
    }

    private static ChatClient buildProactiveClient(ChatClient.Builder builder,
                                                   HumanoidTools humanoidTools,
                                                   ObjectProvider<ToolCallbackProvider> mcpProviders,
                                                   ToolCallLogger toolCallLogger) {
        ChatClient summarizer = builder.build();
        ToolCallbackProvider provider = MethodToolCallbackProvider.builder()
                .toolObjects(humanoidTools.createForProactive(summarizer).toArray())
                .build();
        List<ToolCallback> callbacks = new ArrayList<>(Arrays.asList(provider.getToolCallbacks()));
        try {
            mcpProviders.orderedStream()
                    .flatMap(p -> Arrays.stream(p.getToolCallbacks()))
                    .filter(cb -> BROWSER_TOOLS.contains(cb.getToolDefinition().name()))
                    .forEach(callbacks::add);
        } catch (Exception e) {
            log.warn("主动行为：浏览器工具加载失败（Playwright MCP 可能未就绪）: {}", e.toString());
        }
        List<String> names = callbacks.stream().map(cb -> cb.getToolDefinition().name()).toList();
        log.info("主动行为工具: {}", names);
        return builder.defaultToolCallbacks(toolCallLogger.wrap(callbacks.toArray(ToolCallback[]::new))).build();
    }

    @Scheduled(cron = "${humanoid.proactive.cron:0 */30 * * * *}")
    public void scan() {
        if (!enabled) {
            return;
        }
        List<BotUser> users = new ArrayList<>(userMapper.selectList(new LambdaQueryWrapper<BotUser>()
                .eq(BotUser::getEnabled, true)));
        // 打乱顺序，让刷视频机会在多个用户之间轮换
        Collections.shuffle(users);
        log.info("主动行为扫描触发, 白名单用户数={} 本轮刷视频上限={}",
                users.size(), maxBrowseSessionsPerTick);
        int browseSessions = 0;
        for (BotUser user : users) {
            if (!userIds.isEmpty() && !userIds.contains(user.getUserId())) {
                continue;
            }
            try {
                if (check(user, browseSessions < maxBrowseSessionsPerTick)) {
                    browseSessions++;
                }
            } catch (Exception e) {
                log.error("主动行为执行失败 user_id={}", user.getUserId(), e);
            }
        }
        log.info("主动行为扫描结束, 本轮实际刷视频用户数={}", browseSessions);
    }

    /** @return 本轮是否执行了浏览器操作 */
    private boolean check(BotUser user, boolean browseAllowed) {
        Long userId = user.getUserId();
        BotProactiveState state = findOrCreate(userId);

        LocalDateTime now = LocalDateTime.now(ZONE);
        if (state.getLastCheckedAt() != null
                && Duration.between(state.getLastCheckedAt(), now).toMinutes() < checkIntervalMinutes) {
            log.info("主动行为 user_id={} 跳过: 检查冷却中", userId);
            return false;
        }
        state.setLastCheckedAt(now);
        stateMapper.updateById(state);

        BotConversation lastUserMessage = conversationMapper.selectOne(new LambdaQueryWrapper<BotConversation>()
                .eq(BotConversation::getUserId, userId)
                .eq(BotConversation::getMessageType, "private")
                .eq(BotConversation::getRole, "user")
                .isNull(BotConversation::getGroupId)
                .orderByDesc(BotConversation::getId)
                .last("LIMIT 1"));
        LocalDateTime lastUserAt = lastUserMessage == null ? null
                : (lastUserMessage.getCreatedAt() != null
                    ? lastUserMessage.getCreatedAt() : lastUserMessage.getEventTime());
        long idle = lastUserAt == null ? -1 : Duration.between(lastUserAt, now).toMinutes();
        boolean awaitingReply = state.getLastProactiveAt() != null
                && (lastUserAt == null || !state.getLastProactiveAt().isBefore(lastUserAt));

        String summary = summaryService.peekSummary(userId, "private", null);
        String activeTask = taskTool.activeTaskBrief(userId, "private", null);
        List<BotContext> recent = contextStore.recent(userId, "private", null, 10);
        String context = buildContext(now, lastUserAt, idle, awaitingReply, browseAllowed,
                summary, activeTask, recent);
        if (log.isDebugEnabled()) {
            log.debug("主动行为上下文 user_id={} browseAllowed={} awaitingReply={}",
                    userId, browseAllowed, awaitingReply);
        }

        long before = countBrowserCalls(userId);
        String reply;
        ToolContext.set(new ToolContext.ChatScope(userId, "private", null));
        try {
            reply = chatClient.prompt().system(proactivePrompt).user(context).call().content();
        } finally {
            ToolContext.clear();
        }
        boolean browsed = countBrowserCalls(userId) > before;

        Decision decision = parseDecision(reply);
        switch (decision.type()) {
            case "CHAT" -> {
                if (awaitingReply) {
                    log.info("主动行为 user_id={} 想聊天但用户尚未回复，跳过发送", userId);
                } else {
                    String text = PromptSanitizer.truncate(decision.content(), MAX_CHAT_CHARS);
                    if (sendChat(userId, text)) {
                        state.setLastProactiveAt(LocalDateTime.now(ZONE));
                        stateMapper.updateById(state);
                        log.info("主动行为 user_id={} 主动聊天: {}", userId,
                                PromptSanitizer.truncate(text, 80));
                    }
                }
            }
            case "DONE" -> log.info("主动行为 user_id={} 已完成: {}", userId,
                    PromptSanitizer.truncate(decision.content(), 120));
            default -> log.info("主动行为 user_id={} 决定 SKIP (browsed={})", userId, browsed);
        }
        return browsed;
    }

    private String buildContext(LocalDateTime now, LocalDateTime lastUserAt, long idle,
                                boolean awaitingReply, boolean browseAllowed,
                                String summary, String activeTask, List<BotContext> recent) {
        StringBuilder sb = new StringBuilder();
        sb.append("当前时间：").append(now.format(FORMAT))
                .append("（").append(now.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.CHINA))
                .append("）\n");
        sb.append("用户最后一次发言：")
                .append(lastUserAt == null ? "无记录" : lastUserAt.format(FORMAT)
                        + "（已过去 " + idle + " 分钟）").append("\n");
        sb.append("用户尚未回复上次消息：").append(awaitingReply ? "是" : "否").append("\n");
        sb.append("本轮允许刷视频：").append(browseAllowed ? "是" : "否").append("\n");
        sb.append("已有的对话总结：\n")
                .append(summary == null || summary.isBlank() ? "无" : PromptSanitizer.clean(summary))
                .append("\n\n");
        if (activeTask != null && !activeTask.isBlank()) {
            sb.append("当前未完成任务：\n").append(PromptSanitizer.clean(activeTask)).append("\n\n");
        }
        sb.append("最近对话：\n");
        for (BotContext row : recent) {
            String text = row.getContent() == null || row.getContent().isBlank()
                    ? "[图片]" : row.getContent();
            sb.append("assistant".equals(row.getRole()) ? "助手: " : "用户: ")
                    .append(PromptSanitizer.truncate(PromptSanitizer.clean(text), 300))
                    .append("\n");
        }
        return sb.toString();
    }

    private long countBrowserCalls(Long userId) {
        Long count = toolCallMapper.selectCount(new LambdaQueryWrapper<BotToolCall>()
                .eq(BotToolCall::getUserId, userId)
                .eq(BotToolCall::getMessageType, "private")
                .eq(BotToolCall::getGroupId, 0L)
                .likeRight(BotToolCall::getToolName, "browser_"));
        return count == null ? 0 : count;
    }

    private boolean sendChat(Long userId, String text) {
        Optional<Long> messageId = llbotApiClient.sendPrivate(userId, text);
        if (messageId.isEmpty()) {
            log.warn("主动聊天发送失败 user_id={}", userId);
            return false;
        }
        BotConversation bot = new BotConversation();
        bot.setUserId(userId);
        bot.setRole("bot");
        bot.setMessageType("private");
        bot.setOnebotMessageId(messageId.get());
        bot.setContent(text);
        bot.setEventTime(LocalDateTime.now(ZONE));
        bot.setCreatedAt(LocalDateTime.now(ZONE));
        conversationMapper.insert(bot);
        contextStore.appendAssistant(userId, "private", null, text, List.of());
        return true;
    }

    record Decision(String type, String content) {
    }

    /** 解析模型输出：CHAT: xxx / DONE: xxx / 其它视为 SKIP。 */
    static Decision parseDecision(String reply) {
        if (reply == null || reply.isBlank()) {
            return new Decision("SKIP", "");
        }
        String text = reply.trim();
        String upper = text.toUpperCase(Locale.ROOT);
        if (upper.startsWith("CHAT:")) {
            String content = text.substring(5).trim();
            return content.isBlank() ? new Decision("SKIP", "") : new Decision("CHAT", content);
        }
        if (upper.startsWith("DONE")) {
            return new Decision("DONE", text.length() > 4 ? text.substring(4).trim() : "");
        }
        return new Decision("SKIP", "");
    }

    private BotProactiveState findOrCreate(Long userId) {
        BotProactiveState state = stateMapper.selectOne(new LambdaQueryWrapper<BotProactiveState>()
                .eq(BotProactiveState::getUserId, userId)
                .eq(BotProactiveState::getMessageType, "private")
                .eq(BotProactiveState::getGroupId, 0L));
        if (state != null) {
            return state;
        }
        state = new BotProactiveState();
        state.setUserId(userId);
        state.setMessageType("private");
        state.setGroupId(0L);
        state.setCreatedAt(LocalDateTime.now(ZONE));
        stateMapper.insert(state);
        return state;
    }

    private static String loadPrompt(Resource resource) {
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            throw new IllegalStateException("无法读取 prompts/proactive-prompt.txt", e);
        }
    }
}
