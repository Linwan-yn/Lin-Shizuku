# Lin-Shizuku 构建与源码说明

## 前置环境
- JDK 17
- Android SDK：`compileSdk 37`、`minSdk 24`（已在 `app/build.gradle.kts` 中配置）
- Gradle 8.13（仓库自带 `gradlew` wrapper，无需单独安装）
- AGP 8.13.2、Kotlin 2.3.10、Compose Compiler 2.3.10
- 无需 NDK / CMake：`jniLibs/` 下为预编译好的原生库，随源码一并入库

## 克隆与构建
```bash
git clone https://github.com/Linwan-yn/Lin-Shizuku.git
cd Lin-Shizuku

# Debug 构建（自动使用 debug 签名，开箱即用）
./gradlew assembleDebug

# Release 构建（需自备签名，见下一节）
./gradlew assembleRelease
```
产物位于 `app/build/outputs/apk/`。

## 签名说明（重要）
- 仓库**不附带签名私钥**（`*.keystore`）与签名口令，二者均在被 `.gitignore` 排除的 `local.properties` 中，不会进入版本库。
- 官方 Release APK 使用作者私钥签名；第三方克隆后请使用**自己的 keystore** 构建，无法、也不应复现官方签名。
- 需要 Release 签名时，在工程根目录新建 `local.properties`（不入库）：

```properties
sdk.dir=/path/to/android-sdk
keystore.path=/path/to/your.keystore
keystore.storePassword=你的密钥库口令
keystore.keyAlias=你的别名
keystore.keyPassword=你的密钥口令
```

## 仓库结构
- `app/`：应用模块（Kotlin / Java 源码、资源、预编译原生库、ProGuard 规则）
- `gradle/wrapper/`：Gradle wrapper 配置
- `build.gradle.kts` / `settings.gradle.kts` / `gradle.properties`：工程级构建配置

## 常见疑问
- **APK 签名一致吗？** 官方各历史版本由同一私钥签名，可覆盖安装、无缝升级；自行构建的产物签名不同于官方，不能覆盖安装官方包。
- **遇到问题怎么办？** 请提交 Issue，附上手机型号、Android 版本、应用版本与启动方式。
