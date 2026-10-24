package com.skevenxu.humanoid.llbot;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.skevenxu.humanoid.ai.AiChatService;
import com.skevenxu.humanoid.ai.ContextStore;
import com.skevenxu.humanoid.ai.VoiceMessageService;
import com.skevenxu.humanoid.entity.BotConversation;
import com.skevenxu.humanoid.entity.BotFriendRequest;
import com.skevenxu.humanoid.entity.BotGroup;
import com.skevenxu.humanoid.entity.BotUser;
import com.skevenxu.humanoid.mapper.BotConversationMapper;
import com.skevenxu.humanoid.mapper.BotFriendRequestMapper;
import com.skevenxu.humanoid.mapper.BotGroupMapper;
import com.skevenxu.humanoid.mapper.BotUserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 接收 llbot 通过 OneBot 11 HTTP POST 推送的事件，
 * 对白名单内用户的消息写入 bot_conversation。
 */
@Component
public class BotEventService {

    private static final Logger log = LoggerFactory.getLogger(BotEventService.class);
    private static final Gson GSON = new Gson();
    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().create();
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final String REJECT_MESSAGE = "你没在白名单上，和开发者聊聊";

    private final BotUserMapper botUserMapper;
    private final BotGroupMapper botGroupMapper;
    private final BotConversationMapper conversationMapper;
    private final BotFriendRequestMapper friendRequestMapper;
    private final AiChatService aiChatService;
    private final LlbotApiClient llbotApiClient;
    private final VoiceMessageService voiceMessageService;
    private final ContextStore contextStore;
    private final long rejectCooldownMillis;
    private final Map<Long, Long> lastRejectAt = new ConcurrentHashMap<>();

    public BotEventService(BotUserMapper botUserMapper,
                           BotGroupMapper botGroupMapper,
                           BotConversationMapper conversationMapper,
                           BotFriendRequestMapper friendRequestMapper,
                           AiChatService aiChatService,
                           LlbotApiClient llbotApiClient,
                           VoiceMessageService voiceMessageService,
                           ContextStore contextStore,
                           @Value("${humanoid.whitelist.reject-cooldown-minutes:10}") long rejectCooldownMinutes) {
        this.botUserMapper = botUserMapper;
        this.botGroupMapper = botGroupMapper;
        this.conversationMapper = conversationMapper;
        this.friendRequestMapper = friendRequestMapper;
        this.aiChatService = aiChatService;
        this.llbotApiClient = llbotApiClient;
        this.voiceMessageService = voiceMessageService;
        this.contextStore = contextStore;
        this.rejectCooldownMillis = TimeUnit.MINUTES.toMillis(Math.max(0, rejectCooldownMinutes));
    }

    /** 接收一条推送事件；仅白名单用户的 message 事件会入库。 */
    public BotEvent accept(JsonElement payload) {
        BotEvent event = BotEvent.of(payload);
        JsonObject obj = payload.getAsJsonObject();
        String postType = str(obj, "post_type", "");

        if ("request".equals(postType) && "friend".equals(str(obj, "request_type", ""))) {
            recordFriendRequest(obj);
            return event;
        }

        if (!"message".equals(postType)) {
            log.debug("忽略非消息事件 {} seq={}", postType, event.seq());
            return event;
        }

        JsonObject sender = obj.has("sender") && obj.get("sender").isJsonObject()
                ? obj.getAsJsonObject("sender") : new JsonObject();
        Long userId = longVal(obj, "user_id", longVal(sender, "user_id", null));
        Long selfId = longVal(obj, "self_id", null);
        String nickname = str(sender, "nickname", null);

        if (userId == null || userId.equals(selfId)) {
            return event;
        }

        String messageType = str(obj, "message_type", "private");
        Long groupId = longVal(obj, "group_id", null);
        if ("group".equals(messageType)) {
            if (groupId == null || !isGroupAllowed(groupId)) {
                log.info("忽略未注册群消息 group_id={} user_id={} seq={}", groupId, userId, event.seq());
                return event;
            }
        }

        if (!isAllowed(userId)) {
            if ("private".equals(messageType)) {
                rejectUnknownUser(userId);
            }
            log.info("忽略非白名单用户消息 user_id={} seq={}", userId, event.seq());
            return event;
        }

        JsonElement message = obj.get("message");
        JsonObject record = findRecord(message);
        Long replyId = findReplyId(message);
        String quoted = replyId == null ? null : llbotApiClient.getMessageText(replyId).orElse(null);
        if (replyId != null && quoted == null) {
            log.warn("引用消息获取失败 reply_id={}", replyId);
        }

        BotConversation conversation = toConversation(obj, userId, nickname, quoted);
        if (record != null) {
            // 语音先落库占位，转写与 AI 回复在后台完成，避免阻塞 LLBot webhook
            conversation.setContent("[语音消息]（识别中）");
            conversationMapper.insert(conversation);
            refreshNickname(userId, nickname);
            log.info("[{}] user={} 收到语音消息 conversation_id={}",
                    conversation.getMessageType(), userId, conversation.getId());
            voiceMessageService.handleAsync(conversation, str(record, "file", null),
                    longVal(obj, "message_id", null));
            return event;
        }

        conversationMapper.insert(conversation);
        contextStore.appendUser(userId, conversation.getMessageType(), conversation.getGroupId(),
                conversation.getContent(), imageRefs(message));
        refreshNickname(userId, nickname);

        log.info("[{}] user={} content={}", conversation.getMessageType(), userId, conversation.getContent());
        log.debug("event {} persisted as conversation id={}", PRETTY.toJson(event), conversation.getId());
        aiChatService.replyAsync(conversation);
        return event;
    }

