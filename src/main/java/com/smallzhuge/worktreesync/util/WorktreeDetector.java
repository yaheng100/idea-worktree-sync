package com.smallzhuge.worktreesync.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.smallzhuge.worktreesync.model.WorktreeInfo;

/**
 * Git worktree 的识别与同仓库 worktree 枚举。
 *
 * <p>全部基于文件系统，<b>不调用 git 命令</b> —— 插件运行在 IDEA 里，
 * 不保证 PATH 上有 git，也不该为了这点事去启动子进程。
 *
 * <p>依据：
 * <ul>
 *   <li>worktree 的 {@code .git} 是<b>文件</b>而非目录，内容形如
 *       {@code gitdir: E:/Work/xzg-system/.git/worktrees/xzg-system-pro}</li>
 *   <li>{@code <主仓库>/.git/worktrees/<名字>/gitdir} 指向该 worktree 的
 *       {@code .git} 文件（内容是绝对路径），据此反推项目目录</li>
 * </ul>
 */
public final class WorktreeDetector {

    private WorktreeDetector() {
    }

    /** {@code .git} 是文件 → 这是 worktree（主仓库的 .git 是目录）。 */
    public static boolean isWorktree(Path projectDir) {
        return projectDir != null && Files.isRegularFile(projectDir.resolve(".git"));
    }

    /**
     * 项目的 git 目录，两种形态都支持：
     * <ul>
     *   <li>{@code .git} 是<b>文件</b>（worktree）→ 解析里面的 {@code gitdir:} 行</li>
     *   <li>{@code .git} 是<b>目录</b>（主仓库）→ 直接就是 {@code <项目>/.git}</li>
     * </ul>
     * 两者都不是则返回 {@code null}。
     */
    public static Path resolveGitDir(Path projectDir) {
        if (projectDir == null) {
            return null;
        }
        Path dotGit = projectDir.resolve(".git");
        if (Files.isRegularFile(dotGit)) {
            return readGitDir(projectDir);
        }
        if (Files.isDirectory(dotGit)) {
            return dotGit.toAbsolutePath().normalize();
        }
        return null;
    }

    /** {@code .git} 是目录 → 当前项目就是主仓库。 */
    public static boolean isMainRepo(Path projectDir) {
        return projectDir != null && Files.isDirectory(projectDir.resolve(".git"));
    }

