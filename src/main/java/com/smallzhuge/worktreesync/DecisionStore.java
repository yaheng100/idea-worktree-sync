package com.smallzhuge.worktreesync;

import com.intellij.ide.util.PropertiesComponent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.StringTokenizer;

import com.smallzhuge.worktreesync.model.SyncPlan;

/**
 * 「记住选择」的持久化。
 *
 * <p>用 app 级 {@link PropertiesComponent} 存纯文本，不落任何文件到项目目录 ——
 * worktree 本来就很敏感，插件不该往里写自己的状态。
 *
 * <p>记录格式（字段分隔符 &#92;u0001，记录分隔符 &#92;u0002，Windows 路径不会包含这两个字符）：
 * <pre>1|&lt;basePath&gt;|&lt;sourcePath 或空&gt;|&lt;idea 0/1&gt;|&lt;claudeConfig 0/1&gt;|&lt;sessions 0/1&gt;|&lt;days&gt;|&lt;overwrite 0/1&gt;|&lt;maven 0/1&gt;</pre>
 *
 * <p>尾部字段是后加的：老记录解析出来是 {@code null}，按「开启」处理，与界面默认一致。
 */
public final class DecisionStore {

    private static final String KEY = "worktree-sync.decisions";
    /** 已自动询问过弹窗的项目路径集（无论用户确认还是取消）。 */
    private static final String ASKED_KEY = "worktree-sync.asked";
    /** 插件基准时间：早于它建好的 worktree 视为「已有」，不再自动弹窗。 */
    private static final String BASELINE_KEY = "worktree-sync.baselineAt";
    private static final char FIELD = '\u0001';
    private static final char RECORD = '\u0002';
    private static final String VERSION = "1";
    /** 已询问路径集的保留上限，超出后丢最早记录，避免无界增长。 */
    private static final int ASKED_LIMIT = 500;

    private DecisionStore() {
    }

    /** 记住某个项目的选择。 */
    public static void remember(Path projectDir, SyncPlan plan) {
        if (projectDir == null || plan == null) {
            return;
        }
        String key = normalize(projectDir);
        List<String> records = readRecords();
        records.removeIf(r -> key.equalsIgnoreCase(field(r, 0)));

        StringBuilder sb = new StringBuilder();
        sb.append(VERSION).append(FIELD)
                .append(key).append(FIELD)
                .append(plan.sourceDir == null ? "" : normalize(plan.sourceDir)).append(FIELD)
                .append(plan.ideaSettings ? "1" : "0").append(FIELD)
                .append(plan.claudeProjectConfig ? "1" : "0").append(FIELD)
                .append(plan.claudeSessions ? "1" : "0").append(FIELD)
                .append(plan.sessionDays).append(FIELD)
                .append(plan.overwriteExisting ? "1" : "0").append(FIELD)
                .append(plan.mavenSettings ? "1" : "0");
        records.add(sb.toString());
        writeRecords(records);
    }

    /** 取回某个项目记住的选择；没有则返回 null。 */
    public static SyncPlan recall(Path projectDir) {
        if (projectDir == null) {
            return null;
        }
        String key = normalize(projectDir);
        for (String record : readRecords()) {
            if (!key.equalsIgnoreCase(field(record, 0))) {
                continue;
            }
            SyncPlan plan = new SyncPlan();
            String source = field(record, 1);
            plan.sourceDir = source == null || source.isEmpty() ? null : Path.of(source);
            plan.ideaSettings = "1".equals(field(record, 2));
            plan.claudeProjectConfig = "1".equals(field(record, 3));
            plan.claudeSessions = "1".equals(field(record, 4));
            plan.sessionDays = parseInt(field(record, 5), 7);
            plan.overwriteExisting = "1".equals(field(record, 6));
            // 老记录没有第 8 位：缺省按开启处理，与界面默认一致
            plan.mavenSettings = !"0".equals(field(record, 7));
            plan.rememberDecision = true;
            return plan;
        }
        return null;
    }

    /** 忘掉某个项目的选择，同时清掉「已询问」标记 —— 下次打开会重新弹窗。 */
    public static void forget(Path projectDir) {
        if (projectDir == null) {
            return;
        }
        String key = normalize(projectDir);
        List<String> records = readRecords();
        if (records.removeIf(r -> key.equalsIgnoreCase(field(r, 0)))) {
            writeRecords(records);
        }
        clearAsked(projectDir);
    }

