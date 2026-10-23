package com.skevenxu.humanoid.ai.tools;

/** 当前 AI 请求所属的会话，供工具读取，避免让模型传入用户身份。 */
public final class ToolContext {

    public record ChatScope(Long userId, String messageType, Long groupId) {
    }

    private static final ThreadLocal<ChatScope> CURRENT = new ThreadLocal<>();

    private ToolContext() {
    }

    public static void set(ChatScope scope) {
        CURRENT.set(scope);
    }

    public static ChatScope get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
