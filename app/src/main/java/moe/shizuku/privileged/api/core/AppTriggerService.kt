package moe.shizuku.privileged.api.core

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 前台服务：监听应用打开，触发对应脚本。
 * 只有配置了 triggerPackage 的脚本才会启动此服务。
 */
class AppTriggerService : Service() {

    companion object {
        private const val TAG = "AppTriggerService"
        private const val CHANNEL_ID = "app_trigger"
        private const val NOTIFICATION_ID = 42

        /** 90h 轮：主动停止标志——用户关闭开关（stop）时置 true，onDestroy/onTaskRemoved 不再自愈重启。 */
        @Volatile
        private var stopping = false

        /** 目标应用离开前台不足该时长再次出现，视为「自触发的重进/快速闪回」，不触发脚本。 */
        private const val LEAVE_GAP_MS = 5_000L

        /** 同一脚本触发后的冷却期（1 分钟触发窗口）：短时间（1 分钟内）反复检测到目标应用启动，
         *  无论多少次都只执行一次（防死循环双保险，如激活类脚本导致应用重启闪回）。 */
        private const val SCRIPT_COOLDOWN_MS = 60_000L

        // ---- 76 轮：Scene/黑域自动激活 ----
        /** Scene/黑域自动激活：进入这些应用时弹「激活」通知，点击直接启动服务。
         *  黑域 Brevent；Scene6（com.omarea.vtools）；旧版 Scene5（com.lsdroid.ceres）。 */
        private val TARGET_PACKAGES = listOf(
            "me.piebridge.brevent",
            "com.omarea.vtools",
            "com.lsdroid.ceres"
        )

        /** 自动激活通知 ID。 */
        private const val ACTIVATE_NOTIFICATION_ID = 44

        const val ACTION_ACTIVATE = "moe.shizuku.privileged.api.action.AUTO_ACTIVATE"

        /** 激活通知携带的目标包名（81 轮：按进入的 Scene/黑域执行对应激活命令）。 */
        const val EXTRA_TARGET_PKG = "target_pkg"

        // ---- 81 轮：各目标应用的激活命令（用户提供，确保激活真正生效） ----
        // 黑阈：取 APK 路径作 CLASSPATH，app_process 启动 BreventServer bootstrap，再执行 brevent.sh
        private const val CMD_BREVENT =
            "output=\$(pm path me.piebridge.brevent); export CLASSPATH=\${output#*:}; " +
                "app_process /system/bin me.piebridge.brevent.server.BreventServer bootstrap; " +
                "/system/bin/sh /data/local/tmp/brevent.sh"
        // Scene6：执行其提权激活脚本 up.sh
        private const val CMD_SCENE6 =
            "sh /storage/emulated/0/Android/data/com.omarea.vtools/up.sh"
        // Scene5（旧版）：按同样规则尝试
        private const val CMD_SCENE5 =
            "sh /storage/emulated/0/Android/data/com.lsdroid.ceres/up.sh"

        /** 目标应用对应的激活命令；未收录的应用返回 null（仅启动自身服务）。 */
        private fun activateCommandFor(pkg: String): String? = when (pkg) {
            "me.piebridge.brevent" -> CMD_BREVENT
            "com.omarea.vtools" -> CMD_SCENE6
            "com.lsdroid.ceres" -> CMD_SCENE5
            else -> null
        }

        /** 目标应用展示名（通知文案用）。 */
        private fun targetLabel(pkg: String): String = when (pkg) {
            "me.piebridge.brevent" -> "黑阈"
            "com.omarea.vtools" -> "Scene"
            "com.lsdroid.ceres" -> "Scene(旧版)"
            else -> "Scene/黑域"
        }

        /** 进入 Scene/黑域后的激活冷却：2 分钟内只提示一次，避免反复弹通知。 */
        private const val ACTIVATE_COOLDOWN_MS = 120_000L

        // ---- 83 轮：使用情况访问权限（前台应用监测的前提） ----
        /** 权限引导通知 ID。 */
        private const val USAGE_GUIDE_NOTIFICATION_ID = 43

        /** 使用情况访问权限缺失时引导通知的冷却（10 分钟），避免刷屏。 */
        private const val USAGE_GUIDE_COOLDOWN_MS = 600_000L

        /** 是否已授予「使用情况访问权限」（AppOps 特殊权限，需系统设置手动开启）。 */
        fun hasUsageAccess(ctx: Context): Boolean {
            return try {
                val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    appOps.unsafeCheckOpNoThrow(
                        AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName
                    )
                } else {
                    @Suppress("DEPRECATION")
                    appOps.checkOpNoThrow(
                        AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName
                    )
                }
                mode == AppOpsManager.MODE_ALLOWED
            } catch (_: Throwable) {
                false
            }
        }

