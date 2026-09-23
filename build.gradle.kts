plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "com.smallzhuge"
// ⚠️ Marketplace 上一个版本号一旦上传过就永久占用，重复上传会被拒：
//    "The com.smallzhuge.worktree-sync plugin already contains version X in channel …"
//    改版本号时记得同步更新 plugin.xml 的 <change-notes> 与 README/docs 里的 zip 文件名。
version = "1.0.1"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// 平台依赖：优先指向**本机已装的 IDEA**（零下载、版本完全对齐）。
// 路径请放在【用户级】gradle.properties（~/.gradle/gradle.properties，不进仓库）：
//     localIdePath=C:/Program Files/JetBrains/IntelliJ IDEA
// 也可以用环境变量 IDEA_HOME。
// 两者都没有时，回落到从 JetBrains 仓库下载 IDEA Community（首次约 1GB）。
val localIdePath: String? =
    providers.gradleProperty("localIdePath").orNull
        ?: providers.environmentVariable("IDEA_HOME").orNull

dependencies {
    intellijPlatform {
        if (localIdePath != null) {
            local(localIdePath)
        } else {
            intellijIdeaCommunity("2026.2.1")
        }
        bundledPlugin("com.intellij.java")
        // Maven 设置同步需要 org.jetbrains.idea.maven.* 的 API
        bundledPlugin("org.jetbrains.idea.maven")
    }
}

// 目标平台 2026.2.1 要求 Java 25（verifyPluginProjectConfiguration 会检查；
// 低于该版本的 sourceCompatibility 会被告知「可能导致 API 使用不正确」）。
// 用 toVersion(String) 而不是 JavaVersion.VERSION_25，避免 Gradle 版本枚举差异。
java {
    sourceCompatibility = JavaVersion.toVersion("25")
    targetCompatibility = JavaVersion.toVersion("25")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

intellijPlatform {
    instrumentCode = false
    buildSearchableOptions = false

    // 插件校验：Marketplace 用的就是同一套 Plugin Verifier。
    // failureLevel 默认已包含 INTERNAL_API_USAGES —— 即 Marketplace 会因为
    // "uses the Internal API" 拒收，本地跑 verifyPlugin 就能复现，不必靠上传试错。
    // 目标 IDE 优先用本机安装的（免下载整个 IDE）。
    pluginVerification {
        ides {
            // 默认只用本机 IDE：免下载、几十秒出结果，够日常把关
            if (localIdePath != null) {
                local(file(localIdePath))
            }
            // 需要覆盖 Marketplace 用的完整 IDE 矩阵时加 -PverifyRecommendedIdes
            // （会从仓库下载 IC/IU 等发行版，约 1GB 起，慢）
            if (providers.gradleProperty("verifyRecommendedIdes").isPresent) {
                recommended()
            }
        }
    }
}

// 签名与发布：凭证一律走环境变量，绝不写进仓库。
// 证书文件（chain.crt / private.pem / PASSWORD.txt）放在**仓库之外**的私有目录，见 docs/PUBLISHING.md
//
// PowerShell 里跑（PEM 是多行的，先转成单行 Base64 再注入环境变量）：
//   $env:CERTIFICATE_CHAIN    = [Convert]::ToBase64String([IO.File]::ReadAllBytes("<凭证目录>\chain.crt"))
//   $env:PRIVATE_KEY          = [Convert]::ToBase64String([IO.File]::ReadAllBytes("<凭证目录>\private.pem"))
//   $env:PRIVATE_KEY_PASSWORD = (Get-Content "<凭证目录>\PASSWORD.txt" -Raw).Trim()
//   $env:PUBLISH_TOKEN        = "perm:xxxx"
//
// 注意：Kotlin DSL 里 signPlugin / publishPlugin 的顶层访问器是
// `TaskContainer.signPlugin: TaskProvider<SignPluginTask>`，直接写 `signPlugin { }`
// 会因 receiver 类型不匹配而编译失败 —— 必须走 tasks. 前缀配置。
tasks.signPlugin {
    certificateChain.set(providers.environmentVariable("CERTIFICATE_CHAIN"))
    privateKey.set(providers.environmentVariable("PRIVATE_KEY"))
    password.set(providers.environmentVariable("PRIVATE_KEY_PASSWORD"))
}

tasks.publishPlugin {
    token.set(providers.environmentVariable("PUBLISH_TOKEN"))
}

// Gradle 9 会校验任务间隐式依赖：verifyPluginSignature 读的是 signPlugin 的产物，
// 同一次调用里把两者一起跑会报 "Property has implicit dependency"。
// 声明顺序依赖即可（不能用 dependsOn，会形成环）。
tasks.verifyPluginSignature {
    mustRunAfter(tasks.signPlugin)
}
