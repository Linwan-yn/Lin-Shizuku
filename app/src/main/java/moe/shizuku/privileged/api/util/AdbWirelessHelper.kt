package moe.shizuku.privileged.api.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.adb.AdbClient
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbKeyException
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException

/**
 * 1.1.2（启动失败修复）：在执行 liblin.so 之前先清一遍旧服务进程。
 *
 * 不能用 `pgrep -f 'Lin_server|...'`：执行这条命令的 `sh -c` 进程，其 cmdline
 * 里本身就带着 "Lin_server" 这几个字，pgrep -f 会匹配到它自己，接着 `kill -9`
 * 把执行命令的 shell 杀掉——命令当场结束、liblin.so 从未启动、输出全空。
 * 日志上就表现为「已连接…执行启动命令」后 180ms 就结束，没有任何输出。
 * （11:04:16 / 11:05:13 两次启动失败就是这个原因。）
 *
 * 改用 `pkill -x`：只按进程名（comm，来自 --nice-name）精确匹配，
 * 不会命中 sh，也不含 $ 变量，可以安全嵌进 `su -c "..."`。
 * pkill 不存在时由 2>/dev/null 吞掉，不影响后续启动。
 */
private const val KILL_OLD_PID_PREFIX =
    "pkill -9 -x Lin_server 2>/dev/null; " +
        "pkill -9 -x Stellar_server 2>/dev/null; " +
        "pkill -9 -x Lin_daemon 2>/dev/null; " +
        "pkill -9 -x Stellar_daemon 2>/dev/null; " +
        "sleep 1; "

/**
 * Wireless debugging helper — migrated from Lin-Shizuku `AdbWirelessHelper`.
 *
 * Provides:
 *  - [hasAdbPermission]: probe whether the ADB server is reachable with our key
 *    (i.e. the device has been paired already).
 *  - [startShizukuViaAdb]: wait for the port, connect over ADB and run the
 *    starter script, streaming the console output back to the UI.
 */
class AdbWirelessHelper {

    private fun key(ctx: Context): AdbKey = AdbKey(
        PreferenceAdbKeyStore(ctx.getSharedPreferences("adb_key", Context.MODE_PRIVATE)),
        "Lin-Shizuku"
    )

