package moe.shizuku.manager.adb

import android.annotation.TargetApi
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.net.ConnectException
import java.net.SocketTimeoutException
import moe.shizuku.privileged.api.MainActivity
import moe.shizuku.privileged.api.R
import moe.shizuku.privileged.api.core.Prefs
import moe.shizuku.privileged.api.util.AdbMdns
import moe.shizuku.privileged.api.util.AdbWirelessHelper
import moe.shizuku.privileged.api.util.EnvironmentUtils
import rikka.shizuku.Shizuku

@TargetApi(Build.VERSION_CODES.R)
class AdbPairingService : Service() {

    companion object {
        const val notificationChannel = "adb_pairing"
        private const val tag = "AdbPairingService"
        private const val notificationId = 1
        private const val replyRequestId = 1
        private const val stopRequestId = 2
        private const val contentRequestId = 3
        private const val startAction = "start"
        private const val stopAction = "stop"
        private const val replyAction = "reply"
        private const val remoteInputResultKey = "paring_code"
        private const val portKey = "pair_port"

        /** 通知栏配对成功后广播，应用内配对页据此自动继续启动流程。 */
        const val ACTION_ADB_PAIR_SUCCESS = "moe.shizuku.privileged.api.action.ADB_PAIR_SUCCESS"

        fun startIntent(context: Context): Intent =
            Intent(context, AdbPairingService::class.java).setAction(startAction)

        /** 修复：公开停止接口——配对成功/页面退出时由 PairingViewModel 主动停止服务，
         *  避免前台服务通知「已找到配对服务」常驻通知中心。 */
        fun stop(context: Context) {
            runCatching {
                context.startService(stopIntent(context))
            }
        }

        private fun stopIntent(context: Context): Intent =
            Intent(context, AdbPairingService::class.java).setAction(stopAction)

        private fun replyIntent(context: Context, port: Int): Intent =
            Intent(context, AdbPairingService::class.java).setAction(replyAction).putExtra(portKey, port)
    }

    private val handler = Handler(Looper.getMainLooper())

    /** 90ar：最近一次配对端口（配对成功后自动启动服务用；1.1.1 起取自应用内手动输入并落盘） */
    private var lastPairPort = -1

    /** v5.2：mDNS 发现的连接端口（_adb-tls-connect）——用户 Termux python-zeroconf
     *  实测同一环境能收到（40213），推翻"HyperOS 丢多播包"误判；真正原因是
     *  App 缺 MulticastLock（RawMdnsPortFinder v5.2 已持锁）。 */
    private var mdnConnectPort = -1

    /** 90ar：自动启动服务防重入锁（配对成功多通道/重复配对只启动一次） */
    private val serviceStartLocked = java.util.concurrent.atomic.AtomicBoolean(false)

    /** ConnectException 自动重试用：最多重试 1 次，避免无限循环 */
    private var connectRetryCount = 0

