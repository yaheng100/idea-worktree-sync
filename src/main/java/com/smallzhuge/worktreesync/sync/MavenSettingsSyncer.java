package com.smallzhuge.worktreesync.sync;

import com.intellij.jarRepository.RemoteRepositoriesConfiguration;
import com.intellij.jarRepository.RemoteRepositoryDescription;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.idea.maven.project.MavenGeneralSettings;
import org.jetbrains.idea.maven.project.MavenProjectsManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.smallzhuge.worktreesync.model.SyncResult;
import com.smallzhuge.worktreesync.util.XmlOptions;

/**
 * Maven 设置的同步。
 *
 * <p><b>为什么需要它：</b>IDEA 的 Maven 配置是<b>项目级</b>的，
 * 存在 {@code .idea/workspace.xml} 的
 * {@code <component name="MavenImportPreferences">} 里 —— 而 worktree 的 workspace.xml
 * 是各自独立的，所以新 worktree 会整个丢掉「Maven home / settings.xml / 本地仓库」这一套，
 * 表现为依赖解析不到私有仓库或每次都下到错误的本地仓库目录。
 *
 * <p><b>写入路径（已扫 jar 确认）：</b>
 * {@code MavenWorkspaceSettingsComponent#getGeneralSettings()} 返回的就是那个会被持久化的
 * {@link MavenGeneralSettings} 实例，调它的 setter 即改即存，无需重启。
 * 注意 {@code MavenImportPreferences} <b>不是一个 Java 类</b>，它只是 XML 里的组件别名。
 *
 * <p><b>只做能即时生效的项。</b>不碰 {@code originalFiles}（链接的 pom 列表）——
 * 那会触发整仓重新导入，且根 pom 已覆盖多模块 reactor，收益不成比例。
 */
public final class MavenSettingsSyncer {

    private static final Logger LOG = Logger.getInstance(MavenSettingsSyncer.class);

    private static final String WORKSPACE_XML = "workspace.xml";
    private static final String JAR_REPOSITORIES_XML = "jarRepositories.xml";

    /** XML 里的组件别名（不是类名）。 */
    private static final String COMPONENT_MAVEN = "MavenImportPreferences";
    private static final String TAG_GENERAL = "MavenGeneralSettings";
    private static final String COMPONENT_REPOS = "RemoteRepositoriesConfiguration";
    private static final String TAG_REPO = "remote-repository";

    /** 设置项 → 中文短名，用于汇总展示。 */
    private static final Map<String, String> LABELS = new LinkedHashMap<>();

    static {
        LABELS.put("customMavenHome", "Maven home");
        LABELS.put("mavenHomeTypeForPersistence", "home 类型");
        LABELS.put("userSettingsFile", "settings.xml");
        LABELS.put("localRepository", "本地仓库");
        LABELS.put("workOffline", "离线模式");
        LABELS.put("threads", "线程数");
        LABELS.put("alwaysUpdateSnapshots", "总是更新快照");
        LABELS.put("useMavenConfig", "使用 .mvn 配置");
        LABELS.put("nonRecursive", "非递归");
        LABELS.put("printErrorStackTraces", "打印错误栈");
        LABELS.put("emulateTerminal", "模拟终端");
        LABELS.put("showDialogWithAdvancedSettings", "导入显示高级设置");
        LABELS.put("toolchainsPath", "toolchains.xml");
    }

    private MavenSettingsSyncer() {
    }

    public static void apply(Project project, Path sourceDir, Path targetDir, SyncResult result) {
        if (project == null || sourceDir == null || targetDir == null) {
            return;
        }
        if (!Files.isRegularFile(targetDir.resolve("pom.xml"))) {
            result.skip("Maven 设置：当前项目没有 pom.xml，跳过");
            return;
        }

        syncGeneralSettings(project, sourceDir, result);
        syncRemoteRepositories(project, sourceDir, result);
    }

    // ------------------------------------------------------------------ 常规设置

