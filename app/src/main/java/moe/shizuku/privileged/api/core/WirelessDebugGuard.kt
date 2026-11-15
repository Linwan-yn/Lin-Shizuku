package moe.shizuku.privileged.api.core

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 1.1.1：无线调试关闭守护（「无线调试关闭服务依旧运行」修复）。
 *
 * 现象（用户上报）：服务通过无线调试（adb over WiFi，shell uid）启动后，
 * 在系统开发者选项关闭「无线调试」（adb_wifi_enabled 1→0），服务进程随之
 * 消失、应用首页显示未运行。机制：部分 ROM 关闭无线调试时按 adb 会话/shell
 * uid 清理由 adb 派生的后台进程（setsid 已脱离会话，但仍可能被按 uid 清理）。
 *
 * 修复策略（分层）：
 *  1. 启动脚本/兜底命令统一 setsid（见 Starter / res/raw/start.sh），服务进程
 *     脱离 adb shell 会话——大多数 ROM 下系统清理不波及；
 *  2. 本守护监听 adb_wifi_enabled 由 1→0：延时复查服务存活；若进程确已被杀
 *     且设备已 root，立即用 Root 身份重新拉起（Root uid 进程不受无线调试状态
 *     影响），实现「无线调试关闭服务依旧运行」；
 *  3. 无 Root 时无法自动恢复（系统权限限制），记录诊断日志说明原因。
 *
 * 防重复：Root 重拉带 30s 冷却（与 selfHealServerIfNeeded 节流对齐）。
 */
object WirelessDebugGuard {
    private const val TAG = "WirelessDebugGuard"
    private const val RECOVER_COOLDOWN_MS = 30_000L

    @Volatile private var registered = false
    @Volatile private var appContext: Context? = null
    @Volatile private var lastRecoverAt = 0L

    /** 注册 adb_wifi_enabled 内容监听（进程存活期间有效）。 */
    fun register(ctx: Context) {
        if (registered) return
        registered = true
        appContext = ctx.applicationContext
        try {
            ctx.contentResolver.registerContentObserver(
                Settings.Global.getUriFor("adb_wifi_enabled"),
                false,
                observer
            )
            Log.d(TAG, "已监听 adb_wifi_enabled 变化（无线调试关闭守护就绪）")
        } catch (e: Throwable) {
            Log.w(TAG, "注册 adb_wifi_enabled 监听失败", e)
        }
    }

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            super.onChange(selfChange)
            val ctx = appContext ?: return
            // 只关心「无线调试被关闭」（1→0）；开启/无变化不处理
            val enabled = runCatching {
                Settings.Global.getInt(ctx.contentResolver, "adb_wifi_enabled", 0) == 1
            }.getOrDefault(false)
            if (enabled) return
            Log.d(TAG, "检测到无线调试已关闭，延时复查服务存活...")
            GlobalScope.launch(Dispatchers.IO) {
                // 等系统清理动作完成（adbd 停止/清理异步，通常数秒内）
                delay(5_000L)
                // 服务仍存活 → setsid 已生效、系统未清理，无需干预
                if (ShizukuManager.isLinServerRunning()) {
                    Log.d(TAG, "无线调试已关闭，服务仍在运行（setsid 已脱离会话），无需拉起")
                    return@launch
                }
                Log.w(TAG, "无线调试关闭后服务进程已消失，尝试恢复...")
                val now = System.currentTimeMillis()
                if (now - lastRecoverAt < RECOVER_COOLDOWN_MS) {
                    Log.d(TAG, "30s 冷却期内，跳过重复拉起")
                    return@launch
                }
                if (!ShizukuManager.isRooted()) {
                    Log.w(TAG, "设备无 Root，无法自动恢复（系统按 shell 会话清理 adb 派生进程）")
                    DiagnosticLog.append("[无线调试守护] 无线调试已关闭且服务停止，设备无 Root，无法自动恢复")
                    return@launch
                }
                lastRecoverAt = now
                val ok = runCatching {
                    val cmd = moe.shizuku.privileged.api.util.Starter.getSdcardCommand(ctx)
                    val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
                    val out = proc.inputStream.bufferedReader().readText()
                    val err = proc.errorStream.bufferedReader().readText()
                    proc.waitFor()
                    (out + err)
                }.getOrDefault("")
                val started = ok.contains("启动成功") || ok.contains("RUNNING-OK")
                if (started) {
                    Log.i(TAG, "已用 Root 身份重新拉起服务（Root uid 不受无线调试状态影响）")
                    DiagnosticLog.append("[无线调试守护] 无线调试已关闭、服务被杀，已用 Root 重新拉起，服务保持运行")
                } else {
                    Log.w(TAG, "Root 重新拉起失败: ${ok.take(200)}")
                    DiagnosticLog.append("[无线调试守护] Root 重新拉起失败: ${ok.take(200)}")
                }
            }
        }
    }
}
