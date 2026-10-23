package com.skevenxu.humanoid.ai.tools;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.skevenxu.humanoid.ai.ContextStore;
import com.skevenxu.humanoid.ai.FileSandbox;
import com.skevenxu.humanoid.ai.ImageDataLoader;
import com.skevenxu.humanoid.ai.PromptSanitizer;
import com.skevenxu.humanoid.entity.BotConversation;
import com.skevenxu.humanoid.entity.BotImage;
import com.skevenxu.humanoid.llbot.LlbotApiClient;
import com.skevenxu.humanoid.mapper.BotConversationMapper;
import com.skevenxu.humanoid.mapper.BotImageMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/** 私聊/群聊消息工具：发送图片、系统表情，获取收藏表情。 */
@Component
public class ChatMessageTool {

    private static final Logger log = LoggerFactory.getLogger(ChatMessageTool.class);
    private static final Gson GSON = new Gson();
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final LlbotApiClient llbotApiClient;
    private final BotConversationMapper conversationMapper;
    private final BotImageMapper imageMapper;
    private final ImageDataLoader imageDataLoader;
    private final ContextStore contextStore;
    private final FileSandbox sandbox;
    private final int maxImageBytes;
    private final boolean storeSentImages;
    private final int maxStoredImages;

    public ChatMessageTool(LlbotApiClient llbotApiClient,
                           BotConversationMapper conversationMapper,
                           BotImageMapper imageMapper,
                           ImageDataLoader imageDataLoader,
                           ContextStore contextStore,
                           FileSandbox sandbox,
                           @Value("${humanoid.ai.max-image-bytes:10485760}") int maxImageBytes,
                           @Value("${humanoid.ai.store-sent-images:true}") boolean storeSentImages,
                           @Value("${humanoid.ai.max-stored-images:50}") int maxStoredImages) {
        this.llbotApiClient = llbotApiClient;
        this.conversationMapper = conversationMapper;
        this.imageMapper = imageMapper;
        this.imageDataLoader = imageDataLoader;
        this.contextStore = contextStore;
        this.sandbox = sandbox;
        this.maxImageBytes = maxImageBytes;
        this.storeSentImages = storeSentImages;
        this.maxStoredImages = maxStoredImages;
    }

    @Tool(description = """
            向当前聊天发送一张图片。image 可以是 http(s) 图片链接、base64:// 数据，
            或文件目录中的相对路径（如 images/a.png，会读取沙箱文件并以 base64 发送）。
            发送后不需要再重复描述图片内容。""")
    public String sendImage(@ToolParam(description = "图片地址或文件目录中的相对路径") String image) {
        ToolContext.ChatScope scope = ToolContext.get();
        if (scope == null) {
            return "错误：无法确定当前会话";
        }
        if (image == null || image.isBlank()) {
            return "错误：image 不能为空";
        }

        String original = image.trim();
        String source = original;
        if (!source.startsWith("http://") && !source.startsWith("https://") && !source.startsWith("base64://")) {
            Optional<String> dataUrl = readSandboxImage(scope.userId(), source);
            if (dataUrl.isEmpty()) {
                return "错误：图片不存在或无法读取: " + source;
            }
            source = dataUrl.get();
        }

        JsonObject data = new JsonObject();
        data.addProperty("file", source);
        JsonObject segment = new JsonObject();
        segment.addProperty("type", "image");
        segment.add("data", data);

        JsonArray segments = new JsonArray();
        segments.add(segment);

        // 入库时只保存原始地址/路径，避免把 base64 正文写进数据库
        JsonObject storedData = new JsonObject();
        storedData.addProperty("file", original);
        JsonObject storedSegment = new JsonObject();
        storedSegment.addProperty("type", "image");
        storedSegment.add("data", storedData);

        String content = "[图片] " + abbreviate(original);
        String result = send(scope, segments, content, storedSegment);
        if (result.startsWith("已发送")) {
            String imageRef = storeImage(scope, original, source)
                    .map(img -> "image:" + img.getId())
                    .orElse(original);
            contextStore.appendAssistant(scope.userId(), scope.messageType(), scope.groupId(),
                    content, List.of(imageRef));
        }
        return result;
    }

    /** 把发送出去的图片 base64 副本存入 bot_image，供以后查看。 */
    private Optional<BotImage> storeImage(ToolContext.ChatScope scope, String original, String sendSource) {
        if (!storeSentImages) {
            return Optional.empty();
        }
        Optional<String> dataUrl = imageDataLoader.toDataUrl(sendSource);
        if (dataUrl.isEmpty()) {
            log.warn("图片 base64 存储失败 source={}", original);
            return Optional.empty();
        }

        long groupId = "group".equals(scope.messageType()) && scope.groupId() != null
                ? scope.groupId() : 0L;
        BotImage image = new BotImage();
        image.setUserId(scope.userId());
        image.setMessageType(scope.messageType());
        image.setGroupId(groupId);
        image.setSource(abbreviate(original));
        image.setDataUrl(dataUrl.get());
        image.setCreatedAt(LocalDateTime.now(ZONE));
        imageMapper.insert(image);
        trimOldImages(scope, groupId);
        return Optional.of(image);
    }