    private static void syncGeneralSettings(Project project, Path sourceDir, SyncResult result) {
        Map<String, String> source = XmlOptions.readNestedOptionMap(
                sourceDir.resolve(".idea").resolve(WORKSPACE_XML), COMPONENT_MAVEN, TAG_GENERAL);
        if (source.isEmpty()) {
            result.skip("Maven 设置：来源 workspace.xml 里没有 MavenImportPreferences（未配置过）");
            return;
        }

        MavenGeneralSettings settings = resolveGeneralSettings(project);
        if (settings == null) {
            result.fail("Maven 设置：拿不到 Maven 配置对象（Maven 插件可能未启用）");
            return;
        }

        List<String> changed = new ArrayList<>();
        List<String> unsupported = new ArrayList<>();
        for (Map.Entry<String, String> entry : source.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            try {
                if (applyOne(settings, key, value)) {
                    changed.add(LABELS.getOrDefault(key, key));
                }
            } catch (Throwable t) {
                LOG.warn("Worktree Sync: Maven 设置项 " + key + " 应用失败", t);
                unsupported.add(key);
            }
        }

        // 读回校验：确认 setter 真的落到了会被持久化的那个对象上
        List<String> notPersisted = verify(settings, source);

        if (!changed.isEmpty()) {
            result.apply("Maven 设置 " + changed.size() + " 项（" + String.join("、", changed) + "，已即时生效）");
        } else if (notPersisted.isEmpty() && unsupported.isEmpty()) {
            result.skip("Maven 设置：与来源一致，无需变更");
        }
        if (!unsupported.isEmpty()) {
            result.fail("Maven 设置：以下项应用失败 " + String.join("、", unsupported));
        }
        if (!notPersisted.isEmpty()) {
            result.fail("Maven 设置读回不一致：" + String.join("、", notPersisted)
                    + "（可能被 IDEA 拒绝，建议到 Settings → Build Tools → Maven 确认一次）");
        }
    }

    /** 返回 true 表示值确实发生了变化（用于汇总计数）。 */
    private static boolean applyOne(MavenGeneralSettings settings, String key, String value) {
        switch (key) {
            case "customMavenHome":
                if (same(settings.getCustomMavenHome(), value)) {
                    return false;
                }
                settings.setCustomMavenHome(value);
                return true;
            case "mavenHomeTypeForPersistence":
                MavenGeneralSettings.MavenHomeTypeForPersistence type =
                        MavenGeneralSettings.MavenHomeTypeForPersistence.valueOf(value.trim());
                if (type == settings.getMavenHomeTypeForPersistence()) {
                    return false;
                }
                settings.setMavenHomeTypeForPersistence(type);
                return true;
            case "userSettingsFile":
                if (same(settings.getUserSettingsFile(), value)) {
                    return false;
                }
                settings.setUserSettingsFile(value);
                return true;
            case "localRepository":
                if (same(settings.getLocalRepository(), value)) {
                    return false;
                }
                settings.setLocalRepository(value);
                return true;
            case "threads":
                // 注意：IDEA 把线程数存成字符串，不是 int
                if (same(trim(settings.getThreads()), trim(value))) {
                    return false;
                }
                settings.setThreads(value.trim());
                return true;
            case "workOffline":
                boolean offline = Boolean.parseBoolean(value);
                if (offline == settings.isWorkOffline()) {
                    return false;
                }
                settings.setWorkOffline(offline);
                return true;
            case "alwaysUpdateSnapshots":
                boolean snapshots = Boolean.parseBoolean(value);
                if (snapshots == settings.isAlwaysUpdateSnapshots()) {
                    return false;
                }
                settings.setAlwaysUpdateSnapshots(snapshots);
                return true;
            case "useMavenConfig":
                boolean useConfig = Boolean.parseBoolean(value);
                if (useConfig == settings.isUseMavenConfig()) {
                    return false;
                }
                settings.setUseMavenConfig(useConfig);
                return true;
            case "nonRecursive":
                boolean nonRecursive = Boolean.parseBoolean(value);
                if (nonRecursive == settings.isNonRecursive()) {
                    return false;
                }
                settings.setNonRecursive(nonRecursive);
                return true;
            case "printErrorStackTraces":
                boolean printStack = Boolean.parseBoolean(value);
                if (printStack == settings.isPrintErrorStackTraces()) {
                    return false;
                }
                settings.setPrintErrorStackTraces(printStack);
                return true;
            case "emulateTerminal":
                boolean emulation = Boolean.parseBoolean(value);
                if (emulation == settings.isEmulateTerminal()) {
                    return false;
                }
                settings.setEmulateTerminal(emulation);
                return true;
            case "showDialogWithAdvancedSettings":
                boolean dialog = Boolean.parseBoolean(value);
                if (dialog == settings.isShowDialogWithAdvancedSettings()) {
                    return false;
                }
                settings.setShowDialogWithAdvancedSettings(dialog);
                return true;
            case "toolchainsPath":
                if (same(settings.getToolchainsPathString(), value)) {
                    return false;
                }
                settings.setToolchainsPathString(value);
                return true;
            default:
                // 未知项静默跳过：IDEA 版本间会增删字段，不该因此报错
                return false;
        }
    }

