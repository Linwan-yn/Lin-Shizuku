package moe.shizuku.privileged.api.core

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 模块安装 / 执行日志基建（66 轮新增）。
 *
 * 目标（对齐 Magisk/KernelSU 模块管理器的体验）：安装与执行**无论成败都有输出与日志**，
 * 失败时可精准定位报错原因。日志三处落盘：
 *  1. 应用私有目录 filesDir/logs/（权威存储，任何情况可写）：
 *     - install_<ts>.log        每次安装一份（含逐步进度与最终错误）
 *     - exec_<id>_<ts>.log      每次手动执行一份（含时间戳输出与退出码）
 *     - auto_<id>.log           引擎/自动触发执行的滚动日志（追加，封顶 N 行）
 *  2. 公共目录 /storage/emulated/0/Lin-Shizuku/logs/（有「所有文件访问」权限时）
 *     ——用户可用文件管理器直接打开查看/转发。
 *  3. 统一诊断日志 日志.log（DiagnosticLog，概要 + 失败时的关键错误行）。
 */
object ModuleLogs {

    private const val MAX_FILES = 50          // filesDir/logs 保留文件上限
    private const val MAX_AUTO_LINES = 300    // 滚动日志行数上限

    /** 完整时间戳：日志行头（yyyy-MM-dd HH:mm:ss）。 */
    fun tsFull(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

    /** 短时间戳：执行弹窗每行前缀（HH:mm:ss）。 */
    fun tsShort(): String =
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

    /** 组装一行日志：[yyyy-MM-dd HH:mm:ss] 前缀 内容。prefix 常用「-」「!」「✓」「──」。 */
    fun line(prefix: String, text: String, time: String = tsFull()): String =
        "[$time] $prefix $text"

    /** 统一日志目录（外部 cache/Logs，用户可通过文件管理器直接访问）。 */
    fun logDir(ctx: Context): File =
        File(ctx.externalCacheDir ?: ctx.cacheDir, "Logs").apply { mkdirs() }

    /** 公共日志目录（用户可直接访问）。 */
    fun publicLogDir(): File = File("/storage/emulated/0/Lin-Shizuku/logs")

    /** 公共目录当前是否可写（与 DiagnosticLog 同策略：无「所有文件访问」权限时降级）。 */
    fun canWritePublic(): Boolean = try {
        val f = publicLogDir()
        if (f.exists()) f.canWrite()
        else {
            val p = f.parentFile
            p != null && (p.exists() || p.mkdirs()) && f.canWrite()
        }
    } catch (_: Throwable) { false }

    /**
     * 保存一份日志文件（app 私有目录）。自动清理最旧文件，防止无限增长。
     * 返回写入的文件。
     */
    fun save(ctx: Context, fileName: String, content: String): File {
        val safe = sanitizeFileName(fileName)
        val dir = logDir(ctx)
        val f = File(dir, safe)
        try {
            dir.mkdirs()
            f.writeText(content)
            cleanup(ctx)
        } catch (_: Throwable) {}
        return f
    }

    /**
     * 追加到滚动日志（引擎/自动触发用），封顶 MAX_AUTO_LINES 行。
     */
    fun appendAuto(ctx: Context, id: String, block: String) {
        val safe = sanitizeFileName(id)
        val f = File(logDir(ctx), "auto_$safe.log")
        try {
            val old = if (f.exists()) f.readText() else ""
            val merged = (old + "\n" + block).trimStart('\n')
            val lines = merged.lines()
            val capped = if (lines.size > MAX_AUTO_LINES) lines.takeLast(MAX_AUTO_LINES) else lines
            f.writeText(capped.joinToString("\n") + "\n")
        } catch (_: Throwable) {}
    }

    /**
     * 保存到用户可见的公共目录（/storage/emulated/0/Lin-Shizuku/logs/）。
     * 有「所有文件访问」权限时直接写公共目录；无权限时降级写 app 私有目录。
     * 返回实际保存路径（供 UI 提示）。
     */
    fun saveToStorage(ctx: Context, fileName: String, content: String): String {
        val safe = sanitizeFileName(fileName)
        return if (canWritePublic()) {
            try {
                val dir = publicLogDir()
                dir.mkdirs()
                val f = File(dir, safe)
                f.writeText(content)
                f.absolutePath
            } catch (_: Throwable) {
                save(ctx, safe, content).absolutePath
            }
        } else {
            save(ctx, safe, content).absolutePath
        }
    }

    /** 日志文件元信息（列表用）。 */
    data class LogMeta(val name: String, val time: Long, val size: Long)

    /** 列出所有已保存的日志文件，按时间倒序。 */
    fun list(ctx: Context): List<LogMeta> {
        val dir = logDir(ctx)
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() }
            ?.map { LogMeta(it.name, it.lastModified(), it.length()) } ?: emptyList()
    }

    /** 读取指定日志文件内容。 */
    fun read(ctx: Context, name: String): String {
        val f = File(logDir(ctx), sanitizeFileName(name))
        return try {
            if (f.exists()) f.readText() else "（日志文件不存在）"
        } catch (_: Throwable) { "（读取日志失败）" }
    }

    /** 删除指定日志文件。 */
    fun delete(ctx: Context, name: String): Boolean =
        try { File(logDir(ctx), sanitizeFileName(name)).delete() } catch (_: Throwable) { false }

    /** 清理超过上限的最旧文件。 */
    private fun cleanup(ctx: Context) {
        try {
            val files = logDir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".log") }
                ?.sortedByDescending { it.lastModified() } ?: return
            if (files.size > MAX_FILES) {
                files.drop(MAX_FILES).forEach { it.delete() }
            }
        } catch (_: Throwable) {}
    }

    /** 文件名清洗：仅保留字母数字 ._-，其余转下划线（防路径穿越）。 */
    private fun sanitizeFileName(name: String): String {
        val safe = name.replace(Regex("[^A-Za-z0-9._\\-]"), "_")
        return if (safe.isBlank()) "log_${System.currentTimeMillis()}" else safe
    }

    /** 时间戳文件名（含秒），避免同一秒多次执行互相覆盖。 */
    fun fileStamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault()).format(Date())
}