    // ------------------------------------------------------------------ 自动弹窗的门槛

    /**
     * 插件基准时间（毫秒）。首次调用时记为当前时间并持久化。
     *
     * <p>用途：<b>早于这个时间建好的 worktree 都视为「已有 worktree」，不再自动弹窗。</b>
     * 这样装上插件后不会把手上那堆老 worktree 挨个弹一遍，
     * 只有插件装好之后<em>新建</em>的 worktree 才会被询问一次。
     */
    public static long baselineAt() {
        PropertiesComponent pc = PropertiesComponent.getInstance();
        long v = parseLong(get(pc, BASELINE_KEY), 0L);
        if (v <= 0L) {
            v = System.currentTimeMillis();
            set(pc, BASELINE_KEY, String.valueOf(v));
        }
        return v;
    }

    /** 该项目是否已经自动询问过（用户当时是确认还是取消都算）。 */
    public static boolean wasAsked(Path projectDir) {
        if (projectDir == null) {
            return false;
        }
        String key = normalize(projectDir);
        for (String asked : readAsked()) {
            if (key.equalsIgnoreCase(asked)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 标记「已经问过」，之后该项目不再自动弹窗。
     *
     * <p>刻意在弹窗<b>之前</b>调用：即使用户点了取消，也算问过了，下次不再打扰
     * （想重新问就走 Tools 菜单的「重置本项目的同步选择」）。
     */
    public static void markAsked(Path projectDir) {
        if (projectDir == null) {
            return;
        }
        String key = normalize(projectDir);
        List<String> asked = readAsked();
        asked.removeIf(k -> key.equalsIgnoreCase(k));
        asked.add(key);
        while (asked.size() > ASKED_LIMIT) {
            asked.remove(0);
        }
        set(PropertiesComponent.getInstance(), ASKED_KEY, String.join(String.valueOf(RECORD), asked));
    }

    /** 清掉「已询问」标记，让下次打开可以重新弹窗。 */
    public static void clearAsked(Path projectDir) {
        if (projectDir == null) {
            return;
        }
        String key = normalize(projectDir);
        List<String> asked = readAsked();
        if (asked.removeIf(k -> key.equalsIgnoreCase(k))) {
            set(PropertiesComponent.getInstance(), ASKED_KEY, String.join(String.valueOf(RECORD), asked));
        }
    }

    private static List<String> readAsked() {
        List<String> out = new ArrayList<>();
        String raw = get(PropertiesComponent.getInstance(), ASKED_KEY);
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        StringTokenizer tokenizer = new StringTokenizer(raw, String.valueOf(RECORD));
        while (tokenizer.hasMoreTokens()) {
            String token = tokenizer.nextToken();
            if (!token.isEmpty()) {
                out.add(token);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ 内部

    private static String normalize(Path path) {
        return path.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
    }

    private static List<String> readRecords() {
        List<String> out = new ArrayList<>();
        String raw;
        try {
            raw = PropertiesComponent.getInstance().getValue(KEY);
        } catch (Throwable t) {
            return out;
        }
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        StringTokenizer tokenizer = new StringTokenizer(raw, String.valueOf(RECORD));
        while (tokenizer.hasMoreTokens()) {
            out.add(tokenizer.nextToken());
        }
        return out;
    }

    private static void writeRecords(List<String> records) {
        StringBuilder sb = new StringBuilder();
        for (String record : records) {
            if (sb.length() > 0) {
                sb.append(RECORD);
            }
            sb.append(record);
        }
        try {
            PropertiesComponent.getInstance().setValue(KEY, sb.toString());
        } catch (Throwable ignored) {
            // 存不进去不影响本次同步
        }
    }

    /** 取记录中第 index 个字段（跳过开头的版本号）。 */
    private static String field(String record, int index) {
        if (record == null) {
            return null;
        }
        String[] parts = record.split(String.valueOf(FIELD), -1);
        int wanted = index + 1;
        return parts.length > wanted ? parts[wanted] : null;
    }

    private static String get(PropertiesComponent pc, String key) {
        try {
            return pc.getValue(key);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void set(PropertiesComponent pc, String key, String value) {
        try {
            pc.setValue(key, value);
        } catch (Throwable ignored) {
            // 存不进去不影响本次流程
        }
    }

    private static long parseLong(String value, long fallback) {
        if (value == null || value.isEmpty()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int parseInt(String value, int fallback) {
        if (value == null || value.isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
