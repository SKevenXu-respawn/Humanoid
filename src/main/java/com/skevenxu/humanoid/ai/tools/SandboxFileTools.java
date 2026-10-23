package com.skevenxu.humanoid.ai.tools;

import com.skevenxu.humanoid.ai.FileSandbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/**
 * 按 QQ 号隔离的文件工具：相对路径默认落在 users/&lt;QQ号&gt;，
 * skills/ 与 tools/ 为共享目录，可只读或读写。
 */
@Component
public class SandboxFileTools {

    private static final Logger log = LoggerFactory.getLogger(SandboxFileTools.class);
    private static final Set<String> IGNORED_DIRS =
            Set.of(".git", "node_modules", "target", "build", ".idea", "dist", "__pycache__");
    private static final int MAX_LINE_CHARS = 2000;
    private static final int MAX_GREP_OUTPUT = 6000;

    private final FileSandbox sandbox;
    private final int maxFileBytes;

    public SandboxFileTools(FileSandbox sandbox,
                            @Value("${humanoid.tools.max-file-bytes:262144}") int maxFileBytes) {
        this.sandbox = sandbox;
        this.maxFileBytes = maxBytes(maxFileBytes);
    }

    @Tool(description = """
            读取当前用户目录中的文本文件（也可以读取 skills/ 或 tools/ 下的共享文件）。
            path 为相对路径（如 note.txt、docs/a.md）或 skills/xxx.md；可指定起始行 offset 和最大行数 limit。""")
    public String readFile(@ToolParam(description = "相对路径或 skills/、tools/ 前缀路径") String path,
                           @ToolParam(description = "起始行，默认 1", required = false) Integer offset,
                           @ToolParam(description = "最多读取行数，默认 2000", required = false) Integer limit) {
        Long userId = currentUserId();
        if (userId == null) {
            return "错误：无法确定当前用户";
        }
        try {
            Path target = sandbox.resolve(userId, path);
            if (!Files.isRegularFile(target)) {
                return "错误：文件不存在或不是普通文件: " + path;
            }
            if (Files.size(target) > maxFileBytes) {
                return "错误：文件过大（" + Files.size(target) + " 字节），上限 " + maxFileBytes;
            }
            byte[] bytes = Files.readAllBytes(target);
            for (byte b : bytes) {
                if (b == 0) {
                    return "错误：不支持读取二进制文件";
                }
            }
            String content = new String(bytes, StandardCharsets.UTF_8);
            String[] lines = content.split("\n", -1);
            int start = offset == null || offset < 1 ? 1 : offset;
            int max = limit == null || limit < 1 ? 2000 : Math.min(limit, 4000);
            if (start > lines.length) {
                return "文件共有 " + lines.length + " 行，起始行超出范围";
            }
            int end = Math.min(lines.length, start - 1 + max);
            StringBuilder sb = new StringBuilder();
            sb.append("File: ").append(target).append('\n');
            sb.append("Showing lines ").append(start).append('-').append(end)
                    .append(" of ").append(lines.length).append("\n\n");
            for (int i = start - 1; i < end; i++) {
                String line = lines[i].replace("\r", "");
                if (line.length() > MAX_LINE_CHARS) {
                    line = line.substring(0, MAX_LINE_CHARS) + "…";
                }
                sb.append(String.format("%6d\t%s%n", i + 1, line));
            }
            log.info("工具调用 readFile path={} lines={}-{}/{}", target, start, end, lines.length);
            return sb.toString().stripTrailing();
        } catch (IllegalArgumentException e) {
            return "错误：" + e.getMessage();
        } catch (Exception e) {
            log.warn("读取文件失败 path={}: {}", path, e.toString());
            return "错误：读取失败 " + e.getMessage();
        }
    }

    @Tool(description = "在当前用户目录中创建或覆盖一个文本文件（也可写入 skills/、tools/）。path 为相对路径。")
    public String writeFile(@ToolParam(description = "相对路径") String path,
                            @ToolParam(description = "文件内容") String content) {
        Long userId = currentUserId();
        if (userId == null) {
            return "错误：无法确定当前用户";
        }
        String text = content == null ? "" : content;
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxFileBytes) {
            return "错误：内容过大（" + bytes.length + " 字节），上限 " + maxFileBytes;
        }
        try {
            Path target = sandbox.resolve(userId, path);
            if (Files.isDirectory(target)) {
                return "错误：目标是目录: " + path;
            }
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(target, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            log.info("工具调用 writeFile path={} bytes={}", target, bytes.length);
            return "已写入 " + path + "（" + bytes.length + " 字节）";
        } catch (IllegalArgumentException e) {
            return "错误：" + e.getMessage();
        } catch (Exception e) {
            log.warn("写入文件失败 path={}: {}", path, e.toString());
            return "错误：写入失败 " + e.getMessage();
        }
    }

