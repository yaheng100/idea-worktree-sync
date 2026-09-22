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
    private static final char FIELD = '\u0001';
    private static final char RECORD = '\u0002';
    private static final String VERSION = "1";

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

    /** 忘掉某个项目的选择（用于「恢复默认」）。 */
    public static void forget(Path projectDir) {
        if (projectDir == null) {
            return;
        }
        String key = normalize(projectDir);
        List<String> records = readRecords();
        if (records.removeIf(r -> key.equalsIgnoreCase(field(r, 0)))) {
            writeRecords(records);
        }
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
