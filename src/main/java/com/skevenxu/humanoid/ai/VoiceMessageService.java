package com.skevenxu.humanoid.ai;

import com.skevenxu.humanoid.ai.voice.DoubaoAsrClient;
import com.skevenxu.humanoid.entity.BotConversation;
import com.skevenxu.humanoid.llbot.LlbotApiClient;
import com.skevenxu.humanoid.mapper.BotConversationMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * 语音消息处理：从 LLBot 取音频，调用豆包录音文件识别转成文字，
 * 再作为普通用户消息交给主 AI。整个过程异步执行，不阻塞 webhook。
 */
@Service
public class VoiceMessageService {

    private static final Logger log = LoggerFactory.getLogger(VoiceMessageService.class);

    private final DoubaoAsrClient asrClient;
    private final LlbotApiClient llbotApiClient;
    private final BotConversationMapper conversationMapper;
    private final ContextStore contextStore;
    private final AiChatService aiChatService;

    public VoiceMessageService(DoubaoAsrClient asrClient,
                               LlbotApiClient llbotApiClient,
                               BotConversationMapper conversationMapper,
                               ContextStore contextStore,
                               AiChatService aiChatService) {
        this.asrClient = asrClient;
        this.llbotApiClient = llbotApiClient;
        this.conversationMapper = conversationMapper;
        this.contextStore = contextStore;
        this.aiChatService = aiChatService;
    }

    @Async
    public void handleAsync(BotConversation conversation, String file, Long messageId) {
        try {
            String text = transcribe(file, messageId);
            String content = text == null || text.isBlank()
                    ? "[语音消息]（语音转文字失败）"
                    : "[语音转文字] " + text;

            conversation.setContent(content);
            conversationMapper.updateById(conversation);
            contextStore.appendUser(conversation.getUserId(), conversation.getMessageType(),
                    conversation.getGroupId(), content, List.of());

            log.info("语音消息已转写并交给主 AI user_id={} chars={}",
                    conversation.getUserId(), text == null ? 0 : text.length());
            aiChatService.replyAsync(conversation);
        } catch (Exception e) {
            log.error("语音消息处理失败 user_id={} conversation_id={}",
                    conversation.getUserId(), conversation.getId(), e);
        }
    }

    private String transcribe(String file, Long messageId) {
        if (asrClient.isEnabled() && file != null && !file.isBlank()) {
            Optional<String> base64 = llbotApiClient.getRecordBase64(file);
            if (base64.isPresent()) {
                try {
                    String encoded = stripDataUrl(base64.get());
                    if (encoded.length() > 14_000_000) {
                        log.warn("语音 base64 过大，跳过识别 file={} length={}", file, encoded.length());
                    } else {
                        byte[] audio = Base64.getDecoder().decode(encoded);
                        Optional<String> text = asrClient.transcribe(audio, "mp3");
                        if (text.isPresent()) {
                            log.info("语音转文字成功 source=volcengine chars={}", text.get().length());
                            return text.get();
                        }
                    }
                } catch (IllegalArgumentException e) {
                    log.warn("语音 base64 解码失败 file={}: {}", file, e.toString());
                }
            } else {
                log.warn("get_record 未返回音频内容 file={}", file);
            }
        }

        if (messageId != null) {
            Optional<String> text = llbotApiClient.voiceMsgToText(messageId);
            if (text.isPresent()) {
                log.info("语音转文字成功 source=llbot chars={}", text.get().length());
                return text.get();
            }
        }

        log.warn("语音转文字失败 file={} message_id={}", file, messageId);
        return null;
    }

    private static String stripDataUrl(String value) {
        String v = value.trim();
        if (v.startsWith("base64://")) {
            return v.substring("base64://".length());
        }
        if (v.startsWith("data:")) {
            int comma = v.indexOf(',');
            if (comma > 0) {
                return v.substring(comma + 1);
            }
        }
        return v;
    }
}
