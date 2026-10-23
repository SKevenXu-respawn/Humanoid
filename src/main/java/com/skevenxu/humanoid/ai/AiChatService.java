package com.skevenxu.humanoid.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.skevenxu.humanoid.ai.tools.TaskTool;
import com.skevenxu.humanoid.ai.tools.ToolContext;
import com.skevenxu.humanoid.entity.BotContext;
import com.skevenxu.humanoid.entity.BotConversation;
import com.skevenxu.humanoid.entity.BotToolCall;
import com.skevenxu.humanoid.llbot.LlbotApiClient;
import com.skevenxu.humanoid.mapper.BotConversationMapper;
import com.skevenxu.humanoid.mapper.BotToolCallMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 主 AI：系统提示词 + 滚动总结 + 最近 100 条上下文消息（含图片）。 */
@Service
public class AiChatService {

    private static final Logger log = LoggerFactory.getLogger(AiChatService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final DeepSeekClient deepSeekClient;
    private final ChatClient chatClient;
    private final HumanoidTools humanoidTools;
    private final TaskTool taskTool;
    private final ToolCallLogger toolCallLogger;
    private final BotToolCallMapper toolCallMapper;
    private final BotConversationMapper conversationMapper;
    private final ContextStore contextStore;
    private final ImageRefResolver imageRefResolver;
    private final LlbotApiClient llbotApiClient;
    private final SummaryService summaryService;
    private final String systemPrompt;
    private final int maxImages;
    private final int recentToolCalls;
    private final int contextMessages;
    private final int contextImageWindow;
    private ToolCallback[] toolCallbacks = new ToolCallback[0];

    public AiChatService(DeepSeekClient deepSeekClient,
                         ChatClient.Builder chatClientBuilder,
                         HumanoidTools humanoidTools,
                         TaskTool taskTool,
                         ToolCallLogger toolCallLogger,
                         BotToolCallMapper toolCallMapper,
                         BotConversationMapper conversationMapper,
                         ContextStore contextStore,
                         ImageRefResolver imageRefResolver,
                         LlbotApiClient llbotApiClient,
                         SummaryService summaryService,
                         @Value("classpath:prompts/system-prompt.txt") Resource systemPrompt,
                         @Value("${humanoid.ai.max-images:4}") int maxImages,
                         @Value("${humanoid.ai.recent-tool-calls:8}") int recentToolCalls,
                         @Value("${humanoid.ai.context-messages:100}") int contextMessages,
                         @Value("${humanoid.ai.context-image-window-messages:8}") int contextImageWindow) {
        this.deepSeekClient = deepSeekClient;
        this.humanoidTools = humanoidTools;
        this.taskTool = taskTool;
        this.toolCallLogger = toolCallLogger;
        this.toolCallMapper = toolCallMapper;
        this.conversationMapper = conversationMapper;
        this.contextStore = contextStore;
        this.imageRefResolver = imageRefResolver;
        this.chatClient = buildChatClient(chatClientBuilder);
        this.llbotApiClient = llbotApiClient;
        this.summaryService = summaryService;
        this.systemPrompt = loadPrompt(systemPrompt);
        this.maxImages = maxImages;
        this.recentToolCalls = recentToolCalls;
        this.contextMessages = contextMessages;
        this.contextImageWindow = contextImageWindow;
    }

    /**
     * 文本消息走 Spring AI（支持 tool calling）；上下文含图片时走原生 HTTP。
     * 工具回调在这里构建而不是暴露成 Bean：否则 ToolCallingManager 收集 Bean 时会与
     * ChatClient.Builder 形成循环依赖。
     */
    private ChatClient buildChatClient(ChatClient.Builder builder) {
        ChatClient summarizer = builder.build();
        ToolCallbackProvider provider = MethodToolCallbackProvider.builder()
                .toolObjects(humanoidTools.create(summarizer).toArray())
                .build();
        ToolCallback[] callbacks = toolCallLogger.wrap(provider.getToolCallbacks());
        this.toolCallbacks = callbacks;
        List<String> toolNames = Arrays.stream(callbacks)
                .map(callback -> callback.getToolDefinition().name())
                .toList();
        log.info("已注册 AI 工具: {}", toolNames);
        return builder.defaultToolCallbacks(callbacks).build();
    }

    /** .properties 默认按 ISO-8859-1 解析，中文提示词必须从 UTF-8 资源文件读取。 */
    private static String loadPrompt(Resource resource) {
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            throw new IllegalStateException("无法读取 prompts/system-prompt.txt", e);
        }
    }

