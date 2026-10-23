package com.skevenxu.humanoid.ai.tools;

import com.skevenxu.humanoid.ai.ContextStore;
import com.skevenxu.humanoid.entity.BotConversation;
import com.skevenxu.humanoid.llbot.LlbotApiClient;
import com.skevenxu.humanoid.mapper.BotConversationMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 连续发送多条文字消息。 */
@Component
public class SendMessagesTool {

    private static final Logger log = LoggerFactory.getLogger(SendMessagesTool.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final int MAX_LENGTH = 2000;

    private final LlbotApiClient llbotApiClient;
    private final BotConversationMapper conversationMapper;
    private final ContextStore contextStore;
    private final int maxMessages;

    public SendMessagesTool(LlbotApiClient llbotApiClient,
                            BotConversationMapper conversationMapper,
                            ContextStore contextStore,
                            @Value("${humanoid.tools.max-batch-messages:10}") int maxMessages) {
        this.llbotApiClient = llbotApiClient;
        this.conversationMapper = conversationMapper;
        this.contextStore = contextStore;
        this.maxMessages = maxMessages;
    }

    @Tool(description = """
            连续发送多条文字消息（按列表顺序依次发送）。适合需要分多条说完的内容。
            普通回复直接输出文字即可；只有确实需要拆成多条、并且希望它们都出现在聊天记录里时才使用。
            每条消息都会记入对话上下文。""")
    public String sendMessages(@ToolParam(description = "要依次发送的消息内容列表") List<String> messages) {
        ToolContext.ChatScope scope = ToolContext.get();
        if (scope == null) {
            return "错误：无法确定当前会话";
        }
        if (messages == null || messages.isEmpty()) {
            return "错误：messages 不能为空";
        }
        if (messages.size() > maxMessages) {
            return "错误：一次最多发送 " + maxMessages + " 条消息";
        }

        List<String> sent = new ArrayList<>();
        for (String message : messages) {
            if (message == null || message.isBlank()) {
                continue;
            }
            if (message.length() > MAX_LENGTH) {
                return "错误：第 " + (sent.size() + 1) + " 条消息过长（最多 " + MAX_LENGTH + " 字）";
            }
            String content = message.trim();
            Optional<Long> messageId = "group".equals(scope.messageType())
                    ? llbotApiClient.sendGroup(scope.groupId(), content)
                    : llbotApiClient.sendPrivate(scope.userId(), content);
            if (messageId.isEmpty()) {
                return "已发送 " + sent.size() + " 条，第 " + (sent.size() + 1) + " 条发送失败";
            }
            saveBotMessage(scope, content, messageId.get());
            contextStore.appendAssistant(scope.userId(), scope.messageType(), scope.groupId(),
                    content, List.of());
            sent.add(content);
            if (sent.size() < messages.size()) {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        log.info("工具调用 sendMessages user_id={} group_id={} count={}",
                scope.userId(), scope.groupId(), sent.size());
        return "已依次发送 " + sent.size() + " 条消息";
    }

    private void saveBotMessage(ToolContext.ChatScope scope, String content, Long messageId) {
        BotConversation bot = new BotConversation();
        bot.setUserId(scope.userId());
        bot.setRole("bot");
        bot.setMessageType(scope.messageType());
        if ("group".equals(scope.messageType())) {
            bot.setGroupId(scope.groupId());
        }
        bot.setOnebotMessageId(messageId);
        bot.setContent(content);
        bot.setEventTime(LocalDateTime.now(ZONE));
        bot.setCreatedAt(LocalDateTime.now(ZONE));
        conversationMapper.insert(bot);
    }
}