    /** 读回关键项，确认 setter 生效（防止改到了一份不会被持久化的副本上）。 */
    private static List<String> verify(MavenGeneralSettings settings, Map<String, String> source) {
        List<String> mismatched = new ArrayList<>();
        check(mismatched, "customMavenHome", source.get("customMavenHome"), settings.getCustomMavenHome());
        check(mismatched, "userSettingsFile", source.get("userSettingsFile"), settings.getUserSettingsFile());
        check(mismatched, "localRepository", source.get("localRepository"), settings.getLocalRepository());
        String threads = source.get("threads");
        if (threads != null) {
            check(mismatched, "threads", threads.trim(), trim(settings.getThreads()));
        }
        return mismatched;
    }

    private static void check(List<String> mismatched, String key, String expected, String actual) {
        if (expected == null || expected.isEmpty()) {
            return;
        }
        if (!expected.equals(actual)) {
            mismatched.add(LABELS.getOrDefault(key, key));
        }
    }

    /**
     * 拿项目级 Maven 常规设置。
     *
     * <p>用 {@code MavenProjectsManager#getGeneralSettings()} —— 它返回的就是项目里那份
     * 会被持久化到 {@code workspace.xml} 的 {@link MavenGeneralSettings}。
     *
     * <p>走过的弯路记一笔：{@code MavenWorkspaceSettingsComponent} 看似是持久化宿主，
     * 但它<b>没有</b> {@code getGeneralSettings()} 方法（只有 {@code getSettings()} /
     * {@code getRealSettings()} 返回 {@code MavenWorkspaceSettings}），编译期就撞墙了。
     */
    private static MavenGeneralSettings resolveGeneralSettings(Project project) {
        try {
            MavenProjectsManager manager = project.getService(MavenProjectsManager.class);
            if (manager != null) {
                return manager.getGeneralSettings();
            }
        } catch (Throwable t) {
            LOG.warn("Worktree Sync: MavenProjectsManager 不可用", t);
        }
        return null;
    }

    // ------------------------------------------------------------------ 远程仓库

    private static void syncRemoteRepositories(Project project, Path sourceDir, SyncResult result) {
        List<Map<String, String>> repos = XmlOptions.readRepeatedOptionMaps(
                sourceDir.resolve(".idea").resolve(JAR_REPOSITORIES_XML), COMPONENT_REPOS, TAG_REPO);
        if (repos.isEmpty()) {
            result.skip("Maven 远程仓库：来源未配置 jarRepositories.xml");
            return;
        }

        List<RemoteRepositoryDescription> descriptions = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (Map<String, String> repo : repos) {
            String url = repo.get("url");
            if (url == null || url.isEmpty()) {
                continue;
            }
            String id = orDefault(repo.get("id"), "repository");
            String name = orDefault(repo.get("name"), id);
            descriptions.add(new RemoteRepositoryDescription(id, name, url));
            names.add(name);
        }
        if (descriptions.isEmpty()) {
            result.skip("Maven 远程仓库：来源没有可用的仓库条目");
            return;
        }

        try {
            RemoteRepositoriesConfiguration configuration =
                    project.getService(RemoteRepositoriesConfiguration.class);
            if (configuration == null) {
                result.fail("Maven 远程仓库：拿不到 RemoteRepositoriesConfiguration");
                return;
            }
            configuration.setRepositories(descriptions);
            result.apply("Maven 远程仓库 " + descriptions.size() + " 个（已即时生效）");
            LOG.info("Worktree Sync: 已同步 Maven 远程仓库 " + String.join(", ", names));
        } catch (Throwable t) {
            LOG.warn("Worktree Sync: 同步 Maven 远程仓库失败", t);
            result.fail("Maven 远程仓库：" + t.getMessage());
        }
    }

    // ------------------------------------------------------------------ 小工具

    private static boolean same(String a, String b) {
        if (a == null || a.isEmpty()) {
            return b == null || b.isEmpty();
        }
        return a.equals(b);
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
