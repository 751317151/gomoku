package com.gomoku.util;

/**
 * 名称清洗工具，防止注入和异常字符
 */
public class NameSanitizer {

    private static final int MAX_NAME_LENGTH = 16;
    private static final String DEFAULT_NAME_PREFIX = "玩家";

    /**
     * 清洗并校验玩家昵称
     */
    public static String sanitize(String name) {
        if (name == null || name.trim().isEmpty()) {
            return DEFAULT_NAME_PREFIX + (int) (Math.random() * 9000 + 1000);
        }

        String sanitized = name.trim();

        // 移除控制字符和特殊字符
        sanitized = sanitized.replaceAll("[\\p{Cntrl}\\p{Cc}]", "");

        // 截断
        if (sanitized.length() > MAX_NAME_LENGTH) {
            sanitized = sanitized.substring(0, MAX_NAME_LENGTH);
        }

        if (sanitized.isEmpty()) {
            return DEFAULT_NAME_PREFIX + (int) (Math.random() * 9000 + 1000);
        }

        return sanitized;
    }

    /**
     * 清洗聊天消息
     */
    public static String sanitizeChat(String message) {
        if (message == null || message.trim().isEmpty()) {
            return null;
        }

        String sanitized = message.trim();

        // 移除控制字符
        sanitized = sanitized.replaceAll("[\\p{Cntrl}\\p{Cc}]", "");

        // 截断到200字符
        if (sanitized.length() > 200) {
            sanitized = sanitized.substring(0, 200);
        }

        return sanitized;
    }
}
