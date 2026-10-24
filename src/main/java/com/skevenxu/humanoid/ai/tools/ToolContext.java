package com.skevenxu.humanoid.ai.tools;

/** 当前 AI 请求所属的会话，供工具读取，避免让模型传入用户身份。 */
public final class ToolContext {

    /** progressEnabled：是否向用户发送工具调用进度提示（后台主动行为不发送）。 */
    public record ChatScope(Long userId, String messageType, Long groupId, boolean progressEnabled) {

        public ChatScope(Long userId, String messageType, Long groupId) {
            this(userId, messageType, groupId, false);
        }
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
