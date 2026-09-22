package com.smallzhuge.worktreesync.sync;

import com.intellij.compiler.CompilerConfiguration;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.encoding.EncodingProjectManager;

import java.nio.file.Path;

import com.smallzhuge.worktreesync.model.SyncResult;
import com.smallzhuge.worktreesync.util.XmlOptions;

/**
 * IDEA 项目设置的同步。
 *
 * <p><b>为什么只做这几项：</b>2026.2 已经移除了
 * {@code ProjectManagerEx.reloadProject}，所以「复制 .idea 文件再重载项目」
 * 这条路线不存在。代码风格、检查配置、运行配置都没有公开的可编程接口，
 * 只能写文件 + 重启项目才生效，且容易被 IDEA 内存态反向覆盖。
 * 因此本插件只同步<b>有官方 API、可即时生效</b>的项。
 *
 * <p><b>走 API 的额外好处：</b>会同时更新 IDEA 的内存态，所以不会再出现
 * 「IDEA 退出时用内存里的旧值把磁盘配置覆盖回去」——那正是原始 OOM 问题的复发路径。
 */
public final class IdeaSettingsSyncer {

    /** IDEA 内置的默认构建进程堆，用于把「旧值」展示给用户。 */
    public static final int JPS_DEFAULT_HEAP_MB = 700;

    private IdeaSettingsSyncer() {
    }

    public static void apply(Project project, Path sourceDir, SyncResult result) {
        if (project == null || sourceDir == null) {
            return;
        }
        Path compilerXml = sourceDir.resolve(".idea").resolve("compiler.xml");

        CompilerConfiguration compilerConfig;
        try {
            compilerConfig = CompilerConfiguration.getInstance(project);
        } catch (Throwable t) {
            result.fail("IDEA 构建设置不可用：" + t.getMessage());
            return;
        }
        if (compilerConfig == null) {
            result.fail("IDEA 构建设置不可用：CompilerConfiguration 服务未注册");
            return;
        }

        Integer heap = XmlOptions.readIntOption(compilerXml, "CompilerConfiguration", "BUILD_PROCESS_HEAP_SIZE");
        if (heap == null) {
            result.skip("编译进程堆：来源未设置 BUILD_PROCESS_HEAP_SIZE");
        } else {
            int old;
            try {
                old = compilerConfig.getBuildProcessHeapSize(JPS_DEFAULT_HEAP_MB);
            } catch (Throwable t) {
                old = JPS_DEFAULT_HEAP_MB;
            }
            compilerConfig.setBuildProcessHeapSize(heap);
            result.apply("编译进程堆 " + old + "M → " + heap + "M（已即时生效）");
        }

        String vmOptions = XmlOptions.readOption(compilerXml, "CompilerConfiguration", "BUILD_PROCESS_VM_OPTIONS");
        if (vmOptions == null || vmOptions.trim().isEmpty()) {
            result.skip("构建 VM options：来源未设置");
        } else {
            String oldVm = compilerConfig.getBuildProcessVMOptions();
            compilerConfig.setBuildProcessVMOptions(vmOptions);
            result.apply("构建 VM options " + (oldVm == null || oldVm.isEmpty() ? "(空)" : oldVm)
                    + " → " + vmOptions + "（已即时生效）");
        }

        String charset = XmlOptions.readProjectCharset(sourceDir.resolve(".idea").resolve("encodings.xml"));
        if (charset == null || charset.isEmpty()) {
            result.skip("全局编码：来源未显式设置");
        } else {
            try {
                EncodingProjectManager encodingManager = EncodingProjectManager.getInstance(project);
                String oldCharset = encodingManager.getDefaultCharsetName();
                if (charset.equalsIgnoreCase(oldCharset)) {
                    result.skip("全局编码已是 " + charset);
                } else {
                    encodingManager.setDefaultCharsetName(charset);
                    result.apply("全局编码 " + oldCharset + " → " + charset + "（已即时生效）");
                }
            } catch (Throwable t) {
                result.fail("全局编码设置失败：" + t.getMessage());
            }
        }
    }
}
