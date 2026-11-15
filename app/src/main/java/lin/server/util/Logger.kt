package lin.server.util

import android.os.Bundle
import android.os.IBinder
import android.util.Log
import rikka.hidden.compat.ActivityManagerApis
import lin.server.api.IContentProviderUtils
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue

data class LogEntry(
    val timestamp: Long,
    val level: Int,
    val tag: String?,
    val message: String
) {
    fun getLevelName(): String = when (level) {
        Log.VERBOSE -> "V"
        Log.DEBUG -> "D"
        Log.INFO -> "I"
        Log.WARN -> "W"
        Log.ERROR -> "E"
        else -> "?"
    }

    fun format(): String {
        val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault())
        return "${dateFormat.format(Date(timestamp))} ${getLevelName()}/$tag: $message"
    }
}

class Logger(private val tag: String?) {

    companion object {
        private const val MAX_LOG_ENTRIES = 500
        private const val MAX_MESSAGE_LENGTH = 512
        private const val PROVIDER = "moe.shizuku.privileged.api.lin"
        // 有界环形缓冲：写多读少的日志场景，ArrayDeque 避免 CopyOnWriteArrayList 每次写复制整个数组
        private val logBuffer = ArrayDeque<LogEntry>()
        // 攒批队列：日志高频时避免每条日志一次跨进程 Binder 事务（服务端 CPU 热点之一）
        private val pendingBatch = LinkedBlockingQueue<String>()
        private val flushThread = Thread({ flushLoop() }, "stellar-log-flush").apply {
            isDaemon = true
            start()
        }
        private var providerReady = false

        /** 单线程批量回传：每 150ms 把攒下的日志一次性通过 provider 传给 App 进程。 */
        private fun flushLoop() {
            while (true) {
                try {
                    Thread.sleep(150)
                    val batch = ArrayList<String>(64)
                    pendingBatch.drainTo(batch, 256)
                    if (batch.isNotEmpty()) {
                        callProviderBatch(batch)
                    }
                } catch (t: Throwable) {
                    // 守护线程：异常吞掉避免退出循环
                }
            }
        }

        fun initProvider() {
            providerReady = true
        }

        private fun callProviderBatch(lines: List<String>): Bundle? {
            val token: IBinder? = null
            var provider: android.content.IContentProvider? = null
            return try {
                provider = ActivityManagerApis.getContentProviderExternal(PROVIDER, 0, token, PROVIDER)
                    ?: return null
                val extras = Bundle().apply {
                    putStringArray("logs", lines.toTypedArray())
                }
                IContentProviderUtils.callCompat(provider, null, PROVIDER, "saveLogs", null, extras)
            } catch (_: Throwable) {
                null
            } finally {
                if (provider != null) {
                    try { ActivityManagerApis.removeContentProviderExternal(PROVIDER, token) } catch (_: Throwable) {}
                }
            }
        }

        @JvmStatic
        fun getLogsSince(sinceTimestamp: Long): List<String> =
            synchronized(logBuffer) {
                logBuffer.filter { it.timestamp >= sinceTimestamp }.map { it.format() }
            }

        @JvmStatic
        fun getLogs(): List<LogEntry> = synchronized(logBuffer) { logBuffer.toList() }

        @JvmStatic
        fun getLogsFormatted(): List<String> = synchronized(logBuffer) { logBuffer.map { it.format() } }

        @JvmStatic
        fun clearLogs() {
            synchronized(logBuffer) { logBuffer.clear() }
            pendingBatch.clear()
            if (!providerReady) return
            try {
                val token: IBinder? = null
                var provider: android.content.IContentProvider? = null
                try {
                    provider = ActivityManagerApis.getContentProviderExternal(PROVIDER, 0, token, PROVIDER)
                        ?: return
                    IContentProviderUtils.callCompat(provider, null, PROVIDER, "clearLogs", null, Bundle())
                } finally {
                    if (provider != null) {
                        try { ActivityManagerApis.removeContentProviderExternal(PROVIDER, token) } catch (_: Throwable) {}
                    }
                }
            } catch (_: Throwable) {}
        }

        @JvmStatic
        internal fun addLog(level: Int, tag: String?, message: String) {
            // 超长消息截断：防止超长日志（如大块输出）撑高服务端常驻内存
            val msg = if (message.length > MAX_MESSAGE_LENGTH) {
                message.substring(0, MAX_MESSAGE_LENGTH) + "…"
            } else message
            val entry = LogEntry(System.currentTimeMillis(), level, tag, msg)
            synchronized(logBuffer) {
                logBuffer.addLast(entry)
                while (logBuffer.size > MAX_LOG_ENTRIES) logBuffer.removeFirst()
            }
            // v5（MT 授权超时修复）：关键日志（I/W/E）同步落盘到
            // /data/local/tmp/Lin-Shizuku/Lin/log/Lin_server_runtime.log，
            // 授权链路（权限请求/确认/通知客户端）均为 I 级，adb 直接 cat 即可
            // 定位卡点，不依赖管理器 UI 的日志导出。
            if (level >= Log.INFO) appendToRuntimeLog(entry.format())
            // 批量回传（不落盘本地文件，交由管理器侧诊断日志统一落盘）
            if (providerReady) pendingBatch.offer(entry.format())
        }

        /**
         * 确保日志文件对当前进程可写。旧实例（如以 root 启动）创建的文件可能是
         * root:root 644，当前 shell(2000) 实例只有 other 只读权限，append 会抛 EACCES。
         * 日志目录可写时直接 unlink 旧文件并以当前进程身份重建；删不掉则尝试放开写权限。
         * 返回 null 表示确实无法写入，调用方降级到 logcat 告警，不再静默吞掉。
         */
        private fun ensureWritableLogFile(f: java.io.File): java.io.File? {
            f.parentFile?.mkdirs()
            if (!f.exists() || f.canWrite()) return f
            if (f.delete()) return f
            runCatching { f.setWritable(true, false) }
            return if (f.canWrite()) f else null
        }

        /** 追加服务端运行日志到文件（200KB 内循环截断，不可写时降级到 logcat 告警）。 */
        private fun appendToRuntimeLog(line: String) {
            try {
                val f = ensureWritableLogFile(
                    java.io.File("/data/local/tmp/Lin-Shizuku/Lin/log/Lin_server_runtime.log")
                ) ?: run {
                    Log.e("Logger", "runtime 日志文件不可写: $line")
                    return
                }
                if (f.exists() && f.length() > 200 * 1024) {
                    f.delete()
                }
                f.appendText(line + "\n")
            } catch (t: Throwable) {
                Log.e("Logger", "runtime 日志写入失败: ${t.message}")
            }
        }

        /** 追加启动日志到 launch.log（每次启动覆盖写入，只保留本次启动内容）。 */
        @JvmStatic
        fun appendToLaunchLog(line: String) {
            try {
                val f = ensureWritableLogFile(
                    java.io.File("/data/local/tmp/Lin-Shizuku/Lin/log/Lin_server_launch.log")
                ) ?: run {
                    Log.e("Logger", "launch 日志文件不可写: $line")
                    return
                }
                val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault())
                f.appendText("${dateFormat.format(Date())} $line\n")
            } catch (t: Throwable) {
                Log.e("Logger", "launch 日志写入失败: ${t.message}")
            }
        }

