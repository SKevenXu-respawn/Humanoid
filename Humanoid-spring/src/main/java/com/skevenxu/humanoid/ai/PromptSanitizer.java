package com.skevenxu.humanoid.ai;

/**
 * 清理进入模型提示词的文本：
 * - 反斜杠字面量会被 DeepSeek 的 JSON 解析器误判（unexpected end of hex escape），替换为 /
 * - 截断字符串时不能拆开代理对，否则会留下孤立代理字符同样导致解析失败
 */
public final class PromptSanitizer {

    private PromptSanitizer() {
    }

    public static String clean(String value) {
        if (value == null || value.isEmpty()) {
            return value == null ? "" : value;
        }
        String text = value.replace('\\', '/');
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))) {
                    sb.append(c).append(text.charAt(++i));
                }
                // 孤立高位代理直接丢弃
            } else if (Character.isLowSurrogate(c)) {
                // 孤立低位代理直接丢弃
            } else if (c == '\n' || c == '\t' || !Character.isISOControl(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 按字符数截断，但绝不拆开代理对（emoji、数学符号等 4 字节字符）。 */
    public static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        if (value.length() <= max) {
            return value;
        }
        int end = max;
        if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end) + "…";
    }
}
