package com.skevenxu.humanoid.ai.tools;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.skevenxu.humanoid.ai.ContextStore;
import com.skevenxu.humanoid.ai.PromptSanitizer;
import com.skevenxu.humanoid.ai.voice.DoubaoVoiceClient;
import com.skevenxu.humanoid.ai.voice.VoiceConfigService;
import com.skevenxu.humanoid.entity.BotConversation;
import com.skevenxu.humanoid.llbot.LlbotApiClient;
import com.skevenxu.humanoid.mapper.BotConversationMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/** 发送语音工具：文本 -> 豆包 TTS -> QQ 语音消息。 */
@Component
public class VoiceTool {

    private static final Logger log = LoggerFactory.getLogger(VoiceTool.class);
    private static final Gson GSON = new Gson();
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final DoubaoVoiceClient voiceClient;
    private final VoiceConfigService voiceConfigService;
    private final LlbotApiClient llbotApiClient;
    private final BotConversationMapper conversationMapper;
    private final ContextStore contextStore;

    public VoiceTool(DoubaoVoiceClient voiceClient,
                     VoiceConfigService voiceConfigService,
                     LlbotApiClient llbotApiClient,
                     BotConversationMapper conversationMapper,
                     ContextStore contextStore) {
        this.voiceClient = voiceClient;
        this.voiceConfigService = voiceConfigService;
        this.llbotApiClient = llbotApiClient;
        this.conversationMapper = conversationMapper;
        this.contextStore = contextStore;
    }

    @Tool(description = """
            把一段文本合成为语音并发送到当前聊天（私聊或群聊）。
            适合用户要求“发语音/说出来/读给我听”时使用。text 是要读出来的内容，
            建议不超过 300 字；发送后最终回复只需简短说明，不要重复语音全文。""")
    public String sendVoice(@ToolParam(description = "要合成并发送的文本") String text) {
        ToolContext.ChatScope scope = ToolContext.get();
        if (scope == null) {
            return "错误：无法确定当前会话";
        }
        if (text == null || text.isBlank()) {
            return "错误：text 不能为空";
        }
        if (!voiceConfigService.isEnabled()) {
            return "语音发送功能已在管理端关闭";
        }
        String speaker = voiceConfigService.speaker();

        byte[] audio;
        try {
            audio = voiceClient.synthesize(text, speaker);
        } catch (Exception e) {
            log.warn("语音合成失败 user_id={} group_id={} speaker={}: {}",
                    scope.userId(), scope.groupId(), speaker, e.toString());
            return "错误：语音合成失败：" + e.getMessage();
        }

        JsonObject data = new JsonObject();
        data.addProperty("file", "base64://" + Base64.getEncoder().encodeToString(audio));
        JsonObject segment = new JsonObject();
        segment.addProperty("type", "record");
        segment.add("data", data);

        JsonArray segments = new JsonArray();
        segments.add(segment);

        Optional<Long> messageId = "group".equals(scope.messageType())
                ? llbotApiClient.sendGroupSegments(scope.groupId(), segments)
                : llbotApiClient.sendPrivateSegments(scope.userId(), segments);
        if (messageId.isEmpty()) {
            log.warn("语音发送失败 user_id={} group_id={} bytes={}",
                    scope.userId(), scope.groupId(), audio.length);
            return "错误：语音发送失败";
        }

        String content = "[语音] " + PromptSanitizer.truncate(text.trim(), 500);
        saveBotMessage(scope, content, messageId.get());
        contextStore.appendAssistant(scope.userId(), scope.messageType(), scope.groupId(),
                content, List.of());
        log.info("工具调用 sendVoice user_id={} group_id={} speaker={} chars={} audio_bytes={} message_id={}",
                scope.userId(), scope.groupId(), speaker, text.trim().length(), audio.length, messageId.get());
        return "已发送语音（" + text.trim().length() + " 字）";
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