    /** v4.5：mDNS 配对端口发现（官方系机制，Stellar 健壮实现移植）——
     *  无记忆/无障碍端口时动态发现「配对端口」，通知栏路径端口自动获取。 */
    private var adbMdns: AdbMdns? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                notificationChannel, "ADB配对", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                setSound(null, null)
                setShowBadge(false)
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = when (intent?.action) {
            startAction -> onStart()
            replyAction -> {
                val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(remoteInputResultKey) ?: ""
                val port = intent.getIntExtra(portKey, -1)
                if (port != -1) onInput(code.toString(), port) else onStart()
            }
            stopAction -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                // 普通通知（配对结果）也一并移除
                runCatching { getSystemService(NotificationManager::class.java).cancel(notificationId) }
                // 90at：short-service 必须主动退出，否则系统超时强杀并写 ANR trace
                stopSelf()
                null
            }
            else -> return START_NOT_STICKY
        }
        if (notification != null) {
            try {
                // 77 轮：Android 14+ 要求前台服务类型；用 MANIFEST 类型让系统采用清单声明的 shortService
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(notificationId, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST)
                } else {
                    startForeground(notificationId, notification)
                }
            } catch (e: Throwable) {
                Log.e(tag, "startForeground failed", e)
                getSystemService(NotificationManager::class.java).notify(notificationId, notification)
            }
        }
        // 修复：START_REDELIVER_INTENT 会导致服务被系统杀死后自动重启，
        // 通知「已找到配对服务」反复出现。改为 START_NOT_STICKY，
        // 服务被杀后不重启，配对流程由 App 内 PairingViewModel 主导。
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // v4.5：服务销毁 → 停止 mDNS 发现（防泄漏）
        runCatching { adbMdns?.stop() }
        adbMdns = null
        super.onDestroy()
    }

    /** 标准时序（官方 Shizuku 同款）：
     *  点配对 → 「正在搜索配对服务」（停止搜索）→ 用户开无线调试 → mDNS 真发现
     *  _adb-tls-pairing → 「已找到配对服务」（输入配对码）→ 输码 → 配对成功 → 自动启动服务。
     *  端口来源：① 无障碍刚抓取的配对端口（pendingPairPort，弹窗确实出现、真实有效）
     *           ② mDNS 动态发现（_adb-tls-pairing）
     *  不再使用记忆端口（last_pairing_port）冒充「已找到配对服务」——无线调试未开启时
     *  记忆端口已过期，误导用户（实测：无线调试没开也显示「已找到配对服务」）。 */
    private fun onStart(): Notification {
        val pendingPort = Prefs.pendingPairPort.toIntOrNull()
        if (pendingPort != null && pendingPort in 1..65535) {
            Prefs.pendingPairPort = ""
            lastPairPort = pendingPort
            Log.i(tag, "Pairing service port: $pendingPort (from accessibility)")
            // 90at：前台服务兜底——120 秒内未完成配对自动退出，防泄漏
            handler.postDelayed({
                Log.i(tag, "Fallback: pairing not completed within 120s, stopping self")
                stopForeground(STOP_FOREGROUND_REMOVE)
                runCatching { getSystemService(NotificationManager::class.java).cancel(notificationId) }
                stopSelf()
            }, 120_000L)
            return createInputNotification(pendingPort)
        }
        Log.i(tag, "No pairing port yet, searching for pairing service...")
        startMdnsDiscovery()
        return searchingNotification
    }

    /** v4.5：mDNS 动态发现配对端口（官方系机制，Stellar 健壮实现移植）。
     *  发现后更新通知为「已找到配对服务」并启动 120 秒兜底（防泄漏）。 */
    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.R)
    private fun startMdnsDiscovery() {
        if (adbMdns != null) return
        runCatching {
            adbMdns = AdbMdns(
                context = this,
                serviceType = AdbMdns.TLS_PAIRING,
                onFound = { (_, port) ->
                    if (port in 1..65535) {
                        lastPairPort = port
                        Log.i(tag, "mDNS discovered pairing port: $port")
                        // 修复：取消之前的 120 秒兜底，避免 mDNS 多次发现导致
                        // 兜底计时反复重置、服务迟迟不退出
                        handler.removeCallbacksAndMessages(null)
                        // 更新通知为「已找到配对服务，输入配对码」
                        getSystemService(NotificationManager::class.java)
                            .notify(notificationId, createInputNotification(port))
                        handler.postDelayed({
                            Log.i(tag, "Fallback: pairing not completed within 120s, stopping self")
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            runCatching { getSystemService(NotificationManager::class.java).cancel(notificationId) }
                            stopSelf()
                        }, 120_000L)
                    }
                },
                onConnectFound = { port ->
                    // v5.2：mDNS 发现连接端口（官方机制）——自动启动服务时优先使用
                    if (port in 1..65535) {
                        mdnConnectPort = port
                        Log.i(tag, "mDNS discovered connect port: $port")
                    }
                },
                onMaxRefresh = {
                    // auto_pair.py 同款：mDNS 持续监听，不盲扫——达到阈值只提示
                    Log.i(tag, "mDNS 已持续监听约100秒，继续等待配对弹窗（不盲扫）")
                    if (lastPairPort !in 1..65535) {
                        getSystemService(NotificationManager::class.java)
                            .notify(notificationId, searchingNotification)
                    }
                }
            ).also { it.start() }
        }
    }

    private fun onInput(code: String, port: Int): Notification {
        // 1.1.1：端口优先级——onStart 已加载端口 > 无障碍刚抓到的 pendingPairPort > intent 端口
        val pendingPort = Prefs.pendingPairPort.toIntOrNull()
        val actualPort: Int = when {
            lastPairPort in 1..65535 -> lastPairPort
            pendingPort != null && pendingPort in 1..65535 -> {
                Prefs.pendingPairPort = ""
                pendingPort
            }
            else -> port
        }
        Log.i(tag, "Pairing with port: $actualPort (mDNS: $lastPairPort, intent: $port, a11y: ${pendingPort ?: -1})")
        GlobalScope.launch(Dispatchers.IO) {
            val host = "127.0.0.1"
            val key = try {
                AdbKey(PreferenceAdbKeyStore(getSharedPreferences("adb_key", MODE_PRIVATE)), "Lin-Shizuku")
            } catch (e: Throwable) {
                e.printStackTrace()
                return@launch
            }
            // auto_pair.py 同款：端口来自 mDNS 广播，失败直接提示，不盲扫
            val outcome = try {
                val ok = AdbPairingClient(host, actualPort, code, key).use { it.start() }
                if (ok) null else RuntimeException("配对协议交换失败")
            } catch (e: AdbInvalidPairingCodeException) {
                e
            } catch (e: Throwable) {
                e
            }
            handleResult(outcome == null, outcome)
        }
        return workingNotification
    }

    private fun handleResult(success: Boolean, exception: Throwable?) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        val title: String
        val text: String?
        if (success) {
            Log.i(tag, "Pair succeed")
            title = "配对成功"
            text = "您现在可以启动 Shizuku 服务了。"
            // ① 落盘配对成功标记：应用晚进入（小窗/新页面/后台恢复）时读取后自动继续，
            //    解决「只发一次广播、晚注册的 ViewModel 收不到」导致需手动点「配对完成，继续」
            runCatching {
                getSharedPreferences("pair_state", MODE_PRIVATE).edit()
                    .putBoolean("pair_success", true)
                    .putLong("pair_success_time", System.currentTimeMillis())
                    .apply()
            }
            // ② 即时广播（前台场景立即推进）
            runCatching { sendBroadcast(Intent(ACTION_ADB_PAIR_SUCCESS)) }
            // ③ 延迟重发（覆盖 ViewModel 晚注册 / 进程晚恢复的窗口）
            handler.postDelayed({ runCatching { sendBroadcast(Intent(ACTION_ADB_PAIR_SUCCESS)) } }, 2000)
            handler.postDelayed({ runCatching { sendBroadcast(Intent(ACTION_ADB_PAIR_SUCCESS)) } }, 6000)
            // 90ar：配对成功后自动启动服务（让服务进入「正在运行中」），启动完成（或已运行）跳转首页
            autoStartServiceAndOpenHome()
            // 90at：配对成功后延迟 8 秒退出服务——给 autoStart 留时间发通知，
            // 避免 short-service 类型前台服务挂着被系统超时强杀写 ANR trace
            handler.postDelayed({
                stopForeground(STOP_FOREGROUND_REMOVE)
                runCatching { getSystemService(NotificationManager::class.java).cancel(notificationId) }
                stopSelf()
            }, 8000)
        } else {
            title = "配对失败"
            text = when (exception) {
                // 90bm：ConnectException = 配对端口未监听/连接被拒——配对服务生命周期极短
                // （系统无线调试配对弹窗打开时临时启动、关闭或超时即停止；下拉通知栏/切走页面
                // 都可能导致弹窗关闭）。给出可操作指引，并自动重新发现配对服务更新通知端口。
                is ConnectException -> "无法连接端口：系统配对服务已过期（配对弹窗关闭或超时）。请回到无线调试页面重新点「使用配对码配对设备」，保持弹窗显示，再在通知中输入新配对码；或在弹窗保持期间回到本 App 配对页输入。"
                is AdbInvalidPairingCodeException -> "配对码错误：请重新在无线调试页面点「使用配对码配对设备」获取新配对码后重试"
                is AdbKeyException -> "密钥存储错误"
                is SocketTimeoutException -> "连接超时：若系统弹出「选择要连接的设备」授权框，请点击设备名称允许连接（不要点「不连接」）；弹窗持续加载请换手机热点或家庭 Wi-Fi 重试"
                else -> exception?.let { Log.getStackTraceString(it) }
            }
            if (exception != null) Log.w(tag, "Pair failed", exception)
            // 1.1.1：不再 mDNS 自动重发现端口——端口由用户手动输入/记忆。
            // 失败时提示用户回无线调试页重新点「使用配对码配对设备」，并更新端口后重试。
        }
        // 全系统标准结果通知（折叠/展开统一，action 行可见可点）
        val builder = Notification.Builder(this, notificationChannel)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text ?: ""))
            .addAction(Notification.Action.Builder(null, "知道了", stopPendingIntent).build())
        // 配对成功点击通知直达应用首页（配对已完成，流程页不再需要；服务状态在首页可见）
        if (success) builder.setContentIntent(homePendingIntent())
        getSystemService(NotificationManager::class.java).notify(notificationId, builder.build())
    }

    /**
     * 90ar：配对成功后自动启动服务 → 服务「正在运行中」→ 跳转 Lin-Shizuku 首页。
     *
     * 覆盖场景：通过通知栏（RemoteInput）完成配对、应用未停留在配对页时，
     * 配对页 ViewModel 收不到广播、也没有人点「启动」，服务不会自动拉起。
     * 这里在配对服务侧直接走 ADB 启动通道（复用 AdbWirelessHelper 全流程：
     * TCP 固定端口切换 / su 提权），服务启动完成（或检测到已在运行）后
     * 打开 MainActivity 首页，由首页状态区显示「正在运行」。
     *
     * 90bi：lastPairPort 是「配对端口」（_adb-tls-pairing），只接受配对握手
     * （PairingClient），普通 ADB 连接（A_CNXN/TLS）打过去会被 adbd 直接
     * RST → 通知弹「服务启动失败 Connection reset」。自动启动必须先重新发现
     * 「连接端口」（_adb-tls-connect），绝不能用配对端口连接。
     */
    private fun autoStartServiceAndOpenHome() {
        if (!serviceStartLocked.compareAndSet(false, true)) return
        val ctx = applicationContext
        // 服务已在运行 → 直接跳首页（无需重复启动）
        if (Shizuku.pingBinder()) {
            handler.post { openHome() }
            return
        }
        discoverConnectPort { connectPort ->
            if (connectPort <= 0) {
                // 连接端口未发现（异常路径）→ 仍回首页，由首页显示当前状态
                handler.post { openHome() }
                return@discoverConnectPort
            }
            runCatching { Prefs.init(ctx) }
            val helper = AdbWirelessHelper()
            helper.startShizukuViaAdb(
                ctx = ctx,
                host = "127.0.0.1",
                port = connectPort,
                coroutineScope = GlobalScope,
                onOutput = { /* 启动过程保持通知原文「正在自动启动服务」 */ },
                onError = { e ->
                    Log.w(tag, "90as 自动启动服务失败", e)
                    val detail = e.message ?: e.javaClass.simpleName
                    handler.post {
                        showResultNotification("服务启动失败", detail, homePendingIntent())
                        openHome()
                    }
                },
                onSuccess = {
                    handler.post {
                        showResultNotification("服务已启动", "点击进入首页查看运行状态", homePendingIntent())
                        openHome()
                    }
                },
                tcpMode = Prefs.tcpMode,
                tcpPort = Prefs.tcpPort,
                useSu = Prefs.wirelessSu
            )
        }
    }

    /** 1.1.1 v5.2：直读无线调试「连接端口」——优先级：mDNS 发现（_adb-tls-connect，
     *  官方机制）> 系统属性（service.adb.wifi.tcp.port / adb_wifi_port，国产 ROM
     *  可能拦截）。v4.x 曾因"HyperOS 丢多播包"弃用 mDNS，用户 Termux python-zeroconf
     *  实测推翻该结论（缺 MulticastLock 才是真因，v5.2 已修）。 */
    private fun discoverConnectPort(onFound: (Int) -> Unit) {
        val connectPort = if (mdnConnectPort in 1..65535) {
            mdnConnectPort
        } else {
            EnvironmentUtils.getAdbTcpPort()
        }
        Log.i(tag, "Connect port (mDNS=$mdnConnectPort, system=$connectPort): $connectPort")
        onFound(connectPort)
    }

    /** 打开应用首页（非配对页；配对完成自动启动场景直达首页）。 */
    private fun openHome() {
        runCatching {
            val intent = Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            startActivity(intent)
        }
    }

    /**
     * 90as：启动结果通知（自动跳转被系统后台 Activity 限制拦截时，
     * 通知即兜底入口——点击直达首页查看运行状态/诊断日志）。
     */
    private fun showResultNotification(title: String, text: String, contentIntent: PendingIntent) {
        runCatching {
            val builder = Notification.Builder(this, notificationChannel)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(contentIntent)
                .addAction(Notification.Action.Builder(null, "知道了", stopPendingIntent).build())
            getSystemService(NotificationManager::class.java).notify(notificationId, builder.build())
        }
    }

    /** 打开应用并直达首页（配对完成/启动结果通知主点击，不带配对页标记） */
    private fun homePendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            this, contentRequestId, intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /** 打开应用并直达配对页（搜索/配对中通知主点击） */
    private fun openAppPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_GO_PAIRING, true)
        return PendingIntent.getActivity(
            this, contentRequestId, intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private val stopPendingIntent: PendingIntent by lazy {
        PendingIntent.getService(
            this, stopRequestId, stopIntent(this),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0
        )
    }

    private val stopNotificationAction: Notification.Action by lazy {
        Notification.Action.Builder(null, "停止搜索", stopPendingIntent).build()
    }

    /** 通知栏内联输入：RemoteInput 定义（系统 action「输入配对码」点击弹出输入框） */
    private val remoteInput: RemoteInput by lazy {
        RemoteInput.Builder(remoteInputResultKey)
            .setLabel("输入6位配对码")
            .build()
    }

    private fun replyPendingIntent(port: Int): PendingIntent =
        PendingIntent.getForegroundService(
            this, replyRequestId, replyIntent(this, port),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun replyNotificationAction(port: Int): Notification.Action {
        return Notification.Action.Builder(null, "输入配对码", replyPendingIntent(port))
            .addRemoteInput(remoteInput)
            .build()
    }

    private val searchingNotification: Notification by lazy {
        // 标准时序通知：点配对 → 无条件先显示「正在搜索配对服务」+ 停止搜索按钮；
        // 用户开启无线调试/弹出配对弹窗后，mDNS 发现 _adb-tls-pairing 即更新为
        // 「已找到配对服务」。不再依赖记忆端口，避免无线调试未开启时误报。
        Notification.Builder(this, notificationChannel)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("正在搜索配对服务")
            .setContentText("请在系统「开发者选项 → 无线调试」开启并保持「使用配对码配对设备」弹窗显示，发现后自动进入配对")
            .setContentIntent(openAppPendingIntent())
            .setStyle(Notification.BigTextStyle().bigText("正在搜索配对服务。请在系统「开发者选项 → 无线调试」开启，并点「使用配对码配对设备」保持弹窗显示；App 通过 mDNS 持续监听，发现配对服务后本通知自动更新为「已找到配对服务」。"))
            .addAction(stopNotificationAction)
            .build()
    }

    private fun createInputNotification(port: Int): Notification {
        // 全系统标准通知：展开态 action「输入配对码」触发系统 RemoteInput 内联输入框
        // 不使用自定义布局（自定义布局在该机型上会导致输入框弹出即收 + 折叠/展开样式割裂）
        // v5.4（用户 15:30 实测「通知中心端口错误」）：端口来自 mDNS/盲扫发现的
        // lastPairPort，弹窗轮换后端口会过期（实测显示旧值 37863 误导）。通知不再
        // 展示具体端口号，只引导查看系统弹窗并输入配对码——端口永远以系统弹窗为准。
        return Notification.Builder(this, notificationChannel)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("已找到配对服务")
            .setContentText("请在系统弹窗查看配对端口，输入 6 位配对码完成配对")
            .setContentIntent(openAppPendingIntent())
            .setStyle(Notification.BigTextStyle().bigText("已找到配对服务。配对端口以系统「与设备配对」弹窗为准（弹窗关闭后端口即失效），点击「输入配对码」在通知内输入 6 位配对码完成配对。"))
            .addAction(replyNotificationAction(port))
            .build()
    }

    private val workingNotification: Notification by lazy {
        // 全系统标准通知（配对中）
        Notification.Builder(this, notificationChannel)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("正在配对...")
            .setContentText("正在向设备发起配对请求，请稍候…")
            .setContentIntent(openAppPendingIntent())
            .setStyle(Notification.BigTextStyle().bigText("正在向设备发起配对请求，请稍候…"))
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
