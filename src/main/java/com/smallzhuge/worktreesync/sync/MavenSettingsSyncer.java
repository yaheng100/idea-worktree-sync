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

    /**
     * 设置项 → 中文短名，用于汇总展示。
     *
     * <p>注意这里**没有** {@code mavenHomeTypeForPersistence}：它对应的枚举是
     * {@code @ApiStatus.Internal}，不能使用（详见 {@link #applyOne} 里的说明）。
     */
    private static final Map<String, String> LABELS = new LinkedHashMap<>();

    static {
        LABELS.put("customMavenHome", "Maven home");
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

    /**
     * 应用单个设置项，返回 true 表示值确实变了。
     *
     * <p><b>为什么用已废弃的 {@code getMavenHome}/{@code setMavenHome(String)}：</b>
     * Maven home 只有三种可选 API ——
     * <ul>
     *   <li>{@code getCustomMavenHome}/{@code setCustomMavenHome} → 标了
     *       {@code @ApiStatus.Internal}，会被 Marketplace 的 Plugin Verifier 拒收；</li>
     *   <li>{@code getMavenHomeType}/{@code setMavenHomeType(MavenHomeType)} → 公开未废弃，
     *       但 {@code MavenHomeType} 实例唯一的「路径→类型」工厂
     *       {@code MavenHomeKt.resolveMavenHomeType} 自己也是 {@code @ApiStatus.Internal}，
     *       等于换了个地方用 internal；</li>
     *   <li>{@code getMavenHome}/{@code setMavenHome(String)} → <b>公开但已废弃</b>（forRemoval）。
     *       它内部会调 {@code resolveMavenHomeType} 再落到 {@code setMavenHome(type, fire)}，
     *       语义正确、一步到位。</li>
     * </ul>
     * 三者相比，「公开但废弃」优于「internal」，所以选它。
     *
     * <p><b>废弃的风险已被兜住：</b>调用点外层是 {@code catch (Throwable)}，
     * 将来 IDE 真删掉这两个方法，最坏结果是「Maven home 这一项同步失败」并把原因报到通知里，
     * 不会让整个插件崩掉。
     */
    @SuppressWarnings("removal")
    private static boolean applyOne(MavenGeneralSettings settings, String key, String value) {
        switch (key) {
            case "customMavenHome":
                // ⚠️ 必须用公开的 getMavenHome/setMavenHome，不能用
                // getCustomMavenHome/setCustomMavenHome —— 后者标了 @ApiStatus.Internal，
                // 会被 Marketplace 的 Plugin Verifier 判为「uses the Internal API」而拒收。
                if (same(settings.getMavenHome(), value)) {
                    return false;
                }
                settings.setMavenHome(value);
                return true;
            // mavenHomeTypeForPersistence 这一项【刻意不处理】：
            // 它对应的枚举 MavenHomeTypeForPersistence 整个类都是 @ApiStatus.Internal。
            // 而 setMavenHome(String) 内部会先调 MavenHomeKt.resolveMavenHomeType，
            // 再落到 setMavenHome(type, fire) —— 类型由 IDEA 自己解析，
            // 我们既不需要、也不应该插手。
            case "userSettingsFile":
                if (same(settings.getUserSettingsFile(), value)) {
                    return false;
                }
                settings.setUserSettingsFile(value);
                return true;
            case "localRepository":
                // setLocalRepository 是公开的，但 getLocalRepository 标了 @ApiStatus.Internal，
                // 因此无法做「值没变就不写」的判断 —— 直接写入。
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
                // 同上：setToolchainsPathString 公开，getToolchainsPathString internal
                settings.setToolchainsPathString(value);
                return true;
            default:
                // 未知项静默跳过：IDEA 版本间会增删字段，不该因此报错
                return false;
        }
    }

    /**
     * 读回关键项，确认 setter 生效（防止改到了一份不会被持久化的副本上）。
     *
     * <p><b>只能读公开 getter。</b>{@code getLocalRepository()} / {@code getToolchainsPathString()}
     * 标了 {@code @ApiStatus.Internal}，会被 Marketplace 的 Plugin Verifier 拒收，所以那两项
     * 「只写不读」。少这两个读回校验不影响功能 —— 它们本来就由 user settings.xml 兜底
     * （settings.xml 里通常已声明 {@code <localRepository>}）。
     */
    @SuppressWarnings("removal")   // 同上：Maven home 只有「公开但废弃」这一条可走
    private static List<String> verify(MavenGeneralSettings settings, Map<String, String> source) {
        List<String> mismatched = new ArrayList<>();

        // Maven home 走路径比较：忽略盘符大小写与分隔符风格，避免误报
        String expectedHome = source.get("customMavenHome");
        if (expectedHome != null && !expectedHome.isEmpty()
                && !samePath(expectedHome, settings.getMavenHome())) {
            mismatched.add(LABELS.getOrDefault("customMavenHome", "customMavenHome"));
        }

        check(mismatched, "userSettingsFile", source.get("userSettingsFile"), settings.getUserSettingsFile());

        String threads = source.get("threads");
        if (threads != null) {
            check(mismatched, "threads", threads.trim(), trim(settings.getThreads()));
        }
        return mismatched;
    }

    /** Windows 路径比较：`E:\maven` 与 `e:/maven` 视为相同。 */
    private static boolean samePath(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return a.replace('\\', '/').equalsIgnoreCase(b.replace('\\', '/'));
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