    private boolean isAllowed(Long userId) {
        return botUserMapper.selectCount(new LambdaQueryWrapper<BotUser>()
                .eq(BotUser::getUserId, userId)
                .eq(BotUser::getEnabled, true)) > 0;
    }

    private boolean isGroupAllowed(Long groupId) {
        return botGroupMapper.selectCount(new LambdaQueryWrapper<BotGroup>()
                .eq(BotGroup::getGroupId, groupId)
                .eq(BotGroup::getEnabled, true)) > 0;
    }

    /** 好友申请事件落库，供管理台同意/拒绝。 */
    private void recordFriendRequest(JsonObject obj) {
        Long userId = longVal(obj, "user_id", null);
        String flag = str(obj, "flag", null);
        if (userId == null || flag == null || flag.isBlank()) {
            log.warn("好友申请事件缺少 user_id 或 flag: {}", obj);
            return;
        }
        if (friendRequestMapper.selectCount(new LambdaQueryWrapper<BotFriendRequest>()
                .eq(BotFriendRequest::getFlag, flag)) > 0) {
            log.debug("好友申请已存在 flag={} user_id={}", flag, userId);
            return;
        }

        BotFriendRequest request = new BotFriendRequest();
        request.setUserId(userId);
        request.setFlag(flag);
        request.setComment(str(obj, "comment", null));
        request.setVia(str(obj, "via", null));
        request.setSource(BotFriendRequest.SOURCE_EVENT);
        request.setStatus(BotFriendRequest.STATUS_PENDING);
        Long time = longVal(obj, "time", null);
        if (time != null) {
            request.setEventTime(LocalDateTime.ofInstant(Instant.ofEpochSecond(time), ZONE));
        }
        request.setCreatedAt(LocalDateTime.now(ZONE));
        friendRequestMapper.insert(request);
        log.info("收到好友申请 user_id={} comment={}", userId, request.getComment());
    }

    /** 私聊非白名单用户：发送提示，按冷却时间避免刷屏。 */
    private void rejectUnknownUser(Long userId) {
        long now = System.currentTimeMillis();
        Long last = lastRejectAt.get(userId);
        if (last != null && now - last < rejectCooldownMillis) {
            log.debug("非白名单提示冷却中 user_id={}", userId);
            return;
        }
        lastRejectAt.put(userId, now);
        lastRejectAt.entrySet().removeIf(entry -> now - entry.getValue() > TimeUnit.DAYS.toMillis(1));

        llbotApiClient.sendPrivate(userId, REJECT_MESSAGE)
                .ifPresentOrElse(
                        id -> log.info("已提示非白名单用户 user_id={} message_id={}", userId, id),
                        () -> log.warn("非白名单提示发送失败 user_id={}", userId));
    }

