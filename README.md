# Worktree Sync — IDEA 插件

面向 AI Agent 并行 vibe coding 的 worktree 配置同步：打开新 worktree 时弹窗选一次来源，自动继承 IDEA 与 Maven 设置，并同步 Agent 的配置与会话记录。

## 解决什么问题

一个仓库开多个 git worktree、每个 worktree 跑一个 agent，是常用的并行开发姿势。但每开一个新 worktree 都要重踩两遍同样的坑：

1. **IDEA 项目设置不会跟过来** —— worktree 的 `.idea` 由 IDEA 重新生成，主仓库里调好的编译进程堆、Maven home、`settings.xml`、本地仓库都不会复制过去。少设一次构建堆就可能编译 OOM，少设一次 Maven 就找不到私有仓库。
   > IDEA 的「新项目默认设置」对走 Maven 导入的 worktree 不生效，设全局默认解决不了。
2. **Agent 会话对不上** —— 会话按工作目录绝对路径的 slug 分目录存放，换 worktree 路径 = 换 project，历史对话看不到，agent 失去上下文。

## 功能

三组同步都是**即时生效、无需重启**。前两组走官方 API —— 会同时更新 IDEA 内存态，因此不会被 IDEA 退出时的旧值覆盖回去。

| 组 | 同步内容 | 来源 |
|:--|:--|:--|
| **IDEA 构建设置** | 编译进程堆 `BUILD_PROCESS_HEAP_SIZE`、`BUILD_PROCESS_VM_OPTIONS` | `.idea/compiler.xml` |
| | 全局编码 `PROJECT` charset | `.idea/encodings.xml` |
| **Maven 设置** | Maven home、home 类型、user settings.xml、本地仓库、离线模式、线程数、总是更新快照、使用 .mvn 配置、非递归、打印错误栈、模拟终端、toolchains.xml | `.idea/workspace.xml` 的 `MavenImportPreferences` → `MavenGeneralSettings` |
| | 远程仓库列表（Nexus、镜像） | `.idea/jarRepositories.xml` |
| **Agent 配置与会话**<br>（当前支持 Claude Code） | 项目级配置：`.claude/settings.json`、`.claude/settings.local.json`、`.mcp.json`、`CLAUDE.md` —— 目标不存在才复制；被 git 跟踪的文件在 worktree 里已存在会自动跳过，不弄脏工作树 | 来源项目目录 |
| | 会话记录：`~/.claude/projects/<源slug>/` → `<目标slug>/`，按时间范围过滤，复制 `*.jsonl` 与 subagent 目录，并把会话内的 `cwd` 改写成新路径 | `~/.claude/projects/` |

> Maven 设置是**项目级**配置、存在各 worktree 自己的 `workspace.xml` 里，所以同样会丢。写入后插件会**读回校验**关键项，不一致就明确报错，不假装成功。
> `~/.claude/settings.json` 是全局共享的，不同步；`session-env` / `sessions` / `tasks` 是运行态，不同步。

**刻意不做**

- 代码风格 / 检查配置 / 运行配置：2026.2 已移除 `ProjectManagerEx.reloadProject`，这些项没有公开的可编程接口，写文件后必须重启才生效，且容易被内存态反向覆盖。
- Maven `originalFiles`（链接的 pom 列表）：会触发整仓重新导入，而根 pom 已覆盖多模块 reactor，收益不成比例。

## 使用

- **自动**：打开 worktree → 弹窗选来源与同步项 → 应用。勾「记住本次选择」后，该项目之后静默应用。
- **手动**：`Tools → Sync Worktree Settings...`（普通项目也能用，靠「浏览...」选来源）。
- 非 worktree 项目**不弹窗**。判定依据：`<项目>/.git` 是**文件**（主仓库是目录），内容形如 `gitdir: <主仓库>/.git/worktrees/<名字>`。

## 构建与安装

```bash
./gradlew buildPlugin          # Windows: gradlew.bat buildPlugin
```

或等价的便捷脚本（同样转交 wrapper）：

