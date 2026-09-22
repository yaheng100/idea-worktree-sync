package com.smallzhuge.worktreesync.sync;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.smallzhuge.worktreesync.model.SyncResult;
import com.smallzhuge.worktreesync.util.PathSlug;

/**
 * Claude Code 侧的同步：项目级配置文件 + 会话记录。
 *
 * <p>会话目录规则见 {@link PathSlug}：Claude 按工作目录绝对路径的 slug 分目录，
 * 所以换个 worktree 路径就等于换了一个 project，{@code --resume} 恒为空。
 * 本类把来源 slug 目录里的会话复制到目标 slug 目录，并改写会话内的 cwd 路径。
 *
 * <p>安全性：
 * <ul>
 *   <li>只读来源、只写目标，不做任何删除或移动</li>
 *   <li>目标已存在同名文件时默认跳过，不覆盖</li>
 *   <li>想完全回退，直接删掉目标 slug 目录即可</li>
 * </ul>
 */
public final class ClaudeCodeSyncer {

    /** 需要同步的项目级配置文件（相对项目根）。 */
    private static final String[] PROJECT_CONFIG_FILES = {
            ".claude/settings.json",
            ".claude/settings.local.json",
            ".mcp.json",
            "CLAUDE.md",
    };

    private ClaudeCodeSyncer() {
    }

    // ------------------------------------------------------------------ 项目级配置