        /** 追加 UserService stderr 日志（100KB 内循环截断）。 */
        @JvmStatic
        fun appendToUserServiceStderr(line: String) {
            try {
                val f = ensureWritableLogFile(
                    java.io.File("/data/local/tmp/Lin-Shizuku/Lin/log/Lin_user_service_stderr.log")
                ) ?: run {
                    Log.e("Logger", "user_service stderr 日志文件不可写: $line")
                    return
                }
                if (f.exists() && f.length() > 100 * 1024) {
                    f.delete()
                }
                val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault())
                f.appendText("${dateFormat.format(Date())} $line\n")
            } catch (t: Throwable) {
                Log.e("Logger", "user_service stderr 日志写入失败: ${t.message}")
            }
        }
    }

    fun v(msg: String) = println(Log.VERBOSE, msg)
    fun v(fmt: String, vararg args: Any?) = println(Log.VERBOSE, String.format(Locale.ENGLISH, fmt, *args))
    fun v(msg: String?, tr: Throwable?) = println(Log.VERBOSE, msg + '\n' + Log.getStackTraceString(tr))

    fun d(msg: String) = println(Log.DEBUG, msg)
    fun d(fmt: String, vararg args: Any?) = println(Log.DEBUG, String.format(Locale.ENGLISH, fmt, *args))
    fun d(msg: String?, tr: Throwable?) = println(Log.DEBUG, msg + '\n' + Log.getStackTraceString(tr))

    fun i(msg: String) = println(Log.INFO, msg)
    fun i(fmt: String, vararg args: Any?) = println(Log.INFO, String.format(Locale.ENGLISH, fmt, *args))
    fun i(msg: String?, tr: Throwable?) = println(Log.INFO, msg + '\n' + Log.getStackTraceString(tr))

    fun w(msg: String) = println(Log.WARN, msg)
    fun w(fmt: String, vararg args: Any?) = println(Log.WARN, String.format(Locale.ENGLISH, fmt, *args))
    fun w(tr: Throwable?, fmt: String, vararg args: Any?) = println(Log.WARN, String.format(Locale.ENGLISH, fmt, *args) + '\n' + Log.getStackTraceString(tr))
    fun w(msg: String?, tr: Throwable?) = println(Log.WARN, msg + '\n' + Log.getStackTraceString(tr))

    fun e(msg: String) = println(Log.ERROR, msg)
    fun e(fmt: String, vararg args: Any?) = println(Log.ERROR, String.format(Locale.ENGLISH, fmt, *args))
    fun e(msg: String?, tr: Throwable?) = println(Log.ERROR, msg + '\n' + Log.getStackTraceString(tr))
    fun e(tr: Throwable?, fmt: String, vararg args: Any?) = println(Log.ERROR, String.format(Locale.ENGLISH, fmt, *args) + '\n' + Log.getStackTraceString(tr))

    fun println(priority: Int, msg: String): Int {
        addLog(priority, tag, msg)
        return Log.println(priority, tag, msg)
    }
}
