package moe.shizuku.privileged.api.core

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import moe.shizuku.privileged.api.util.AdbWirelessHelper

/**
 * A4（90n 实现）：双进程互守守护服务（独立进程 :guard）。
 *
 * 开启「进程守护」后：
 *  1. 服务端守护：Lin-Shizuku / Shizuku 兼容服务端未运行时自动重启
 *     （优先 Root 启动；Root 不可用且开启 TCP 模式时走 127.0.0.1:端口 无线通道）；
 *  2. 主进程守护：主应用进程被系统回收后自动拉起 MainActivity。
 *
 * 防风暴：重启带 30s 冷却；连续失败会拉长间隔（最多 5 分钟）；
 * 关闭开关或 stop 后立即停止轮询并进入终止态。
 */
class GuardService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        Log.i(TAG, "GuardService created (进程 :guard)")
        DiagnosticLog.append("[进程守护] :guard 服务已创建")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running) {
            running = true
            Log.i(TAG, "GuardService started")
            DiagnosticLog.append("[进程守护] 守护轮询已启动（5s 间隔，连续失败退避至 5min）")
            job = scope.launch {
                var failStreak = 0
                while (isActive && running) {
                    try { tick(failStreak) } catch (e: Throwable) { Log.w(TAG, "guard tick 异常", e) }
                    // 失败连续时拉长间隔，最多 5 分钟
                    val interval = if (failStreak >= 5) 300_000L else 5_000L
                    delay(interval)
                    if (ShizukuManager.isRunningCompat()) failStreak = 0 else failStreak++
                }
            }
        }
        return START_STICKY
    }

    private suspend fun tick(failStreak: Int) {
        // 1. 服务端未运行 → 重启（带冷却）
        if (!ShizukuManager.isRunningCompat()) {
            val now = System.currentTimeMillis()
            if (now - lastRestartAt >= RESTART_COOLDOWN_MS) {
                lastRestartAt = now
                DiagnosticLog.append("[进程守护] 检测到服务端未运行，尝试恢复（连续失败 ${failStreak + 1} 次）")
                val ok = tryRestartServer()
                Log.i(TAG, if (ok) "守护重启服务端成功" else "守护重启服务端失败（连续 $failStreak 次）")
                DiagnosticLog.append(if (ok) "[进程守护] 恢复服务端成功" else "[进程守护] 恢复服务端失败（连续 ${failStreak + 1} 次，退避等待）")
            } else {
                Log.d(TAG, "服务端未运行，但处于 30s 恢复冷却期，跳过")
            }
        }
        // 2. 主进程被系统回收 → 拉起 MainActivity
        if (!isMainProcessAlive()) {
            Log.i(TAG, "主进程不在，拉起 MainActivity")
            DiagnosticLog.append("[进程守护] 主进程不在，拉起 MainActivity")
            runCatching {
                val intent = Intent(this, moe.shizuku.privileged.api.MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                startActivity(intent)
            }
        }
    }

    /** 重启服务端：Root 优先（start.sh 按进程名精确清理，绝不误杀 :guard），
     *  仅 Root 不可用时才走 TCP/无线调试通道（liblin.so native starter 的 killing old
     *  process 存在连坐杀同 uid 进程的风险——1.1.2 起 Root 优先策略防自杀）。 */
    private suspend fun tryRestartServer(): Boolean {
        // Root 路径（含 A3 降权逻辑，由 startService 内部处理）
        if (ShizukuManager.isRooted()) {
            DiagnosticLog.append("[进程守护] 恢复通道：Root 启动（start.sh 精确清理，不误伤 :guard）")
            val result = ShizukuManager.startService(this)
            Log.d(TAG, "guard Root 重启: ${result.take(120)}")
            if (ShizukuManager.isRunningCompat()) return true
            DiagnosticLog.append("[进程守护] Root 恢复未就绪：${result.take(80)}")
        } else {
            DiagnosticLog.append("[进程守护] 设备无 Root，Root 通道跳过")
        }
        // TCP 固定端口通道（127.0.0.1 无需 WiFi）——仅在 Root 不可用/失败后兜底
        if (Prefs.tcpMode) {
            DiagnosticLog.append("[进程守护] 恢复通道：TCP 无线调试（127.0.0.1:${Prefs.tcpPort}）")
            try {
                val helper = AdbWirelessHelper()
                val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
                helper.startShizukuViaAdb(
                    ctx = this,
                    host = "127.0.0.1",
                    port = Prefs.tcpPort,
                    coroutineScope = scope,
                    onOutput = { Log.d(TAG, "guard TCP 恢复: ${it.trim()}") },
                    onError = { Log.w(TAG, "guard TCP 恢复失败", it) },
                    onSuccess = { deferred.complete(true) },
                    tcpMode = false,
                    useSu = Prefs.wirelessSu
                )
                kotlinx.coroutines.withTimeoutOrNull(20_000L) { deferred.await() }
                kotlinx.coroutines.delay(2_000L)
                return ShizukuManager.isRunningCompat()
            } catch (e: Throwable) {
                Log.w(TAG, "guard TCP 恢复异常", e)
                DiagnosticLog.append("[进程守护] TCP 恢复异常: ${e.message?.take(80)}")
            }
        } else {
            DiagnosticLog.append("[进程守护] TCP 模式未开启，无兜底通道")
        }
        return false
    }

    /** 主应用进程存活判断（本应用进程；getRunningAppProcesses 对第三方仅返回本应用）。 */
    private fun isMainProcessAlive(): Boolean {
        return try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val myPid = android.os.Process.myPid()
            am.runningAppProcesses?.any { it.pid == myPid } != false
        } catch (_: Throwable) {
            true // 无法判断时视为存活，避免误拉起
        }
    }

    private fun buildNotification(): Notification {
        val channelId = "guard"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(channelId, "进程守护", NotificationManager.IMPORTANCE_MIN)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        return Notification.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("Lin-Shizuku 进程守护")
            .setContentText("守护服务端与主进程，崩溃自动恢复")
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        running = false
        job?.cancel()
        job = null
        Log.i(TAG, "GuardService stopped")
        DiagnosticLog.append("[进程守护] :guard 服务已停止（onDestroy）——若为异常消失，进程级兜底将在下次进程创建时重新拉起")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "GuardService"
        private const val NOTIFICATION_ID = 0x91
        private const val RESTART_COOLDOWN_MS = 30_000L
        @Volatile private var lastRestartAt = 0L

        fun start(context: Context) {
            runCatching {
                val intent = Intent(context, GuardService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, GuardService::class.java))
            }
        }
    }
}