    /** 异步生成回复并发送，避免阻塞 llbot 的 webhook 请求。 */
    @Async
    public void replyAsync(BotConversation userMessage) {
        try {
            String summary = PromptSanitizer.clean(summaryService.getSummary(userMessage));
            String system = summary.isBlank()
                    ? systemPrompt
                    : systemPrompt + "\n\n以前的总结为：\n" + summary;
            system += "\n\n文件目录根路径：" + PromptSanitizer.clean(
                    humanoidTools.userRoot(userMessage.getUserId()))
                    + "（共享目录：" + PromptSanitizer.clean(humanoidTools.sharedDirs())
                    + "）。相对路径默认相对用户目录。";

            String activeTask = PromptSanitizer.clean(taskTool.activeTaskBrief(
                    userMessage.getUserId(), userMessage.getMessageType(), userMessage.getGroupId()));
            if (!activeTask.isBlank()) {
                system += "\n\n当前未完成任务：\n" + activeTask;
            }
            String skills = PromptSanitizer.clean(humanoidTools.skillsBrief());
            if (!skills.isBlank()) {
                system += "\n\n可用技能（需要时先用 useSkill 加载详细步骤）：\n" + skills;
            }
            system += recentToolCallsBrief(userMessage);
            if (log.isDebugEnabled()) {
                log.debug("主 AI 系统提示词 len={} backslash={}", system.length(), system.indexOf('\\') >= 0);
            }

            List<BotContext> rows = contextStore.recent(userMessage.getUserId(),
                    userMessage.getMessageType(), userMessage.getGroupId(), contextMessages);
            if (rows.isEmpty()) {
                return;
            }
            Map<Long, List<String>> imageMap = resolveImages(userMessage.getUserId(), rows);
            boolean hasImages = !imageMap.isEmpty();
            log.debug("主 AI 请求 user_id={} summary_chars={} context={} images={} path={}",
                    userMessage.getUserId(), summary.length(), rows.size(), imageMap.size(),
                    hasImages ? "http" : "spring-ai");

            Optional<String> reply;
            if (hasImages) {
                ToolContext.set(new ToolContext.ChatScope(
                        userMessage.getUserId(), userMessage.getMessageType(), userMessage.getGroupId()));
                try {
                    reply = deepSeekClient.chat(system, buildNativeMessages(rows, imageMap),
                            Arrays.asList(toolCallbacks));
                } finally {
                    ToolContext.clear();
                }
            } else {
                List<Message> messages = buildSpringMessages(rows);
                if (messages.isEmpty()) {
                    return;
                }
                ToolContext.set(new ToolContext.ChatScope(
                        userMessage.getUserId(), userMessage.getMessageType(), userMessage.getGroupId()));
                try {
                    reply = Optional.ofNullable(chatClient.prompt()
                            .system(system)
                            .messages(messages)
                            .call()
                            .content());
                } finally {
                    ToolContext.clear();
                }
            }
            if (reply.isEmpty() || reply.get().isBlank()) {
                log.warn("AI 返回空内容 user_id={}", userMessage.getUserId());
                sendReplyQuietly(userMessage, "（我这边没有生成出回复，请稍后再试）");
                return;
            }

            Optional<Long> messageId = sendReply(userMessage, reply.get());
            messageId.ifPresent(id -> saveBotMessage(userMessage, reply.get(), id));
            messageId.ifPresent(id -> log.info("已回复 user_id={} group_id={} message_id={}",
                    userMessage.getUserId(), userMessage.getGroupId(), id));
        } catch (Exception e) {
            log.error("AI 回复失败 user_id={} group_id={}",
                    userMessage.getUserId(), userMessage.getGroupId(), e);
            sendReplyQuietly(userMessage, "（处理出错了，请稍后再试）");
        }
    }

    /** 出错或空回复时兜底发一条提示，避免用户那头完全没反应。 */
    private void sendReplyQuietly(BotConversation current, String text) {
        try {
            sendReply(current, text);
        } catch (Exception e) {
            log.warn("兜底消息发送失败 user_id={}: {}", current.getUserId(), e.toString());
        }
    }

    /** 从最近的上下文里挑选最多 maxImages 张图片并解析成 data URL。 */
    private Map<Long, List<String>> resolveImages(Long userId, List<BotContext> rows) {
        Map<Long, List<String>> images = new HashMap<>();
        int remaining = maxImages;
        int start = Math.max(0, rows.size() - contextImageWindow);
        for (int i = rows.size() - 1; i >= start && remaining > 0; i--) {
            BotContext row = rows.get(i);
            for (String ref : parseImageRefs(row.getImageRefs())) {
                if (remaining == 0) {
                    break;
                }
                Optional<String> dataUrl = imageRefResolver.resolve(userId, ref);
                if (dataUrl.isPresent()) {
                    images.computeIfAbsent(row.getId(), k -> new ArrayList<>()).add(dataUrl.get());
                    remaining--;
                }
            }
        }
        return images;
    }

