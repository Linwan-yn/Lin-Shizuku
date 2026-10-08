# Lin-Shizuku 构建指南

本文档说明如何本地构建 Lin-Shizuku 项目。

## 📋 前置要求

### 系统环境
- **操作系统**：Windows 10+、macOS 10.14+ 或 Linux
- **Java**：JDK 11 或更高版本
- **Android SDK**：API 29 以上
- **Gradle**：7.0 或更高版本（项目已包含 Gradle Wrapper）

### 开发工具
- **Android Studio**：Arctic Fox (2020.3.1) 或更高版本（推荐）
- 或者 **IntelliJ IDEA** + Android 插件

### 开发设备
- **Android 手机/模拟器**：Android 10 以上
- **ADB 工具**（用于安装和测试）

## 🚀 快速开始

### 方式 1：使用 Android Studio（推荐）

1. **克隆项目**
   ```bash
   git clone https://github.com/Linwan-yn/Lin-Shizuku.git
   cd Lin-Shizuku
   ```

2. **打开项目**
   - 打开 Android Studio
   - 选择 `File > Open` 或 `Open an Existing Project`
   - 选择项目目录

3. **等待 Gradle 同步**
   - Android Studio 会自动下载依赖
   - 可能需要 5-10 分钟（取决于网速）

4. **构建项目**
   - 菜单：`Build > Make Project`
   - 或按快捷键：`Ctrl+F9` (Windows/Linux) 或 `Cmd+F9` (macOS)

5. **运行应用**
   - 菜单：`Run > Run 'app'`
   - 或按快捷键：`Shift+F10` (Windows/Linux) 或 `Ctrl+R` (macOS)
   - 选择目标设备/模拟器

### 方式 2：使用命令行

1. **克隆项目**
   ```bash
   git clone https://github.com/Linwan-yn/Lin-Shizuku.git
   cd Lin-Shizuku
   ```

2. **查看 Gradle 任务**
   ```bash
   # Windows
   gradlew tasks
   
   # macOS / Linux
   ./gradlew tasks
   ```

3. **构建调试版 APK**
   ```bash
   # Windows
   gradlew assembleDebug
   
   # macOS / Linux
   ./gradlew assembleDebug
   ```
   
   生成的 APK：`app/build/outputs/apk/debug/app-debug.apk`

4. **构建发布版 APK**
   ```bash
   # Windows
   gradlew assembleRelease
   
   # macOS / Linux
   ./gradlew assembleRelease
   ```
   
   生成的 APK：`app/build/outputs/apk/release/app-release.apk`

5. **安装到设备**
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

6. **运行应用**
   ```bash
   adb shell am start -n com.linwan.lin_shizuku/.MainActivity
   ```

## 📁 项目结构

```
Lin-Shizuku/
├── app/                          # 主应用模块
│   ├── build/                    # 构建输出目录
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/            # Java/Kotlin 源代码
│   │   │   ├── res/             # 资源文件（布局、字符串、图片等）
│   │   │   └── AndroidManifest.xml
│   │   ├── debug/               # 调试特定配置
│   │   └── release/             # 发布特定配置
│   └── build.gradle             # App 模块 Gradle 配置
│
├── libs/                         # 依赖库目录
│
├── build.gradle                  # 项目根 Gradle 配置
├── settings.gradle               # 项目设置
├── gradle.properties             # Gradle 属性配置
│
├── README.md                     # 项目说明
├── BUILD.md                      # 本文件
├── CHANGELOG.md                  # 更新日志
└── LICENSE                       # 许可证
```

## 🔧 构建配置说明

### build.gradle (根目录)

```gradle
// Gradle 版本和依赖
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath 'com.android.tools.build:gradle:7.1.2'
        classpath 'org.jetbrains.kotlin:kotlin-gradle-plugin:1.6.10'
    }
}
```

### app/build.gradle

```gradle
android {
    compileSdk 34              // 目标 SDK
    
    defaultConfig {
        applicationId "com.linwan.lin_shizuku"
        minSdk 29              // 最低 SDK
        targetSdk 34           // 目标 SDK
        versionCode 108        // 版本号（内部）
        versionName "1.0.8"    // 版本号（显示）
    }
    
    // 构建类型
    buildTypes {
        debug {
            debuggable true
        }
        release {
            minifyEnabled true     // 启用代码混淆
            proguardFiles ...      // ProGuard 配置
        }
    }
}
```

## 🔑 签名配置（发布版本）

### 创建签名密钥

1. **使用 Android Studio**
   - 菜单：`Build > Generate Signed Bundle/APK`
   - 选择 `APK`
   - 选择 `Create new...` 创建新密钥
   - 填写密钥信息并保存

2. **使用命令行**
   ```bash
   keytool -genkey -v -keystore my-release-key.keystore \
     -keyalg RSA -keysize 2048 -validity 10000 \
     -alias my-key-alias
   ```

### 配置签名（app/build.gradle）

```gradle
android {
    signingConfigs {
        release {
            storeFile file('/path/to/my-release-key.keystore')
            storePassword 'store-password'
            keyAlias 'my-key-alias'
            keyPassword 'key-password'
        }
    }
    
    buildTypes {
        release {
            signingConfig signingConfigs.release
        }
    }
}
```

### 使用签名构建

```bash
# Windows
gradlew assembleRelease

# macOS / Linux
./gradlew assembleRelease
```

## 📦 依赖管理

### 主要依赖

