package com.skevenxu.humanoid.ai;

import com.skevenxu.humanoid.ai.tools.ToolContext;
import com.skevenxu.humanoid.entity.BotToolCall;
import com.skevenxu.humanoid.mapper.BotToolCallMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Locale;

/** 包装所有工具回调，把调用参数和结果写入 bot_tool_call。 */
@Component
public class ToolCallLogger {

    private static final Logger log = LoggerFactory.getLogger(ToolCallLogger.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final int MAX_ARGUMENTS = 500;
    private static final int MAX_RESULT = 1000;

    private final BotToolCallMapper toolCallMapper;

    public ToolCallLogger(BotToolCallMapper toolCallMapper) {
        this.toolCallMapper = toolCallMapper;
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
            String result = PromptSanitizer.clean(delegate.call(toolInput));
            record(delegate.getToolDefinition().name(), toolInput, result);
            return result;
        }

        @Override
        public String call(String toolInput,
                           org.springframework.ai.chat.model.ToolContext toolContext) {
            String result = PromptSanitizer.clean(delegate.call(toolInput, toolContext));
            record(delegate.getToolDefinition().name(), toolInput, result);
            return result;
        }
    }
}
