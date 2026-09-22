package com.smallzhuge.worktreesync;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import kotlin.Unit;
import kotlin.coroutines.Continuation;

/**
 * 插件入口：项目打开后触发。
 *
 * <p>用 {@code postStartupActivity} 扩展点（2026.2 里它的 interface 是
 * {@link ProjectActivity}）。注意 {@code projectManagerListener} 已经<b>不是扩展点</b>了，
 * 全量扫描平台的 XML 描述符可以确认这一点。
 *
 * <p>这里只负责把工作丢回 EDT 并立即返回 —— 弹窗和文件 IO 都不该阻塞启动流程。
 * 默认项目（default project）的 basePath 为 null，会被自然排除。
 */
public final class WorktreeSyncActivity implements ProjectActivity {

    private static final Logger LOG = Logger.getInstance(WorktreeSyncActivity.class);

    @Nullable
    @Override
    public Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        try {
            ApplicationManager.getApplication().invokeLater(
                    () -> {
                        try {
                            WorktreeSyncRunner.runForOpenedProject(project);
                        } catch (Throwable t) {
                            LOG.warn("Worktree Sync 自动流程失败", t);
                        }
                    },
                    ModalityState.nonModal());
        } catch (Throwable t) {
            LOG.warn("Worktree Sync 调度失败", t);
        }
        return Unit.INSTANCE;
    }
}
