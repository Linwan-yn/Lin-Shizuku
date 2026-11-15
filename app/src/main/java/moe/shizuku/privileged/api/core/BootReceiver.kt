package moe.shizuku.privileged.api.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import moe.shizuku.privileged.api.util.AdbWirelessHelper
import rikka.shizuku.Shizuku

/**
 * Boot receiver — starts Shizuku automatically on boot if enabled in settings.
 * 优先级：Root → TCP 模式(127.0.0.1:5555) → 无线调试 mDNS 发现
 *
 * 90l 轮：启动主体抽为 [tryAutoStart]（companion 共享），
 * 供 BootReceiver（开机广播）与 LinAccessibilityService（无障碍自启）共用；
 * 结束把结果写入 Prefs.lastBootResult，设置页可见，不再"静默空壳"。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        try {
            Prefs.init(context)
        } catch (_: Throwable) {}

        if (!Prefs.startOnBoot) {
            Log.d(TAG, "Auto-start on boot is disabled")
            return
        }

        Log.d(TAG, "Auto-start on boot is enabled, starting Shizuku...")

        // A5/A6（90n 实现）：启动模式分流——「预热」只初始化 ADB TCP 端口不启动服务端；
        // 「Root脚本」只走 Root 启动；「开机广播」走完整序列（Root→TCP→上次无线端口）
        val mode = runCatching { Prefs.bootMode }.getOrDefault("开机广播")

        val pendingResult = goAsync()
        tryAutoStart(context, mode = mode) { pendingResult.finish() }
    }

    companion object {
        private const val TAG = "BootReceiver"
        private const val PREFS = "boot_auto"
        private const val KEY_LAST_PORT = "last_adb_port"

        /** 防重入：开机广播与无障碍自启可能同时触发，只执行一次启动序列。 */
        @Volatile
        private var inFlight = false

        /**
         * 开机自动启动序列：
         *  - 模式「Root脚本」：仅 Root 启动（3 次重试），不降级到 TCP/无线；
         *  - 模式「预热」：等待网络 → 初始化 ADB TCP 端口（root 时 setprop），不启动服务端；
         *  - 模式「开机广播」/其他：Root → TCP 模式 → 上次无线调试端口。
         * 供 BootReceiver（BOOT_COMPLETED）与 LinAccessibilityService（无障碍拉起进程）共用；
         * 内部 IO 协程，主线程可安全调用。
         *
         * @param mode 启动模式（设置页「开机启动 → 启动模式」），默认完整序列（无障碍自启用）。
         * @param onDone 序列结束回调（任意线程；BootReceiver 传 pendingResult.finish()）
         */
        fun tryAutoStart(context: Context, mode: String = "开机广播", onDone: (() -> Unit)? = null) {
            if (inFlight) {
                Log.d(TAG, "Auto-start already in flight, skip")
                onDone?.invoke()
                return
            }
            inFlight = true
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    // Wait for the system to settle after boot
                    kotlinx.coroutines.delay(5_000L)

                    // 启动模式「关闭」：不执行任何自启动作
                    if (mode == "关闭") {
                        runCatching { writeBootResult(context, false, "启动模式已设为关闭") }
                        return@launch
                    }

                    // A5（90n 实现）：预热模式——只初始化 ADB TCP 端口，不启动服务端
                    if (mode == "预热") {
                        val ok = prewarmAdb(context)
                        val ts = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date())
                        Prefs.lastBootResult = if (ok) {
                            "$ts 预热成功：ADB TCP 端口已就绪（未启动服务端，可手动配对启动）"
                        } else {
                            "$ts 预热失败：需要 Root 或先手动开启一次无线调试（无 su 且 adb 端口未配置）"
                        }
                        return@launch
                    }

                    var started = ShizukuManager.isRunningCompat()
                    // 1.0.11：累积每一步的输出/错误，失败时写进 lastBootResult 供设置页诊断
                    val trace = StringBuilder()
                    // 1. 尝试 Root 启动
                    if (!started) {
                        repeat(3) { attempt ->
                            if (ShizukuManager.isRunningCompat()) { started = true; return@repeat }
                            val result = ShizukuManager.startService(context)
                            Log.d(TAG, "Root start attempt ${attempt + 1}: ${result.take(100)}")
                            trace.append("[Root启动 第${attempt + 1}次] ").append(result.trim().take(300)).append("\n")
                            if (ShizukuManager.isRunningCompat()) { started = true; return@repeat }
                            kotlinx.coroutines.delay(4_000L)
                        }
                    }

                    // A6（90n 实现）：Root脚本模式——Root 失败后不再降级到 TCP/无线
                    if (mode == "Root脚本") {
                        runCatching { writeBootResult(context, started, trace.toString()) }
                        if (!started) {
                            Log.w(TAG, "Root script mode: root 启动失败，不降级")
                        }
                        finishBootServices(context, started)
                        return@launch
                    }

                    // 90ai（P1-1）：等 Wi-Fi 就绪——无线调试（非 TCP 回环模式）依赖局域网连接，
                    // 开机初期网络常未就绪导致直接失败。TCP 模式走 127.0.0.1 回环不需要网络。
                    if (!started && !Prefs.tcpMode) {
                        val wifiOk = awaitWifiReady(context)
                        if (wifiOk) {
                            Log.d(TAG, "Wi-Fi ready, continue wireless start")
                            trace.append("[Wi-Fi] 已就绪\n")
                        } else {
                            Log.w(TAG, "Wi-Fi 未在时限内就绪，无线调试启动可能失败")
                            trace.append("[Wi-Fi] 等待超时(60s)：局域网未就绪\n")
                        }
                    }

                    // 2. Root 失败，尝试 TCP 模式（固定端口 5555）
                    if (!started && Prefs.tcpMode) {
                        Log.d(TAG, "Root failed, trying TCP mode on ${Prefs.tcpPort}...")
                        trace.append("[TCP模式] 尝试端口 ${Prefs.tcpPort}\n")
                        try {
                            val helper = AdbWirelessHelper()
                            val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
                            helper.startShizukuViaAdb(
                                ctx = context,
                                host = "127.0.0.1",
                                port = Prefs.tcpPort,
                                coroutineScope = this,
                                onOutput = { Log.d(TAG, "TCP: ${it.trim()}"); trace.append("[TCP输出] ").append(it.trim().take(200)).append("\n") },
                                onError = { Log.w(TAG, "TCP failed", it); trace.append("[TCP错误] ").append(it.message?.take(200) ?: "").append("\n") },
                                onSuccess = { deferred.complete(true) },
                                tcpMode = false,
                                useSu = Prefs.wirelessSu
                            )
                            kotlinx.coroutines.withTimeoutOrNull(15_000L) { deferred.await() }
                            kotlinx.coroutines.delay(2_000L)
                            if (ShizukuManager.isRunningCompat()) started = true
                        } catch (e: Throwable) {
                            Log.w(TAG, "TCP mode error", e)
                            trace.append("[TCP异常] ").append(e.message ?: "").append("\n")
                        }
                    }

                    // 3. 尝试上次成功的无线调试端口
                    if (!started) {
                        val lastPort = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_LAST_PORT, 0)
                        if (lastPort > 0) {
                            Log.d(TAG, "Trying last wireless port $lastPort...")
                            trace.append("[无线调试] 尝试上次端口 $lastPort\n")
                            try {
                                val helper = AdbWirelessHelper()
                                val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
                                helper.startShizukuViaAdb(
                                    ctx = context,
                                    host = "127.0.0.1",
                                    port = lastPort,
                                    coroutineScope = this,
                                    onOutput = { Log.d(TAG, "Wireless: ${it.trim()}"); trace.append("[无线输出] ").append(it.trim().take(200)).append("\n") },
                                    onError = { Log.w(TAG, "Wireless failed", it); trace.append("[无线错误] ").append(it.message?.take(200) ?: "").append("\n") },
                                    onSuccess = { deferred.complete(true) },
                                    tcpMode = Prefs.tcpMode,
                                    tcpPort = Prefs.tcpPort,
                                    useSu = Prefs.wirelessSu
                                )
                                kotlinx.coroutines.withTimeoutOrNull(20_000L) { deferred.await() }
                                kotlinx.coroutines.delay(2_000L)
                                if (ShizukuManager.isRunningCompat()) started = true
                            } catch (e: Throwable) {
                                Log.w(TAG, "Wireless port error", e)
                                trace.append("[无线异常] ").append(e.message ?: "").append("\n")
                            }
                        } else {
                            trace.append("[无线调试] 无上次成功端口记录\n")
                        }
                    }

                    // 保存当前端口供下次重试
                    if (started && Prefs.tcpMode) {
                        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                            .edit().putInt(KEY_LAST_PORT, Prefs.tcpPort).apply()
                    }

                    finishBootServices(context, started)

                    // 1.0.11：常规启动（Root/TCP/上次端口）全部失败且开启全自动无线配对时，
                    // 用 root 命令开 Wi-Fi→开发者选项→无线调试→拉起配对界面；
                    // 后续抓码→mDNS→配对由现有无障碍+PairingViewModel 链路完成。
                    if (!started && Prefs.autoWirelessBoot) {
                        val autoLog = runCatching { AutoWirelessBootManager.run(context) }
                            .getOrDefault("[全自动无线] 执行异常")
                        trace.append(autoLog).append("\n")
                        started = ShizukuManager.isRunningCompat()
                    }

                    // 90l 轮：结果落盘，设置页可见（失败原因可诊断，不再"静默空壳"）
                    writeBootResult(context, started, trace.toString())

                    if (!started) {
                        Log.w(TAG, "All auto-start methods failed")
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "Boot start failed", e)
                    runCatching { writeBootResult(context, false, "异常：${e.message}") }
                } finally {
                    inFlight = false
                    onDone?.invoke()
                }
            }
        }

        /**
         * 启动序列收尾：按开关启动监听/守护服务；服务就绪后触发模块引擎与开机脚本。
         * （Root脚本 / 开机广播 共用；预热模式不调用——不启动服务端）
         */
        private suspend fun finishBootServices(context: Context, started: Boolean) {
            // 启动应用触发前台服务
            if (ModuleStore.listPackageTriggers(context).isNotEmpty()) {
                AppTriggerService.start(context)
            }
            // 76 轮：Scene/黑域自动激活开启时也启动监听服务
            runCatching {
                if (Prefs.autoActivate) AppTriggerService.start(context)
            }
            // 76 轮：强力保活开启时启动守护服务（闹钟心跳 + 息屏检测 + 划任务复活）
            runCatching {
                if (Prefs.strongKeepAlive) StrongKeepAliveService.start(context)
            }
            // A4（90n 实现）：进程守护开启时启动双进程守护（独立 :guard 进程）
            runCatching {
                if (Prefs.processGuard) GuardService.start(context)
            }

            // 服务已就绪 → 触发模块引擎（拉起所有已启用模块，幂等；adb 模式开机拉不起则跳过）
            if (started) {
                ShizukuManager.triggerModuleEngine(context)
            }

            // 开机触发脚本
            if (started) {
                kotlinx.coroutines.delay(2_000L)
                val bootTriggers = ModuleStore.listBootTriggers(context)
                Log.d(TAG, "Boot triggers: $bootTriggers")
                for (id in bootTriggers) {
                    try {
                        val result = ModuleStore.runScriptById(context, id)
                        Log.d(TAG, "Boot trigger $id: $result")
                    } catch (e: Throwable) {
                        Log.w(TAG, "Boot trigger $id failed", e)
                    }
                }
            }
        }

        /**
         * A5（90n 实现）：预热——等待网络就绪后初始化 ADB TCP/IP 端口（service.adb.tcp.port 5555），
         * 使开机后无线调试端口立即可用；不启动服务端。返回是否就绪。
         *  - 有 su：`setprop service.adb.tcp.port 5555; stop adbd; start adbd` 真实写入；
         *  - 无 su：校验 getprop 当前值——已是 5555（用户曾开启无线调试/TCP）即视为就绪，否则失败并给出引导。
         */
        private suspend fun prewarmAdb(context: Context): Boolean {
            // 等待网络/系统就绪（最多 15s）
            var ready = false
            repeat(15) {
                if (ShizukuManager.isRooted()) { ready = true; return@repeat }
                val prop = runCatching {
                    val p = Runtime.getRuntime().exec(arrayOf("getprop", "service.adb.tcp.port"))
                    p.inputStream.bufferedReader().readText().trim()
                }.getOrDefault("")
                if (prop == "5555") { ready = true; return@repeat }
                kotlinx.coroutines.delay(1_000L)
            }
            if (!ready) {
                Log.w(TAG, "预热：等待就绪超时（无 root 且 adb tcp 端口未配置）")
                return false
            }
            // 有 su：真实写入 adb 无线端口并重启 adbd（adbd 重启会断开当前连接属正常）
            if (ShizukuManager.isRooted()) {
                val result = runCatching {
                    val p = Runtime.getRuntime().exec(arrayOf(
                        "su", "-c",
                        "setprop service.adb.tcp.port 5555; stop adbd; start adbd; sleep 1; getprop service.adb.tcp.port"
                    ))
                    p.inputStream.bufferedReader().readText().trim() + p.errorStream.bufferedReader().readText().trim()
                }.getOrDefault("")
                Log.d(TAG, "预热 setprop 结果: $result")
                return result.contains("5555")
            }
            return true // 无 root 但 getprop 已是 5555：端口就绪
        }

        /** 把最近一次开机自启结果写入 Prefs（失败时附完整过程日志：权限报错/流程失败/脚本输出）。 */
        private fun writeBootResult(context: Context, started: Boolean, detail: String? = null) {
            runCatching {
                val ts = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date())
                val result = if (started) {
                    "$ts 开机自启成功"
                } else {
                    // 识别常见权限/操作错误，给出可读原因
                    val err = (detail ?: "").trim()
                    val reason = when {
                        !ShizukuManager.isRooted() ->
                            "未检测到 su 权限（Root 未授权/未安装 Magisk）。无 root 设备需连接电脑 adb 或手动无线调试一次。"
                        err.contains("Permission denied", ignoreCase = true) ||
                            err.contains("permission denied", ignoreCase = true) ->
                            "权限被拒绝（Permission denied）：root 未授权给 Lin-Shizuku，请到 Magisk 超级用户授权页确认。"
                        err.contains("access", ignoreCase = true) && err.contains("denied", ignoreCase = true) ->
                            "su 访问被拒绝：设备未授予 Lin-Shizuku root 权限。"
                        err.contains("超时", ignoreCase = true) || err.contains("timeout", ignoreCase = true) ->
                            "流程超时：Wi-Fi/无线调试/配对在时限内未就绪。"
                        err.isNotEmpty() -> "流程失败：$err"
                        else -> "Root/无线调试均未就绪"
                    }
                    "$ts 开机自启失败：$reason"
                }
                Prefs.lastBootResult = result
                DiagnosticLog.append("[开机自启] $result")
                Log.d(TAG, "boot result: $result")

                // 发开机自启完成通知
                showBootNotification(context, started)
            }
        }

        /** 发开机自启完成通知（标题：自启动提示，内容：已完成开机自启动） */
        private fun showBootNotification(context: Context, started: Boolean) {
            try {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
                    ?: return
                // 创建渠道
                val channelId = "boot_auto_start"
                val channel = android.app.NotificationChannel(
                    channelId,
                    "开机自启动",
                    android.app.NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "开机自启动完成通知"
                    setShowBadge(false)
                }
                nm.createNotificationChannel(channel)

                val content = if (started) "已完成开机自启动" else "开机自启动失败，请检查"
                val notification = android.app.Notification.Builder(context, channelId)
                    .setSmallIcon(android.R.drawable.ic_menu_revert)
                    .setContentTitle("自启动提示")
                    .setContentText(content)
                    .setAutoCancel(true)
                    .setOngoing(false)
                    .build()
                nm.notify(2001, notification)
            } catch (e: Throwable) {
                Log.w(TAG, "showBootNotification failed", e)
            }
        }

        /**
         * 90ai（P1-1）：等待 Wi-Fi/局域网就绪（无线调试依赖局域网；TCP 回环模式不需要）。
         * 开机初期网络常未就绪，直接尝试无线调试必然失败。上限 60 秒，2 秒轮询。
         */
        private suspend fun awaitWifiReady(context: Context, timeoutMs: Long = 60_000): Boolean {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
                ?: return false
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val net = cm.activeNetwork
                val caps = net?.let {
                    runCatching { cm.getNetworkCapabilities(it) }.getOrNull()
                }
                val hasWifi = caps != null &&
                    (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
                        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET))
                if (net != null && hasWifi) return true
                kotlinx.coroutines.delay(2_000L)
            }
            return false
        }
    }
}