    private void refreshNickname(Long userId, String nickname) {
        if (nickname == null || nickname.isBlank()) {
            return;
        }
        BotUser user = botUserMapper.selectOne(new LambdaQueryWrapper<BotUser>()
                .eq(BotUser::getUserId, userId));
        if (user != null && !nickname.equals(user.getNickname())) {
            user.setNickname(nickname);
            botUserMapper.updateById(user);
        }
    }

    private BotConversation toConversation(JsonObject obj, Long userId, String nickname, String quoted) {
        BotConversation c = new BotConversation();
        c.setUserId(userId);
        c.setRole("user");
        c.setMessageType(str(obj, "message_type", "private"));
        c.setGroupId(longVal(obj, "group_id", null));
        c.setOnebotMessageId(longVal(obj, "message_id", null));
        c.setMessageSeq(longVal(obj, "message_seq", null));
        c.setNickname(nickname);

        JsonElement message = obj.get("message");
        if (message != null && !message.isJsonNull()) {
            c.setSegments(GSON.toJson(message));
            String text = extractText(message);
            c.setContent(quoted == null ? text : "[引用] " + quoted + "\n" + text);
        }

        Long time = longVal(obj, "time", null);
        if (time != null) {
            c.setEventTime(LocalDateTime.ofInstant(Instant.ofEpochSecond(time), ZONE));
        }
        c.setCreatedAt(LocalDateTime.now(ZONE));
        return c;
    }

    /** 从消息段中取出图片引用，用于 bot_context。 */
    private List<String> imageRefs(JsonElement message) {
        if (message == null || !message.isJsonArray()) {
            return List.of();
        }
        List<String> refs = new ArrayList<>();
        for (JsonElement element : message.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject segment = element.getAsJsonObject();
            if (!"image".equals(str(segment, "type", ""))) {
                continue;
            }
            JsonElement dataElement = segment.get("data");
            if (dataElement == null || !dataElement.isJsonObject()) {
                continue;
            }
            JsonObject data = dataElement.getAsJsonObject();
            String url = firstNonBlank(str(data, "url", null), str(data, "thumb", null), str(data, "file", null));
            if (url != null) {
                refs.add(url);
            }
        }
        return refs;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    /** 找出消息里的语音段，返回其 data 对象。 */
    private static JsonObject findRecord(JsonElement message) {
        if (message == null || !message.isJsonArray()) {
            return null;
        }
        for (JsonElement element : message.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject segment = element.getAsJsonObject();
            if (!"record".equals(str(segment, "type", ""))) {
                continue;
            }
            JsonElement data = segment.get("data");
            if (data != null && data.isJsonObject()) {
                return data.getAsJsonObject();
            }
        }
        return null;
    }

    /** 从消息段中取出被引用消息的 id。 */
    private Long findReplyId(JsonElement message) {
        if (message == null || !message.isJsonArray()) {
            return null;
        }
        for (JsonElement element : message.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject segment = element.getAsJsonObject();
            if (!"reply".equals(str(segment, "type", ""))) {
                continue;
            }
            JsonElement data = segment.get("data");
            if (data == null || !data.isJsonObject()) {
                continue;
            }
            return longVal(data.getAsJsonObject(), "id", null);
        }
        return null;
    }

    private String extractText(JsonElement message) {
        if (!message.isJsonArray()) {
            return "";
        }
        JsonArray segments = message.getAsJsonArray();
        StringBuilder text = new StringBuilder();
        for (JsonElement segment : segments) {
            if (!segment.isJsonObject()) {
                continue;
            }
            JsonObject seg = segment.getAsJsonObject();
            if (!"text".equals(str(seg, "type", ""))) {
                continue;
            }
            JsonElement data = seg.get("data");
            if (data != null && data.isJsonObject()) {
                text.append(str(data.getAsJsonObject(), "text", ""));
            }
        }
        return text.toString();
    }

    private static String str(JsonObject obj, String key, String fallback) {
        JsonElement el = obj.get(key);
        return el == null || el.isJsonNull() ? fallback : el.getAsString();
    }

    private static Long longVal(JsonObject obj, String key, Long fallback) {
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull()) {
            return fallback;
        }
        try {
            return el.getAsLong();
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