    /** 每个会话只保留最近 maxStoredImages 张，避免数据库无限增长。 */
    private void trimOldImages(ToolContext.ChatScope scope, long groupId) {
        Long count = imageMapper.selectCount(new LambdaQueryWrapper<BotImage>()
                .eq(BotImage::getUserId, scope.userId())
                .eq(BotImage::getMessageType, scope.messageType())
                .eq(BotImage::getGroupId, groupId));
        if (count == null || count <= maxStoredImages) {
            return;
        }
        long excess = count - maxStoredImages;
        List<BotImage> old = imageMapper.selectList(new LambdaQueryWrapper<BotImage>()
                .select(BotImage::getId)
                .eq(BotImage::getUserId, scope.userId())
                .eq(BotImage::getMessageType, scope.messageType())
                .eq(BotImage::getGroupId, groupId)
                .orderByAsc(BotImage::getId)
                .last("LIMIT " + excess));
        if (!old.isEmpty()) {
            imageMapper.deleteByIds(old.stream().map(BotImage::getId).toList());
        }
    }

    private static String abbreviate(String value) {
        return PromptSanitizer.truncate(value, 200);
    }

    @Tool(description = """
            向当前聊天发送一个 QQ 系统表情（小黄脸）。faceId 为表情 ID，
            常用值：0=惊讶、1=撇嘴、2=色、3=发呆、4=得意、5=流泪、6=害羞、7=闭嘴、
            8=睡、9=大哭、10=尴尬、11=发怒、12=调皮、13=呲牙、14=微笑、15=难过。""")
    public String sendFace(@ToolParam(description = "QQ 系统表情 ID") Integer faceId) {
        ToolContext.ChatScope scope = ToolContext.get();
        if (scope == null) {
            return "错误：无法确定当前会话";
        }
        if (faceId == null || faceId < 0) {
            return "错误：faceId 不合法";
        }

        JsonObject data = new JsonObject();
        data.addProperty("id", faceId);
        JsonObject segment = new JsonObject();
        segment.addProperty("type", "face");
        segment.add("data", data);

        JsonArray segments = new JsonArray();
        segments.add(segment);
        String content = "[表情 " + faceId + "]";
        String result = send(scope, segments, content, segment);
        if (result.startsWith("已发送")) {
            contextStore.appendAssistant(scope.userId(), scope.messageType(), scope.groupId(),
                    content, List.of());
        }
        return result;
    }

    @Tool(description = "获取当前 QQ 账号收藏的表情包图片链接列表。可以挑选其中一个链接，用 sendImage 发送。")
    public String getFavoriteFaces() {
        List<String> urls = llbotApiClient.fetchCustomFaces();
        if (urls.isEmpty()) {
            return "没有获取到收藏表情";
        }
        StringBuilder sb = new StringBuilder("收藏表情链接：\n");
        int limit = Math.min(urls.size(), 30);
        for (int i = 0; i < limit; i++) {
            sb.append(i + 1).append(". ").append(urls.get(i)).append("\n");
        }
        if (urls.size() > limit) {
            sb.append("... 共 ").append(urls.size()).append(" 个，已省略剩余部分");
        }
        return sb.toString().stripTrailing();
    }

    private String send(ToolContext.ChatScope scope, JsonArray segments, String content, JsonObject storedSegment) {
        Optional<Long> messageId = "group".equals(scope.messageType())
                ? llbotApiClient.sendGroupSegments(scope.groupId(), segments)
                : llbotApiClient.sendPrivateSegments(scope.userId(), segments);
        if (messageId.isEmpty()) {
            return "错误：发送失败";
        }

        BotConversation bot = new BotConversation();
        bot.setUserId(scope.userId());
        bot.setRole("bot");
        bot.setMessageType(scope.messageType());
        if ("group".equals(scope.messageType())) {
            bot.setGroupId(scope.groupId());
        }
        bot.setOnebotMessageId(messageId.get());
        bot.setContent(content);
        JsonArray stored = new JsonArray();
        stored.add(storedSegment);
        bot.setSegments(GSON.toJson(stored));
        bot.setEventTime(LocalDateTime.now(ZONE));
        bot.setCreatedAt(LocalDateTime.now(ZONE));
        conversationMapper.insert(bot);

        log.info("工具调用发送消息 user_id={} group_id={} type={} message_id={}",
                scope.userId(), scope.groupId(), content, messageId.get());
        return "已发送";
    }

    /** 读取沙箱内（用户目录/skills/tools）的图片并转成 base64 data URL。 */
    private Optional<String> readSandboxImage(Long userId, String path) {
        try {
            Path target = sandbox.resolve(userId, path);
            if (!Files.isRegularFile(target)) {
                return Optional.empty();
            }
            if (Files.size(target) > maxImageBytes) {
                log.warn("图片过大，拒绝发送: {}", target);
                return Optional.empty();
            }
            byte[] bytes = Files.readAllBytes(target);
            return Optional.of("base64://" + Base64.getEncoder().encodeToString(bytes));
        } catch (Exception e) {
            log.warn("读取待发送图片失败 path={}: {}", path, e.toString());
            return Optional.empty();
        }
    }
}
