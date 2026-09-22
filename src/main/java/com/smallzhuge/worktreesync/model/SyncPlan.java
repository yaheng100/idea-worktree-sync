package com.smallzhuge.worktreesync.model;

import java.nio.file.Path;

/** 本次要做的事。 */
public final class SyncPlan {

    /** 来源目录；为 null 表示「使用 IDEA 默认项目设置」，即不做任何 IDEA 侧同步。 */
    public Path sourceDir;

    public boolean ideaSettings = true;
    /** Maven home / settings.xml / 本地仓库 / 远程仓库等（项目级，存在 workspace.xml 里）。 */
    public boolean mavenSettings = true;
    public boolean claudeProjectConfig = true;
    public boolean claudeSessions = true;

    /** 会话迁移的时间范围（天）。0 表示全部。 */
    public int sessionDays = 7;

    /** 目标已存在同名文件时是否覆盖。默认不覆盖。 */
    public boolean overwriteExisting = false;

    /** 是否记住本次选择，之后同一项目不再询问。 */
    public boolean rememberDecision = true;

    public boolean useIdeaDefaults() {
        return sourceDir == null;
    }
}