    /**
     * 同步项目级配置文件。
     *
     * <p>采用「目标不存在才复制」的策略，天然避开了 git 已跟踪的文件 ——
     * 被跟踪的文件在新建 worktree 时已经由 git 检出到目标目录，因此会被自动跳过，
     * 不会把工作树弄脏。这也让插件不必依赖 git 命令。
     */
    public static void syncProjectConfig(Path sourceDir, Path targetDir,
                                         boolean overwrite, SyncResult result) {
        if (sourceDir == null || targetDir == null) {
            return;
        }
        for (String relative : PROJECT_CONFIG_FILES) {
            Path src = sourceDir.resolve(relative.replace('/', java.io.File.separatorChar));
            Path dst = targetDir.resolve(relative.replace('/', java.io.File.separatorChar));
            if (!Files.isRegularFile(src)) {
                continue;
            }
            try {
                if (Files.exists(dst) && !overwrite) {
                    result.skip("Claude 配置 " + relative + "（目标已存在，未覆盖）");
                    continue;
                }
                Path parent = dst.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                result.apply("Claude 配置 " + relative);
            } catch (IOException e) {
                result.fail("Claude 配置 " + relative + "：" + e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------ 会话记录

    /**
     * 迁移会话记录。
     *
     * @param days 只迁移最近 N 天内修改过的会话；0 或负数表示不限
     */
    public static void syncSessions(Path sourceDir, Path targetDir, int days, SyncResult result) {
        if (sourceDir == null || targetDir == null) {
            return;
        }
        Path projectsRoot = claudeProjectsRoot();
        if (projectsRoot == null) {
            result.fail("Claude 会话：找不到 ~/.claude/projects 目录");
            return;
        }
        Path srcSlugDir = projectsRoot.resolve(PathSlug.slugify(sourceDir.toString()));
        Path dstSlugDir = projectsRoot.resolve(PathSlug.slugify(targetDir.toString()));

        if (!Files.isDirectory(srcSlugDir)) {
            result.skip("Claude 会话：来源 slug 目录不存在（" + srcSlugDir.getFileName() + "）");
            return;
        }

        long cutoff = days > 0 ? System.currentTimeMillis() - days * 86_400_000L : 0L;

        List<Path> sessions = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(srcSlugDir)) {
            for (Path p : stream) {
                if (!Files.isRegularFile(p) || !p.getFileName().toString().endsWith(".jsonl")) {
                    continue;
                }
                if (cutoff > 0 && Files.getLastModifiedTime(p).toMillis() < cutoff) {
                    continue;
                }
                sessions.add(p);
            }
        } catch (IOException e) {
            result.fail("Claude 会话：读取来源目录失败 " + e.getMessage());
            return;
        }

        if (sessions.isEmpty()) {
            result.skip("Claude 会话：最近 " + (days > 0 ? days + " 天" : "全部时间") + "内没有可迁移的会话");
            return;
        }
        sessions.sort(Comparator.comparingLong((Path p) -> {
            try {
                return Files.getLastModifiedTime(p).toMillis();
            } catch (IOException e) {
                return 0L;
            }
        }).reversed());

        try {
            Files.createDirectories(dstSlugDir);
        } catch (IOException e) {
            result.fail("Claude 会话：创建目标 slug 目录失败 " + e.getMessage());
            return;
        }

        int migrated = 0;
        for (Path src : sessions) {
            String name = src.getFileName().toString();
            Path dst = dstSlugDir.resolve(name);
            try {
                if (Files.exists(dst)) {
                    result.skip("会话 " + name + "（目标已存在）");
                    continue;
                }
                String text = new String(Files.readAllBytes(src), StandardCharsets.UTF_8);
                String rewritten = rewritePaths(text, sourceDir.toString(), targetDir.toString());
                Files.write(dst, rewritten.getBytes(StandardCharsets.UTF_8));

                // 同名子目录 = 该会话的 subagent 转录，属于会话的一部分
                String stem = name.substring(0, name.length() - ".jsonl".length());
                Path sibling = srcSlugDir.resolve(stem);
                if (Files.isDirectory(sibling)) {
                    copyDirectory(sibling, dstSlugDir.resolve(stem));
                }
                migrated++;
            } catch (IOException e) {
                result.fail("会话 " + name + "：" + e.getMessage());
            }
        }
        if (migrated > 0) {
            // 不把 slug 目录名写进消息：它是长且无空格的长 token，气泡里会被从中间截断
            result.apply("Claude 会话 " + migrated + " 个（"
                    + (days > 0 ? "最近 " + days + " 天" : "全部时间") + "）");
        }
    }

    /**
     * 把会话内容里的源路径改写成目标路径。
     *
     * <p>三个坑：
     * <ol>
     *   <li>JSON 里 Windows 路径是<b>双反斜杠</b>形态 {@code E:\\Work\\xzg-system}，
     *       用单反斜杠去替换永远匹配不到</li>
     *   <li>也可能存在正斜杠形态 {@code E:/Work/xzg-system}</li>
     *   <li>必须用「后接引号 / 反斜杠 / 正斜杠 / 逗号」做右边界，
     *       否则 {@code xzg-system} 会把 {@code xzg-system-pro} 的前缀一起吃掉</li>
     * </ol>
     */
    static String rewritePaths(String text, String source, String target) {
        if (text == null || source == null || target == null || source.isEmpty()) {
            return text;
        }
        // 长形态优先：双反斜杠 → 正斜杠，最后才是裸形态
        String escapedSource = source.replace("\\", "\\\\");
        String escapedTarget = target.replace("\\", "\\\\");
        String slashSource = source.replace("\\", "/");
        String slashTarget = target.replace("\\", "/");

        String out = replaceWithBoundary(text, escapedSource, escapedTarget);
        out = replaceWithBoundary(out, slashSource, slashTarget);
        if (!escapedSource.equals(source) && !slashSource.equals(source)) {
            out = replaceWithBoundary(out, source, target);
        }
        return out;
    }

    private static String replaceWithBoundary(String text, String oldValue, String newValue) {
        Pattern pattern = Pattern.compile(Pattern.quote(oldValue) + "(?=[\"\\\\/,])");
        return pattern.matcher(text).replaceAll(Matcher.quoteReplacement(newValue));
    }

    private static Path claudeProjectsRoot() {
        String home = System.getProperty("user.home");
        if (home == null || home.isEmpty()) {
            return null;
        }
        return Paths.get(home, ".claude", "projects");
    }

    private static void copyDirectory(final Path from, final Path to) throws IOException {
        Files.walkFileTree(from, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(to.resolve(from.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, to.resolve(from.relativize(file).toString()),
                        StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
