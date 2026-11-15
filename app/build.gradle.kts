import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("dev.rikka.tools.refine")
}

// 构建产物目录可被 -PlinBuildDir=/本地盘路径 覆盖：
// 默认 build（项目内）；云电脑/网络盘环境下指向本地盘，规避 ASM/R8 读 class 的网络 IO 死锁
layout.buildDirectory = file(providers.gradleProperty("linBuildDir").getOrElse("build"))

// Kotlin 2.3 DSL：jvmTarget 迁移到 compilerOptions
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

android {
    namespace = "moe.shizuku.privileged.api"
    compileSdk = 37
    // release 构建不跑 lintVital（2 核小内存环境下分析器拖慢/被杀），语法错误由编译器保证
    lint {
        checkReleaseBuilds = false
    }

    defaultConfig {
        applicationId = "moe.shizuku.privileged.api"
        minSdk = 24
        targetSdk = 37
        // 1.1.0：Android 17 适配（ACCESS_LOCAL_NETWORK 权限 + 权限请求优化）
        // 1.1.1：无线调试关闭守护——系统关闭无线调试后服务仍保持运行（root 身份重拉）
        // 1.1.1：配对流程去 mDNS——手动输入端口+配对码 TCP 直连，不弹本地网络权限窗
        // 1.1.1 v5.0：无线调试启动改用官方同款 native 启动器 liblin.so（starter.cpp 改编编译）
        // 1.1.1（1.0.3 服务端 API）：保活根治——开关自愈闭环/进程级兜底/守护可观测/服务端死亡事件上报/精确闹钟心跳
        // 1.1.5：消除悬浮底栏周围露纯色背景——去掉第5档容器层重复避让、收窄模块列表尾部留白；第5档深浅判断对齐 App 主题
        // 1.1.6：各页底部避让统一收窄到 48dp，卡片延伸进底栏后方；录制层底部端色贴近卡片面板底色，弱化避让区色差
        versionCode = 20261115
        versionName = "1.1.4-lin-Release"
        // 体积优化：仅保留 64 位 ABI（现代设备均为 arm64），只保留简体中文资源
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        // 仅保留简体中文资源（AGP 8.13 弃用 resourceConfigurations，改用 localeFilters）
        androidResources {
            localeFilters += listOf("zh-rCN")
        }
    }

    signingConfigs {
        create("release") {
            // 签名配置从 local.properties 读取（Android 构建标配，不入库）。
            // 仓库内不附带私钥与口令；release 签名所需 keystore.path / storePassword /
            // keyAlias / keyPassword 均在本机 local.properties 中填写，脚本不保留明文回退值。
            // 外部克隆后请使用自己的 keystore，或直接构建 debug 变体。
            val lp = Properties().apply {
                val f = rootProject.file("local.properties")
                if (f.exists()) f.inputStream().use { load(it) }
            }
            storeFile = file(lp.getProperty("keystore.path", "../release.keystore"))
            storePassword = lp.getProperty("keystore.storePassword", "")
            keyAlias = lp.getProperty("keystore.keyAlias", "shizuku-deepmod")
            keyPassword = lp.getProperty("keystore.keyPassword", "")
        }
    }

    buildTypes {
        release {
            // 1.0.2+：R8 全量压缩（体积优化至 ≤6MB）。49 轮网络盘 R8 死锁问题由本地盘构建规避；
            // 服务端入口 roro.stellar.server.StellarService / Binder 协议 / 四大组件均有 keep 规则保障。
            // 1.1.1 v4（202704）：无障碍自动抓配对端口。构建配置维持标准 R8 全量基线。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        aidl = true
        // 设置页「关于」版本号动态显示需要 BuildConfig.VERSION_NAME
        buildConfig = true
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            // 排除 BouncyCastle 后量子密码学静态数据（本应用不需要，节省约 1MB）
            excludes += listOf(
                "org/bouncycastle/pqc/**",
                "META-INF/**/versions/**",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*"
            )
        }
    }
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }
}

dependencies {
    // Compose BOM 2026.02.00：ui/foundation/ui-graphics = 1.10.3，与 AndroidLiquidGlass(backdrop 1.0.6) 编译目标完全一致
    val composeBom = platform("androidx.compose:compose-bom:2026.02.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    // AndroidLiquidGlass 液态玻璃库（官方 AAR + shapes 圆角矩形形状库）
    implementation("io.github.kyant0:backdrop:1.0.6")
    implementation("io.github.kyant0:shapes:1.2.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.navigation:navigation-compose:2.7.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("io.coil-kt:coil-compose:2.5.0")
    implementation("androidx.webkit:webkit:1.10.0")
    // Stellar server 合并依赖（与 Stellar 官方构建一致；shizuku-server.jar 已整体移除，
    // 因其内置的 hidden compat / parcelablelist 为旧版，运行时会让 Stellar server NoSuchMethodError 崩溃）
    compileOnly("dev.rikka.hidden:stub:4.4.0")
    implementation("dev.rikka.hidden:compat:4.4.0")
    implementation("dev.rikka.tools.refine:runtime:4.4.0")
    implementation("dev.rikka.rikkax.parcelablelist:parcelablelist:2.0.1")
    implementation("com.google.code.gson:gson:2.13.1")
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.77")
    // conscrypt：ADB 配对协议需要 Conscrypt.exportKeyingMaterial（RFC 5705 密钥导出，
    // 平台公开 API 无替代）；R8 会收缩其 dex，体积影响主要在 libconscrypt_jni.so
    implementation("org.conscrypt:conscrypt-android:2.5.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
