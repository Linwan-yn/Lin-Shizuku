package moe.shizuku.privileged.api.core

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Build
import android.os.Debug
import java.io.File

/**
 * 公平运行内存机制适配（金标联盟 vivo/小米/OPPO/荣耀 统一标准 + Android 17 App memory limits）：
 * 1) 响应系统内存通知（onTrimMemory/onLowMemory），按级别主动释放缓存，降低被系统管控影响；
 * 2) 检测应用是否因系统内存限制（MemoryLimiter / 低内存）被杀，供界面提示；
 * 3) 提供当前内存占用（PSS）查询，便于对照内存区间自查。
 */
object FairMemory {

    /** 系统内存通知分级处理：级别越高释放越激进 */
    fun onTrimMemory(context: Context, level: Int) {
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ->
                // 后台（BACKGROUND/MODERATE/COMPLETE）：应用不可见，激进释放
                releaseCaches(context, aggressive = true)
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ->
                releaseCaches(context, aggressive = true)
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ->
                releaseCaches(context, aggressive = false)
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE ->
                releaseCaches(context, aggressive = false)
            level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ->
                // 90j 轮：离开前台即激进释放（含 GC）——切后台立刻降 RES/SWAP，
                // 不再等系统内存压力才回收
                releaseCaches(context, aggressive = true)
        }
    }

    /** 极端内存紧张 */
    fun onLowMemory(context: Context) {
        releaseCaches(context, aggressive = true)
    }

    /**
     * 主动释放缓存：
     * - 模块 WebUI 同步副本（cacheDir/webui_*，重新打开 WebUI 时自动重新同步，可安全删除）
     * - 模块安装临时目录（mod_install_*）
     * - aggressive 时触发 GC
     *
     * 90j 修复：**移除 `WebView(context).clearCache(true)`**——每次激进释放都 new 一个
     * WebView，会把 Chromium 内核/渲染器加载进进程（数十 MB 匿名内存，SWAP 暴涨）；
     * WebView 缓存是磁盘文件，与内存无关，清它不值 Chromium 常驻的代价。
     * 释放 WebView 内存的正确方式是销毁实例（BrowserActivity AndroidView onRelease）。
     */
    fun releaseCaches(context: Context, aggressive: Boolean) {
        runCatching {
            val cache = context.cacheDir
            cache.listFiles { f -> f.name.startsWith("webui_") }?.forEach { it.deleteRecursively() }
            cache.listFiles { f -> f.name.startsWith("mod_install_") }?.forEach { it.deleteRecursively() }
        }
        if (aggressive) {
            runCatching { System.gc() }
        }
    }

    /**
     * 最近 10 分钟内应用是否因系统内存限制被杀。
     * Android 17（API 37）公平运存：ApplicationExitInfo.description 含 "MemoryLimiter"；
     * 旧系统：REASON_LOW_MEMORY。
     */
    fun lastExitWasMemoryKill(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        return runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val reasons = am.getHistoricalProcessExitReasons(context.packageName, 0, 5)
            val cutoff = System.currentTimeMillis() - 10 * 60 * 1000L
            reasons.any { r ->
                r.timestamp >= cutoff && (
                    r.reason == ApplicationExitInfo.REASON_LOW_MEMORY ||
                        r.description?.contains("MemoryLimiter") == true
                    )
            }
        }.getOrDefault(false)
    }

    /** 当前应用内存占用 PSS（MB） */
    fun currentPssMB(): Long {
        return runCatching {
            val mi = Debug.MemoryInfo()
            Debug.getMemoryInfo(mi)
            mi.totalPss / 1024L
        }.getOrDefault(0L)
    }
}
