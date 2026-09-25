package com.smallzhuge.worktreesync;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationGroup;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.jetbrains.annotations.NotNull;

/**
 * Tools 菜单：<b>重置本项目的同步选择</b>。
 *
 * <p>清掉「记住的选择」与「已询问」标记。为什么需要它：
 * 自动弹窗只对<b>插件装好之后新建的 worktree</b>生效，且问过一次就永不再问 ——
 * 所以当你想让某个已有项目重新弹窗、或者想忘掉之前记住的选择时，
 * 这是唯一的入口。
 */
public final class WorktreeSyncResetAction extends AnAction {

    private static final Logger LOG = Logger.getInstance(WorktreeSyncResetAction.class);
    private static final String NOTIFICATION_GROUP = "Worktree Sync";

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        if (project == null) {
            return;
        }
        String base = project.getBasePath();
        if (base == null || base.isEmpty()) {
            Messages.showInfoMessage(project, "当前项目没有可用的本地路径。", "Worktree Sync");
            return;
        }
        DecisionStore.forget(Paths.get(base));
        notifyDone(project);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.EDT;
    }

    private static void notifyDone(Project project) {
        String text = "已清除本项目的同步选择与「已询问」标记。"
                + "<br/>为了让自动弹窗重新出现，需要<b>关闭并重新打开本项目</b>；"
                + "也可以随时用 Tools 菜单里的 Sync Worktree Settings... 手动执行一次。";
        try {
            NotificationGroup group = NotificationGroupManager.getInstance()
                    .getNotificationGroup(NOTIFICATION_GROUP);
            Notification notification = group.createNotification(
                    "Worktree Sync 已重置", text, NotificationType.INFORMATION);
            Notifications.Bus.notify(notification, project);
        } catch (Throwable t) {
            LOG.warn("通知发送失败，改用对话框", t);
            Messages.showInfoMessage(project,
                    "已清除本项目的同步选择与「已询问」标记。\n"
                            + "关闭并重新打开本项目可让自动弹窗重新出现。",
                    "Worktree Sync 已重置");
        }
    }
}
