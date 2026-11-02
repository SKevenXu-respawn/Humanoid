package com.skevenxu.humanoid.ai.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

/**
 * 沙箱文件读取：只能读取配置目录内的文本文件，防止越权访问服务器文件。
 */
@Component
public class FileReadTool {

    private static final Logger log = LoggerFactory.getLogger(FileReadTool.class);

    private final Path baseDir;
    private final int maxBytes;

    public FileReadTool(@Value("${humanoid.tools.file-base-dir:/app/files}") String baseDir,
                        @Value("${humanoid.tools.max-file-bytes:262144}") int maxBytes) {
        this.baseDir = Paths.get(baseDir).toAbsolutePath().normalize();
        this.maxBytes = maxBytes;
        try {
            Files.createDirectories(this.baseDir);
        } catch (Exception e) {
            log.warn("无法创建文件目录 {}: {}", this.baseDir, e.toString());
        }
    }

    @Tool(description = "列出目录下的文件和子目录。path 为相对路径，例如 '.' 表示根目录，'docs' 表示子目录。")
    public String listFiles(String path) {
        // 1. 处理空路径，默认查看根目录
        if (path == null || path.isBlank()) {
            path = ".";
        }

        try {
            // 2. 解析并校验基础路径安全性（防止路径穿越）
            Path target = baseDir.resolve(path).normalize();
            if (!target.startsWith(baseDir)) {
                return "错误：不允许访问指定目录之外的文件";
            }

            // 3. 使用 toRealPath 解析真实路径，防止符号链接（软链接）绕过
            Path realBase = baseDir.toRealPath();
            Path realTarget = target.toRealPath();

            if (!realTarget.startsWith(realBase)) {
                return "错误：不允许访问指定目录之外的文件";
            }

            // 4. 检查是否为目录
            if (!Files.isDirectory(realTarget)) {
                return "错误：指定路径不是一个目录: " + path;
            }

            // 5. 读取目录内容，限制返回数量防止上下文爆炸
            int maxEntries = 100; // 最多返回 100 个条目
            StringBuilder sb = new StringBuilder();
            sb.append("目录 [").append(path).append("] 下的内容：\n");

            try (Stream<Path> stream = Files.list(realTarget)) {
                List<Path> entries = stream
                        .limit(maxEntries + 1) // 多取一个用来判断是否被截断
                        .toList();

                if (entries.isEmpty()) {
                    return sb.append("(空目录)").toString();
                }

                int count = 0;
                for (Path entry : entries) {
                    if (count >= maxEntries) {
                        sb.append("... (已省略更多文件，请缩小查询范围)\n");
                        break;
                    }
                    // 如果是目录，加一个 / 后缀区分
                    String suffix = Files.isDirectory(entry) ? "/" : "";
                    sb.append("- ").append(entry.getFileName().toString()).append(suffix).append("\n");
                    count++;
                }
            }

            log.info("工具调用 listFiles path={} count={}", realTarget, sb.toString().split("\n").length - 1);
            return sb.toString();

        } catch (java.nio.file.NoSuchFileException e) {
            return "错误：目录不存在: " + path;
        } catch (Exception e) {
            log.warn("列出目录失败 path={}: {}", path, e.toString());
            return "错误：读取目录失败 " + e.getMessage();
        }
    }

    @Tool(description = "读取文件目录中的文本文件内容。path 为相对路径，例如 note.txt 或 docs/a.md")
    public String readFile(String path) {
        if (path == null || path.isBlank()) {
            return "错误：path 不能为空";
        }
        try {
            Path target = baseDir.resolve(path).normalize();
            if (!target.startsWith(baseDir)) {
                return "错误：不允许访问指定目录之外的文件";
            }
            if (!Files.isRegularFile(target)) {
                return "错误：文件不存在或不是普通文件: " + path;
            }

            Path realBase = baseDir.toRealPath();
            Path realTarget = target.toRealPath();
            if (!realTarget.startsWith(realBase)) {
                return "错误：不允许访问指定目录之外的文件";
            }

            long size = Files.size(realTarget);
            if (size > maxBytes) {
                return "错误：文件过大（" + size + " 字节），上限为 " + maxBytes + " 字节";
            }
            byte[] bytes = Files.readAllBytes(realTarget);
            for (byte b : bytes) {
                if (b == 0) {
                    return "错误：不支持读取二进制文件";
                }
            }

            log.info("工具调用 readFile path={} bytes={}", realTarget, bytes.length);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("读取文件失败 path={}: {}", path, e.toString());
            return "错误：读取失败 " + e.getMessage();
        }
    }
}
