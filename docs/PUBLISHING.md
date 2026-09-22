# 发布到 JetBrains Marketplace — 准备清单

> 只是想自己用 / 团队内用：**不用走这里**。直接 `build.bat buildPlugin` 产出 zip，
> `Settings → Plugins → ⚙ → Install Plugin from Disk...` 装上就行。下面只针对「公开上架」。

> 本文档最初是给作者本人的操作清单，「我做 / 你做 / 上传时填」是当时的标注方式；**步骤本身对任何发布者通用**。
> 文中的 `<凭证目录>` 指**仓库之外**的私有目录，用于存放证书、私钥、口令与 token —— 它们绝不进 git。

图例：**我做** = 代码/材料侧的准备；**你做** = 需要发布者本人操作或提供信息；**上传时填** = 网页表单里填。

---

## 0. 当前进度总览

| 项 | 状态 |
|:--|:--|
| 插件 id / 名称 / 版本 | ✅ 我做（`com.smallzhuge.worktree-sync` / `Worktree Sync` / `1.0.0`） |
| 描述（中文优先 + 精简英文段） | ✅ 我做 —— ⚠️ 刻意偏离官方的「前 40 字符英文」建议，见下方说明 |
| 变更日志 `change-notes` | ✅ 我做（中文优先 + 一句英文） |
| 插件图标 40×40 SVG（明/暗两版） | ✅ 我做 |
| 签名私钥 + 证书链 | ✅ 已生成（放在仓库外的 `<凭证目录>`，不进 git） |
| 构建 / 签名 / 发布 Gradle 接线 | ✅ 我做（环境变量驱动，无凭证入仓） |
| Vendor 显示名 | ✅ 已定：`liyaheng`（与证书 CN 一致，**不需要重签证书**） |
| License | ✅ 已定：**MIT**，已放 `LICENSE` 文件 |
| 截图清单 | ✅ 我做（见 [`SCREENSHOTS.md`](SCREENSHOTS.md)） |
| JetBrains 账号 + Vendor 档案 | ⬜ **你做** |
| Permanent Token | ⬜ **你做** |
| 截图（按清单拍） | ⬜ **你做** |
| 上传表单的 Tags / Category | ⬜ **上传时填** |
| vendor 邮箱 / 网址（可选） | ⬜ **你做**（要显示才需要） |

### ⚠️ 关于「描述前 40 字符必须是英文」

Marketplace 文档的原文要求是：描述**前 40 字符必须是英文摘要**，因为**插件预览卡直接截取这一段**。
当前实现是**中文在前**（按你的要求），所以卡片上会显示中文。

官方文档同时给了另两个口子，按需选：

| 做法 | 效果 |
|:--|:--|
| **保持现状**（中文打头） | 中文受众体验最好；卡片显示中文，国际用户第一眼可能划过 |
| 在 admin panel 里**单独设置页面描述** | 文档说页面描述存在插件包**之外**、可独立编辑：IDE 插件管理器里看到的仍是中文，网页可另写英文优先版 |
| 在描述最前面加**一行短英文**（如 `Sync worktree settings and agent sessions.`）再跟中文 | 两边都照顾；代价是中文受众要先跳过一行英文 |

需要哪条说一声，改起来都很快（前两条只是内容/配置调整，不动代码）。

---

## 1. 你做：账号与资质

1. **登录 JetBrains Account**
   `https://plugins.jetbrains.com` — 用你 IDEA 登录的那个账号。
   没有的话先在 `https://account.jetbrains.com` 注册。

2. **建 Vendor 档案**（上传前必须有）
   右上角头像 → `Vendor Profile` / `My Profile`。
   需要填：**Vendor 名称**（建议就用 `liyaheng`，和证书里的 CN 一致）、联系邮箱、可选网址/地址。
   > 之后插件页的 "Vendor" 链接就指向这里。

3. **接受 Marketplace Developer Agreement**
   首次上传时会弹出，必须同意。

4. **创建 Permanent Token**
   `https://plugins.jetbrains.com/author/me/tokens` → `Generate token`
   拿到的形如 `perm:xxxxxxxxxxxx`，**只显示一次，立刻存好**。
   存到**仓库之外**的 `<凭证目录>`，和证书放一起；`.gitignore` 已排除 `PUBLISH_TOKEN.txt`。

5. **（可选）给我两个值，我加进 plugin.xml**
   Marketplace 只在 plugin.xml 里填了这两个属性时才展示 vendor 网址与邮箱：
   ```xml
   <vendor url="https://github.com/你的账号" email="你的邮箱">liyaheng</vendor>
   ```
   现在 `<vendor>liyaheng</vendor>` 没带属性 —— **不影响上架**，只是插件页少了两个链接。

