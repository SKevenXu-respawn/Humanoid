package com.skevenxu.humanoid.ai.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;

/** 沙箱文件写入：只能写入配置目录内的文本文件。 */
@Component
public class FileWriteTool {

    private static final Logger log = LoggerFactory.getLogger(FileWriteTool.class);

    private final Path baseDir;
    private final int maxBytes;

    public FileWriteTool(@Value("${humanoid.tools.file-base-dir:/app/files}") String baseDir,
                         @Value("${humanoid.tools.max-file-bytes:262144}") int maxBytes) {
        this.baseDir = Paths.get(baseDir).toAbsolutePath().normalize();
        this.maxBytes = maxBytes;
    }

    @Tool(description = "在文件目录中创建或覆盖一个文本文件。path 为相对路径，例如 note.txt；content 为文本内容")
    public String writeFile(String path, String content) {
        if (path == null || path.isBlank()) {
            return "错误：path 不能为空";
        }
        if (content == null) {
            content = "";
        }
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxBytes) {
            return "错误：内容过大（" + bytes.length + " 字节），上限为 " + maxBytes + " 字节";
        }

        try {
            Path target = baseDir.resolve(path).normalize();
            if (!target.startsWith(baseDir)) {
                return "错误：不允许写入指定目录之外的文件";
            }
            if (target.equals(baseDir)) {
                return "错误：path 必须指向文件";
            }

            Path parent = target.getParent();
            Files.createDirectories(parent);
            Path realBase = baseDir.toRealPath();
            Path realParent = parent.toRealPath();
            if (!realParent.startsWith(realBase)) {
                return "错误：不允许写入指定目录之外的文件";
            }

            if (Files.exists(target) && Files.isDirectory(target)) {
                return "错误：目标是一个目录: " + path;
            }
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                Path realTarget = target.toRealPath();
                if (!realTarget.startsWith(realBase)) {
                    return "错误：不允许写入指定目录之外的文件";
                }
            }

            Files.write(target, bytes);
            log.info("工具调用 writeFile path={} bytes={}", target, bytes.length);
            return "已写入 " + path + "（" + bytes.length + " 字节）";
        } catch (Exception e) {
            log.warn("写入文件失败 path={}: {}", path, e.toString());
            return "错误：写入失败 " + e.getMessage();
        }
    }
}
