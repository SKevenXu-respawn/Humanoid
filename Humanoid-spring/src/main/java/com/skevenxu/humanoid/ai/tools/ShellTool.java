package com.skevenxu.humanoid.ai.tools;

import com.skevenxu.humanoid.ai.FileSandbox;
import com.skevenxu.humanoid.ai.PromptSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * 沙箱 shell 工具：工作目录固定在 bot_files，清空继承的环境变量，限制超时与输出长度。
 * 注意：这不是真正的安全沙箱，容器内仍可访问文件和网络，请谨慎启用。
 */
@Component
public class ShellTool {

    private static final Logger log = LoggerFactory.getLogger(ShellTool.class);
    private static final List<Pattern> BLOCKED = List.of(
            Pattern.compile("(?i)\\brm\\s+(-[a-zA-Z]+\\s+)*/(\\s|$)"),
            Pattern.compile("(?i)\\bmkfs(\\.|\\s|$)"),
            Pattern.compile("(?i)\\b(shutdown|reboot|poweroff|halt)\\b"),
            Pattern.compile("(?i)\\bdd\\b[^\\n]*\\bof=/dev/"),
            Pattern.compile("(?i):\\(\\)\\s*\\{"),
            Pattern.compile("(?i)\\bchmod\\s+-R\\s+777\\s+/"),
            Pattern.compile("(?i)\\bchown\\s+-R\\s+[^\\s]+\\s+/\\s*$"),
            Pattern.compile("(?i)>\\s*/dev/(sd|nvme|hd)"));

    private final FileSandbox sandbox;
    private final boolean enabled;
    private final int timeoutSeconds;
    private final int maxOutputChars;

    public ShellTool(FileSandbox sandbox,
                     @Value("${humanoid.tools.shell.enabled:true}") boolean enabled,
                     @Value("${humanoid.tools.shell.timeout-seconds:15}") int timeoutSeconds,
                     @Value("${humanoid.tools.shell.max-output-chars:4000}") int maxOutputChars) {
        this.sandbox = sandbox;
        this.enabled = enabled;
        this.timeoutSeconds = Math.max(1, timeoutSeconds);
        this.maxOutputChars = Math.max(200, maxOutputChars);
    }

    @Tool(description = """
            在服务器的工作目录中执行一条 shell 命令，返回输出和退出码。
            工作目录固定为 bot_files，环境里可用的解释器有 sh / bash / python3 / pip3。
            有超时和输出长度限制；仅在确实需要系统命令时使用，不要读取服务器敏感文件。""")
    public String runShell(@ToolParam(description = "要执行的 shell 命令") String command) {
        if (!enabled) {
            return "错误：shell 工具已禁用";
        }
        if (command == null || command.isBlank()) {
            return "错误：命令不能为空";
        }
        if (command.length() > 2000) {
            return "错误：命令过长";
        }
        ToolContext.ChatScope scope = ToolContext.get();
        if (scope == null) {
            return "错误：无法确定当前用户";
        }
        Path workingDir = sandbox.userDir(scope.userId());
        for (Pattern pattern : BLOCKED) {
            if (pattern.matcher(command).find()) {
                log.warn("拒绝执行危险命令: {}", command);
                return "错误：该命令被安全策略拒绝";
            }
        }

        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        List<String> cmd = windows
                ? List.of("cmd.exe", "/c", command)
                : List.of("/bin/sh", "-c", command);

        try {
            ProcessBuilder builder = new ProcessBuilder(cmd);
            builder.directory(workingDir.toFile());
            builder.redirectErrorStream(true);
            if (!windows) {
                Map<String, String> env = builder.environment();
                env.clear();
                env.put("PATH", "/usr/local/bin:/usr/bin:/bin:/usr/local/sbin:/usr/sbin:/sbin");
                env.put("HOME", workingDir.toString());
                env.put("LANG", "C.UTF-8");
                env.put("LC_ALL", "C.UTF-8");
                env.put("HUMANOID_SKILLS_DIR", sandbox.skillsDir().toString());
                env.put("HUMANOID_TOOLS_DIR", sandbox.toolsDir().toString());
            }

            Process process = builder.start();
            StringBuilder output = new StringBuilder();
            AtomicBoolean truncated = new AtomicBoolean(false);
            Thread reader = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        synchronized (output) {
                            if (output.length() < maxOutputChars) {
                                output.append(line).append('\n');
                            } else {
                                truncated.set(true);
                            }
                        }
                    }
                } catch (IOException ignored) {
                }
            }, "shell-tool-reader");
            reader.setDaemon(true);
            reader.start();

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            int exitCode = -1;
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
            } else {
                exitCode = process.exitValue();
            }
            reader.join(1000);

            String text;
            synchronized (output) {
                text = output.toString().trim();
            }
            if (truncated.get()) {
                text += "\n...(输出已截断)";
            }
            if (!finished) {
                text = "命令超时（" + timeoutSeconds + " 秒）已终止\n" + text;
            }

            log.info("工具调用 runShell command={} exit={}",
                    PromptSanitizer.truncate(command.replaceAll("\\s+", " "), 200), exitCode);
            return "退出码 " + exitCode + "\n" + text;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "错误：命令执行被中断";
        } catch (Exception e) {
            log.warn("shell 执行失败: {}", e.toString());
            return "错误：命令执行失败 " + e.getMessage();
        }
    }
}
