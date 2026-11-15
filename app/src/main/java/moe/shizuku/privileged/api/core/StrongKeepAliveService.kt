package moe.shizuku.privileged.api.core

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.shizuku.privileged.api.util.AdbWirelessHelper

/**
 * 强力保活（76 轮）：在进程守护之上增加：
 * 1) 闹钟心跳兜底——AlarmManager 周期唤醒（setAndAllowWhileIdle，Doze 下也能执行），
 *    检测服务端是否存活，不存活则自动恢复（Root → TCP 127.0.0.1）；
 * 2) 息屏持续检测——闹钟在息屏/Doze 下仍触发，实现「息屏也持续守护」；
 * 3) 划掉任务后自动复活——onTaskRemoved 主动重新拉起，服务被划掉也能回到运行状态。
 * 耗电量会略微增加（周期闹钟唤醒）。
 */
class StrongKeepAliveService : Service() {

    companion object {
        private const val TAG = "StrongKeepAlive"
        private const val CHANNEL_ID = "strong_keep_alive"
        private const val NOTIFICATION_ID = 43

        /** 1.1.2：唤醒广播是否已注册（onDestroy 注销用）。 */
        @Volatile private var wakeRegistered = false

        /** 心跳周期：10 分钟一次（精确闹钟，Doze 下也能执行）。 */
        const val HEARTBEAT_MS = 10 * 60_000L

        const val ACTION_ALARM = "moe.shizuku.privileged.api.action.STRONG_KEEP_ALIVE_ALARM"

        fun start(ctx: Context) {
            try {
                val intent = Intent(ctx, StrongKeepAliveService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(intent)
                } else {
                    ctx.startService(intent)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "start failed", e)
            }
        }

        fun stop(ctx: Context) {
            try {
                ctx.stopService(Intent(ctx, StrongKeepAliveService::class.java))
                val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                am.cancel(alarmPendingIntent(ctx))
            } catch (_: Throwable) {}
        }

        fun scheduleAlarm(ctx: Context) {
            try {
                val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val pi = alarmPendingIntent(ctx)
                val triggerAt = System.currentTimeMillis() + HEARTBEAT_MS
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    // 1.1.2（T6 心跳升级）：Doze 下用精确闹钟（USE_EXACT_ALARM 声明即授），
                    // 避免 setAndAllowWhileIdle 在深度 Doze 被大幅推迟导致心跳中断；
                    // 无精确闹钟权限（极少 ROM 拒绝）时回退 setAndAllowWhileIdle。
                    try {
                        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                    } catch (_: SecurityException) {
                        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                    }
                } else {
                    am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "schedule failed", e)
            }
        }

        fun alarmPendingIntent(ctx: Context): PendingIntent {
            val intent = Intent(ctx, StrongKeepAliveReceiver::class.java)
                .setAction(ACTION_ALARM)
            return PendingIntent.getBroadcast(
                ctx, 0x57, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        scheduleAlarm(this)
        startAccessibilityHeartbeat()
        // 1.1.2（T6 补充唤醒源）：亮屏/充电广播触发即时探活——系统高压清进程往往发生在
        // 息屏待机期，亮屏/充电是最早的「系统压力解除」信号，此时立即自检恢复（比等
        // 闹钟心跳更早介入）。SCREEN_ON 无法静态注册，只能服务存活期动态监听。
        runCatching {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_POWER_CONNECTED)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(wakeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(wakeReceiver, filter)
            }
            wakeRegistered = true
        }
        Log.d(TAG, "强力保活已启动，心跳周期 ${HEARTBEAT_MS / 60000} 分钟 + 无障碍 15s 心跳 + 亮屏/充电即时探活")
        DiagnosticLog.append("[强力保活] 服务已启动：精确闹钟心跳 + 亮屏/充电即时探活")
    }

