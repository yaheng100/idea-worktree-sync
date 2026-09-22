import com.smallzhuge.worktreesync.model.WorktreeInfo;
import com.smallzhuge.worktreesync.sync.ClaudeCodeSyncer;
import com.smallzhuge.worktreesync.util.PathSlug;
import com.smallzhuge.worktreesync.util.WorktreeDetector;
import com.smallzhuge.worktreesync.util.XmlOptions;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * 纯 JDK 冒烟测试：只覆盖不依赖 IDEA 平台的核心算法，不用起 IDE。
 *
 * <p>编译运行（用 IDEA 自带的 JBR，或任意 JDK 21+）：
 * <pre>
 * javac -encoding UTF-8 -d out \
 *   src/main/java/com/smallzhuge/worktreesync/util/PathSlug.java \
 *   src/main/java/com/smallzhuge/worktreesync/util/XmlOptions.java \
 *   src/main/java/com/smallzhuge/worktreesync/util/WorktreeDetector.java \
 *   src/main/java/com/smallzhuge/worktreesync/model/WorktreeInfo.java \
 *   src/main/java/com/smallzhuge/worktreesync/model/SyncResult.java \
 *   src/main/java/com/smallzhuge/worktreesync/sync/ClaudeCodeSyncer.java \
 *   devtools/DetectorSmokeTest.java
 * java -Dstdout.encoding=UTF-8 -cp out DetectorSmokeTest [主仓库目录] [worktree 目录]
 * </pre>
 *
 * <p><b>设计原则：可移植的用例永远跑，依赖本机仓库的用例按参数走、缺参数就 SKIP。</b>
 * 不把任何具体项目路径写死进来 —— 那既会让别人跑不过，也可能把私有配置带进公开仓库。
 */
public class DetectorSmokeTest {

    private static int pass = 0;
    private static int fail = 0;
    private static int skip = 0;

    /** 主仓库目录（`.git` 是目录）。可为 null。 */
    private static Path mainRepo;
    /** 该仓库下的某个 worktree 目录（`.git` 是文件）。可为 null。 */
    private static Path worktree;