---

## 2. 我做：已生成的凭证

目录：`<凭证目录>`（仓库之外的私有目录）

| 文件 | 用途 | 要不要给出去 |
|:--|:--|:--|
| `chain.crt` | 证书链（自签，CN=liyaheng，有效期到 2036-09-19） | ✅ **要**：首次上传时登记到账号 |
| `private.pem` | 明文私钥 | ❌ **绝不外传**，只给签名任务 |
| `private_encrypted.pem` | AES-256 加密私钥（备份用） | ❌ 绝不外传 |
| `PASSWORD.txt` | 加密私钥的口令（32 位随机） | ❌ 绝不外传 |

> 生成命令（已执行，留档）：
> ```bash
> openssl genpkey -aes-256-cbc -algorithm RSA -out private_encrypted.pem \
>   -pkeyopt rsa_keygen_bits:4096 -pass file:PASSWORD.txt
> openssl rsa -in private_encrypted.pem -out private.pem -passin file:PASSWORD.txt
> openssl req -key private.pem -new -x509 -days 3650 -out chain.crt \
>   -subj "/CN=liyaheng/O=liyaheng/C=CN"
> ```

**签名机制**：签名证明插件从作者到用户手里的链路没被改过。缺签名不会让插件无法安装，
但安装时会弹「未签名」警告。首次上传签名包时，Marketplace 会要求把 `chain.crt` 的公钥登记到账号，
之后每次发布都用这一对密钥。

---

## 3. 上传时填：产品页字段

| 字段 | 建议值 | 说明 |
|:--|:--|:--|
| Name | `Worktree Sync` | 来自 plugin.xml，不用填。已查重：**无同名插件** |
| Version | `1.0.0` | 来自 plugin.xml |
| **Tags** | `Git`、`Build Tools`、`Project Management`、`AI Assistant`（按实际可选项勾） | **至少勾 1 个**，影响搜索命中 |
| **Category** | `Tools` 或 `Build Tools` | 单选 |
| **License** | 见下 | 上传表单里填，之后可在 General Information 改 |
| Channel | 留空（= Stable） | 要发预览版才填 nightly / eap |
| Screenshots | 按 4 张清单来（见 [`SCREENSHOTS.md`](SCREENSHOTS.md)），**最小 1200×760** | 上传后在 admin panel 的 Media 里加 |
| License | **MIT**（已定，工程根目录已放 `LICENSE`） | 上传表单里选 MIT |

**License 已定为 MIT** —— 别人可自由使用、修改、商用、再分发。工程根目录的 `LICENSE` 文件与上传表单里选的 License 保持一致即可。

> 插件本身不收集任何数据、不联网上报（除 Maven/Gradle 构建本身），所以**不需要隐私政策**。

---

## 4. 首次发布：用网页表单

**直达链接（都已实测页面标题正确）**：

| 用途 | 地址 | 实测页面标题 |
|:--|:--|:--|
| **上传插件** | `https://plugins.jetbrains.com/plugin/add` | `Upload Plugin \| JetBrains Marketplace` |
| 我的档案 | `https://plugins.jetbrains.com/author/me` | `My profile \| JetBrains Marketplace` |
| Permanent Token | `https://plugins.jetbrains.com/author/me/tokens` | （来自官方文档） |

> ⚠️ **找不到上传入口的常见原因**：商店首页（`plugins.jetbrains.com`）是面向**使用者**的，
> 那里天然没有上传入口。上传入口挂在**登录后的个人档案**下，未登录时右上角只显示 `Sign In`。

### 顺序不能跳

1. **登录**（右上角 `Sign In`，用 JetBrains Account）
   > 若直接开 `/plugin/add` 只看到空壳页或跳回登录，就是还没登录
2. **建 Vendor 档案** → `https://plugins.jetbrains.com/author/me`
   需要 Vendor 名称、联系邮箱。**没建档案时上传会被拦去先建档**
3. **同意 Marketplace Developer Agreement**（首次上传时弹）
4. 打开 `https://plugins.jetbrains.com/plugin/add` → 上传
   `build\distributions\worktree-sync-1.0.0.zip`
   > 上传前先跑带签名的构建（见第 5 节），否则会带「未签名」警告
