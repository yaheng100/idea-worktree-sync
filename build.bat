@echo off
rem 便捷构建脚本 —— 只是把参数转交给 Gradle wrapper（gradlew.bat）。
rem
rem 前置要求：JDK 21+（IntelliJ Platform Gradle Plugin 2.x 的要求）。
rem 如果系统只装了旧 JDK，把 JAVA_HOME 指向 IDEA 自带的 JBR 即可，例如：
rem     set "JAVA_HOME=%LOCALAPPDATA%\Programs\IntelliJ IDEA\jbr"
rem （IDEA 2026.2 的 JBR 是 JDK 25；安装目录带版本后缀时按实际路径改）
rem
rem 用法:
rem     build.bat buildPlugin
rem     build.bat clean buildPlugin
rem     build.bat signPlugin buildPlugin     （需先设好证书相关环境变量，见 docs/PUBLISHING.md）
setlocal
call "%~dp0gradlew.bat" %*
endlocal
