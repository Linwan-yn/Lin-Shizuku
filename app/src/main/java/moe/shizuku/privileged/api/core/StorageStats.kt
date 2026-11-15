package moe.shizuku.privileged.api.core

import android.content.Context
import java.io.File

/**
 * Lin-Shizuku 自身存储占用统计与清理。
 *
 * 统计口径（对应设置 → 内存与储存 → 存储空间管理）：
 *  - 应用缓存：context.cacheDir（含模块 WebUI 同步副本 webui_*、安装临时目录）
 *  - WebView 数据集：cacheDir/webview（WebView 浏览模块 WebUI 产生）
 *  - 应用：APK 本体（applicationInfo.sourceDir）
 *  - 应用数据：私有 dataDir 下文件 / 数据库 / 配置（不含缓存）
 */
object StorageStats {

    data class Sizes(
        val cacheBytes: Long,
        val webViewBytes: Long,
        val apkBytes: Long,
        val dataBytes: Long
    ) {
        val total: Long get() = cacheBytes + webViewBytes + apkBytes + dataBytes
    }

    private fun dirSize(file: File): Long {
        if (!file.exists()) return 0L
        if (file.isFile) return file.length()
        var sum = 0L
        file.listFiles()?.forEach { child ->
            sum += if (child.isFile) child.length() else dirSize(child)
        }
        return sum
    }

    fun measure(ctx: Context): Sizes {
        val appCtx = ctx.applicationContext
        val cache = appCtx.cacheDir
        val webView = File(cache, "webview")
        val apk = runCatching { File(appCtx.applicationInfo.sourceDir).length() }.getOrDefault(0L)
        // 应用数据：dataDir 整体减去缓存目录
        val dataDir = appCtx.dataDir
        val data = (dirSize(dataDir) - dirSize(cache)).coerceAtLeast(0L)
        return Sizes(
            cacheBytes = dirSize(cache) - dirSize(webView).coerceAtMost(dirSize(cache)),
            webViewBytes = dirSize(webView),
            apkBytes = apk,
            dataBytes = data
        )
    }

    /** 清空应用缓存（cacheDir 内全部文件，含 webview 子目录）。返回释放字节数。 */
    fun clearCache(ctx: Context): Long {
        val cache = ctx.applicationContext.cacheDir
        val freed = dirSize(cache)
        cache.listFiles()?.forEach { it.deleteRecursively() }
        return freed
    }

    /** 清空 WebView 缓存（仅 webview 子目录，不动模块 WebUI 副本）。 */
    fun clearWebView(ctx: Context): Long {
        val webView = File(ctx.applicationContext.cacheDir, "webview")
        val freed = dirSize(webView)
        webView.deleteRecursively()
        return freed
    }

    /** 重置 App：清空缓存 + 文件目录 + 数据库 + 共享配置（等同"清除数据"前的用户数据）。 */
    fun resetApp(ctx: Context): Long {
        val appCtx = ctx.applicationContext
        var freed = 0L
        freed += dirSize(appCtx.cacheDir); appCtx.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        freed += dirSize(appCtx.filesDir); appCtx.filesDir.listFiles()?.forEach { it.deleteRecursively() }
        appCtx.databaseList()?.forEach { db ->
            val f = File(appCtx.getDatabasePath(db).parentFile, db)
            freed += f.length()
            appCtx.deleteDatabase(db)
        }
        // 共享配置：清空 shared_prefs 目录（Prefs 会用默认值重建）
        val prefsDir = File(appCtx.dataDir, "shared_prefs")
        freed += dirSize(prefsDir); prefsDir.listFiles()?.forEach { it.delete() }
        return freed
    }

    fun formatMB(bytes: Long): String {
        val mb = bytes / 1024.0 / 1024.0
        return String.format("%.2f", mb)
    }
}