    /** 1.1.2：亮屏/充电即时探活接收器。 */
    private val wakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.d(TAG, "唤醒广播：${intent.action}，立即探活")
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    if (!Prefs.strongKeepAlive) return@launch
                    if (ShizukuManager.isRunningCompat()) return@launch
                    DiagnosticLog.append("[强力保活] ${intent.action} 触发探活：服务端不在，尝试恢复")
                    if (ShizukuManager.isRooted()) {
                        val result = ShizukuManager.startService(context)
                        DiagnosticLog.append("[强力保活] 唤醒恢复结果：${result.take(80)}")
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "唤醒探活异常", e)
                }
            }
        }
    }

    /**
     * 90ba：无障碍服务心跳——ColorOS 17 系统安全中心/省电策略会强制关闭无障碍
     * 服务。本应用已被服务端授予 WRITE_SECURE_SETTINGS（ManagerGrantHelper），
     * 这里每 15 秒检查 Settings.Secure 启用列表，发现本应用无障碍服务被移除则
     * 追加回写（追加模式，绝不覆盖其他无障碍服务），并确保 accessibility_enabled=1。
     * 受「无障碍自启（accessibilityBoot）+ 强力保活（strongKeepAlive）」开关控制。
     */
    private fun startAccessibilityHeartbeat() {
        CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                try {
                    // 90bb：保活开启即执行无障碍心跳，不再依赖 accessibilityBoot 开关——
                    // 「无障碍自启」只控制开机唤醒序列；保活心跳负责对抗系统强杀，二者独立。
                    if (Prefs.strongKeepAlive) {
                        ensureAccessibilityEnabled()
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "无障碍心跳异常", e)
                }
                delay(15_000L)
            }
        }
    }

    private fun ensureAccessibilityEnabled() {
        val serviceName = packageName + "/.core.LinAccessibilityService"
        val current = runCatching {
            Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        }.getOrNull() ?: ""
        val services = current.split(":")
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "null" }
            .toMutableSet()
        if (services.contains(serviceName)) return
        services.add(serviceName)
        runCatching {
            Settings.Secure.putString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                services.joinToString(":")
            )
            Settings.Secure.putString(contentResolver, "accessibility_enabled", "1")
            Log.i(TAG, "无障碍服务被系统关闭，已自动回写恢复（追加模式，保留其他服务）")
            // UI 联动：恢复成功 → 广播通知前台刷新状态（首页状态卡立即显示最新状态）
            runCatching {
                sendBroadcast(Intent(ShizukuManager.ACTION_SERVICE_STATE_CHANGED).setPackage(packageName))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 每次启动（含系统恢复、被划掉后重启）都确保闹钟心跳在排程
        scheduleAlarm(this)
        return START_STICKY
    }

    /** 划掉任务后：延迟 3 秒重新拉起（START_STICKY 之外再加主动复活，加快恢复）。 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "onTaskRemoved：任务被划掉，3 秒后主动复活")
        CoroutineScope(Dispatchers.IO).launch {
            delay(3_000L)
            try {
                if (!ShizukuManager.isRunningCompat()) {
                    start(this@StrongKeepAliveService)
                }
            } catch (_: Throwable) {}
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 1.1.2：注销唤醒广播（服务停止后不再探活）
        if (wakeRegistered) {
            runCatching { unregisterReceiver(wakeReceiver) }
            wakeRegistered = false
        }
        DiagnosticLog.append("[强力保活] 服务已停止（onDestroy）——开关关闭或主进程被杀")
        // 正常停止时取消闹钟（由 stop() 统一处理）
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "强力保活",
                NotificationManager.IMPORTANCE_MIN
            ).apply { description = "闹钟心跳守护 Shizuku 服务（息屏也持续检测）" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder
            .setContentTitle("Lin-Shizuku")
            .setContentText("强力保活运行中，息屏也持续守护服务")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .build()
    }
}

/**
 * 心跳广播：检查服务端是否存活，不存活则自动恢复。
 * 恢复顺序：Root 启动 → TCP 模式 127.0.0.1（无需 WiFi / 无线调试）。
 */
class StrongKeepAliveReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "StrongKeepAlive"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != StrongKeepAliveService.ACTION_ALARM) return
        try {
            Prefs.init(context)
        } catch (_: Throwable) {}
        if (!Prefs.strongKeepAlive) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (ShizukuManager.isRunningCompat()) {
                    Log.d(TAG, "心跳：服务正常")
                } else {
                    Log.w(TAG, "心跳：服务不在，尝试恢复")
                    DiagnosticLog.append("[强力保活] 心跳触发：服务端不在，尝试恢复")
                    recover(context, this)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "心跳检查异常", e)
            } finally {
                // 重新排程下一次心跳
                StrongKeepAliveService.scheduleAlarm(context)
                pendingResult.finish()
            }
        }
    }

    private suspend fun recover(context: Context, scope: CoroutineScope) {
        // 1. Root 启动（1.1.2：Root 优先——start.sh 按进程名精确清理，不误伤 :guard）
        if (ShizukuManager.isRooted()) {
            DiagnosticLog.append("[强力保活] 恢复通道：Root 启动")
            val result = ShizukuManager.startService(context)
            Log.d(TAG, "Root 恢复: ${result.take(100)}")
            if (ShizukuManager.isRunningCompat()) {
                DiagnosticLog.append("[强力保活] Root 恢复成功")
                return
            }
            DiagnosticLog.append("[强力保活] Root 恢复未就绪：${result.take(80)}")
        } else {
            DiagnosticLog.append("[强力保活] 设备无 Root，Root 通道跳过")
        }
        // 2. TCP 模式固定端口（127.0.0.1，无需 WiFi）——Root 失败后兜底
        if (Prefs.tcpMode) {
            DiagnosticLog.append("[强力保活] 恢复通道：TCP 无线调试（127.0.0.1:${Prefs.tcpPort}）")
            try {
                val helper = AdbWirelessHelper()
                val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
                helper.startShizukuViaAdb(
                    ctx = context,
                    host = "127.0.0.1",
                    port = Prefs.tcpPort,
                    coroutineScope = scope,
                    onOutput = { Log.d(TAG, "TCP 恢复: ${it.trim()}") },
                    onError = { Log.w(TAG, "TCP 恢复失败", it) },
                    onSuccess = { deferred.complete(true) },
                    tcpMode = false,
                    useSu = Prefs.wirelessSu
                )
                kotlinx.coroutines.withTimeoutOrNull(15_000L) { deferred.await() }
                if (ShizukuManager.isRunningCompat()) {
                    DiagnosticLog.append("[强力保活] TCP 恢复成功")
                    return
                }
            } catch (e: Throwable) {
                Log.w(TAG, "TCP 恢复异常", e)
                DiagnosticLog.append("[强力保活] TCP 恢复异常: ${e.message?.take(80)}")
            }
        }
        // 3. 上次成功的无线调试端口（1.1.2 修复）：此前只有 Root / TCP 两条通道，
        //    无 Root 且未开 TCP 的设备心跳恢复时直接落到「无兜底通道」——服务掉了
        //    就再也起不来（实测 12:57 / 13:16 / 13:33 连续失败）。无线调试走的正是
        //    配对页同一套 TLS 通道，配对密钥与端口都还在，可直接复用。
        val lastPort = runCatching {
            context.getSharedPreferences("boot_auto", Context.MODE_PRIVATE)
                .getInt("last_adb_port", 0)
        }.getOrDefault(0)
        if (lastPort in 1..65535) {
            DiagnosticLog.append("[强力保活] 恢复通道：无线调试（上次端口 $lastPort）")
            try {
                val helper = AdbWirelessHelper()
                val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
                helper.startShizukuViaAdb(
                    ctx = context,
                    host = "127.0.0.1",
                    port = lastPort,
                    coroutineScope = scope,
                    onOutput = { Log.d(TAG, "无线恢复: ${it.trim()}") },
                    onError = { Log.w(TAG, "无线恢复失败", it) },
                    onSuccess = { deferred.complete(true) },
                    tcpMode = Prefs.tcpMode,
                    tcpPort = Prefs.tcpPort,
                    useSu = Prefs.wirelessSu
                )
                kotlinx.coroutines.withTimeoutOrNull(20_000L) { deferred.await() }
                delay(2_000L)
                if (ShizukuManager.isRunningCompat()) {
                    DiagnosticLog.append("[强力保活] 无线调试恢复成功")
                    return
                }
                DiagnosticLog.append("[强力保活] 无线调试恢复未就绪（端口已失效，需重新配对启动）")
            } catch (e: Throwable) {
                Log.w(TAG, "无线恢复异常", e)
                DiagnosticLog.append("[强力保活] 无线恢复异常: ${e.message?.take(80)}")
            }
        } else {
            DiagnosticLog.append("[强力保活] 无线调试无历史端口记录，无兜底通道")
        }
    }
}
