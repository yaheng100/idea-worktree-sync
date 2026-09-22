package com.smallzhuge.worktreesync.model;

import java.nio.file.Path;

import com.smallzhuge.worktreesync.util.XmlOptions;

/** 一个可作为「设置来源」的仓库目录。 */
public final class WorktreeInfo {

    /** 项目根目录（含 .git 文件或 .git 目录）。 */
    public final Path path;
    /** 分支名，可能为 null（无法解析时）。 */
    public final String branch;
    /** 是否为主仓库（非 worktree）。 */
    public final boolean main;

    public WorktreeInfo(Path path, String branch, boolean main) {
        this.path = path;
        this.branch = branch;
        this.main = main;
    }

    /** 读取该目录 .idea/compiler.xml 里的构建进程堆，供下拉框展示。 */
    public Integer heapSize() {
        return XmlOptions.readIntOption(
                path.resolve(".idea").resolve("compiler.xml"),
                "CompilerConfiguration",
                "BUILD_PROCESS_HEAP_SIZE");
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(path);
        if (main) {
            sb.append("  [主仓库]");
        }
        if (branch != null && !branch.isEmpty()) {
            sb.append("  (").append(branch).append(')');
        }
        Integer heap = heapSize();
        sb.append("  ·  堆 ").append(heap == null ? "默认" : heap + "M");
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof WorktreeInfo)) {
            return false;
        }
        WorktreeInfo other = (WorktreeInfo) o;
        return path.toString().equalsIgnoreCase(other.path.toString());
    }

    @Override
    public int hashCode() {
        return path.toString().toLowerCase().hashCode();
    }
}