    /**
     * 79 轮：关闭 TCP 模式时恢复 adbd——向残留的固定 TCP 端口发送 `usb:`（等价官方 `adb usb`），
     * 让 adbd 回到无线调试管理的随机端口；失败（未开过 TCP / 端口不可连 / 未配对）时静默，
     * 不影响任何状态。恢复后配对页端口检测将显示真实系统无线调试端口，不再残留 5555。
     */
    fun revertTcpMode(ctx: Context, tcpPort: Int = 5555) {
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            try {
                val key = key(ctx)
                AdbClient("127.0.0.1", tcpPort, key).use { client ->
                    client.connect()
                    client.command("usb:")
                }
                Log.d("AdbWirelessHelper", "已发送 usb: 恢复 adbd 到无线调试端口")
            } catch (e: Throwable) {
                Log.w("AdbWirelessHelper", "revertTcpMode 失败（忽略）: ${e.message}")
            }
        }
    }

    /** True if we can establish an ADB connection (already paired). */
    fun hasAdbPermission(ctx: Context, host: String, port: Int): Boolean {
        if (port !in 1..65535) return false
        val key = try { key(ctx) } catch (_: Throwable) { return false }
        return try {
            AdbClient(host, port, key).use { it.connect() }
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 90bt：ADB transport 通道切换 TCP 模式（无线调试激活场景，**无需 root**）。
     *
     * 机制：不直接 setprop（普通 shell 会被 SELinux 拒），而是连上当前无线调试
     * 动态端口，发送 ADB transport 级命令 `tcpip:<port>`（开启）/ `usb:`（关闭）——
     * 由 adbd 自己执行端口切换（adbd 改自己的属性有权限，SELinux 允许自管）。
     *
     * 开启时 adbd 重启并固定监听 [port]，无线调试动态端口随之失效（二者互斥）；
     * 关闭时 adbd 回到无线调试管理，动态端口恢复。切换不影响已运行的 Shizuku 服务端。
     *
     * 校验：重启完成后重连目标端口执行 `getprop service.adb.tcp.port` 比对。
     */
    suspend fun switchTcpModeViaAdb(ctx: Context, enable: Boolean, port: Int): Boolean =
        withContext(Dispatchers.IO) {
            try {
                // 1. 定位当前无线调试动态端口（getprop → 保存端口 → mDNS 已弃用）
                val currentPort = detectAvailablePort(ctx, "127.0.0.1")
                if (currentPort !in 1..65535) {
                    moe.shizuku.privileged.api.core.DiagnosticLog.append("[TCP] transport 通道：未找到无线调试端口，跳过")
                    return@withContext false
                }
                val key = key(ctx)
                // 2. 发送 transport 命令（adbd 自己切；发送后旧连接断开属预期）
                try {
                    AdbClient("127.0.0.1", currentPort, key).use { client ->
                        client.connect()
                        if (enable) client.command("tcpip:$port")
                        else client.command("usb:")
                    }
                } catch (_: Throwable) {
                    // adbd 已在 transport 命令后重启，连接被 RST 是预期行为
                }
                // 3. 等待 adbd 重启完成，重连校验 getprop（附带设置 persist 持久化，失败静默）
                val verifyPort = if (enable) port else currentPort
                delay(1500)
                var ok = false
                repeat(5) {
                    try {
                        AdbClient("127.0.0.1", verifyPort, key).use { client ->
                            client.connect()
                            val sb = StringBuilder()
                            client.shellCommand(
                                "setprop persist.adb.tcp.port $port; getprop service.adb.tcp.port",
                                { chunk -> sb.append(String(chunk)) },
                                timeoutMs = 5_000
                            )
                            val v = sb.toString().trim()
                            ok = if (enable) v == port.toString()
                            else v == "0" || v.isEmpty()
                            if (ok) return@repeat
                        }
                    } catch (_: Throwable) {}
                    delay(1000)
                }
                moe.shizuku.privileged.api.core.DiagnosticLog.append(
                    "[TCP] transport 通道${if (enable) "开启（端口 $port）" else "关闭"}校验: ${if (ok) "成功" else "失败"}"
                )
                ok
            } catch (e: Throwable) {
                moe.shizuku.privileged.api.core.DiagnosticLog.append("[TCP] transport 通道异常: ${e.message}")
                false
            }
        }

    private fun waitForAdbPortAvailable(host: String, port: Int, timeoutMs: Long): Boolean {
        val intervalMs = 500L
        var elapsed = 0L
        while (elapsed < timeoutMs) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), 2000)
                }
                return true
            } catch (_: Exception) {
                elapsed += intervalMs
                try { Thread.sleep(intervalMs) } catch (_: InterruptedException) {}
            }
        }
        return false
    }

    /**
     * Connect to the wireless ADB server and run the starter command.
     *
     * TCP mode (thedjchi feature): when [tcpMode] is on and the detected port
     * differs from [tcpPort], the adbd daemon is first switched to a fixed TCP
     * port via the `tcpip:<port>` transport command, then reconnected on that
     * port to start the service. After the first successful start, Shizuku can
     * be restarted over 127.0.0.1 even with Wi-Fi / wireless debugging off.
     *
     * [onOutput] receives raw chunks of the shell output (called on an IO thread).
     * [onError] is invoked when the connection or the command fails.
     * [onSuccess] is invoked when the shell stream finished (EOFException is normal:
     * the server process has forked and the shell exited).
     *
     * A2（90n 实现）：[useSu] =「无线调试提权 su」。开启后先检测 adb shell 当前身份：
     *  - `id -u` 输出 0（adb 以 root 运行）→ 直接以 root 启动服务端；
     *  - 否则尝试 `su -c id -u`（有 su 可用）→ 以 `su -c` 提权启动服务端（root 身份）；
     *  - 均不可用 → 普通 shell 身份启动，并在输出中注明（不再静默）。
     */
    fun startShizukuViaAdb(
        ctx: Context,
        host: String,
        port: Int,
        coroutineScope: CoroutineScope,
        onOutput: (String) -> Unit,
        onError: (Throwable) -> Unit,
        onSuccess: () -> Unit = {},
        tcpMode: Boolean = false,
        tcpPort: Int = 5555,
        useSu: Boolean = false
    ) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val key = try {
                    key(ctx)
                } catch (e: Throwable) {
                    moe.shizuku.privileged.api.core.DiagnosticLog.append("[启动] ADB 密钥加载失败: ${e.message}")
                    onError(AdbKeyException(e))
                    return@launch
                }
                moe.shizuku.privileged.api.core.DiagnosticLog.append("[启动] 密钥已加载，等待 ADB 端口 $port 可用...")

                if (!waitForAdbPortAvailable(host, port, timeoutMs = 15_000L)) {
                    moe.shizuku.privileged.api.core.DiagnosticLog.append("[启动] 等待 ADB 端口 $port 可用超时")
                    onError(SocketTimeoutException("等待 ADB 端口 $port 可用超时"))
                    return@launch
                }
                moe.shizuku.privileged.api.core.DiagnosticLog.append("[启动] ADB 端口 $port 可用，开始连接（TLS 握手）...")

                // 90bt：TCP 模式（thedjchi 特性）真正接入启动链路——
                // [tcpMode]=true 且当前端口不是固定端口时，先连当前端口发送
                // `tcpip:<tcpPort>` transport 命令让 adbd 切到固定端口，等待
                // 固定端口可连后再执行 starter。之后服务端掉线/重启都走
                // 127.0.0.1:<tcpPort>，无需无线调试动态端口（v5.3 曾因等待
                // 新端口不可靠而禁用，现改为「发送后等固定端口可连」的完整切换）。
                var activePort = port
                if (tcpMode && tcpPort in 1..65535 && port != tcpPort) {
                    onOutput("TCP 模式：正在切换 ADB 到固定端口 $tcpPort...\n")
                    moe.shizuku.privileged.api.core.DiagnosticLog.append("[启动] TCP 模式切换 $port → $tcpPort")
                    try {
                        AdbClient(host, port, key).use { client ->
                            client.connect()
                            client.command("tcpip:$tcpPort")
                        }
                    } catch (_: EOFException) {
                        // adbd 收到 transport 命令后重启，旧连接断开属预期
                    } catch (_: SocketException) {
                        // 同上：Connection reset
                    } catch (e: Throwable) {
                        moe.shizuku.privileged.api.core.DiagnosticLog.append("[启动] TCP 切换命令发送异常: ${e.message}")
                        onOutput("TCP 切换失败，继续使用原端口 $port\n")
                    }
                    if (waitForAdbPortAvailable(host, tcpPort, timeoutMs = 15_000L)) {
                        activePort = tcpPort
                        moe.shizuku.privileged.api.core.DiagnosticLog.append("[启动] 已切换固定端口 $tcpPort，继续启动")
                    } else {
                        moe.shizuku.privileged.api.core.DiagnosticLog.append("[启动] 等待固定端口 $tcpPort 可用超时，回退原端口 $port")
                        onOutput("等待固定端口 $tcpPort 超时，回退原端口 $port\n")
                    }
                }

                val startupLog = StringBuilder()
                try {
                    AdbClient(host, activePort, key).use { client ->
                        // v4.18：移植官方 AdbStarter.connectWithRetry——连接端口刚探测过，
                        // 但正式连接可能因 adbd 状态/并发连接被瞬断，官方 5 次指数退避重试
                        // 是无线调试稳定连接的关键（实测探测成功但正式连接失败→服务未启动）。
                        connectWithRetry(client)
                        moe.shizuku.privileged.api.core.DiagnosticLog.append("[启动] 已连接 127.0.0.1:$activePort，执行启动命令...")
                        val baseCmd = Starter.getAdbShellCommand(ctx)
                        moe.shizuku.privileged.api.core.DiagnosticLog.append("[启动] 启动命令: ${baseCmd.take(160)}...")
                        // A2（90n 实现）：无线调试提权 su —— 检测身份并按可用性提权
                        var cmd = KILL_OLD_PID_PREFIX + baseCmd
                        if (useSu) {
                            val uid = shellFirstLine(client, "id -u")?.trim()
                            when {
                                uid == "0" -> {
                                    onOutput("已检测到 adb 以 Root 身份运行（id -u=0），直接启动服务端\n")
                                }
                                shellFirstLine(client, "su -c id -u 2>/dev/null")?.trim() == "0" -> {
                                    onOutput("检测到 su 可用，将以 Root 身份提权启动服务端\n")
                                    cmd = "su -c \"${KILL_OLD_PID_PREFIX}${baseCmd}\""
                                }
                                else -> {
                                    onOutput("未检测到 su（无线调试为普通 Shell 身份），将以 Shell 身份启动服务端\n")
                                }
                            }
                        }
                        try {
                            client.shellCommand(cmd, { chunk ->
                                val text = String(chunk)
                                startupLog.append(text)
                                onOutput(text)
                                moe.shizuku.privileged.api.core.DiagnosticLog.append("[启动] 命令输出: ${text.trim().take(200)}")
                                moe.shizuku.privileged.api.core.ShizukuManager.appendStartupLog(text.trim())
                            }, timeoutMs = 60_000)
                        } catch (_: EOFException) {
                            // shell stream closed — the server has forked, this is normal
                        } catch (_: SocketException) {
                            // 90bh：Connection reset 与 EOF 同义——adbd 在 start.sh 执行完
                            // （setsid nohup 已 fork 服务）后关闭 shell 会话的方式因 ROM 而异
                            // （EOF / RST 都有）。此前直接 onError → 服务实际已拉起却显示
                            // 「服务启动失败 Connection reset」。改为继续走下方输出校验：
                            // 有启动成功输出 → 成功；有明确失败标志 → 失败；否则保守判成功
                            // （后续 waitForService Binder 轮询会兜底验证，起不来仍会报失败）。
                        } catch (_: IllegalStateException) {
                            // AdbClient 收到非标准消息类型时的传输层错误：
                            // 命令已发出、服务已 fork，后续消息格式不影响启动结果
                            // （否则会出现"报错但服务已启动"的矛盾）
                        }
                        // 拉取服务端内部日志（/data/local/tmp/Lin_server.log），让启动失败原因可见
                        runCatching {
                            client.shellCommand(
                                "echo \"── 服务端日志(/data/local/tmp) ──\"; cat /data/local/tmp/Lin_server.log 2>/dev/null | tail -60; echo \"── 诊断日志(专属目录) ──\"; cat /storage/emulated/0/Android/data/moe.shizuku.privileged.api/files/日志.log 2>/dev/null | tail -60",
                                { chunk ->
                                    val text = String(chunk)
                                    onOutput("── 服务端日志 ──\n" + text)
                                    moe.shizuku.privileged.api.core.ShizukuManager.appendStartupLog("服务端日志: " + text.trim())
                                },
                                timeoutMs = 8_000
                            )
                        }
                        // 90bu：激活成功（启动命令已执行、Server 已 fork）后，若开启 TCP 模式，
                        // 通过当前连接发 transport `tcpip:<port>` 建立「固定端口重启通道」。
                        // 时序关键：此刻 App 已不再依赖该 adb 连接（后续全靠 Binder），
                        // adbd 重启不会中断任何正在使用的通道；之后重启/拉起服务无需无线调试
                        // 动态端口，直连 127.0.0.1:<port> 即可（detectAvailablePort 优先 systemPort）。
                        // （v5.3 的教训是「启动命令执行前」切端口导致连接中断；现在切在启动成功后，安全。）
                        if (moe.shizuku.privileged.api.core.Prefs.tcpMode) {
                            runCatching {
                                val tcpPort = moe.shizuku.privileged.api.core.Prefs.tcpPort
                                client.command("tcpip:$tcpPort")
                                moe.shizuku.privileged.api.core.DiagnosticLog.append("[TCP] 激活完成，已建立固定端口 $tcpPort 重启通道（transport tcpip）")
                                onOutput("已建立 TCP 固定端口 $tcpPort 重启通道\n")
                            }
                        }
                        // 73 轮修复：移除「关闭无线调试开关（adb_wifi_enabled 0）」。
                        // 原设计意图是启动成功后关掉无线调试 UI 开关省电/整洁，
                        // 但 adbd 关闭 WiFi 监听会中断正在经 adb shell 执行的启动脚本，
                        // 服务进程无法完整拉起 → 应用不显示「已连接」，且无线调试开关被自动关闭
                        // （REDMI/小米等设备实测）。无线调试应保持开启，便于后续重连与重启服务；
                        // TCP 固定端口（tcpip）已独立提供 127.0.0.1 重启通道，无需关闭无线调试。
                    }
                } catch (_: EOFException) {
                    // shell stream closed — the server has forked, this is normal
                } catch (e: Throwable) {
                    onError(e)
                    return@launch
                }

                // 校验 starter 输出（90as：新老脚本关键字全兼容）：
                //  - 失败标志：start.sh「启动失败：…」、fallback 命令 START-FAILED、以及既有 错误：/权限不足/SELinux
                //  - 成功标志：start.sh「启动成功：…新 PID」、fallback 命令 RUNNING-OK、以及既有 正常退出/进程号
                //  - 两者皆无 → 保守输出兜底等待文案后判成功（服务端可能已 fork）
                val log = startupLog.toString()
                moe.shizuku.privileged.api.core.DiagnosticLog.append("[启动] 启动命令输出汇总: ${log.trim().take(300)}")
                if (log.contains("错误：") || log.contains("权限不足") || log.contains("SELinux")
                    || log.contains("启动失败") || log.contains("START-FAILED")
                    || log.contains("fatal:")
                ) {
                    moe.shizuku.privileged.api.core.DiagnosticLog.append("[启动] 启动失败标志命中: ${log.trim().lines().lastOrNull()}")
                    onError(RuntimeException(log.trim().lines().lastOrNull() ?: "启动失败"))
                    return@launch
                }
                if (log.contains("启动成功") || log.contains("RUNNING-OK")
                    || log.contains("正常退出") || log.contains("进程号") || log.contains("新 PID")
                    || log.contains("exit with 0")
                ) {
                    moe.shizuku.privileged.api.core.DiagnosticLog.append("[启动] 服务端已启动（RUNNING-OK/新 PID）")
                    onOutput("服务进程已启动，正在等待 Binder 就绪...\n")
                    onSuccess()
                    return@launch
                }
                if (log.isNotEmpty()) {
                    // 有输出但无成功也无明确错误（如超时未完成），保守提示
                    moe.shizuku.privileged.api.core.DiagnosticLog.append("[启动] 命令有输出但未确认成功，保守判成功等待 Binder")
                    onOutput("启动命令已发出，正在等待服务 Binder 就绪...\n")
                }
                onSuccess()
            } catch (e: Throwable) {
                moe.shizuku.privileged.api.core.DiagnosticLog.append("[启动] 启动链路异常: ${e::class.java.simpleName}: ${e.message}")
                onError(e)
            }
        }
    }

    /** 在已连接的 adb shell 上执行单行命令并返回首行输出（A2 提权检测用）。 */
    private fun shellFirstLine(client: AdbClient, command: String): String? {
        return try {
            val sb = StringBuilder()
            client.shellCommand(command, { chunk -> sb.append(String(chunk)) }, timeoutMs = 5_000)
            sb.toString().trim()
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * v4.18：移植官方 AdbStarter.connectWithRetry——TCP 连接 5 次指数退避重试。
     * 官方无线调试激活在每次正式连接前都用它（0/1s/2s/3s/4s），
     * 防 adbd 在探测/切端口后瞬时拒绝新连接；超时类异常不重试直接抛。
     */
    private suspend fun connectWithRetry(client: AdbClient) {
        var delayTime = 0L
        val maxAttempts = 5
        for (attempt in 1..maxAttempts) {
            try {
                delay(delayTime)
                client.connect()
                return
            } catch (e: Exception) {
                if (attempt == maxAttempts || e is kotlinx.coroutines.CancellationException || e is SocketTimeoutException) {
                    throw e
                }
                delayTime += 1000
            }
        }
    }

    /**
     * 智能检测当前可用的无线调试端口（首页「启动」/「重启服务」按钮用）。
     *
     * 优先级：
     *  1. 系统属性 service.adb.tcp.port（tcpip 模式固定端口）
     *  2. 上次保存的配对端口（boot_auto/last_adb_port）
     *  3. 配置的固定 TCP 端口（Prefs.tcpPort，历史 tcpip 通道；adbd 可能仍在 lo 监听，
     *     无线调试开关/WiFi 关闭后 loopback 通道仍可连）
     *  4. mDNS 实时检测连接端口（无线调试开启时的随机端口，已弃用）
     *
     * 每个候选端口都用 hasAdbPermission 验证真正可连（密钥已授权），
     * 避免端口号正确但密钥未配对的假阳性。
     *
     * @return 可用端口（1..65535），0 表示未检测到可用端口。
     */
    suspend fun detectAvailablePort(ctx: Context, host: String = "127.0.0.1"): Int = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val key = try { key(ctx) } catch (_: Throwable) { return@withContext 0 }

        // 1. 系统属性端口（tcpip 固定端口模式）
        val systemPort = moe.shizuku.privileged.api.util.EnvironmentUtils.getAdbTcpPort()
        if (systemPort in 1..65535) {
            if (tryConnect(host, systemPort, key)) return@withContext systemPort
        }

        // 2. 上次保存的配对端口
        val savedPort = ctx.getSharedPreferences("boot_auto", android.content.Context.MODE_PRIVATE)
            .getInt("last_adb_port", 0)
        if (savedPort in 1..65535 && savedPort != systemPort) {
            if (tryConnect(host, savedPort, key)) return@withContext savedPort
        }

        // 3. 配置的固定 TCP 端口（历史 tcpip 通道；未切过时该端口无监听，tryConnect 自会失败）
        val fixedPort = moe.shizuku.privileged.api.core.Prefs.tcpPort
        if (fixedPort in 1..65535 && fixedPort != systemPort && fixedPort != savedPort) {
            if (tryConnect(host, fixedPort, key)) return@withContext fixedPort
        }

        // 1.1.1：移除 mDNS（NsdManager）兜底——Android 16+ 本地网络保护下会触发
        // ACCESS_LOCAL_NETWORK 权限弹窗且 HyperOS 丢多播包易失败。无线调试连接端口
        // 已由 getAdbTcpPort() 直接读取（service.adb.wifi.tcp.port / adb_wifi_port）。
        // 读不到时返回 0，由上层引导用户手动输入端口，不再扫描。

        0
    }

    /** 尝试连接指定端口，验证密钥授权。 */
    private fun tryConnect(host: String, port: Int, key: AdbKey): Boolean {
        return try {
            AdbClient(host, port, key).use { it.connect() }
            true
        } catch (_: Throwable) {
            false
        }
    }
}
