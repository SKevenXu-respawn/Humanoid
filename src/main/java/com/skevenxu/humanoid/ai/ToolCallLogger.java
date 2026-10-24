package com.skevenxu.humanoid.ai;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.skevenxu.humanoid.ai.tools.ToolContext;
import com.skevenxu.humanoid.entity.BotToolCall;
import com.skevenxu.humanoid.mapper.BotToolCallMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Locale;
import java.util.UUID;
import java.util.Locale;

/** 包装所有工具回调，把调用参数和结果写入 bot_tool_call。 */
@Component
public class ToolCallLogger {

    private static final Logger log = LoggerFactory.getLogger(ToolCallLogger.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Gson GSON = new Gson();
    private static final int MAX_ARGUMENTS = 500;
    private static final int MAX_RESULT = 1000;

    private final BotToolCallMapper toolCallMapper;
    private final ToolResultPostProcessor postProcessor;
    private final ToolProgressNotifier progressNotifier;
    private final String playwrightOutputDir;

    public ToolCallLogger(BotToolCallMapper toolCallMapper,
                          ToolResultPostProcessor postProcessor,
                          ToolProgressNotifier progressNotifier,
                          @Value("${humanoid.tools.playwright-output-dir:/app/output}") String playwrightOutputDir) {
        this.toolCallMapper = toolCallMapper;
        this.postProcessor = postProcessor;
        this.progressNotifier = progressNotifier;
        this.playwrightOutputDir = playwrightOutputDir.endsWith("/")
                ? playwrightOutputDir.substring(0, playwrightOutputDir.length() - 1)
                : playwrightOutputDir;
    }

    public ToolCallback[] wrap(ToolCallback[] callbacks) {
        ToolCallback[] wrapped = new ToolCallback[callbacks.length];
        for (int i = 0; i < callbacks.length; i++) {
            wrapped[i] = new LoggingCallback(callbacks[i]);
        }
        return wrapped;
    }

    private void record(String toolName, String arguments, String result) {
        try {
            ToolContext.ChatScope scope = ToolContext.get();
            if (scope == null) {
                return;
            }
            long groupId = "group".equals(scope.messageType()) && scope.groupId() != null
                    ? scope.groupId() : 0L;

            BotToolCall call = new BotToolCall();
            call.setUserId(scope.userId());
            call.setMessageType(scope.messageType());
            call.setGroupId(groupId);
            call.setToolName(toolName);
            call.setArguments(PromptSanitizer.truncate(arguments, MAX_ARGUMENTS));
            call.setResult(PromptSanitizer.truncate(result, MAX_RESULT));
            call.setCreatedAt(LocalDateTime.now(ZONE));
            toolCallMapper.insert(call);

            if (isNetworkTool(toolName)) {
                log.info("网络工具调用 {} 参数={} 结果长度={}", toolName,
                        PromptSanitizer.truncate(arguments, 200),
                        result == null ? 0 : result.length());
            }
        } catch (Exception e) {
            log.warn("工具调用日志写入失败 tool={}: {}", toolName, e.toString());
        }
    }

    private record ScreenshotCall(String input, String filename) {
    }

    /** 截图工具统一注入唯一文件名，避免并发用户互相覆盖，也便于把文件移动到用户目录。 */
    private ScreenshotCall prepareScreenshot(String toolName, String toolInput) {
        if (toolName == null || !toolName.toLowerCase(Locale.ROOT).contains("screenshot")) {
            return new ScreenshotCall(toolInput, null);
        }
        try {
            JsonObject args = JsonParser.parseString(
                    toolInput == null || toolInput.isBlank() ? "{}" : toolInput).getAsJsonObject();
            String type = args.has("type") && !args.get("type").isJsonNull()
                    ? args.get("type").getAsString().toLowerCase(Locale.ROOT) : "png";
            String extension = type.contains("jpeg") || type.equals("jpg") ? ".jpg" : ".png";
            String filename = "shot-" + System.currentTimeMillis() + "-"
                    + UUID.randomUUID().toString().substring(0, 8) + extension;
            String containerPath = playwrightOutputDir + "/" + filename;
            // 显式文件名不会被 Playwright 放进 output-dir，必须传容器内的绝对路径
            args.addProperty("filename", containerPath);
            return new ScreenshotCall(GSON.toJson(args), containerPath);
        } catch (Exception e) {
            return new ScreenshotCall(toolInput, null);
        }
    }

    private static boolean isNetworkTool(String toolName) {
        if (toolName == null) {
            return false;
        }
        String name = toolName.toLowerCase(Locale.ROOT);
        return name.contains("search") || name.contains("fetch");
    }

    /** 委托真实回调，执行后记录日志。 */
    private final class LoggingCallback implements ToolCallback {

        private final ToolCallback delegate;

        private LoggingCallback(ToolCallback delegate) {
            this.delegate = delegate;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return delegate.getToolDefinition();
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return delegate.getToolMetadata();
        }

        @Override
        public String call(String toolInput) {
            String toolName = delegate.getToolDefinition().name();
            ScreenshotCall call = prepareScreenshot(toolName, toolInput);
            String result;
            try (ToolProgressNotifier.ToolExecution ignored = progressNotifier.toolStarted(toolName)) {
                result = PromptSanitizer.clean(delegate.call(call.input()));
            }
            result = postProcessor.process(toolName, result, call.filename());
            record(toolName, call.input(), result);
            return result;
        }

        @Override
        public String call(String toolInput,
                           org.springframework.ai.chat.model.ToolContext toolContext) {
            String toolName = delegate.getToolDefinition().name();
            ScreenshotCall call = prepareScreenshot(toolName, toolInput);
            String result;
            try (ToolProgressNotifier.ToolExecution ignored = progressNotifier.toolStarted(toolName)) {
                result = PromptSanitizer.clean(delegate.call(call.input(), toolContext));
            }
            result = postProcessor.process(toolName, result, call.filename());
            record(toolName, call.input(), result);
            return result;
        }
    }
}