```bat
build.bat buildPlugin
```

产物 `build/distributions/worktree-sync-1.0.1.zip` → `Settings → Plugins → ⚙ → Install Plugin from Disk...`

**前置要求**：JDK 21+。Gradle 无需预装 —— wrapper 已锁定 9.7.1，首次运行自动下载
（IntelliJ Platform Gradle Plugin 2.x 要求 Gradle ≥ 9.0.0）。
系统只装了旧 JDK 时，把 `JAVA_HOME` 指向 IDEA 自带的 JBR 即可（2026.2 的 JBR 是 JDK 25）。

> **两个可选配置**（本仓库的默认值对多数人是空操作，按需改）：
> - `build.gradle.kts` 里用 `local("<IDEA 安装路径>")` 指向本地 IDE 作为平台依赖 —— 零下载且版本完全对齐。
>   想换成从仓库拉取就把它替换成 `intellijIdeaCommunity("<版本>")`。
> - `gradle.properties` 里配了本机代理。**不需要代理就把那 5 行 `systemProp.*` 删掉**，
>   否则没有该代理的机器上依赖拉不下来。

<details>
<summary>故障排查：wrapper 脚本跑不起来时</summary>

在缺少 coreutils 的极简 shell 里，`gradlew` 的启动脚本可能报错。此时绕过 wrapper 直接喂启动器类：

```bash
java -classpath "<gradle 发行版>/lib/gradle-launcher-<版本>.jar" \
     org.gradle.launcher.GradleMain -p . -g "<gradle 用户目录>" --console=plain buildPlugin
```
</details>

## 验证

`devtools/DetectorSmokeTest.java` 是**纯 JDK 冒烟测试**，用 IDEA 自带 JBR 的 `javac`/`java` 直接编译运行，不用起 IDE。覆盖 slug 规则、worktree 探测与同仓库候选枚举、会话 cwd 改写（含路径前缀边界）、Maven 设置的嵌套与重复 XML 解析。

## 已知的 API 陷阱（踩过，别重复踩）

| 陷阱 | 真相 |
|:--|:--|
| `com.intellij.openapi.compiler.CompilerConfiguration` | **包名是 `com.intellij.compiler`**，在 `plugins/java/lib/modules/intellij.java.compiler.jar` |
| `ProjectManagerEx` 在 `com.intellij.openapi.project` | **在 `com.intellij.openapi.project.ex`** |
| `ProjectManagerEx.reloadProject` | **2026.2 已移除**，只剩 `forceCloseProject` / `loadProject` |
| `<projectManagerListener>` 扩展点 | **不存在**，全量扫描平台 XML 描述符零命中 |
| `<postStartupActivity>` 的实现接口 | **`com.intellij.openapi.startup.ProjectActivity`**（Kotlin suspend），不是已废弃的 `StartupActivity` |
| `DialogWrapper.getProject()` | 已无此方法，自己存 `Project` 字段 |
| `DialogWrapper.doValidate()` 返回 `String` | 返回 `com.intellij.openapi.ui.ValidationInfo` |
| `MavenImportPreferences` 是一个 Java 类 | **不是类**，只是 `workspace.xml` 里的组件别名；宿主是 `MavenWorkspaceSettings(Component)` |
| `MavenWorkspaceSettingsComponent#getGeneralSettings()` | **不存在**（只有 `getSettings()` / `getRealSettings()`）；用 `MavenProjectsManager#getGeneralSettings()` |
| `MavenGeneralSettings#getThreads()/setThreads()` 收发 `int` | 收发**字符串** |
| Maven 设置存在应用级全局配置里 | 存在**项目级** `.idea/workspace.xml` 的 `<component name="MavenImportPreferences">` 里 |
| `RemoteRepositoryDescription` 构造器 | `(String id, String name, String url)`，在 `plugins/java/lib/modules/intellij.java.jar` |
| Claude 会话里的路径 | JSON 里是**双反斜杠** `E:\\Work\\myrepo`，用单反斜杠替换永远匹配不到 |