    public static void main(String[] args) throws Exception {
        if (args.length >= 1 && !args[0].isBlank()) {
            mainRepo = Paths.get(args[0]).toAbsolutePath().normalize();
        }
        if (args.length >= 2 && !args[1].isBlank()) {
            worktree = Paths.get(args[1]).toAbsolutePath().normalize();
        }

        System.out.println("=== Worktree Sync 冒烟测试（纯 JDK）===");
        System.out.println("主仓库  : " + (mainRepo == null ? "(未提供，相关用例将 SKIP)" : mainRepo));
        System.out.println("worktree: " + (worktree == null ? "(未提供，相关用例将 SKIP)" : worktree));
        System.out.println();

        testSlug();
        testRewritePaths();
        testMavenSettingsXml();
        testGitDirResolution();
        testCandidates();

        System.out.println();
        System.out.println("==== 通过 " + pass + " / 失败 " + fail + " / 跳过 " + skip + " ====");
        if (fail > 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------ 可移植用例

    private static void testSlug() {
        System.out.println("--- PathSlug ---");
        eq("E:\\Work\\myrepo", "E--Work-myrepo", PathSlug.slugify("E:\\Work\\myrepo"));
        eq("E:\\Work\\myrepo\\", "E--Work-myrepo", PathSlug.slugify("E:\\Work\\myrepo\\"));
        eq("/home/u/proj", "-home-u-proj", PathSlug.slugify("/home/u/proj"));
        eq("C:\\a\\repo-b", "C--a-repo-b", PathSlug.slugify("C:\\a\\repo-b"));
    }

    private static void testRewritePaths() throws Exception {
        System.out.println("--- rewritePaths（私有方法，反射调用）---");
        Method m = ClaudeCodeSyncer.class.getDeclaredMethod(
                "rewritePaths", String.class, String.class, String.class);
        m.setAccessible(true);

        String src = "E:\\Work\\myrepo";
        String dst = "E:\\Work\\myrepo-feature";

        // JSON 转义形态：文件里真实存的是双反斜杠
        String json = "{\"cwd\":\"E:\\\\Work\\\\myrepo\",\"n\":1}";
        String out = (String) m.invoke(null, json, src, dst);
        System.out.println("      in : " + json);
        System.out.println("      out: " + out);
        check("双反斜杠形态应被改写", out.contains("E:\\\\Work\\\\myrepo-feature"));
        check("不应残留旧路径", !out.contains("myrepo\""));

        String slash = "{\"cwd\":\"E:/Work/myrepo\"}";
        String out2 = (String) m.invoke(null, slash, src, dst);
        System.out.println("      in : " + slash);
        System.out.println("      out: " + out2);
        check("正斜杠形态应被改写", out2.contains("E:/Work/myrepo-feature"));

        // 关键边界：myrepo 不能吃掉 myrepo-feature 的前缀
        String tricky = "E:\\\\Work\\\\myrepo-feature\\\\file.txt";
        String out3 = (String) m.invoke(null, tricky, src, dst);
        System.out.println("      in : " + tricky);
        System.out.println("      out: " + out3);
        check("前缀边界：myrepo 不应误改 myrepo-feature",
                out3.equals("E:\\\\Work\\\\myrepo-feature\\\\file.txt"));

        // 真实场景里两种形态并存：一部分会话字段是双反斜杠，另一部分可能是正斜杠。
        // 注意分隔符必须是 rewritePaths 认可的右侧边界（引号 / 反斜杠 / 正斜杠 / 逗号），
        // 否则按设计就不该替换 —— 那正是防止 myrepo 吃掉 myrepo-feature 的机制。
        String mixed = "{\"a\":\"E:\\\\Work\\\\myrepo\",\"b\":\"E:/Work/myrepo\"}";
        String out4 = (String) m.invoke(null, mixed, src, dst);
        System.out.println("      in : " + mixed);
        System.out.println("      out: " + out4);
        check("混合形态：双反斜杠部分应被改写", out4.contains("E:\\\\Work\\\\myrepo-feature"));
        check("混合形态：正斜杠部分应被改写", out4.contains("E:/Work/myrepo-feature"));
        check("混合形态：不应残留任一旧路径",
                !out4.contains("myrepo\"") && !out4.contains("myrepo\","));

        // 右侧边界缺失时**按设计不替换**（这是防前缀误伤的核心约束，值得固定住）
        String noBoundary = "prefix-E:\\Work\\myrepo";
        String out5 = (String) m.invoke(null, noBoundary, src, dst);
        check("右边界缺失时不应替换（防误伤设计）", out5.equals(noBoundary));
    }

    private static void testMavenSettingsXml() throws Exception {
        System.out.println("--- Maven 设置 XML 解析（合成文件）---");

        // 用合成文件断言 —— 真实项目的 .idea 文件状态会随使用变化，
        // 且内容是本机私有配置，不该硬编码进仓库的测试。
        String workspace = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<project version=\"4\">\n"
                + "  <component name=\"MavenImportPreferences\">\n"
                + "    <option name=\"generalSettings\">\n"
                + "      <MavenGeneralSettings>\n"
                + "        <option name=\"customMavenHome\" value=\"C:\\tools\\maven\" />\n"
                + "        <option name=\"userSettingsFile\" value=\"C:\\tools\\maven\\conf\\settings.xml\" />\n"
                + "        <option name=\"localRepository\" value=\"C:\\repo\\.m2\\repository\" />\n"
                + "        <option name=\"mavenHomeTypeForPersistence\" value=\"CUSTOM\" />\n"
                + "        <option name=\"threads\" value=\"16\" />\n"
                + "      </MavenGeneralSettings>\n"
                + "    </option>\n"
                + "  </component>\n"
                + "</project>\n";

        Path tempWorkspace = Files.createTempFile("wts-workspace", ".xml");
        try {
            Files.writeString(tempWorkspace, workspace, StandardCharsets.UTF_8);
            Map<String, String> map = XmlOptions.readNestedOptionMap(
                    tempWorkspace, "MavenImportPreferences", "MavenGeneralSettings");
            System.out.println("      解析结果 -> " + map);
            check("嵌套结构应能取到 MavenGeneralSettings 的 option 表", map.size() == 5);
            check("customMavenHome 应解析正确",
                    "C:\\tools\\maven".equals(map.get("customMavenHome")));
            check("userSettingsFile 应解析正确",
                    "C:\\tools\\maven\\conf\\settings.xml".equals(map.get("userSettingsFile")));
            check("localRepository 应解析正确",
                    "C:\\repo\\.m2\\repository".equals(map.get("localRepository")));
            check("枚举值应原样保留",
                    "CUSTOM".equals(map.get("mavenHomeTypeForPersistence")));
            check("只取目标元素的直接子 option", "16".equals(map.get("threads")));
        } finally {
            Files.deleteIfExists(tempWorkspace);
        }

        // 契约：组件不存在时返回空表/空列表，而不是抛异常
        Path bare = Files.createTempFile("wts-bare", ".xml");
        try {
            Files.writeString(bare,
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                            + "<project version=\"4\">\n"
                            + "  <component name=\"ProjectRootManager\" version=\"2\" />\n"
                            + "</project>\n",
                    StandardCharsets.UTF_8);
            check("没有该组件时应返回空表（而不是抛异常）",
                    XmlOptions.readNestedOptionMap(
                            bare, "MavenImportPreferences", "MavenGeneralSettings").isEmpty());
            check("没有该组件时重复元素解析应返回空列表",
                    XmlOptions.readRepeatedOptionMaps(
                            bare, "RemoteRepositoriesConfiguration", "remote-repository").isEmpty());
        } finally {
            Files.deleteIfExists(bare);
        }

        // 重复元素（远程仓库列表）
        String reposXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<project version=\"4\">\n"
                + "  <component name=\"RemoteRepositoriesConfiguration\">\n"
                + "    <remote-repository>\n"
                + "      <option name=\"id\" value=\"central\" />\n"
                + "      <option name=\"name\" value=\"Central Repository\" />\n"
                + "      <option name=\"url\" value=\"https://repo1.maven.org/maven2\" />\n"
                + "    </remote-repository>\n"
                + "    <remote-repository>\n"
                + "      <option name=\"id\" value=\"private\" />\n"
                + "      <option name=\"name\" value=\"Private Nexus\" />\n"
                + "      <option name=\"url\" value=\"https://nexus.example.com/repository/maven-public/\" />\n"
                + "    </remote-repository>\n"
                + "  </component>\n"
                + "</project>\n";

        Path tempRepos = Files.createTempFile("wts-repos", ".xml");
        try {
            Files.writeString(tempRepos, reposXml, StandardCharsets.UTF_8);
            List<Map<String, String>> parsed = XmlOptions.readRepeatedOptionMaps(
                    tempRepos, "RemoteRepositoriesConfiguration", "remote-repository");
            System.out.println("      解析出 " + parsed.size() + " 条仓库");
            for (Map<String, String> repo : parsed) {
                System.out.println("         " + repo);
            }
            check("重复元素应逐条解析出来", parsed.size() == 2);
            check("每条都应带非空 url", parsed.stream()
                    .allMatch(r -> r.get("url") != null && !r.get("url").isEmpty()));
            check("id 与 name 应分别归位", "private".equals(parsed.get(1).get("id"))
                    && "Private Nexus".equals(parsed.get(1).get("name")));
        } finally {
            Files.deleteIfExists(tempRepos);
        }
    }

    // ------------------------------------------------------------------ 依赖本机仓库的用例

    private static void testGitDirResolution() {
        System.out.println("--- git 目录解析（两种形态）---");
        if (mainRepo == null || worktree == null) {
            skip("未提供两个目录参数，跳过 git 目录解析用例");
            return;
        }
        check("主仓库应判定为「.git 是目录」", WorktreeDetector.isMainRepo(mainRepo));
        check("主仓库不应判定为 worktree", !WorktreeDetector.isWorktree(mainRepo));
        check("worktree 应判定为「.git 是文件」", WorktreeDetector.isWorktree(worktree));
        check("worktree 不应判定为「.git 是目录」", !WorktreeDetector.isMainRepo(worktree));

        Path worktreeGitDir = WorktreeDetector.readGitDir(worktree);
        System.out.println("      worktree gitdir = " + worktreeGitDir);
        Path mainGitDir = WorktreeDetector.mainGitDirOf(worktreeGitDir);
        System.out.println("      反推主仓库 .git  = " + mainGitDir);
        check("从 worktree 的 gitdir 应能反推出主仓库 .git",
                mainGitDir != null && WorktreeDetector.samePath(
                        mainGitDir.getParent(), mainRepo));

        check("resolveGitDir 在主仓库上应指向 <项目>/.git",
                ".git".equals(String.valueOf(WorktreeDetector.resolveGitDir(mainRepo).getFileName())));
    }

    private static void testCandidates() {
        System.out.println("--- 候选 worktree 枚举 ---");
        if (mainRepo == null || worktree == null) {
            skip("未提供两个目录参数，跳过候选枚举用例");
            return;
        }

        // 从 worktree 视角：应包含主仓库，且不含自己
        List<WorktreeInfo> fromWorktree = WorktreeDetector.candidates(worktree);
        System.out.println("      从 worktree 看 ->");
        for (WorktreeInfo info : fromWorktree) {
            System.out.println("         " + info);
        }
        check("应至少探测到 1 个候选", !fromWorktree.isEmpty());
        check("候选里应包含主仓库", fromWorktree.stream().anyMatch(i -> i.main));
        check("候选里不应包含自己", fromWorktree.stream()
                .noneMatch(i -> WorktreeDetector.samePath(i.path, worktree)));

        // 从主仓库视角：这是曾经的 bug —— .git 是目录时拿到空列表
        if (!WorktreeDetector.isMainRepo(mainRepo)) {
            skip("第一个参数不是主仓库形态，跳过主仓库视角用例");
            return;
        }
        List<WorktreeInfo> fromMain = WorktreeDetector.candidates(mainRepo);
        System.out.println("      从主仓库看 ->");
        for (WorktreeInfo info : fromMain) {
            System.out.println("         " + info);
        }
        check("主仓库里必须能列出候选 worktree（历史 bug 回归）", !fromMain.isEmpty());
        check("主仓库视角不应把主仓库自己列为候选", fromMain.stream()
                .noneMatch(i -> WorktreeDetector.samePath(i.path, mainRepo)));
        check("主仓库视角应包含传入的那个 worktree", fromMain.stream()
                .anyMatch(i -> WorktreeDetector.samePath(i.path, worktree)));
    }

    // ------------------------------------------------------------------ 断言工具

    private static void eq(String input, String expected, String actual) {
        check("slugify(" + input + ") = " + expected, expected.equals(actual));
        if (!expected.equals(actual)) {
            System.out.println("      实际: " + actual);
        }
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            pass++;
            System.out.println("  [PASS] " + name);
        } else {
            fail++;
            System.out.println("  [FAIL] " + name);
        }
    }

    private static void skip(String reason) {
        skip++;
        System.out.println("  [SKIP] " + reason);
    }
}
