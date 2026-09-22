# Worktree Sync 插件设计文档

日期：2026-09-19
状态：设计已确认，已实现
工程目录：本仓库根目录

> **脱敏说明**：本文是当时的原始设计记录，其中出现的本机路径（`C:\...`、`E:\...`）、
> 取证用的项目名与文件数，都是作者当时的开发上下文。保留它们是为了让「为什么这么设计」可追溯；
> 换到你的环境时按实际替换即可。

---

## 1. 问题背景

IDEA 编译 `xzg-common` 模块时报 `GC overhead limit exceeded`。根因不是某个类写错了，而是：

1. 仓库 `.gitignore` 排除了 `.idea`，`git worktree add` 不复制未跟踪文件 → 每个新 worktree 的 `.idea` 由 IDEA 重新生成。
2. IDEA 的「新项目默认设置」（`options\project.default.xml` 的 `defaultProject` 组件）**对走 Maven 导入的 worktree 完全不生效** —— 它只在从零 New Project 时套用。
3. 于是 JPS 构建进程退回内置默认堆（约 700MB），撞上单模块 1969 个 Lombok 源文件 → OOM。

同类问题还有：Claude Code 的会话按 **cwd 绝对路径 slug** 分目录存放（`E:\Work\xzg-system` → `~/.claude/projects/E--Work-xzg-system/`），新 worktree 必然是全新 slug 目录，历史会话一条都看不到。

**目标**：写一个 IDEA 插件，在打开新 worktree 时弹窗让用户选择设置来源，并自动应用 IDEA 项目设置与 Claude Code 配置/会话。

---

## 2. 环境事实（已实测）

| 项 | 值 |
|:--|:--|
| IDEA | 2026.2.1，`IU-262.9437.185` |
| 安装路径 | `C:\Users\pc\AppData\Local\Programs\IntelliJ IDEA 2` |
| 自带 JBR | JDK **25.0.3**（`jbr\bin\java.exe`），含 `javac.exe`，**无 `jar.exe`/`javap.exe`** |
| 系统 Java | 1.8（`E:\java`），不足以构建插件 |
| Gradle | 本机无，`~/.gradle` 无 |
| 网络 | 沙箱代理下载大文件被断（`http=000`）；本机代理 `127.0.0.1:10809` 可用 ✅ |
| 可用仓库 | `www.jetbrains.com/intellij-repository/releases` 200、`cache-redirector.jetbrains.com` 200、`plugins.gradle.org/m2` 200、`repo.maven.apache.org` 200 |

---

## 3. 关键技术验证结果

### 3.1 核心 API（全部实测存在于本机 IDEA jar 中）

| API | 包名 | 所在 jar |
|:--|:--|:--|
| `CompilerConfiguration` | **`com.intellij.compiler`**（注意不是 `com.intellij.openapi.compiler`） | `plugins\java\lib\modules\intellij.java.compiler.jar` |
| `setBuildProcessHeapSize(int)` / `getBuildProcessHeapSize(int)` | 同上，public | 同上 |
| `setBuildProcessVMOptions(String)` / `getBuildProcessVMOptions()` | 同上，public | 同上 |
| `CompilerManager` | `com.intellij.openapi.compiler` | 同上 |
| `EncodingProjectManager` | `com.intellij.openapi.encoding` | `lib\intellij.platform.core.jar` |
| `ProjectManagerEx` | **`com.intellij.openapi.project.ex`**（带 `.ex`） | `lib\intellij.platform.usageView.jar` |
| `NotificationGroup` / `NotificationGroupManager` | `com.intellij.notification` | `lib\intellij.platform.ide.core.jar` |
| `ProjectActivity` | `com.intellij.openapi.startup` | `lib\intellij.platform.core.jar` |

### 3.2 两条推翻前期笔记的结论

**（一）`ProjectManagerEx.reloadProject` 在 2026.2 已被移除。**
实测该类只有 `newProject` / `loadProject` / `openProject` / `isProjectOpened` / `forceCloseProject` / `saveAndForceCloseProject` / `closeAndDisposeAllProjects` / `checkCanClose`。
→ 「复制 `.idea` 文件再重载项目」的路线不可行，只能依赖 API 即时生效。

**（二）`projectManagerListener` 不是扩展点。**
对 `lib/` 与 `plugins/java/` 全量 XML 字节扫描，`projectManagerListener` 零命中（仅出现在实现该接口的 `.class` 中）。
→ 触发点改用 `postStartupActivity`：

```xml
<!-- lib/intellij.platform.core.jar :: META-INF/Core.xml -->
<extensionPoint name="postStartupActivity"
                interface="com.intellij.openapi.startup.ProjectActivity"
                dynamic="true"/>
```

**必须实现 `ProjectActivity`**（Kotlin suspend 接口），不是已废弃的 `StartupActivity`，否则 EP 类型校验会失败。

`notificationGroup` 扩展点存在（`beanClass="com.intellij.notification.impl.NotificationGroupEP"`），可正常使用。

### 3.3 构建链路要求

- IntelliJ Platform Gradle Plugin 最新 **2.19.0**
- 该插件要求 **Gradle ≥ 9.0.0**、Java Runtime ≥ 17
- 用 `local("<IDEA 安装路径>")` 指向本机 IDEA，**零平台包下载**，且版本完全对齐
- 依赖 `bundledPlugin("com.intellij.java")`（`CompilerConfiguration` 在该插件里）

---

## 4. 设计决策（已与用户确认）

