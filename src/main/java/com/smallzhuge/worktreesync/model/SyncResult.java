package com.smallzhuge.worktreesync.model;

import java.util.ArrayList;
import java.util.List;

/** 同步结果汇总。 */
public final class SyncResult {

    public final List<String> applied = new ArrayList<>();
    public final List<String> skipped = new ArrayList<>();
    public final List<String> failed = new ArrayList<>();

    public void apply(String message) {
        applied.add(message);
    }

    public void skip(String message) {
        skipped.add(message);
    }

    public void fail(String message) {
        failed.add(message);
    }

    public boolean hasFailure() {
        return !failed.isEmpty();
    }

    public String plainSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("已应用 ").append(applied.size())
                .append(" 项，跳过 ").append(skipped.size())
                .append(" 项，失败 ").append(failed.size()).append(" 项");
        return sb.toString();
    }

    /** 通知标题：保持短，避免被气泡截断。 */
    public String notificationTitle() {
        return hasFailure()
                ? "Worktree Sync — 有 " + failed.size() + " 项失败"
                : "Worktree Sync 已完成";
    }

    /** 一句话计数，放在正文首行。 */
    public String summaryLine() {
        StringBuilder sb = new StringBuilder();
        appendCount(sb, "已应用", applied.size());
        appendCount(sb, "跳过", skipped.size());
        appendCount(sb, "失败", failed.size());
        return sb.toString();
    }

    /**
     * 通知正文（HTML）。每组最多列 {@value #MAX_LINES} 条，超出折叠计数，
     * 避免气泡被撑得过高。
     */
    public String toHtml() {
        StringBuilder sb = new StringBuilder();
        sb.append("<b>").append(escape(summaryLine())).append("</b>");
        appendGroup(sb, "已应用", applied, "✓");
        appendGroup(sb, "失败", failed, "✗");
        appendGroup(sb, "跳过", skipped, "·");
        return sb.toString();
    }

    private static final int MAX_LINES = 6;

    private void appendGroup(StringBuilder sb, String title, List<String> items, String bullet) {
        if (items.isEmpty()) {
            return;
        }
        sb.append("<br><br><b>").append(title).append(" ").append(items.size()).append(" 项</b>");
        int shown = Math.min(items.size(), MAX_LINES);
        for (int i = 0; i < shown; i++) {
            sb.append("<br>&nbsp;&nbsp;").append(bullet).append(' ').append(escape(items.get(i)));
        }
        if (items.size() > shown) {
            sb.append("<br>&nbsp;&nbsp;… 另有 ").append(items.size() - shown).append(" 项");
        }
    }

    private static void appendCount(StringBuilder sb, String label, int count) {
        if (count <= 0) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(" · ");
        }
        sb.append(label).append(' ').append(count).append(" 项");
    }

    private static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    public String toPlainText() {
        StringBuilder sb = new StringBuilder();
        sb.append("已应用（").append(applied.size()).append("）\n");
        for (String s : applied) {
            sb.append("  ✓ ").append(s).append('\n');
        }
        if (!failed.isEmpty()) {
            sb.append("\n失败（").append(failed.size()).append("）\n");
            for (String s : failed) {
                sb.append("  ✗ ").append(s).append('\n');
            }
        }
        if (!skipped.isEmpty()) {
            sb.append("\n跳过（").append(skipped.size()).append("）\n");
            for (String s : skipped) {
                sb.append("  - ").append(s).append('\n');
            }
        }
        return sb.toString();
    }
}
