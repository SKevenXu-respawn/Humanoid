package com.skevenxu.humanoid.admin.support;

import java.time.LocalDateTime;

/** 管理端通用参数处理。 */
public final class AdminSupport {

    private AdminSupport() {
    }

    public static int page(Integer page) {
        return page == null || page < 1 ? 1 : page;
    }

    public static int size(Integer size) {
        if (size == null || size < 1) {
            return 20;
        }
        return Math.min(size, 100);
    }

    public static long scopeGroup(String messageType, Long groupId) {
        return "group".equals(messageType) && groupId != null ? groupId : 0L;
    }

    public static String messageType(String messageType) {
        if (messageType == null || messageType.isBlank()) {
            return "private";
        }
        String value = messageType.trim().toLowerCase();
        if (!"private".equals(value) && !"group".equals(value)) {
            throw new IllegalArgumentException("messageType 只能是 private 或 group");
        }
        return value;
    }

    public static String requireText(String value, int max, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        String text = value.trim();
        if (text.length() > max) {
            throw new IllegalArgumentException(field + " 不能超过 " + max + " 字");
        }
        return text;
    }

    /** 解析 ISO 或 "yyyy-MM-dd HH:mm[:ss]" 时间；空值返回 null。 */
    public static LocalDateTime parseDateTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.trim();
        try {
            return LocalDateTime.parse(text);
        } catch (Exception ignored) {
            try {
                return LocalDateTime.parse(text.replace(' ', 'T'));
            } catch (Exception e) {
                throw new IllegalArgumentException("时间格式错误: " + value);
            }
        }
    }
}