    /** 解析 worktree 的 {@code .git} 文件，取出 {@code gitdir:} 指向的目录。 */
    public static Path readGitDir(Path projectDir) {
        if (projectDir == null) {
            return null;
        }
        Path dotGit = projectDir.resolve(".git");
        if (!Files.isRegularFile(dotGit)) {
            return null;
        }
        try {
            for (String line : Files.readAllLines(dotGit, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.toLowerCase(Locale.ROOT).startsWith("gitdir:")) {
                    String raw = trimmed.substring("gitdir:".length()).trim();
                    if (raw.isEmpty()) {
                        continue;
                    }
                    Path p = Paths.get(raw);
                    if (!p.isAbsolute()) {
                        // 相对路径是相对于 worktree 目录的
                        p = projectDir.resolve(raw);
                    }
                    return p.toAbsolutePath().normalize();
                }
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    /**
     * 从 gitdir 反推主仓库的 {@code .git} 目录：
     * {@code <主仓库>/.git/worktrees/<名字>} → {@code <主仓库>/.git}。
     * 若结构不符（例如指向别的形态），返回 {@code null}。
     */
    public static Path mainGitDirOf(Path gitDir) {
        if (gitDir == null) {
            return null;
        }
        Path parent = gitDir.getParent();
        if (parent != null && "worktrees".equals(fileName(parent))) {
            return parent.getParent();
        }
        return null;
    }

    /** 主仓库项目目录。 */
    public static Path mainProjectDirOf(Path gitDir) {
        Path mainGit = mainGitDirOf(gitDir);
        return mainGit == null ? null : mainGit.getParent();
    }

    /**
     * 枚举同一仓库下的全部 worktree（不含主仓库本身，也不含 self）。
     *
     * @param mainGitDir 主仓库的 .git 目录
     * @param selfPath   当前项目目录，用于标记「当前」
     */
    public static List<WorktreeInfo> listSiblingWorktrees(Path mainGitDir, Path selfPath) {
        List<WorktreeInfo> result = new ArrayList<>();
        if (mainGitDir == null) {
            return result;
        }
        Path worktreesRoot = mainGitDir.resolve("worktrees");
        if (!Files.isDirectory(worktreesRoot)) {
            return result;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(worktreesRoot)) {
            for (Path dir : stream) {
                Path gitDirPointer = dir.resolve("gitdir");
                if (!Files.isRegularFile(gitDirPointer)) {
                    continue;
                }
                String raw = new String(Files.readAllBytes(gitDirPointer), StandardCharsets.UTF_8).trim();
                if (raw.isEmpty()) {
                    continue;
                }
                Path projectDotGit = Paths.get(raw);
                if (!projectDotGit.isAbsolute()) {
                    // 极少见：相对路径是相对于 <主仓库>/.git/worktrees/<名字> 的
                    projectDotGit = dir.resolve(raw);
                }
                projectDotGit = projectDotGit.toAbsolutePath().normalize();
                Path projectDir = projectDotGit.getParent();
                if (projectDir == null || !Files.isDirectory(projectDir)) {
                    continue;
                }
                // 当前项目不放进候选（自己继承自己没有意义）
                if (samePath(projectDir, selfPath)) {
                    continue;
                }
                result.add(new WorktreeInfo(projectDir, readBranch(dir.resolve("HEAD")), false));
            }
        } catch (IOException ignored) {
        }
        result.sort((a, b) -> a.path.toString().compareToIgnoreCase(b.path.toString()));
        return result;
    }

    /**
     * 构造候选列表：主仓库优先，其后是同仓库其他 worktree。
     *
     * <p><b>当前项目是主仓库时也必须能出候选。</b>手动入口（Tools 菜单）允许在主仓库里打开弹窗，
     * 而主仓库的 {@code .git} 是<b>目录</b>、没有 gitdir 指针 —— 早先只处理了 worktree 形态，
     * 结果在主仓库里打开时候选框是空的。现在两种形态都能反推出同一批 worktree。
     */
    public static List<WorktreeInfo> candidates(Path projectDir) {
        List<WorktreeInfo> result = new ArrayList<>();
        if (projectDir == null) {
            return result;
        }
        Path gitDir = resolveGitDir(projectDir);
        if (gitDir == null) {
            return result;
        }

        Path mainGitDir;
        Path mainProject;
        if (isMainRepo(projectDir)) {
            mainGitDir = gitDir;
            mainProject = projectDir.toAbsolutePath().normalize();
        } else {
            mainGitDir = mainGitDirOf(gitDir);
            mainProject = mainGitDir == null ? null : mainGitDir.getParent();
        }
        if (mainGitDir == null) {
            return result;
        }

        // 主仓库排第一个；当前项目就是主仓库时跳过自己（继承自己没有意义）
        if (mainProject != null && Files.isDirectory(mainProject)
                && !samePath(mainProject, projectDir)) {
            result.add(new WorktreeInfo(mainProject, readBranch(mainGitDir.resolve("HEAD")), true));
        }
        result.addAll(listSiblingWorktrees(mainGitDir, projectDir));
        return result;
    }

    /** HEAD 形如 {@code ref: refs/heads/feature/x}，取出 {@code feature/x}。 */
    private static String readBranch(Path headFile) {
        if (headFile == null || !Files.isRegularFile(headFile)) {
            return null;
        }
        try {
            String text = new String(Files.readAllBytes(headFile), StandardCharsets.UTF_8).trim();
            int idx = text.lastIndexOf("refs/heads/");
            if (idx >= 0) {
                return text.substring(idx + "refs/heads/".length()).trim();
            }
            // 分离头指针：直接给短 hash，便于用户辨认
            return text.length() >= 8 ? text.substring(0, 8) : text;
        } catch (IOException e) {
            return null;
        }
    }

    public static boolean samePath(Path a, Path b) {
        if (a == null || b == null) {
            return false;
        }
        String sa = a.toAbsolutePath().normalize().toString();
        String sb = b.toAbsolutePath().normalize().toString();
        return sa.equalsIgnoreCase(sb);
    }

    /**
     * worktree 的创建时间（毫秒），取不到返回 0。
     *
     * <p>取的是 worktree 的 git 元数据目录 {@code <主仓库>/.git/worktrees/<名字>} 的创建时间 ——
     * 它由 {@code git worktree add} 建立，比项目目录本身更贴近「这个 worktree 是什么时候建的」。
     * 取不到时退化到项目目录的创建时间。
     *
     * <p>返回值 0 表示<b>无法判定</b>。调用方应按「未知视作已有」处理，避免误弹窗打扰用户。
     */
    public static long createdAt(Path projectDir) {
        if (projectDir == null) {
            return 0L;
        }
        long t = creationTimeOf(resolveGitDir(projectDir));
        if (t <= 0L) {
            t = creationTimeOf(projectDir);
        }
        return t;
    }

    private static long creationTimeOf(Path p) {
        if (p == null) {
            return 0L;
        }
        try {
            return Files.readAttributes(p, BasicFileAttributes.class).creationTime().toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private static String fileName(Path p) {
        Path name = p.getFileName();
        return name == null ? "" : name.toString();
    }
}