| 决策点 | 结论 |
|:--|:--|
| IDEA 同步范围 | 方案甲：**只做能即时生效的项**，不做 codeStyles / inspections / .run |
| 工程目录 | `E:\Work\idea-worktree-sync` |
| 会话迁移范围 | 弹窗里可选 7 天 / 15 天 / 30 天 / 全部，默认 7 天 |
| 来源选择 | 自动探测同仓库 worktree + 弹窗选择（含手动浏览） |
| 弹窗频率 | 每项目首次弹窗，记住选择，可选「本次不再询问」 |

### 4.1 能力 A：IDEA 项目设置同步（全部即时生效）

| 项 | 手段 |
|:--|:--|
| 构建进程堆 `BUILD_PROCESS_HEAP_SIZE` ⭐ | `CompilerConfiguration.getInstance(project).setBuildProcessHeapSize(n)` |
| 构建 `VM_OPTIONS` | `setBuildProcessVMOptions(opts)` |
| 并行编译 | `setParallelCompilationOption(...)` |
| 全局编码 | `EncodingProjectManager.getInstance(project).setDefaultCharsetName("UTF-8")` |

**关键收益**：走 API 会同时更新 IDEA 内存态，因此**不会再出现「IDEA 退出时用内存里的旧值覆盖磁盘」**——这正是原始 OOM 问题的复发路径。

### 4.2 能力 B：Claude Code 同步

| 项 | 做法 |
|:--|:--|
| 项目级配置 | `.claude\settings.json`、`.claude\settings.local.json`、`.mcp.json`、`CLAUDE.md` —— **只复制 git 未跟踪的**（`git ls-files --error-unmatch` 判定），被跟踪的 worktree 自带，复制会弄脏工作树 |
| 会话记录 | `~/.claude/projects/<源slug>/` → `<目标slug>/`，按天数过滤 mtime，复制 `*.jsonl` 与子代理兄弟目录，并改写 jsonl 内 `cwd` 为新路径 |
| `~/.claude/settings.json` | **不同步**（全局共享） |
| `session-env` / `sessions` / `tasks` | **不做**（运行态，复制有损坏风险） |

slug 规则：绝对路径里的 `:` 与 `\` 全部替换为 `-`。`E:\Work\xzg-system` → `E--Work-xzg-system`。

### 4.3 UI / 触发 / 记忆

- 触发：`postStartupActivity` → 实现 `ProjectActivity`
- worktree 判定：`<basePath>\.git` 是**文件**（内容 `gitdir: ...`）；普通项目 `.git` 是目录 → 直接跳过不打扰
- 来源探测（零外部命令依赖）：解析 `.git` 得 gitdir → 反推主仓库 → 扫 `<主仓库>\.git\worktrees\*\gitdir` 得全部 worktree，叠加 `RecentProjectsManager` 里同仓库路径
- 记忆：app 级 `PersistentStateComponent`，落 `%APPDATA%\JetBrains\IntelliJIdea2026.2\options\worktree-sync.xml`，key 为规范化 basePath。**不写进项目目录**，避免污染工作树
- 手动入口：`Tools → Sync Worktree Settings…`，任意项目可用

### 4.4 排除项（明确不做）

- 不整份复制 `.idea`：排除 `workspace.xml`、`modules.xml`、`*.iml`、`libraries/`、`dataSources*.xml`（含绝对路径 / 凭证 / 机器态）
- 不做自动重开项目窗口（有丢未保存状态风险）
- 不做 `session-env` / `sessions` / `tasks` 同步

---

## 5. 架构

```
com.smallzhuge.worktreesync
├── WorktreeSyncActivity          ProjectActivity 入口，判定 + 查记忆 + 弹窗
├── WorktreeDetector              worktree / gitdir / 同仓库候选解析
├── WorktreeSyncDialog            DialogWrapper：来源单选 + 同步项复选 + 时间范围
├── WorktreeSyncSettings          app 级 PersistentStateComponent（记忆决策）
├── model/
│   ├── SyncPlan                  本次要做什么（来源 + 各开关 + 时间范围）
│   └── SyncResult                每个同步项的结果（成功/跳过/失败 + 原因）
├── sync/
│   ├── IdeaSettingsSyncer        编译器堆 / VM options / 并行编译 / 编码
│   └── ClaudeCodeSyncer          项目级配置 + 会话迁移 + cwd 改写
├── util/
│   ├── GitIgnoreChecker          判定文件是否被 git 跟踪
│   └── PathSlug                  cwd → Claude 项目 slug
└── WorktreeSyncAction            Tools 菜单手动入口
```

执行流程见 `docs/plans/flow.md`（或对话中的流程图）。

---

## 6. 构建与交付

```
JAVA_HOME = C:\Users\pc\AppData\Local\Programs\IntelliJ IDEA 2\jbr
Gradle 9.7.1（经本机代理 127.0.0.1:10809 下载）
plugins: java + org.jetbrains.intellij.platform 2.19.0
dependencies: intellijPlatform { local("<IDEA 路径>"); bundledPlugin("com.intellij.java") }
产物: build\distributions\worktree-sync-1.0.0.zip
安装: Settings → Plugins → ⚙ → Install Plugin from Disk
```

---

## 7. 验证计划

1. 在 `xzg-system-test` 或新建一个 worktree 上实测弹窗是否如期出现
2. 选择继承 `xzg-system-pro`（堆 3072），确认 `Settings → Compiler → Shared build process heap size` 立即变为 3072
3. 确认编译 `xzg-common` 不再 OOM
4. 确认 IDEA 重启后堆值仍是 3072（验证「内存态不被覆盖」）
5. 确认 `~/.claude/projects/<新slug>/` 出现会话文件，且在 Claude Code 里 `--resume` 能看到历史会话
6. 确认非 worktree 的普通项目打开时**不弹窗**（`xzg-system` 主仓库打开时不打扰）
