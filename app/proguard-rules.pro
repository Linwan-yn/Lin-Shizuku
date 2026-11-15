# ---------- 基础 ----------
# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# ---------- 跨进程协议红线（类名/接口名不可混淆） ----------
# Binder/AIDL：接口名即 DESCRIPTOR（服务端与客户端通信凭接口全名匹配），
# 接口、存根、Binder 实现一律保留类名与成员
-keep class * extends android.os.Binder { *; }
-keep class * implements android.os.IInterface { *; }
-keep interface moe.shizuku.privileged.api.** { *; }

# Shizuku 兼容 API（关键！）：moe.shizuku.api.BinderContainer 是发送给第三方应用的
# 跨进程 Parcelable，必须保留类名与 CREATOR，否则第三方反序列化 Bundle 报
# ClassNotFoundException -> 第三方应用收不到 Shizuku Binder
-keep class moe.shizuku.api.** { *; }
# Scene 反射加载 moe.shizuku.manager.shell.Shell（main(String[], String, IBinder, Handler)），R8 混淆会导致反射失败
-keep class moe.shizuku.manager.shell.** { *; }

# ---------- 合入的 Lin-Shizuku 服务端（app_process 入口 roro.stellar.server.StellarService，
# 类名不可变；服务端内部逻辑全部保留防运行期反射/加载失败） ----------
-keep class roro.stellar.** { *; }
-keep class com.stellar.** { *; }

# ---------- RC5 改造版服务端（lin.server.**）----------
# v7（MT 授权超时修复，根因）：RC5 将服务端包名由 roro.stellar.** 整体改为 lin.server.**，
# 但 keep 规则未同步补充，R8 全量压缩（isMinifyEnabled=true）把独立 app_process 入口
# lin.server.userservice.UserServiceStarter（以及 daemon 入口 LinDaemon 等非 Binder 类）
# 当作无引用代码删除 -> 服务端 exec 用户服务进程时 ClassNotFound 即崩 -> MT 管理器的
# Dhizuku server 永不 attach -> 授权后仍报"请求 Shell 权限超时"。
# 现按与 1.0.8 roro.stellar.** 完全对齐的保守策略整包保留，防止任何反射/动态加载缺口。
-keep class lin.server.** { *; }

# ---------- rikka 基础设施（rish/sui/shizuku JNI + refine 运行时） ----------
-keep class rikka.** { *; }
-keep class dev.rikka.** { *; }

# ---------- 反射/动态调用安全 ----------
# WebView JS 桥（ksu 兼容）方法经反射调用
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
# refine 注解生成的 Ref 类
-keepclassmembers class * {
    @dev.rikka.tools.refine.annotation.RefineAs *;
}

# ---------- 依赖库 ----------
# Keep Compose
-dontwarn androidx.compose.**
# BouncyCastle（AdbKey 证书/加解密）
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
# Gson（服务端权限配置 JSON 序列化，保留字段）
-keep class com.google.gson.** { *; }
-dontwarn com.google.gson.**
# OkHttp / WebSocket
-dontwarn okhttp3.**
-dontwarn okio.**
# RikkaX ParcelableList
-dontwarn dev.rikka.rikkax.parcelablelist.**
# androidx.webkit
-dontwarn androidx.webkit.**
# conscrypt（已移除依赖，仅保留 dontwarn 兼容）
-dontwarn org.conscrypt.**
