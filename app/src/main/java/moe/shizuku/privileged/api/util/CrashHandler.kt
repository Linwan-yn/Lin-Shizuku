package moe.shizuku.privileged.api.util

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Catches uncaught exceptions and writes them to a crash log file
 * in the app's external files directory for debugging.
 */
object CrashHandler {

    private var context: Context? = null
    private var defaultHandler: Thread.UncaughtExceptionHandler? = null

    fun install(ctx: Context) {
        context = ctx.applicationContext
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrashLog(thread, throwable)
            } catch (_: Throwable) {}
            // Let the default handler also run (shows system crash dialog)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrashLog(thread: Thread, throwable: Throwable) {
        val ctx = context ?: return
        val dir = File(ctx.externalCacheDir ?: ctx.cacheDir, "Logs").apply { mkdirs() }
        val logFile = File(dir, "crash_log.txt")
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))

        val log = buildString {
            appendLine("=== Crash at $timestamp ===")
            appendLine("Thread: ${thread.name} (id=${thread.id})")
            appendLine("Throwable: ${throwable.javaClass.name}: ${throwable.message}")
            appendLine("Stack trace:")
            appendLine(sw.toString())
            appendLine("Cause chain:")
            var cause: Throwable? = throwable.cause
            while (cause != null) {
                appendLine("  Caused by: ${cause.javaClass.name}: ${cause.message}")
                cause = cause.cause
            }
            appendLine()
        }

        try {
            // Append to existing log (keep last 5 crashes)
            val existing = if (logFile.exists()) logFile.readText() else ""
            val crashes = existing.split("===").filter { it.isNotBlank() }
            val kept = crashes.takeLast(4).joinToString("===") { "===$it" }
            logFile.writeText(log + kept)
        } catch (_: Throwable) {}
    }
}
