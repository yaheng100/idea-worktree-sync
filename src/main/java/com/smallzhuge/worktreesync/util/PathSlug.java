package com.smallzhuge.worktreesync.util;

/**
 * Claude Code 按「工作目录绝对路径」的 slug 分目录存放会话：
 * {@code ~/.claude/projects/<slug>/<session-uuid>.jsonl}
 *
 * <p>slug 规则：把路径中所有非字母数字字符替换成 '-'。
 * 例：{@code E:\Work\xzg-system} → {@code E--Work-xzg-system}。
 */
public final class PathSlug {

    private PathSlug() {
    }

    public static String slugify(String absolutePath) {
        if (absolutePath == null) {
            return "";
        }
        String p = absolutePath.trim();
        // 去掉尾部斜杠，避免把 "E:\Work\xzg-system\" 变成 "E--Work-xzg-system-" 多加一横
        while (p.endsWith("\\") || p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        StringBuilder sb = new StringBuilder(p.length());
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            boolean alnum = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
            sb.append(alnum ? c : '-');
        }
        return sb.toString();
    }
}
