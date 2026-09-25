package com.smallzhuge.worktreesync;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationGroup;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import com.smallzhuge.worktreesync.model.SyncPlan;
import com.smallzhuge.worktreesync.model.SyncResult;
import com.smallzhuge.worktreesync.model.WorktreeInfo;
import com.smallzhuge.worktreesync.sync.ClaudeCodeSyncer;
import com.smallzhuge.worktreesync.sync.IdeaSettingsSyncer;
import com.smallzhuge.worktreesync.sync.MavenSettingsSyncer;
import com.smallzhuge.worktreesync.util.WorktreeDetector;

/** 把「探测 → 弹窗 → 应用 → 汇报」串起来。 */
public final class WorktreeSyncRunner {

    private static final Logger LOG = Logger.getInstance(WorktreeSyncRunner.class);
    private static final String NOTIFICATION_GROUP = "Worktree Sync";

    private WorktreeSyncRunner() {
    }

    /**
     * 项目打开后的自动流程。
     *
     * <p><b>什么情况下才弹窗</b>（三条都满足）：
     * <ol>
     *   <li>是 worktree（主仓库与普通项目不打扰）</li>
     *   <li>没有记住过的选择</li>
     *   <li>创建时间晚于插件基准时间 —— 也就是<b>装了插件之后才新建的 worktree</b>，
     *       且此前没问过</li>
     * </ol>
     * 已有 worktree 一律不弹；新建的只弹一次（点取消也算问过，不再重复打扰）。
     * 想重新弹或对已有 worktree 同步，走 <code>Tools</code> 菜单。
     */
    public static void runForOpenedProject(Project project) {
        Path base = basePathOf(project);
        if (base == null || !WorktreeDetector.isWorktree(base)) {
            return;
        }

        SyncPlan remembered = DecisionStore.recall(base);
        if (remembered != null) {
            LOG.info("Worktree Sync: 命中记忆的决策，直接应用 " + base);
            apply(project, base, remembered);
            return;
        }

        if (DecisionStore.wasAsked(base)) {
            LOG.info("Worktree Sync: " + base + " 已经询问过，跳过弹窗");
            return;
        }

        long created = WorktreeDetector.createdAt(base);
        long baseline = DecisionStore.baselineAt();
        if (created <= 0L || created < baseline) {
            LOG.info("Worktree Sync: " + base + " 视为已有 worktree（无法判定创建时间或早于基准 "
                    + baseline + "），跳过弹窗");
            return;
        }

        List<WorktreeInfo> candidates = WorktreeDetector.candidates(base);
        if (candidates.isEmpty()) {
            LOG.info("Worktree Sync: " + base + " 是新 worktree，但未探测到同仓库的其他 worktree，跳过弹窗");
            return;
        }

        // 先标记再弹：即使用户点取消，下次也不再重复打扰
        DecisionStore.markAsked(base);

        WorktreeSyncDialog dialog = new WorktreeSyncDialog(project, base, candidates);
        if (!dialog.showAndGet()) {
            return;
        }
        SyncPlan plan = dialog.getPlan();
        if (plan.rememberDecision) {
            DecisionStore.remember(base, plan);
        }
        apply(project, base, plan);
    }

    /** 从 Tools 菜单手动触发；普通项目也能用（靠「浏览...」选来源）。不受自动弹窗的任何门槛限制。 */
    public static void runManually(Project project) {
        Path base = basePathOf(project);
        if (base == null) {
            Messages.showInfoMessage(project, "当前项目没有可用的本地路径。", "Worktree Sync");
            return;
        }
        List<WorktreeInfo> candidates = WorktreeDetector.candidates(base);

        WorktreeSyncDialog dialog = new WorktreeSyncDialog(project, base, candidates);
        if (!dialog.showAndGet()) {
            return;
        }
        SyncPlan plan = dialog.getPlan();
        if (plan.rememberDecision) {
            DecisionStore.remember(base, plan);
        }
        apply(project, base, plan);
    }

    // ------------------------------------------------------------------ 应用

    /** 文件类同步丢到后台线程；IDEA API 类同步回到 EDT 执行。 */
    private static void apply(Project project, Path base, SyncPlan plan) {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            SyncResult result = new SyncResult();
            try {
                final Path source = plan.sourceDir;
                if (source == null) {
                    result.skip("IDEA / Maven 设置：已选择「使用 IDEA 默认项目设置」");
                } else if (plan.ideaSettings || plan.mavenSettings) {
                    try {
                        ApplicationManager.getApplication().invokeAndWait(() -> {
                            if (plan.ideaSettings) {
                                IdeaSettingsSyncer.apply(project, source, result);
                            }
                            if (plan.mavenSettings) {
                                MavenSettingsSyncer.apply(project, source, base, result);
                            }
                        });
                    } catch (Throwable t) {
                        result.fail("IDEA / Maven 设置：" + t.getMessage());
                    }
                }

                if (plan.claudeProjectConfig) {
                    if (source == null) {
                        result.skip("Claude 项目配置：未指定来源目录");
                    } else {
                        ClaudeCodeSyncer.syncProjectConfig(source, base, plan.overwriteExisting, result);
                    }
                }
                if (plan.claudeSessions) {
                    if (source == null) {
                        result.skip("Claude 会话：未指定来源目录");
                    } else {
                        ClaudeCodeSyncer.syncSessions(source, base, plan.sessionDays, result);
                    }
                }
            } catch (Throwable t) {
                LOG.warn("Worktree Sync 执行失败", t);
                result.fail("未预期的异常：" + t);
            }
            final SyncResult finalResult = result;
            ApplicationManager.getApplication().invokeLater(() -> report(project, finalResult));
        });
    }

    // ------------------------------------------------------------------ 汇报

    private static void report(Project project, SyncResult result) {
        if (result.applied.isEmpty() && !result.hasFailure()) {
            return;
        }
        // 标题保持短（气泡标题过长会被截断成「…」），计数放正文首行
        String title = result.notificationTitle();
        String plain = result.toPlainText();
        try {
            NotificationGroup group = NotificationGroupManager.getInstance()
                    .getNotificationGroup(NOTIFICATION_GROUP);
            NotificationType type = result.hasFailure()
                    ? NotificationType.WARNING : NotificationType.INFORMATION;
            Notification notification = group.createNotification(title, result.toHtml(), type);
            Notifications.Bus.notify(notification, project);
        } catch (Throwable t) {
            LOG.warn("通知发送失败，改用对话框", t);
            Messages.showInfoMessage(project, plain, "Worktree Sync");
        }
    }

    private static Path basePathOf(Project project) {
        if (project == null) {
            return null;
        }
        String base = project.getBasePath();
        if (base == null || base.isEmpty()) {
            return null;
        }
        return Paths.get(base);
    }
}