5. 填 Tags / Category / License（License 选 MIT）
6. 提交 → 等人工审核（通常 1–3 个工作日）
7. 通过后到插件的 admin panel：
   - `Media` 加截图（按 [`SCREENSHOTS.md`](SCREENSHOTS.md)）
   - `Technical Information` 加 issue tracker（有 GitHub 仓库就填）
   - `General Information` 校对分类与描述来源

> **首次创建只能走网页表单**：上传 API（第 6 节）用于**已有插件**发新版本，
> 文档里的 `pluginXmlId` 参数说明也是指着「插件详情页右侧的 Plugin XML ID」，
> 意味着插件得先在网页上建出来。所以别想着用 curl 跳过这一步。

---

## 5. 构建带签名的包

证书环境变量就绪后（PEM 是多行的，Gradle 要求 Base64 单行）：

```powershell
$D = "<凭证目录>"    # 仓库之外，存放 chain.crt / private.pem / PASSWORD.txt

$env:CERTIFICATE_CHAIN    = [Convert]::ToBase64String([IO.File]::ReadAllBytes("$D\chain.crt"))
$env:PRIVATE_KEY          = [Convert]::ToBase64String([IO.File]::ReadAllBytes("$D\private.pem"))
$env:PRIVATE_KEY_PASSWORD = (Get-Content "$D\PASSWORD.txt" -Raw).Trim()

.\gradlew.bat --console=plain signPlugin buildPlugin
```

产物：`build\distributions\worktree-sync-1.0.0.zip`（签名后）

验证签名。**`verifyPluginSignature` 任务有个坑**：当证书链是通过环境变量（Base64）传入时，
该任务会把 **Base64 字符串原样写进临时 `.pem`**（不像 `signPlugin` 会自动解码），
于是报 `CertificateException: No certificate data found`。所以用 CLI 直接验更可靠：

```powershell
$SIGNER = "<gradle 用户目录>\caches\modules-2\files-2.1\org.jetbrains\marketplace-zip-signer\*\*\marketplace-zip-signer-*-cli.jar"
java -cp $SIGNER org.jetbrains.zip.signer.ZipSigningTool verify `
  -in build\distributions\worktree-sync-1.0.0-signed.zip `
  -cert "<凭证目录>\chain.crt"
```

验签**通过时无输出**；包未签名时会打印 `Provided zip archive is not signed` —— 可用它做反证，确认验证器确实在工作。

> 另外 `signPlugin` 的产物是 `worktree-sync-1.0.0-signed.zip`（带 `-signed` 后缀），
> 而 `buildPlugin` 的产物是 `worktree-sync-1.0.0.zip`。**要上架/发 Release 的是带 `-signed` 的那个。**

> 只跑 `buildPlugin`、不设环境变量时会**自动跳过签名**，本地自用不受影响。

---

## 6. 后续版本：命令行发布

改完代码、把 `version` 和 `<change-notes>` 更新后：

```powershell
$env:PUBLISH_TOKEN = "perm:你的token"
# 再设上面那三个签名环境变量，然后：
.\gradlew.bat --console=plain publishPlugin
```

`signPlugin` 会在 `publishPlugin` 之前自动执行（检测到证书环境变量时）。

等价的裸 API 调用（不依赖 Gradle，应急用）：

```bash
curl -i --header "Authorization: Bearer perm:你的token" \
  -F xmlId=com.smallzhuge.worktree-sync \
  -F file=@build/distributions/worktree-sync-1.0.0.zip \
  https://plugins.jetbrains.com/api/updates/upload
```

> 单包上限 **400 MB**；`channel` 留空即发到 Stable。

---

## 7. 上架前必须知道的两条限制

1. **只兼容 IntelliJ IDEA 2026.2 及以上**（`<idea-version since-build="262"/>`）。
   原因：用到的 `com.intellij.compiler.CompilerConfiguration`、`ProjectInstance` 系列 API 与
   `ProjectActivity`（Kotlin suspend 接口）都是 2026.2 才稳定的形态。
   想覆盖更老版本需要重写这几处适配层 —— 是个工作量决策，不是配置问题。

2. **硬依赖 Maven 插件**（`<depends>org.jetbrains.idea.maven</depends>`）。
   Maven 在 IC/IU 里都内置，正常不会缺；但用户在插件管理里禁用它，本插件会整体不加载。
   如果你觉得这个风险不可接受，可以改成可选依赖 + 反射调用（约十几行改动）。

审核时可能被问到，提前想好答复：

