package com.skevenxu.humanoid.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.skevenxu.humanoid.ai.tools.ToolContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;

/**
 * 工具结果后处理：把 MCP 截图（image content）从 base64 落盘到当前用户目录，
 * 再把工具结果替换成文件路径，避免把巨大的 base64 塞进模型上下文。
 */
@Component
public class ToolResultPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(ToolResultPostProcessor.class);
    private static final Gson GSON = new Gson();

    private final FileSandbox sandbox;
    private final Path mcpOutputDir;
    private final int maxImageBytes;

    public ToolResultPostProcessor(FileSandbox sandbox,
                                   @Value("${humanoid.tools.mcp-output-dir:/app/mcp-output}") String mcpOutputDir,
                                   @Value("${humanoid.ai.max-image-bytes:10485760}") int maxImageBytes) {
        this.sandbox = sandbox;
        this.mcpOutputDir = Paths.get(mcpOutputDir).toAbsolutePath().normalize();
        this.maxImageBytes = maxImageBytes;
    }

    /**
     * 截图后处理：把 Playwright MCP 输出目录里的文件移动到当前用户目录，
     * 并把工具结果替换成用户可见的相对路径。screenshotFile 为空时退化为处理内联 base64。
     */
    public String process(String toolName, String result, String screenshotFile) {
        if (screenshotFile != null && toolName != null
                && toolName.toLowerCase().contains("screenshot")) {
            String moved = moveScreenshot(screenshotFile);
            if (moved != null) {
                JsonObject text = new JsonObject();
                text.addProperty("type", "text");
                text.addProperty("text",
                        "截图已保存到 " + moved + "。需要发给用户时调用 sendImage，参数为这个相对路径。");
                JsonArray array = new JsonArray();
                array.add(text);
                return GSON.toJson(array);
            }
        }
        return process(toolName, result);
    }

    private String moveScreenshot(String filename) {
        ToolContext.ChatScope scope = ToolContext.get();
        if (scope == null) {
            return null;
        }
        String baseName = Paths.get(filename).getFileName().toString();
        Path source = mcpOutputDir.resolve(baseName).normalize();
        if (!source.startsWith(mcpOutputDir) || !Files.isRegularFile(source)) {
            log.warn("截图文件未找到: {} (查找目录 {})", filename, mcpOutputDir);
            return null;
        }
        try {
            if (Files.size(source) > maxImageBytes) {
                log.warn("截图过大，拒绝保存: {} bytes", Files.size(source));
                return null;
            }
            Path dir = sandbox.userDir(scope.userId()).resolve("downloads").resolve("screenshots");
            Files.createDirectories(dir);
            Path target = dir.resolve(baseName);
            Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(source);

            String relative = "downloads/screenshots/" + baseName;
            log.info("工具调用截图已保存 user_id={} path={}", scope.userId(), relative);
            return relative;
        } catch (Exception e) {
            log.warn("截图移动失败 file={}: {}", filename, e.toString());
            return null;
        }
    }

    public String process(String toolName, String result) {
        if (toolName == null || !toolName.toLowerCase().contains("screenshot")
                || result == null || !result.contains("mimeType")) {
            return result;
        }
        try {
            JsonElement root = JsonParser.parseString(result);
            if (!root.isJsonArray()) {
                return result;
            }
            JsonArray output = new JsonArray();
            boolean changed = false;
            for (JsonElement element : root.getAsJsonArray()) {
                if (element.isJsonObject()) {
                    JsonObject content = element.getAsJsonObject();
                    String mime = content.has("mimeType") && !content.get("mimeType").isJsonNull()
                            ? content.get("mimeType").getAsString() : "";
                    String data = content.has("data") && !content.get("data").isJsonNull()
                            ? content.get("data").getAsString() : null;
                    if (mime.startsWith("image/") && data != null && !data.isBlank()) {
                        String saved = saveImage(data, mime);
                        if (saved != null) {
                            JsonObject text = new JsonObject();
                            text.addProperty("type", "text");
                            text.addProperty("text",
                                    "截图已保存到 " + saved + "。需要发给用户时调用 sendImage，参数为这个相对路径。");
                            output.add(text);
                            changed = true;
                            continue;
                        }
                    }
                }
                output.add(element);
            }
            return changed ? GSON.toJson(output) : result;
        } catch (Exception e) {
            log.warn("截图结果处理失败 tool={}: {}", toolName, e.toString());
            return result;
        }
    }

    private String saveImage(String base64, String mime) {
        ToolContext.ChatScope scope = ToolContext.get();
        if (scope == null) {
            return null;
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(base64);
            if (bytes.length == 0 || bytes.length > maxImageBytes) {
                log.warn("截图大小异常: {} bytes", bytes.length);
                return null;
            }
            Path dir = sandbox.userDir(scope.userId()).resolve("downloads").resolve("screenshots");
            Files.createDirectories(dir);
            String extension = mime.contains("jpeg") ? ".jpg" : mime.contains("webp") ? ".webp" : ".png";
            Path file = dir.resolve("shot-" + System.currentTimeMillis() + extension);
            Files.write(file, bytes);

            String relative = "downloads/screenshots/" + file.getFileName();
            log.info("工具调用截图已保存 user_id={} path={} bytes={}",
                    scope.userId(), relative, bytes.length);
            return relative;
        } catch (Exception e) {
            log.warn("截图保存失败: {}", e.toString());
            return null;
        }
    }
}
