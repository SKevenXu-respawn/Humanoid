package com.skevenxu.humanoid.ai.tools;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.skevenxu.humanoid.ai.ContextStore;
import com.skevenxu.humanoid.ai.FileSandbox;
import com.skevenxu.humanoid.entity.BotConversation;
import com.skevenxu.humanoid.llbot.LlbotApiClient;
import com.skevenxu.humanoid.mapper.BotConversationMapper;
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
import java.util.Optional;

/** 发送文件工具：按当前会话上传到私聊或群文件。 */
@Component
public class SendFileTool {

    private static final Logger log = LoggerFactory.getLogger(SendFileTool.class);
    private static final Gson GSON = new Gson();
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final LlbotApiClient llbotApiClient;
    private final BotConversationMapper conversationMapper;
    private final ContextStore contextStore;
    private final FileSandbox sandbox;
    private final long maxFileBytes;

    public SendFileTool(LlbotApiClient llbotApiClient,
                        BotConversationMapper conversationMapper,
                        ContextStore contextStore,
                        FileSandbox sandbox,
                        @Value("${humanoid.tools.max-send-file-bytes:20971520}") long maxFileBytes) {
        this.llbotApiClient = llbotApiClient;
        this.conversationMapper = conversationMapper;
        this.contextStore = contextStore;
        this.sandbox = sandbox;
        this.maxFileBytes = maxFileBytes;
    }

    @Tool(description = """
            向当前聊天发送一个文件。file 可以是文件目录中的相对路径（如 reports/a.pdf）、
            http(s) 链接或 base64:// 数据；name 为可选的文件显示名称。""")
    public String sendFile(@ToolParam(description = "文件路径、URL 或 base64:// 数据") String file,
                           @ToolParam(description = "文件显示名称，可选", required = false) String name) {
        ToolContext.ChatScope scope = ToolContext.get();
        if (scope == null) {
            return "错误：无法确定当前会话";
        }
        if (file == null || file.isBlank()) {
            return "错误：file 不能为空";
        }

        String original = file.trim();
        String source = original;
        if (!source.startsWith("http://") && !source.startsWith("https://") && !source.startsWith("base64://")) {
            Optional<String> base64 = readSandboxFile(scope.userId(), source);
            if (base64.isEmpty()) {
                return "错误：文件不存在、过大或无法读取: " + source;
            }
            source = base64.get();
        }

        String fileName = resolveName(name, original);
        Optional<String> fileId = "group".equals(scope.messageType())
                ? llbotApiClient.uploadGroupFile(scope.groupId(), source, fileName)
                : llbotApiClient.uploadPrivateFile(scope.userId(), source, fileName);
        if (fileId.isEmpty()) {
            return "错误：文件发送失败";
        }

        saveBotMessage(scope, fileName, original);
        contextStore.appendAssistant(scope.userId(), scope.messageType(), scope.groupId(),
                "[文件] " + fileName, java.util.List.of());
        log.info("工具调用 sendFile user_id={} group_id={} name={} file_id={}",
                scope.userId(), scope.groupId(), fileName, fileId.get());
        return "已发送文件「" + fileName + "」（file_id: " + fileId.get() + "）";
    }

    private String resolveName(String name, String source) {
        String candidate = "";
        if (name != null && !name.isBlank()) {
            candidate = name.trim();
        } else if (source.startsWith("http://") || source.startsWith("https://")) {
            String path = java.net.URI.create(source).getPath();
            Path p = path == null || path.isBlank() ? null : Paths.get(path);
            candidate = p == null || p.getFileName() == null ? "" : p.getFileName().toString();
        } else if (!source.startsWith("base64://")) {
            Path p = Paths.get(source);
            candidate = p.getFileName() == null ? "" : p.getFileName().toString();
        }

        candidate = candidate.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        return candidate.isBlank() ? "file-" + System.currentTimeMillis() : candidate;
    }

    /** 读取沙箱内（用户目录/skills/tools）的文件转 base64。 */
    private Optional<String> readSandboxFile(Long userId, String path) {
        try {
            Path target = sandbox.resolve(userId, path);
            if (!Files.isRegularFile(target)) {
                return Optional.empty();
            }
            if (Files.size(target) > maxFileBytes) {
                log.warn("文件过大，拒绝发送: {} ({} bytes)", target, Files.size(target));
                return Optional.empty();
            }
            byte[] bytes = Files.readAllBytes(target);
            return Optional.of("base64://" + Base64.getEncoder().encodeToString(bytes));
        } catch (Exception e) {
            log.warn("读取待发送文件失败 path={}: {}", path, e.toString());
            return Optional.empty();
        }
    }

    private void saveBotMessage(ToolContext.ChatScope scope, String fileName, String source) {
        BotConversation bot = new BotConversation();
        bot.setUserId(scope.userId());
        bot.setRole("bot");
        bot.setMessageType(scope.messageType());
        if ("group".equals(scope.messageType())) {
            bot.setGroupId(scope.groupId());
        }
        bot.setContent("[文件] " + fileName);
        JsonObject data = new JsonObject();
        data.addProperty("file", source);
        data.addProperty("name", fileName);
        JsonObject segment = new JsonObject();
        segment.addProperty("type", "file");
        segment.add("data", data);
        JsonArray segments = new JsonArray();
        segments.add(segment);
        bot.setSegments(GSON.toJson(segments));
        bot.setEventTime(LocalDateTime.now(ZONE));
        bot.setCreatedAt(LocalDateTime.now(ZONE));
        conversationMapper.insert(bot);
    }
}
