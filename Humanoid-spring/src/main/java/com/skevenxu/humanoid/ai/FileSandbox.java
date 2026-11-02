package com.skevenxu.humanoid.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 文件沙箱布局：
 * <pre>
 * /app/files/
 *   skills/            共享技能（只按名字访问）
 *   tools/             共享脚本/工具
 *   users/&lt;QQ号&gt;/      每个用户的私有工作区（读写）
 * </pre>
 * 相对路径默认解析到 users/&lt;QQ号&gt;；skills/、tools/ 前缀解析到共享目录。
 */
@Component
public class FileSandbox {

    private static final Logger log = LoggerFactory.getLogger(FileSandbox.class);

    private final Path baseDir;
    private final Path skillsDir;
    private final Path toolsDir;

    public FileSandbox(@Value("${humanoid.tools.file-base-dir:/app/files}") String fileBaseDir,
                       @Value("${humanoid.tools.skills-dir:${humanoid.tools.file-base-dir:/app/files}/skills}") String skillsDir) {
        this.baseDir = Paths.get(fileBaseDir).toAbsolutePath().normalize();
        this.skillsDir = Paths.get(skillsDir).toAbsolutePath().normalize();
        this.toolsDir = baseDir.resolve("tools");
        try {
            Files.createDirectories(this.baseDir);
            Files.createDirectories(this.skillsDir);
            Files.createDirectories(this.toolsDir);
        } catch (IOException e) {
            log.warn("无法创建沙箱目录 {}: {}", this.baseDir, e.toString());
        }
    }

    public Path baseDir() {
        return baseDir;
    }

    public Path skillsDir() {
        return skillsDir;
    }

    public Path toolsDir() {
        return toolsDir;
    }

    /** 用户私有工作区，不存在时自动创建。 */
    public Path userDir(Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException("缺少用户身份，无法访问私有目录");
        }
        Path dir = baseDir.resolve("users").resolve(String.valueOf(userId)).normalize();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建用户目录: " + e.getMessage(), e);
        }
        return dir;
    }

    public Path downloadsDir(Long userId) {
        Path dir = userDir(userId).resolve("downloads");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建下载目录: " + e.getMessage(), e);
        }
        return dir;
    }

    /** 解析路径：相对路径落到用户目录，skills/ 与 tools/ 落到共享目录，绝对路径必须落在允许范围内。 */
    public Path resolve(Long userId, String path) {
        Path userDir = userDir(userId);
        String value = path == null ? "" : path.replace('\\', '/').trim();
        if (value.startsWith("./")) {
            value = value.substring(2);
        }

        Path candidate;
        if (value.isEmpty() || ".".equals(value)) {
            candidate = userDir;
        } else if (value.equals("skills") || value.startsWith("skills/")) {
            candidate = baseDir.resolve(value);
        } else if (value.equals("tools") || value.startsWith("tools/")) {
            candidate = baseDir.resolve(value);
        } else if (Paths.get(value).isAbsolute()) {
            candidate = Paths.get(value);
        } else {
            candidate = userDir.resolve(value);
        }
        candidate = candidate.normalize();

        verifyAllowed(userDir, candidate);
        return candidate;
    }

    private void verifyAllowed(Path userDir, Path candidate) {
        if (!candidate.startsWith(userDir)
                && !candidate.startsWith(skillsDir)
                && !candidate.startsWith(toolsDir)) {
            throw new IllegalArgumentException("不允许访问指定目录之外的文件: " + candidate);
        }
        if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Path real = candidate.toRealPath();
                if (!real.startsWith(realOrSelf(userDir))
                        && !real.startsWith(realOrSelf(skillsDir))
                        && !real.startsWith(realOrSelf(toolsDir))) {
                    throw new IllegalArgumentException("不允许通过符号链接访问其它目录");
                }
            } catch (IOException e) {
                throw new IllegalArgumentException("路径校验失败: " + e.getMessage());
            }
        }
    }

    private static Path realOrSelf(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path;
        }
    }
}
