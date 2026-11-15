package moe.shizuku.privileged.api.core

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 统一诊断日志：固定写入应用专属外部目录 `<externalFilesDir>/日志.log`
 * （即 /storage/emulated/0/Android/data/<包名>/files/日志.log），由 App 自己写、自己在日志总览读取。
 *
 * 设计约定：
 *  - 不依赖「所有文件访问」权限，也不再写公共存储根目录 /storage/emulated/0/日志.log；
 *  - 专属目录 App 是 owner，任意可写，无需额外授权；
 *  - [fallbackDir] 必须在 Application.onCreate 调用 [setFallbackDir] 初始化，
 *    覆盖主进程、:guard 守护进程、开机自启等所有上下文，避免日志在非主界面进程被丢弃。
 *
 * 性能：热路径减载——SimpleDateFormat 复用单例（临界区内使用，线程安全）；
 * 超限检查由 bufferedReader 计数，行数超限才重写文件。
 */
object DiagnosticLog {

    private const val FILE_NAME = "日志.log"
    private const val MAX_LINES = 3000
    private val lock = Any()
    private var fallbackDir: File? = null

    /** 格式化器单例；仅在 [lock] 临界区内使用，线程安全。 */
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    fun setFallbackDir(dir: File) {
        fallbackDir = dir
    }

    /** 当前诊断日志文件（fallbackDir 未初始化时返回 null，调用方直接丢弃该行）。 */
    fun currentFile(): File? = fallbackDir?.let { File(it, FILE_NAME) }

    /**
     * 追加一行（带时间戳）。写入应用专属目录；目录未初始化或写入失败时静默，不影响主流程。
     */
    fun append(line: String) {
        val time = timeFormat.format(Date())
        synchronized(lock) {
            try {
                val target = currentFile() ?: return
                target.parentFile?.mkdirs()
                // 只计数行数（避免 readLines 全量分配内存）；超限才重写截断
                if (target.exists() && target.length() > 0) {
                    var lineCount = 0
                    target.bufferedReader().use { r ->
                        while (r.readLine() != null) lineCount++
                    }
                    if (lineCount > MAX_LINES) {
                        target.writeText(target.readLines().takeLast(MAX_LINES).joinToString("\n") + "\n")
                    }
                }
                target.appendText("$time $line\n")
            } catch (_: Throwable) { /* 日志写入失败不影响功能 */ }
        }
    }
}