    private static List<String> parseImageRefs(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonArray()) {
                return List.of();
            }
            List<String> refs = new ArrayList<>();
            for (JsonElement element : root.getAsJsonArray()) {
                if (element != null && !element.isJsonNull()) {
                    refs.add(element.getAsString());
                }
            }
            return refs;
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<Message> buildSpringMessages(List<BotContext> rows) {
        List<Message> messages = new ArrayList<>();
        for (BotContext row : rows) {
            boolean assistant = BotContext.ROLE_ASSISTANT.equals(row.getRole());
            String text = assistant
                    ? cleanAssistantContent(row.getContent())
                    : PromptSanitizer.clean(row.getContent());
            if (text.isBlank()) {
                if (!assistant) {
                    text = "[图片]";
                } else {
                    continue;
                }
            }
            messages.add(assistant ? new AssistantMessage(text) : new UserMessage(text));
        }
        return messages;
    }

    private List<DeepSeekClient.ChatMessage> buildNativeMessages(List<BotContext> rows,
                                                                 Map<Long, List<String>> imageMap) {
        List<DeepSeekClient.ChatMessage> messages = new ArrayList<>();
        for (BotContext row : rows) {
            boolean assistant = BotContext.ROLE_ASSISTANT.equals(row.getRole());
            String text = assistant
                    ? cleanAssistantContent(row.getContent())
                    : PromptSanitizer.clean(row.getContent());
            List<String> images = imageMap.getOrDefault(row.getId(), List.of());

            if (!images.isEmpty()) {
                // DeepSeek 只允许 user/tool 消息携带图片，助手发送的图片转成 user 消息
                String prefix = assistant ? "[助手之前发送的图片] " : "";
                String body = text.isBlank() ? "[图片]" : text;
                messages.add(new DeepSeekClient.ChatMessage("user", prefix + body, images));
                continue;
            }
            if (text.isBlank()) {
                if (assistant) {
                    continue;
                }
                text = "[图片]";
            }
            messages.add(assistant
                    ? DeepSeekClient.ChatMessage.assistant(text)
                    : DeepSeekClient.ChatMessage.user(text, List.of()));
        }
        return messages;
    }

    /** 清理助手历史文本：去掉网页注入的安全标签和模型伪造的“文本版工具调用”。 */
    private static String cleanAssistantContent(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String cleaned = text.replaceAll("(?s)<ds_safety>.*?</ds_safety>", "").trim();
        StringBuilder sb = new StringBuilder();
        for (String line : cleaned.split("\\R")) {
            if (line.trim().matches("[A-Za-z][A-Za-z0-9_]*\\(\\{.*\\}\\)\\s*")) {
                continue;
            }
            sb.append(line).append('\n');
        }
        return sb.toString().trim();
    }

    /** 最近工具调用摘要，注入系统提示词，让模型知道最近做过什么。 */
    private String recentToolCallsBrief(BotConversation current) {
        long groupId = "group".equals(current.getMessageType()) && current.getGroupId() != null
                ? current.getGroupId() : 0L;
        List<BotToolCall> calls = new ArrayList<>(toolCallMapper.selectList(
                new LambdaQueryWrapper<BotToolCall>()
                        .eq(BotToolCall::getUserId, current.getUserId())
                        .eq(BotToolCall::getMessageType, current.getMessageType())
                        .eq(BotToolCall::getGroupId, groupId)
                        .orderByDesc(BotToolCall::getId)
                        .last("LIMIT " + recentToolCalls)));
        if (calls.isEmpty()) {
            return "";
        }
        Collections.reverse(calls);

        StringBuilder sb = new StringBuilder("\n\n最近工具调用：");
        for (BotToolCall call : calls) {
            sb.append("\n- ").append(call.getToolName())
                    .append("(").append(abbrev(PromptSanitizer.clean(call.getArguments()), 80)).append(") → ")
                    .append(abbrev(PromptSanitizer.clean(call.getResult()), 150));
        }
        return sb.toString();
    }

    private static String abbrev(String value, int max) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String oneLine = value.replaceAll("\\s+", " ").trim();
        return PromptSanitizer.truncate(oneLine, max);
    }

    private Optional<Long> sendReply(BotConversation current, String reply) {
        if ("group".equals(current.getMessageType())) {
            return current.getGroupId() == null
                    ? Optional.empty()
                    : llbotApiClient.sendGroup(current.getGroupId(), reply);
        }
        return llbotApiClient.sendPrivate(current.getUserId(), reply);
    }

    private void saveBotMessage(BotConversation source, String reply, Long messageId) {
        BotConversation bot = new BotConversation();
        bot.setUserId(source.getUserId());
        bot.setRole("bot");
        bot.setMessageType(source.getMessageType());
        bot.setGroupId(source.getGroupId());
        bot.setOnebotMessageId(messageId);
        bot.setContent(reply);
        bot.setEventTime(LocalDateTime.now(ZONE));
        bot.setCreatedAt(LocalDateTime.now(ZONE));
        conversationMapper.insert(bot);
        contextStore.appendAssistant(source.getUserId(), source.getMessageType(),
                source.getGroupId(), reply, List.of());
    }
}