        /** 打开系统「使用情况访问权限」设置页（MIUI/HyperOS 兼容，带包名定位）。 */
        fun usageAccessSettingsIntent(ctx: Context): Intent =
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                .putExtra("app_package", ctx.packageName)
                .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /** 83 轮：使用情况访问权限缺失时发引导通知（点击前往授权），否则无法监测前台应用。 */
        private fun showUsageAccessGuideNotification(ctx: Context) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val ch = NotificationChannel(
                        "usage_guide",
                        "权限引导",
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply { description = "使用情况访问权限未开启时引导开启" }
                    ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
                }
                val pi = PendingIntent.getActivity(
                    ctx, 0x59, usageAccessSettingsIntent(ctx),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Notification.Builder(ctx, "usage_guide")
                } else {
                    @Suppress("DEPRECATION") Notification.Builder(ctx)
                }
                ctx.getSystemService(NotificationManager::class.java).notify(
                    USAGE_GUIDE_NOTIFICATION_ID,
                    builder
                        .setContentTitle("Lin-Shizuku")
                        .setContentText("需要开启「使用情况访问权限」，点击前往开启（用于监测进入 Scene/黑域）")
                        .setSmallIcon(android.R.drawable.ic_menu_manage)
                        .setContentIntent(pi)
                        .setAutoCancel(true)
                        .setVisibility(Notification.VISIBILITY_PUBLIC)
                        .build()
                )
                Log.d(TAG, "已弹出使用情况访问权限引导通知")
            } catch (e: Throwable) {
                Log.w(TAG, "showUsageAccessGuideNotification failed", e)
            }
        }

        // ---- P1-5 省电轮询参数 ----
        /** 有触发活动时的轮询间隔（保持响应性）。86 轮：500ms→250ms 提升识别速度。 */
        private const val ACTIVE_INTERVAL_MS = 250L

        /** 连续无包切换活动的静默期超过该时长后，放宽轮询间隔。 */
        private const val IDLE_GAP_MS = 30_000L

        /** 静默期轮询间隔（省电）。90i 轮：800ms→2000ms——后台静默时 binder 查询频率降 60%，
         *   CPU 占用显著下降；激活通知依赖 250ms 活跃间隔（进入 Scene 时仍即时响应）。 */
        private const val IDLE_INTERVAL_MS = 2_000L

        /** 屏幕熄灭后的低频检查间隔（熄屏时不做 usageStats 轮询，等亮屏再恢复）。 */
        private const val SCREEN_OFF_CHECK_MS = 5_000L

        // ---- 86 轮：UsageEvents 事件流实时识别 ----
        /** 事件流查询窗口：覆盖系统事件缓冲延迟（3 秒），轮询 250ms 时重复读同一批事件由冷却兜底。 */
        private const val EVENT_WINDOW_MS = 3_000L

        fun start(ctx: Context) {
            // 90h 轮：主动停止标志复位（服务被自愈/手动拉起后，再次被动死亡仍可自愈）
            stopping = false
            // 76 轮：有脚本触发配置，或开启了 Scene/黑域自动激活，才启动监听服务
            try {
                Prefs.init(ctx)
            } catch (_: Throwable) {}
            val needTrigger = runCatching { ModuleStore.listPackageTriggers(ctx).isNotEmpty() }.getOrDefault(false)
            val needActivate = Prefs.autoActivate
            if (!needTrigger && !needActivate) return
            // 83 轮：前台应用监测依赖「使用情况访问权限」——未授权先发引导通知，
            // 否则进入 Scene/黑域也监测不到，激活通知永远不会弹
            if ((needTrigger || needActivate) && !hasUsageAccess(ctx)) {
                showUsageAccessGuideNotification(ctx)
            }
            val intent = Intent(ctx, AppTriggerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }

        fun stop(ctx: Context) {
            // 90h 轮：主动停止置位，onDestroy 不再自愈重启
            stopping = true
            ctx.stopService(Intent(ctx, AppTriggerService::class.java))
        }

        /** 90h 轮：当前是否仍需监听（Scene/黑域自动激活 或 存在脚本触发配置）。自愈前重读，防误重启。 */
        fun needMonitor(ctx: Context): Boolean {
            return runCatching {
                Prefs.init(ctx)
                Prefs.autoActivate || ModuleStore.listPackageTriggers(ctx).isNotEmpty()
            }.getOrDefault(false)
        }

        /** 90h 轮：被动死亡自愈——延迟后重读需求再拉起（服务被系统回收/通知被划掉后自动恢复）。 */
        fun selfHeal(ctx: Context, delayMs: Long) {
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    if (!stopping && needMonitor(ctx)) {
                        start(ctx)
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "self-heal restart failed", e)
                }
            }, delayMs)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null
    private var lastPkg: String? = null

    /** 各包最后一次作为前台应用被观察到的时间（用于离场时长判定）。 */
    private val lastSeenTimes = HashMap<String, Long>()

    /** 各脚本最近一次触发时间（用于冷却防抖）。 */
    private val lastRunTimes = HashMap<String, Long>()

    /** 76 轮：Scene/黑域自动激活最近一次提示时间（2 分钟冷却防重复弹通知）。 */
    private var lastActivateAt = 0L

    /** 83 轮：使用情况访问权限引导最近一次提示时间（10 分钟冷却防刷屏）。 */
    private var lastUsageGuideAt = 0L

    /** 83 轮：权限缺失检查节流（30 秒一次，AppOps 查询是本地快速调用）。 */
    private var lastUsageCheckAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        pollJob = scope.launch { pollLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 90h 轮：兜底——pollJob 意外终止（如轮询协程被取消）时重新拉起，服务活着即持续监听
        if (pollJob?.isActive != true) {
            pollJob = scope.launch { pollLoop() }
        }
        // 76/81 轮：点击「激活」通知 → 先执行目标应用激活命令（黑阈/Scene），再启动自身服务
        if (intent?.action == ACTION_ACTIVATE) {
            val pkg = intent.getStringExtra(EXTRA_TARGET_PKG) ?: ""
            scope.launch {
                try {
                    // 81 轮：按进入的应用执行其真实激活命令，确保 Scene/黑域被真正激活
                    val cmd = activateCommandFor(pkg)
                    if (cmd != null) {
                        val r = ShizukuManager.runPrivilegedCommand(cmd)
                        Log.d(TAG, "Activate command for $pkg: ${r.take(160)}")
                    }
                    val result = ShizukuManager.startService(this@AppTriggerService)
                    Log.d(TAG, "Auto activate result: ${result.take(120)}")
                } catch (e: Throwable) {
                    Log.w(TAG, "Auto activate failed", e)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        pollJob?.cancel()
        scope.coroutineContext[Job]?.cancel()
        // 90h 轮：被动死亡自愈——系统回收/通知被划掉等非主动停止时，1 秒后重读需求并自愈重启，
        // 修复「打开下次无效，需关闭重开才生效」（服务死亡后无任何恢复通道的问题）
        if (!stopping) {
            selfHeal(applicationContext, 1_000L)
        }
        super.onDestroy()
    }

    /** 90h 轮：划掉任务后延迟自愈（进程未被杀时立即恢复监听；被系统杀则依赖 START_STICKY）。 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!stopping) {
            Log.d(TAG, "onTaskRemoved：任务被划掉，2 秒后自愈重启")
            selfHeal(applicationContext, 2_000L)
        }
    }

    private suspend fun pollLoop() {
        val usageStats = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        var lastActivityAt = 0L
        while (currentCoroutineContext().isActive) {
            // P1-5 省电：屏幕熄灭时不做 usageStats 轮询（应用不会被打开），低频等待亮屏。
            // 同时规避 Doze 下的无谓唤醒（前台服务在 Doze 中仍可运行，但不必空转）。
            if (!powerManager.isInteractive) {
                delay(SCREEN_OFF_CHECK_MS)
                continue
            }
            var now = System.currentTimeMillis()
            var hasActivity = false
            // 83 轮：使用情况访问权限缺失时每 30s 检查、10 分钟冷却发引导通知——
            // 覆盖「升级后服务已在运行」的场景，让用户能发现并前往授权
            if (now - lastUsageCheckAt >= 30_000L) {
                lastUsageCheckAt = now
                if (Prefs.autoActivate && !hasUsageAccess(this@AppTriggerService)) {
                    if (now - lastUsageGuideAt >= USAGE_GUIDE_COOLDOWN_MS) {
                        lastUsageGuideAt = now
                        showUsageAccessGuideNotification(this@AppTriggerService)
                    }
                }
            }
            try {
                // 86 轮：优先用 UsageEvents 事件流实时识别前台切换——queryUsageStats 的聚合统计
                // 在部分 ROM（小米/HyperOS）上 lastTimeUsed 更新会延迟数秒，而事件流的
                // ACTIVITY_RESUMED 随系统写入即可捕获，进入 Scene/黑域后激活通知几乎瞬时弹出。
                val events = usageStats.queryEvents(
                    now - EVENT_WINDOW_MS,
                    now
                )
                for (pkg in collectResumedPackages(events)) {
                    if (Prefs.autoActivate && TARGET_PACKAGES.contains(pkg)) {
                        if (now - lastActivateAt >= ACTIVATE_COOLDOWN_MS) {
                            lastActivateAt = now
                            showActivateNotification(pkg)
                        }
                    }
                }

                val stats = usageStats.queryUsageStats(
                    UsageStatsManager.INTERVAL_BEST,
                    now - 1000,
                    now
                )
                val topPkg = stats
                    ?.maxByOrNull { it.lastTimeUsed }
                    ?.packageName

                if (topPkg != null && topPkg != lastPkg) {
                    lastPkg = topPkg
                    now = System.currentTimeMillis()
                    val lastSeenAt = lastSeenTimes[topPkg] ?: 0L
                    lastSeenTimes[topPkg] = now

                    // 76/83 轮：Scene/黑域自动激活——进入目标应用即弹「激活」通知（2 分钟冷却）。
                    // 注意：与 Shizuku 自身服务是否运行无关——点击执行的是 Scene/黑域 自身的
                    // 激活命令（黑阈 bootstrap / Scene up.sh），即使 Lin-Shizuku 服务已在运行
                    // 也要弹，否则用户无法一键激活 Scene/黑域（83 轮修复）。
                    if (Prefs.autoActivate && TARGET_PACKAGES.contains(topPkg)) {
                        if (now - lastActivateAt >= ACTIVATE_COOLDOWN_MS) {
                            lastActivateAt = now
                            showActivateNotification(topPkg)
                        }
                    }

                    // 离场时长足够才触发；不足视为脚本执行后自触发的重进（如 Scene 激活后重启）
                    if (now - lastSeenAt >= LEAVE_GAP_MS) {
                        hasActivity = true
                        // 查是否有脚本绑定这个包名（带脚本级冷却，双保险防死循环）
                        val triggers = ModuleStore.listPackageTriggers(this@AppTriggerService)
                        val matched = triggers.filterValues { it == topPkg }.keys
                        for (id in matched) {
                            val lastRunAt = lastRunTimes[id] ?: 0L
                            if (now - lastRunAt < SCRIPT_COOLDOWN_MS) {
                                continue
                            }
                            lastRunTimes[id] = now
                            try {
                                val result = ModuleStore.runScriptById(this@AppTriggerService, id)
                                Log.d(TAG, "Trigger $id for $topPkg: $result")
                            } catch (e: Throwable) {
                                Log.w(TAG, "Trigger $id failed", e)
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "poll error", e)
            }
            if (hasActivity) lastActivityAt = now
            // P1-5 自适应间隔：刚触发/有活动用 250ms 保持快速响应；静默超过 30s 放宽到 800ms 省电
            val idleMs = now - lastActivityAt
            val interval = if (lastActivityAt != 0L && idleMs > IDLE_GAP_MS) {
                IDLE_INTERVAL_MS
            } else {
                ACTIVE_INTERVAL_MS
            }
            delay(interval)
        }
    }

    /** 86 轮：收集查询窗口内 ACTIVITY_RESUMED（前台切换）的包名集合。 */
    private fun collectResumedPackages(events: UsageEvents?): Set<String> {
        if (events == null) return emptySet()
        val pkgs = LinkedHashSet<String>()
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                e.packageName?.let { pkgs.add(it) }
            }
        }
        return pkgs
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "应用打开触发",
                NotificationManager.IMPORTANCE_MIN
            ).apply { description = "监测应用打开以触发脚本" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder
            .setContentTitle("Lin-Shizuku")
            .setContentText("正在监测应用打开触发")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .build()
    }

    /** 76/81/83 轮：Scene/黑域自动激活通知——点击执行对应激活命令并启动 Shizuku 服务。 */
    private fun showActivateNotification(pkg: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch = NotificationChannel(
                    "auto_activate",
                    "自动激活",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply { description = "进入 Scene/黑域时可一键激活" }
                getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
            }
            val clickIntent = Intent(this, AppTriggerService::class.java)
                .setAction(ACTION_ACTIVATE)
                .putExtra(EXTRA_TARGET_PKG, pkg)
            val pi = PendingIntent.getService(
                this, 0x58, clickIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, "auto_activate")
            } else {
                @Suppress("DEPRECATION") Notification.Builder(this)
            }
            val label = targetLabel(pkg)
            val notification = builder
                .setContentTitle("Lin-Shizuku")
                .setContentText("检测到进入$label，点击激活$label")
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .build()
            getSystemService(NotificationManager::class.java).notify(ACTIVATE_NOTIFICATION_ID, notification)
            Log.d(TAG, "已弹出自动激活通知 pkg=$pkg")
        } catch (e: Throwable) {
            Log.w(TAG, "showActivateNotification failed", e)
        }
    }
}