| 可能的问题 | 答复要点 |
|:--|:--|
| 插件会修改项目配置，如何保证安全？ | 全部走 IDEA 官方 API，不直接改文件；只在用户勾选并点「应用」后执行；目标已存在文件默认不覆盖；Claude 会话迁移只增不删，回退就是删掉目标 slug 目录 |
| 为什么需要访问 `~/.claude/`？ | 迁移 Agent 会话记录是本插件的核心功能之一；只读写目标 slug 目录，不触碰其他目录 |
| 会上报数据吗？ | 否。无网络请求、无遥测 |

---

## 8. 坑（都踩过）

### 上传前必跑这三个本地校验任务

Marketplace 的 plugin.xml 校验**本地就能完整复现**，不要靠上传试错（每次都要等审核）：

```powershell
.\gradlew.bat verifyPluginProjectConfiguration verifyPluginStructure
.\gradlew.bat verifyPlugin        # 约 10 分钟，首次还要下 ~900MB 依赖
```

- `verifyPluginProjectConfiguration` —— 项目配置（Java 版本、依赖、扩展点声明等）
- `verifyPluginStructure` —— plugin.xml 描述符完整性与插件包结构
- `verifyPlugin` —— **Plugin Verifier，与 Marketplace 审核用的是同一套**。
  `failureLevel` 默认包含 `INTERNAL_API_USAGES`，所以能提前发现
  「uses the Internal API」这类拒收

### ⚠️ 不要用 @ApiStatus.Internal 标记的任何成员

Marketplace 会以 "Your plugin uses the Internal API" 拒收，邮件**不列位置**。
`verifyPlugin` 报告里才会给出逐条明细。

本次实际被标记的 10 处，全部集中在 `MavenGeneralSettings`：

| 原用（❌ internal） | 公开替代（✅） |
|:--|:--|
| `getCustomMavenHome` / `setCustomMavenHome` | **`getMavenHome` / `setMavenHome`** |
| `MavenHomeTypeForPersistence` 枚举及其 getter/setter | 无需处理 —— `setMavenHome(String)` 内部会调 `MavenHomeKt.resolveMavenHomeType` 自行解析类型 |
| `getLocalRepository()` | 无公开 getter，但 **`setLocalRepository` 是公开的** → 该项「只写不读」 |
| `getToolchainsPathString()` | 同上，`setToolchainsPathString` 公开 → 「只写不读」 |

> **读写可能不对称**：同一字段的 getter 可能 internal 而 setter 公开。
> 这种情况把「读回校验」降级为「只写」，并在代码注释里写清原因。
>
> 另外 `MavenProjectsManager.getGeneralSettings()` 是**公开**的，可以放心用。

### description 必须以【拉丁字符】开头（硬校验，不是建议）

中文打头会直接被拒：

```
Invalid plugin descriptor 'description'.
The plugin description must start with Latin characters and have at least 40 characters.
```

对策：**第一行写英文摘要**，中文主体紧随其后。注意 `<![CDATA[` 后面**不要留换行或空格**，
直接接 `<p>`，避免解析出的纯文本以空白字符开头。

```xml
<description><![CDATA[<p><b>Sync IDEA and Maven project settings, ...</b></p>

<p>面向 AI Agent 并行 vibe coding 的 worktree 配置同步：...</p>
```

### sourceCompatibility 必须等于平台要求的 Java 版本

`verifyPluginProjectConfiguration` 会直接告警：

```
Java sourceCompatibility is set to '21', but IntelliJ Platform '2026.2.1' requires Java '25'.
```

2026.2.1 要 **25**（对应 IDEA 自带 JBR 25）。build.gradle.kts 里同步改
`sourceCompatibility` / `targetCompatibility` / `options.release`。
用 `JavaVersion.toVersion("25")` 比 `JavaVersion.VERSION_25` 稳，不受 Gradle 枚举版本差异影响。

### 其他

- **不要提交 `*.pem` / `*.crt` / token**。`.gitignore` 已加，但推到公开仓库前**先 `git status` 确认一遍**。
- **证书有效期**：本次自签 10 年（到 2036-09-19）。到期后签名校验会失败，需要重新生成并去账号里换证书。
- **换了证书 = 换了身份**，Marketplace 侧的旧公钥要一并更新，别以为只是本地替换文件。
- **name 不能带 "Plugin"、不能含 JetBrains 产品名**。`Worktree Sync` 合规。
- **截图别用默认主题**（Marketplace 明确不建议），并且不要出现桌面背景、浏览器窗口、个人信息。
- **描述里若含本机路径**（`settings.xml`、本地仓库、内网域名），**要么脱敏、要么用 demo 项目截图** —— 见 [`SCREENSHOTS.md`](SCREENSHOTS.md)。
