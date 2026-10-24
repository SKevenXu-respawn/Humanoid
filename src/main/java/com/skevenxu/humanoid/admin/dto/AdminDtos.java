package com.skevenxu.humanoid.admin.dto;

import java.time.LocalDateTime;
import java.util.List;

/** 管理端请求/响应数据结构。 */
public final class AdminDtos {

    private AdminDtos() {
    }

    public record PageResponse<T>(List<T> records, long total, long page, long size) {
    }

    public record UserRequest(Long userId, String nickname, String remark, Boolean enabled) {
    }

    public record EnabledRequest(Boolean enabled) {
    }

    public record GroupRequest(Long groupId, String name, String remark, Boolean enabled) {
    }

    public record ContextScopeView(Long userId, String messageType, Long groupId,
                                   long messageCount, LocalDateTime lastMessageAt,
                                   int summaryChars, Long lastConversationId) {
    }

    public record ContextMessageView(Long id, String role, String content,
                                     List<String> imageRefs, List<Long> imageIds,
                                     LocalDateTime createdAt) {
    }

    public record SummaryView(Long id, String summary, Long lastConversationId,
                              LocalDateTime updatedAt) {
    }

    public record SummaryUpdateRequest(Long userId, String messageType, Long groupId,
                                       String summary, Long lastConversationId) {
    }

    public record TaskView(Long id, Long userId, String messageType, Long groupId,
                           String title, String status, String pauseReason,
                           LocalDateTime createdAt, LocalDateTime updatedAt,
                           LocalDateTime completedAt, long stepsDone, long stepsTotal) {
    }

    public record TaskStepView(Long id, Long taskId, Integer stepNo, String content,
                               String status, Integer attempts,
                               LocalDateTime createdAt, LocalDateTime completedAt) {
    }

    public record TaskDetailView(TaskView task, List<TaskStepView> steps) {
    }

    public record TaskCreateRequest(Long userId, String messageType, Long groupId,
                                    String title, List<String> steps) {
    }

    public record StepCreateRequest(String content) {
    }

    public record PauseRequest(String reason) {
    }

    public record ReminderCreateRequest(Long userId, String messageType, Long groupId,
                                        String content, LocalDateTime remindAt) {
    }

    public record FriendView(Long userId, String nickname, String remark, String sex,
                             Integer age, String qid, String longNick) {
    }

    public record FriendRequestView(Long id, Long userId, String comment, String via,
                                    String status, String remark, LocalDateTime eventTime,
                                    LocalDateTime createdAt, LocalDateTime handledAt) {
    }

    public record FriendRequestHandleRequest(String remark) {
    }

    public record FilteredFriendRequestView(String flag, String uin, String nick, String source,
                                            String reason, String msg, String groupCode,
                                            String time, String type) {
    }

    public record FilteredFriendHandleRequest(String flag) {
    }

    public record VoiceConfigView(boolean enabled, String speaker, String defaultSpeaker) {
    }

    public record VoiceConfigUpdateRequest(Boolean enabled, String speaker) {
    }
}
