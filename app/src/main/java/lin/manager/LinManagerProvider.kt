package lin.manager

import android.content.Context
import android.os.Bundle
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import lin.LinProvider
import lin.server.LinConfig
import moe.shizuku.privileged.api.core.DiagnosticLog
import moe.shizuku.privileged.api.core.Prefs
import moe.shizuku.privileged.api.core.ShizukuManager

/**
 * Lin 生态 provider（authority = ${applicationId}.lin）。
 *
 * Lin server（app_process 中运行）通过它：
 *  - 发送自身 Binder 给 manager（基类 LinProvider 处理 sendBinder/getBinder/sendUserService，
 *    收到的 Binder 同时桥接给官方 rikka.shizuku.Shizuku 单例，本 App 既有 UI 立即生效）；
 *  - 读取/保存服务配置（loadConfig/saveConfig，持久化到 SharedPreferences）；
 *  - 上传/读取日志（saveLog/getLogs，内存保留最近 500 条）。
 *
 * 照抄官方 LinManagerProvider 的协议，去掉 Room 依赖（配置/日志落 SharedPreferences）。
 */
class LinManagerProvider : LinProvider() {

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        when (method) {
            METHOD_GET_SHIZUKU_COMPAT -> {
                val value = prefs().getString(KEY_SHIZUKU_COMPAT, "true") ?: "true"
                return Bundle().apply { putBoolean(KEY_SHIZUKU_COMPAT, value.toBoolean()) }
            }
            METHOD_LOAD_CONFIG -> {
                val config = LinConfig()
                prefs().all.forEach { (k, v) ->
                    val s = v.toString()
                    when (k) {
                        KEY_SHIZUKU_COMPAT -> config.shizukuCompatEnabled = s.toBoolean()
                        KEY_ACCESSIBILITY_AUTO_START -> config.accessibilityAutoStart = s.toBoolean()
                        KEY_DAEMON_ENABLED -> config.daemonEnabled = s.toBoolean()
                        KEY_PACKAGES_JSON -> runCatching {
                            config.packages = GSON.fromJson(
                                s, object : com.google.gson.reflect.TypeToken<MutableMap<Int, LinConfig.PackageEntry>>() {}.type
                            ) ?: mutableMapOf()
                        }
                    }
                }
                return Bundle().apply { putString(KEY_CONFIG_JSON, GSON.toJson(config)) }
            }
            METHOD_SAVE_CONFIG -> {
                val json = extras?.getString(KEY_CONFIG_JSON) ?: return null
                val config = GSON.fromJson(json, LinConfig::class.java) ?: return null
                prefs().edit()
                    .putString(KEY_SHIZUKU_COMPAT, config.shizukuCompatEnabled.toString())
                    .putString(KEY_ACCESSIBILITY_AUTO_START, config.accessibilityAutoStart.toString())
                    .putString(KEY_DAEMON_ENABLED, config.daemonEnabled.toString())
                    .putString(KEY_PACKAGES_JSON, GSON.toJson(config.packages))
                    .apply()
                return Bundle()
            }
            METHOD_SAVE_LOG -> {
                val line = arg ?: return null
                synchronized(logBuckets) {
                    val idx = levelOf(line)
                    val b = logBuckets[if (idx in 0..4) idx else 0]
                    b.addLast(line)
                    while (b.size > BUCKET_CAP) b.removeFirst()
                }
                return Bundle()
            }
            METHOD_SAVE_LOGS_BATCH -> {
                // 服务端批量回传：一次 Binder 事务携带多条日志，显著降低高频日志下的跨进程开销
                val lines = extras?.getStringArray(KEY_LOGS) ?: return null
                synchronized(logBuckets) {
                    for (line in lines) {
                        val idx = levelOf(line)
                        val b = logBuckets[if (idx in 0..4) idx else 0]
                        b.addLast(line)
                        while (b.size > BUCKET_CAP) b.removeFirst()
                    }
                }
                return Bundle()
            }
            METHOD_GET_LOGS -> {
                val lines = synchronized(logBuckets) { mergeBuckets() }
                return Bundle().apply { putStringArray(KEY_LOGS, lines.toTypedArray()) }
            }
            METHOD_CLEAR_LOGS -> {
                synchronized(logBuckets) { logBuckets.forEach { it.clear() } }
                return Bundle()
            }
            METHOD_NOTIFY_SERVER_DIED -> {
                // V3-1（1.1.2 保活根治）：用户服务进程检测到「Lin 服务器已死亡」时经本 Provider
                // 上报（Provider 进程 = 主进程，被清后系统会随本次 call 自动重建），触发：
                //  1) 诊断日志记录死亡事件（含上报来源）；
                //  2) 立即自检恢复：开关开启且服务端未运行 → Root 启动（先启后停）。
                // 不依赖 :guard 进程存活，是服务端死亡后的第一道即时恢复链路。
                val from = arg ?: "?"
                DiagnosticLog.append("[服务端] 收到用户服务死亡上报（来源 $from）：Lin 服务器已死亡，立即自检恢复")
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    try {
                        if (Prefs.processGuard || Prefs.watchdog) {
                            if (!ShizukuManager.isRunningCompat()) {
                                if (ShizukuManager.isRooted()) {
                                    DiagnosticLog.append("[服务端] 死亡上报触发 Root 恢复...")
                                    val result = ShizukuManager.startService(context!!)
                                    DiagnosticLog.append("[服务端] 死亡上报恢复结果：${result.take(80)}")
                                } else {
                                    DiagnosticLog.append("[服务端] 设备无 Root，死亡上报无法直接恢复（等待 :guard/TCP 通道）")
                                }
                            }
                        }
                    } catch (e: Throwable) {
                        DiagnosticLog.append("[服务端] 死亡上报恢复异常: ${e.message?.take(80)}")
                    }
                }
                runCatching {
                    context!!.sendBroadcast(
                        android.content.Intent(ShizukuManager.ACTION_SERVICE_STATE_CHANGED)
                            .setPackage(context!!.packageName)
                    )
                }
                return Bundle()
            }
        }
        if (extras == null) return null
        return super.call(method, arg, extras)
    }

    private fun prefs(): android.content.SharedPreferences =
        context!!.getSharedPreferences("Lin_server", Context.MODE_PRIVATE)

    companion object {
        private val GSON = Gson()

        /**
         * 分级日志缓冲：V/D/I/W/E 每级别独立保留最近 BUCKET_CAP 条，
         * 高频琐碎日志（Verbose 刷屏）不会挤占 Info/Warn/Error 级日志。
         */
        private val logBuckets = Array(5) { java.util.ArrayDeque<String>() }
        private const val BUCKET_CAP = 200

        /** 解析日志行级别：行格式 `MM-dd HH:mm:ss.SSS L/Tag: message`，级别字符在 index 19 */
        private fun levelOf(line: String): Int =
            if (line.length > 19) when (line[19]) {
                'V' -> 0; 'D' -> 1; 'I' -> 2; 'W' -> 3; 'E' -> 4; else -> -1
            } else -1

        /** 5 路归并：各桶内按时间有序（行首时间戳等长可字典序比较），合并后整体时间序 */
        private fun mergeBuckets(): List<String> {
            val iters = Array(5) { logBuckets[it].iterator() }
            val current = arrayOfNulls<String>(5)
            var remaining = 0
            for (i in 0..4) {
                remaining += logBuckets[i].size
                if (iters[i].hasNext()) current[i] = iters[i].next()
            }
            val result = ArrayList<String>(remaining)
            while (remaining > 0) {
                var best = -1
                for (i in 0..4) {
                    val c = current[i] ?: continue
                    if (best == -1 || c < current[best]!!) best = i
                }
                result.add(current[best]!!)
                remaining--
                current[best] = if (iters[best].hasNext()) iters[best].next() else null
            }
            return result
        }

        /** App 同进程读取服务端回传日志（供"服务端日志"页面展示，按时间归并） */
        @JvmStatic
        fun snapshotLogs(): List<String> = synchronized(logBuckets) { mergeBuckets() }

        /** App 同进程清空服务端回传日志 */
        @JvmStatic
        fun clearSnapshotLogs() {
            synchronized(logBuckets) { logBuckets.forEach { it.clear() } }
        }

        const val METHOD_GET_SHIZUKU_COMPAT = "getShizukuCompat"
        const val KEY_SHIZUKU_COMPAT = "shizukuCompat"
        const val KEY_ACCESSIBILITY_AUTO_START = "accessibilityAutoStart"
        const val KEY_DAEMON_ENABLED = "daemonEnabled"
        const val METHOD_LOAD_CONFIG = "loadConfig"
        const val METHOD_SAVE_CONFIG = "saveConfig"
        const val KEY_CONFIG_JSON = "configJson"
        const val KEY_PACKAGES_JSON = "packagesJson"
        const val METHOD_SAVE_LOG = "saveLog"
        const val METHOD_SAVE_LOGS_BATCH = "saveLogs"
        const val METHOD_GET_LOGS = "getLogs"
        const val METHOD_CLEAR_LOGS = "clearLogs"
        const val METHOD_NOTIFY_SERVER_DIED = "notifyServerDied"
        const val KEY_LOGS = "logs"
    }
}