    @Tool(description = "编辑文件：把 oldString 替换为 newString。replaceAll 为 true 时替换全部，否则只替换第一处。")
    public String editFile(@ToolParam(description = "相对路径") String path,
                           @ToolParam(description = "要替换的原文") String oldString,
                           @ToolParam(description = "新内容") String newString,
                           @ToolParam(description = "是否替换全部，默认 false", required = false) Boolean replaceAll) {
        if (oldString == null || oldString.isEmpty()) {
            return "错误：oldString 不能为空";
        }
        Long userId = currentUserId();
        if (userId == null) {
            return "错误：无法确定当前用户";
        }
        try {
            Path target = sandbox.resolve(userId, path);
            if (!Files.isRegularFile(target)) {
                return "错误：文件不存在: " + path;
            }
            if (Files.size(target) > maxFileBytes) {
                return "错误：文件过大，无法编辑";
            }
            String content = Files.readString(target, StandardCharsets.UTF_8);
            if (!content.contains(oldString)) {
                return "错误：文件中没有找到要替换的内容";
            }
            boolean all = Boolean.TRUE.equals(replaceAll);
            String updated = all ? content.replace(oldString, newString == null ? "" : newString)
                    : content.replaceFirst(Pattern.quote(oldString), java.util.regex.Matcher.quoteReplacement(
                            newString == null ? "" : newString));
            byte[] bytes = updated.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > maxFileBytes) {
                return "错误：修改后内容过大";
            }
            Files.writeString(target, updated, StandardCharsets.UTF_8);
            log.info("工具调用 editFile path={} replaceAll={}", target, all);
            return "已更新 " + path + "（" + (all ? "全部替换" : "替换 1 处") + "）";
        } catch (IllegalArgumentException e) {
            return "错误：" + e.getMessage();
        } catch (Exception e) {
            log.warn("编辑文件失败 path={}: {}", path, e.toString());
            return "错误：编辑失败 " + e.getMessage();
        }
    }

    @Tool(description = "列出当前用户目录（或 skills/、tools/、指定子目录）下的文件和子目录。depth 默认 1，limit 默认 100。")
    public String listDirectory(@ToolParam(description = "目录路径，默认当前用户根目录", required = false) String path,
                                @ToolParam(description = "递归深度，默认 1", required = false) Integer depth,
                                @ToolParam(description = "最多返回条目数，默认 100", required = false) Integer limit) {
        Long userId = currentUserId();
        if (userId == null) {
            return "错误：无法确定当前用户";
        }
        try {
            Path dir = sandbox.resolve(userId, path);
            if (!Files.isDirectory(dir)) {
                return "错误：不是目录: " + path;
            }
            int maxDepth = depth == null || depth < 1 ? 1 : Math.min(depth, 3);
            int maxEntries = limit == null || limit < 1 ? 100 : Math.min(limit, 500);
            List<Path> entries;
            try (Stream<Path> stream = Files.walk(dir, maxDepth)) {
                entries = stream.filter(p -> !p.equals(dir))
                        .filter(p -> !isIgnored(p))
                        .limit(maxEntries + 1L)
                        .toList();
            }
            if (entries.isEmpty()) {
                return "目录 " + dir + " 是空的";
            }
            List<Path> sorted = new ArrayList<>(entries);
            sorted.sort(Comparator.<Path, Integer>comparing(p -> Files.isDirectory(p) ? 0 : 1)
                    .thenComparing(p -> p.getFileName().toString()));
            StringBuilder sb = new StringBuilder(dir.toString()).append('\n');
            int shown = 0;
            for (Path entry : sorted) {
                if (shown >= maxEntries) {
                    sb.append("... (已达上限 ").append(maxEntries).append(")\n");
                    break;
                }
                String relative = dir.relativize(entry).toString().replace('\\', '/');
                sb.append(Files.isDirectory(entry) ? "  [dir]  " : "  [file] ").append(relative).append('\n');
                shown++;
            }
            log.info("工具调用 listDirectory path={} count={}", dir, shown);
            return sb.toString().stripTrailing();
        } catch (IllegalArgumentException e) {
            return "错误：" + e.getMessage();
        } catch (Exception e) {
            log.warn("列出目录失败 path={}: {}", path, e.toString());
            return "错误：读取目录失败 " + e.getMessage();
        }
    }

    @Tool(description = "在当前用户目录（或指定目录）的文本文件里用正则搜索内容，返回匹配的文件、行号和内容。")
    public String grep(@ToolParam(description = "正则表达式") String pattern,
                       @ToolParam(description = "搜索目录，默认当前用户根目录", required = false) String path,
                       @ToolParam(description = "文件名 glob 过滤，如 *.md", required = false) String glob) {
        Long userId = currentUserId();
        if (userId == null) {
            return "错误：无法确定当前用户";
        }
        if (pattern == null || pattern.isBlank()) {
            return "错误：pattern 不能为空";
        }
        Pattern regex;
        try {
            regex = Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            return "错误：正则表达式无效";
        }
        PathMatcher nameMatcher = glob == null || glob.isBlank()
                ? null
                : FileSystems.getDefault().getPathMatcher("glob:" + glob);
        try {
            Path dir = sandbox.resolve(userId, path);
            if (!Files.exists(dir)) {
                return "错误：路径不存在: " + path;
            }
            StringBuilder sb = new StringBuilder();
            int matches = 0;
            List<Path> files;
            if (Files.isRegularFile(dir)) {
                files = List.of(dir);
            } else {
                try (Stream<Path> stream = Files.walk(dir, 6)) {
                    files = stream.filter(Files::isRegularFile)
                            .filter(p -> !isIgnored(p))
                            .filter(p -> nameMatcher == null || nameMatcher.matches(p.getFileName()))
                            .limit(500)
                            .toList();
                }
            }
            for (Path file : files) {
                if (Files.size(file) > 512 * 1024) {
                    continue;
                }
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    if (!regex.matcher(lines.get(i)).find()) {
                        continue;
                    }
                    matches++;
                    String line = lines.get(i).trim();
                    if (line.length() > 300) {
                        line = line.substring(0, 300) + "…";
                    }
                    sb.append(file).append(':').append(i + 1).append(": ").append(line).append('\n');
                    if (matches >= 100 || sb.length() >= MAX_GREP_OUTPUT) {
                        sb.append("... (结果已截断)\n");
                        log.info("工具调用 grep pattern={} matches={} (truncated)", pattern, matches);
                        return sb.toString().stripTrailing();
                    }
                }
            }
            log.info("工具调用 grep pattern={} matches={}", pattern, matches);
            return matches == 0 ? "没有匹配结果" : sb.toString().stripTrailing();
        } catch (IllegalArgumentException e) {
            return "错误：" + e.getMessage();
        } catch (Exception e) {
            log.warn("grep 失败 pattern={}: {}", pattern, e.toString());
            return "错误：搜索失败 " + e.getMessage();
        }
    }

    @Tool(description = "按 glob 模式查找文件，如 **/*.png。默认在当前用户目录中查找，最多返回 200 条。")
    public String glob(@ToolParam(description = "glob 模式") String pattern,
                       @ToolParam(description = "搜索目录，默认当前用户根目录", required = false) String path) {
        Long userId = currentUserId();
        if (userId == null) {
            return "错误：无法确定当前用户";
        }
        if (pattern == null || pattern.isBlank()) {
            return "错误：pattern 不能为空";
        }
        try {
            Path dir = sandbox.resolve(userId, path);
            if (!Files.isDirectory(dir)) {
                return "错误：不是目录: " + path;
            }
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            List<String> results = new ArrayList<>();
            try (Stream<Path> stream = Files.walk(dir, 8)) {
                for (Path p : (Iterable<Path>) stream.filter(Files::isRegularFile).filter(p -> !isIgnored(p))::iterator) {
                    Path relative = dir.relativize(p);
                    if (matcher.matches(relative) || matcher.matches(p.getFileName())) {
                        results.add(p.toString());
                        if (results.size() >= 200) {
                            break;
                        }
                    }
                }
            }
            log.info("工具调用 glob pattern={} matches={}", pattern, results.size());
            return results.isEmpty() ? "没有匹配的文件" : String.join("\n", results);
        } catch (IllegalArgumentException e) {
            return "错误：" + e.getMessage();
        } catch (Exception e) {
            log.warn("glob 失败 pattern={}: {}", pattern, e.toString());
            return "错误：查找失败 " + e.getMessage();
        }
    }

    private static Long currentUserId() {
        ToolContext.ChatScope scope = ToolContext.get();
        return scope == null ? null : scope.userId();
    }

    private static boolean isIgnored(Path path) {
        for (Path part : path) {
            if (IGNORED_DIRS.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    private static int maxBytes(int value) {
        return value < 1024 ? 262144 : value;
    }
}