```gradle
dependencies {
    // Shizuku 核心库
    implementation 'dev.rikka.shizuku:api:13.5.4'
    implementation 'dev.rikka.shizuku:provider:13.5.4'
    
    // Sui 权限库
    implementation 'dev.rikka.sui:sui:1.0.2'
    
    // 其他重要依赖
    implementation 'androidx.appcompat:appcompat:1.x.x'
    implementation 'com.google.android.material:material:1.x.x'
    // ...
}
```

### 更新依赖

```bash
# 检查依赖更新
./gradlew dependencyUpdates

# 更新特定依赖
./gradlew upgrade
```

## 🧪 测试

### 单元测试

```bash
# 运行所有单元测试
./gradlew test

# 运行特定测试类
./gradlew test --tests com.linwan.lin_shizuku.ExampleUnitTest
```

### 运行 Instrumented 测试（设备/模拟器）

```bash
# 运行所有 Instrumented 测试
./gradlew connectedAndroidTest

# 运行特定测试类
./gradlew connectedAndroidTest --tests com.linwan.lin_shizuku.ExampleInstrumentedTest
```

## 🐛 调试

### 启用调试模式

1. **在代码中启用日志**
   ```kotlin
   import android.util.Log
   
   Log.d("Lin-Shizuku", "Debug message")
   ```

2. **使用 Android Studio 调试器**
   - 在代码中设置断点（左边栏点击）
   - 点击 `Debug 'app'` 按钮
   - 使用调试工具栏控制执行流程

3. **使用 Logcat 查看日志**
   - 下方面板：`Logcat` 标签
   - 按包名过滤：`com.linwan.lin_shizuku`

### 常用 ADB 命令

```bash
# 查看日志
adb logcat | grep Lin-Shizuku

# 清空日志
adb logcat -c

# 获取应用信息
adb shell dumpsys package com.linwan.lin_shizuku

# 启动活动
adb shell am start -n com.linwan.lin_shizuku/.MainActivity

# 强制停止应用
adb shell am force-stop com.linwan.lin_shizuku
```

## 📊 构建优化

### 加快构建速度

1. **启用离线模式**（Gradle 已下载过依赖）
   - Android Studio：`File > Settings > Gradle` 
   - 勾选 `Offline work`

2. **并行构建**
   ```gradle
   // gradle.properties
   org.gradle.parallel=true
   org.gradle.workers.max=8
   ```

3. **增量构建**
   - Gradle 会自动缓存构建结果
   - 避免清理构建：不要频繁运行 `clean`

4. **降低 lint 检查**（调试时）
   ```gradle
   android {
       lintOptions {
           checkReleaseBuilds false
       }
   }
   ```

### APK 瘦身

1. **启用 ProGuard/R8 混淆**
   ```gradle
   buildTypes {
       release {
           minifyEnabled true
           shrinkResources true
       }
   }
   ```

2. **删除未使用资源**
   ```gradle
   android {
       bundle {
           language.enableSplit = true
       }
   }
   ```

## ⚙️ 高级配置

### 环境变量

```bash
# 设置 Android SDK 路径
export ANDROID_SDK_ROOT=/path/to/android/sdk
export ANDROID_HOME=/path/to/android/sdk

# 设置 Java Home
export JAVA_HOME=/path/to/jdk11

# 验证
echo $ANDROID_SDK_ROOT
```

### Gradle 属性（gradle.properties）

```properties
# JVM 内存配置
org.gradle.jvmargs=-Xmx2048m -XX:MaxPermSize=512m

# 构建功能
android.useAndroidX=true
android.enableJetifier=true
android.nonTransitiveRClass=true

# 启用新特性
android.enableR8=true
```

## 🆘 常见问题

### Q: Gradle 同步失败怎么办？

**A:**
```bash
# 清空 Gradle 缓存
rm -rf ~/.gradle/caches

# 重新同步
./gradlew --refresh-dependencies

# 或在 Android Studio 中
# File > Invalidate Caches > Invalidate and Restart
```

### Q: 编译错误：Cannot find symbol

**A:**
- 检查依赖是否正确配置
- 确认 Gradle 已完全同步
- 运行 `./gradlew clean build`

### Q: APK 安装失败怎么办？

**A:**
```bash
# 卸载旧版本
adb uninstall com.linwan.lin_shizuku

# 重新安装
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 如果提示版本冲突，可以加 -r 参数强制覆盖
```

### Q: 模拟器太慢怎么办？

**A:**
- 使用 Google Play 镜像的模拟器
- 增加模拟器 RAM（建议 2GB 以上）
- 在真实设备上测试
- 启用硬件加速（如支持）

## 📚 更多资源

- [Android 开发官方文档](https://developer.android.com/docs)
- [Gradle 官方文档](https://gradle.org/docs/)
- [Shizuku GitHub](https://github.com/RikkaApps/Shizuku)
- [Sui GitHub](https://github.com/RikkaApps/Sui)

## 💡 贡献代码

如果要贡献代码：

1. Fork 本仓库
2. 创建特性分支：`git checkout -b feature/your-feature`
3. 提交更改：`git commit -am 'Add your feature'`
4. 推送到分支：`git push origin feature/your-feature`
5. 开启 Pull Request

## 📞 获取帮助

- 提交 [Issue](https://github.com/Linwan-yn/Lin-Shizuku/issues)
- 查看 [Discussions](https://github.com/Linwan-yn/Lin-Shizuku/discussions)

---

**最后更新：2026-10-08**
