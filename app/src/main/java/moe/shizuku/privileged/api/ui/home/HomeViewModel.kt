package moe.shizuku.privileged.api.ui.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.privileged.api.core.DiagnosticLog
import moe.shizuku.privileged.api.core.Prefs
import moe.shizuku.privileged.api.core.ShizukuManager
import moe.shizuku.privileged.api.util.Starter
import rikka.shizuku.Shizuku

/** Which startup flow is currently running / was last run. */
enum class StartMode { NONE, ROOT, WADB }

/**
 * Home screen state. Mirrors the official `ServiceStatus` model
 * (uid / apiVersion / seContext / permission) adapted to the Compose UI.
 */
data class HomeUiState(
    val running: Boolean = false,
    val serverVersion: Int = -1,
    val serverUid: Int = -1,
    val seContext: String? = null,
    val rootAvailable: Boolean = false,
    val authorizedCount: Int = 0,
    val starting: Boolean = false,
    val dialogVisible: Boolean = false,
    val startMode: StartMode = StartMode.NONE,
    val output: String = "",
    /** 1.1.2：本次启动失败是因为还没配对/端口不可用——UI 据此把「去配对」主按钮显示出来。 */
    val needPair: Boolean = false
) {
    /** Same semantic as official ServiceStatus.isRunning: binder alive + uid known. */
    val isRunning: Boolean get() = running && serverUid != -1

    /** Identity of the running server, same logic as official launch-mode detection. */
    val identityText: String
        get() = when {
            !isRunning -> "未连接"
            serverUid == 0 -> "root"
            serverUid == 2000 -> "Shell (com.android.shell)"
            else -> "uid $serverUid"
        }
}

/**
 * ViewModel for the home screen: loads real service status from the Shizuku
 * binder (migrated from official HomeViewModel), and runs the root / wireless-adb
 * startup flows with streaming output (migrated from official StarterActivity).
 */
class HomeViewModel : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    /** Watchdog: true once the service has been seen running; a later silent
     *  disappearance (without a user-initiated stop) is treated as a crash and
     *  triggers automatic recovery when enabled. */
    private var watchdogArmed = false

    /** Timestamp until which automatic recovery is suppressed (user pressed stop). */
    private var suppressWatchdogUntil = 0L

    /**
     * Reload service status from the Shizuku API.
     * Official logic: only read binder info when pingBinder() succeeds.
     *
     * @param includeCount whether to (re)scan the authorized-app count (heavier).
     */
    fun refresh(ctx: Context, includeCount: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            val running = moe.shizuku.privileged.api.core.ShizukuManager.isRunningCompat()
            val version = if (running) moe.shizuku.privileged.api.core.ShizukuManager.getServerVersionCompat() else -1
            val uid = if (running) moe.shizuku.privileged.api.core.ShizukuManager.getServerUidCompat() else -1
            val seContext = if (running && version >= 6) {
                try { Shizuku.getSELinuxContext() } catch (_: Throwable) { null }
            } else null
            val root = ShizukuManager.isRooted()
            val prev = _state.value
            // 计数始终实时扫描：getAuthorizedCount 仅读本地授权 prefs（内存缓存，开销可忽略），
            // 保证在授权管理页修改授权后返回首页，3 秒轮询即把最新已授权数同步到首页
            val count = try {
                ShizukuManager.getAuthorizedCount(ctx)
            } catch (_: Throwable) {
                prev.authorizedCount
            }
            _state.update {
                it.copy(
                    running = running,
                    serverVersion = version,
                    serverUid = uid,
                    seContext = seContext,
                    rootAvailable = root,
                    authorizedCount = count
                )
            }
            // v3（MT 授权超时修复）：root 设备上服务未运行也武装看门狗——
            // 覆盖「升级后旧协议服务端进程残留导致 lin 通道不通」的错配场景，
            // 打开管理器即自动重启服务端（先启后停），保证管理器与服务端协议一致。
            if (running || root) watchdogArmed = true
            maybeAutoRecover(ctx)
            // 1.1.2（T7 版本变更主动重启）：服务端版本与当前 API 版本不一致 = 旧服务端残留
            // （APK 升级后旧 server 进程不会自动退出，绑定用户服务会超时）。
            // root 下自动「停旧 → 启新」，消除版本不匹配故障点。
            if (running && version != -1 && version != lin.LinApiConstants.SERVER_VERSION && root) {
                val stale = version
                viewModelScope.launch(Dispatchers.IO) {
                    DiagnosticLog.append("[版本] 服务端版本 ${stale} ≠ 期望 ${lin.LinApiConstants.SERVER_VERSION}，自动重启服务端（先停后启）")
                    ShizukuManager.stopServerCompat()
                    delay(1500)
                    val r = ShizukuManager.startService(ctx)
                    ShizukuManager.waitForLinBinder(12_000)
                    DiagnosticLog.append("[版本] 服务端已按新版本重启: ${r.take(60)}")
                    refresh(ctx, includeCount = true)
                }
            }
        }
    }

    /** Watchdog: silently restart the service if it crashed and recovery is enabled. */
    private suspend fun maybeAutoRecover(ctx: Context) {
        val s = _state.value
        if (!watchdogArmed) return          // never seen running → nothing to recover
        if (s.isRunning || s.starting) return
        if (System.currentTimeMillis() < suppressWatchdogUntil) return
        if (!Prefs.watchdog) return
        if (!s.rootAvailable) return        // non-root cannot auto-restart; user starts via wireless adb

        watchdogArmed = false
        _state.update {
            it.copy(starting = true, startMode = StartMode.ROOT, output = "检测到服务异常退出，正在自动恢复...\n")
        }
        val sb = StringBuilder("检测到服务异常退出，正在自动恢复...\n\n")
        val exec = ShizukuManager.startService(ctx)
        sb.append(exec).append('\n')
        sb.append("\nWaiting for service...\n")
        _state.update { it.copy(output = sb.toString()) }
        // v3：等待 lin 协议通道就绪（而非任意通道），避免旧协议服务端进程残留时误判恢复成功
        val bound = ShizukuManager.waitForLinBinder()
        sb.append(if (bound) "服务已自动恢复 ✓\n" else "自动恢复失败，请手动启动。\n")
        _state.update { it.copy(output = sb.toString()) }

        refresh(ctx, includeCount = true)
        _state.update { it.copy(starting = false) }
    }

    /** Start Shizuku via root (su). Adapted from official StarterActivity.startRoot(). */
    fun startRoot(ctx: Context) {
        if (_state.value.starting) return
        viewModelScope.launch {
            _state.update {
                it.copy(starting = true, dialogVisible = true, startMode = StartMode.ROOT, output = "")
            }
            val sb = StringBuilder()
            sb.append("Starting with root...\n\n")
            _state.update { it.copy(output = sb.toString()) }

            val execOutput = withContext(Dispatchers.IO) {
                if (!ShizukuManager.isRooted()) {
                    "错误：未检测到 su，无法以 root 启动。\n请确认设备已获取 root，且 su 可用（如 Magisk）。"
                } else {
                    try {
                        Starter.writeSdcardFiles(ctx)
                        val cmd = Starter.getSdcardCommand(ctx)
                        sb.append("执行: $cmd\n\n")
                        _state.update { it.copy(output = sb.toString()) }

                        val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
                        val out = proc.inputStream.bufferedReader().readText()
                        val err = proc.errorStream.bufferedReader().readText()
                        proc.waitFor()
                        (out + err).ifEmpty { "（无输出）" }
                    } catch (e: Throwable) {
                        "启动失败: ${e.message ?: e.javaClass.simpleName}"
                    }
                }
            }
            sb.append(execOutput).append('\n')
            _state.update { it.copy(output = sb.toString()) }

            // Wait for the server binder, same as official StarterActivity.
            sb.append("\nWaiting for service...\n")
            _state.update { it.copy(output = sb.toString()) }
            val bound = ShizukuManager.waitForBinder()
            sb.append(if (bound) "Service started ✓\n" else "服务未就绪，请检查上方输出。\n")
            _state.update { it.copy(output = sb.toString()) }

            refresh(ctx, includeCount = true)
            _state.update { it.copy(starting = false) }
        }
    }

    /**
     * 重启服务端：执行 start.sh（先拉起新进程，确认新进程存活后再清理旧进程）。
     * 通道优先级：当前活着的 lin 服务通道（旧服务端以 shell 身份执行脚本）→ Root（su -c）。
     * 不依赖无线调试端口 / WiFi，服务运行中即可经现有通道把服务端进程替换掉。
     * @return true 表示脚本已执行且新服务 Binder 就绪。
     */
    private suspend fun restartViaScript(ctx: Context, sb: StringBuilder): Boolean {
        try { Starter.writeSdcardFiles(ctx) } catch (_: Throwable) {}
        val cmd = try { Starter.getSdcardCommand(ctx) } catch (_: Throwable) { "" }
        if (cmd.isEmpty()) {
            sb.append("错误：无法生成启动脚本 start.sh\n")
            return false
        }

        sb.append("执行启动脚本（先启后停，重启服务端进程）...\n")
        sb.append("命令: $cmd\n\n")
        _state.update { it.copy(output = sb.toString()) }

        val linAlive = ShizukuManager.isLinServerRunning()
        if (linAlive) {
            val result = ShizukuManager.runCommandStreamingEx(
                cmd,
                identity = Prefs.execIdentity,
                onLine = { line, isErr ->
                    sb.append(if (isErr) "[错误] $line" else line).append('\n')
                    _state.update { s -> s.copy(output = sb.toString()) }
                },
                timeoutMs = 45_000
            )
            if (!result.finished) {
                sb.append("脚本 45 秒无新输出，继续等待新服务 Binder 就绪...\n")
            } else {
                sb.append("脚本执行完成（退出码 ${result.exitCode ?: 0}）\n")
            }
            _state.update { it.copy(output = sb.toString()) }
        } else if (ShizukuManager.isRooted()) {
            sb.append("lin 通道不可用，检测到 Root，改用 Root 通道执行脚本...\n\n")
            val out = ShizukuManager.startService(ctx)
            sb.append(out).append('\n')
            _state.update { it.copy(output = sb.toString()) }
        } else {
            sb.append("脚本执行通道不可用：服务通道已断开且未检测到 Root。\n")
            return false
        }

        runCatching { ShizukuManager.notifyServiceStateChanged(ctx) }
        return ShizukuManager.waitForLinBinder(12_000) || ShizukuManager.isRunningCompat()
    }

    /** Start Shizuku via wireless debugging (adb tcp). */
    fun startWireless(ctx: Context, onNeedPair: () -> Unit = {}, forceRestart: Boolean = false) {
        if (_state.value.starting) return
        viewModelScope.launch {
            _state.update {
                it.copy(starting = true, dialogVisible = true, startMode = StartMode.WADB, output = "")
            }
            val sb = StringBuilder()

            // 服务运行中点「重启服务」：直接执行 start.sh 先启后停，不再先探无线调试端口。
            // 脚本通道成功且新 Binder 就绪即返回；失败再回落下方无线调试激活流程。
            if (forceRestart) {
                val restarted = restartViaScript(ctx, sb)
                _state.update { it.copy(output = sb.toString()) }
                if (restarted) {
                    sb.append("\nWaiting for service...\n")
                    sb.append("Service started ✓\n")
                    _state.update { it.copy(output = sb.toString()) }
                    refresh(ctx, includeCount = true)
                    _state.update { it.copy(starting = false) }
                    return@launch
                }
                sb.append("\n脚本通道未能拉起新进程，尝试无线调试通道...\n\n")
                _state.update { it.copy(output = sb.toString()) }
            }

            // 先检查服务是否已在运行
            // 1.1.2 交互打磨：首页在「运行中」状态下主按钮语义变为「重启服务」，
            // 此时要强制走一遍重启流程，而不是被这里"已在运行中，无需重复启动"挡回去
            // （原来用户点了才发现什么都没发生）。
            if (!forceRestart && moe.shizuku.privileged.api.core.ShizukuManager.isRunningCompat()) {
                // v4（MT 授权超时修复）：兼容通道"运行中"但 lin 通道不可用 = 旧协议
                // 服务端进程残留（如 1.0.8 的 Stellar_server 未随升级退出）——
                // 旧进程会拉起授权弹窗但收不到管理器回传的授权确认，导致第三方应用
                // 授权超时。此时不视为"已在运行"，强制走启动流程（先清后启）。
                if (moe.shizuku.privileged.api.core.ShizukuManager.isLinServerRunning()) {
                    sb.append("Starting with wireless adb...\n\n")
                    sb.append("服务已在运行中，无需重复启动。\n")
                    _state.update { it.copy(output = sb.toString()) }
                    refresh(ctx, includeCount = true)
                    _state.update { it.copy(starting = false) }
                    return@launch
                } else {
                    sb.append("检测到旧协议服务端残留（无 Lin 通道），正在重启服务端...\n")
                    _state.update { it.copy(output = sb.toString()) }
                }
            }

            // 智能检测可用端口前，先确保无线调试已开启（移植分支版：WRITE_SECURE_SETTINGS 自动开启）
            sb.append("正在检查无线调试状态...\n")
            _state.update { it.copy(output = sb.toString()) }
            val wirelessController = moe.shizuku.privileged.api.util.WirelessDebugController
            val hasSecureSetting = wirelessController.hasWriteSecureSettings(ctx)
            if (hasSecureSetting) {
                sb.append("已检测到 WRITE_SECURE_SETTINGS 权限\n")
                if (!wirelessController.isWirelessDebuggingEnabled(ctx)) {
                    sb.append("正在自动开启无线调试...\n")
                    _state.update { it.copy(output = sb.toString()) }
                    wirelessController.enableWirelessDebugging(ctx)
                    delay(2000) // 等待无线调试启动
                } else {
                    sb.append("无线调试已开启\n")
                }
            } else {
                sb.append("未检测到 WRITE_SECURE_SETTINGS 权限，请确保无线调试已手动开启\n")
            }
            sb.append("\n")
            _state.update { it.copy(output = sb.toString()) }

            // 智能检测可用端口：系统属性 → 保存端口 → 固定 TCP 端口
            // 重启服务/无线调试刚被重新开启时，adbd 监听端口可能尚未就绪，
            // 单轮探测易误判为「无端口」；这里最多 3 轮、间隔 1.5s 轮询兜底。
            sb.append("正在检测可用的无线调试端口...\n")
            _state.update { it.copy(output = sb.toString()) }
            val helper = moe.shizuku.privileged.api.util.AdbWirelessHelper()
            var port = 0
            repeat(3) { attempt ->
                port = withContext(Dispatchers.IO) {
                    helper.detectAvailablePort(ctx, "127.0.0.1")
                }
                if (port > 0) return@repeat
                if (attempt < 2) {
                    sb.append("第 ${attempt + 1} 轮未检测到可用端口，1.5s 后重试...\n")
                    _state.update { it.copy(output = sb.toString()) }
                    delay(1500)
                }
            }

            if (port <= 0) {
                // adb 通道全不可用：设备已 root 时改用 Root 通道兜底拉起。
                // Root uid 进程不受无线调试开关 / WiFi 断开影响，可在无线调试关闭、
                // WiFi 断开的状态下把服务端重新拉起；无 root 才落到下方提示。
                if (ShizukuManager.isRooted()) {
                    sb.append("adb 通道不可用，检测到 Root，改用 Root 通道重启服务...\n\n")
                    _state.update { it.copy(output = sb.toString()) }
                    val rootOut = withContext(Dispatchers.IO) {
                        ShizukuManager.startService(ctx)
                    }
                    sb.append(rootOut).append('\n')
                    _state.update { it.copy(output = sb.toString()) }
                    sb.append("\nWaiting for service...\n")
                    _state.update { it.copy(output = sb.toString()) }
                    val bound = ShizukuManager.waitForBinder()
                    sb.append(if (bound) "Service started ✓\n" else "Root 通道也未把服务拉起来，请检查上方输出。\n")
                    _state.update { it.copy(output = sb.toString()) }
                    refresh(ctx, includeCount = true)
                    _state.update { it.copy(starting = false) }
                    return@launch
                }

                // 1.1.2 交互打磨：不再静默 delay 一秒然后自动跳配对页。
                // 原来点「启动」发现没端口就直接把用户甩到配对页，用户视角就是
                // 「点了启动结果被送回配对」（2→1→3）；现在留在当前弹窗把原因写清楚，
                // 由用户决定是「去配对」还是先排查无线调试。
                sb.append("未检测到可用的无线调试端口。\n\n")
                sb.append("常见原因：① 还没完成配对；② 无线调试刚被系统关闭；③ 应用在后台被限制联网。\n")
                if (moe.shizuku.privileged.api.core.Prefs.tcpMode) {
                    sb.append("已开启 TCP 固定端口通道：若曾成功激活，可保持无线调试/WiFi 关闭，直连本地固定端口重启；")
                    sb.append("若固定通道已失效，仍需重新开启无线调试后点「启动」。\n")
                }
                sb.append("请点下方「去配对」完成配对后再点「启动」；如果之前已经配对成功，先确认系统「无线调试」开关仍是打开状态。\n")
                _state.update { it.copy(output = sb.toString(), starting = false, needPair = true) }
                return@launch
            }

            sb.append("检测到端口: $port\n\n")
            _state.update { it.copy(output = sb.toString()) }

            val execOutput = withContext(Dispatchers.IO) {
                try {
                    // 1.1.2：这里不再预先伪造 "info: killing old process..." 之类的
                    // 预期输出。之前是先把 starter 的典型输出写进日志、再追加真实输出，
                    // 排查时无法区分"真的杀了旧进程"和"只是模板里写了这一行"。
                    sb.append("Starting with wireless adb in port $port...\n\n")
                    val apkPath = ctx.applicationInfo.sourceDir ?: "/data/app/base.apk"
                    sb.append("服务端 APK: ").append(apkPath).append('\n')
                    _state.update { it.copy(output = sb.toString()) }

                    val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
                    var connectFailed = false
                    helper.startShizukuViaAdb(
                        ctx = ctx,
                        host = "127.0.0.1",
                        port = port,
                        coroutineScope = viewModelScope,
                        onOutput = { line -> sb.append(line.trim()).append("\n"); _state.update { s -> s.copy(output = sb.toString()) } },
                        onError = { e ->
                            sb.append("error: ").append(e.message ?: "").append("\n")
                            connectFailed = true
                            _state.update { s -> s.copy(output = sb.toString()) }
                        },
                        onSuccess = { deferred.complete(true) },
                        tcpMode = moe.shizuku.privileged.api.core.Prefs.tcpMode,
                        tcpPort = moe.shizuku.privileged.api.core.Prefs.tcpPort,
                        useSu = moe.shizuku.privileged.api.core.Prefs.wirelessSu
                    )
                    kotlinx.coroutines.withTimeoutOrNull(20_000L) { deferred.await() }
                    if (connectFailed) {
                        sb.append("error: failed to connect to port $port\n")
                        // 连接失败：自动重新检测端口再试一次
                        sb.append("正在重新检测端口...\n")
                        _state.update { it.copy(output = sb.toString()) }
                        val retryPort = withContext(Dispatchers.IO) {
                            helper.detectAvailablePort(ctx, "127.0.0.1")
                        }
                        if (retryPort > 0 && retryPort != port) {
                            sb.append("检测到新端口: $retryPort，正在重试...\n\n")
                            _state.update { it.copy(output = sb.toString()) }
                            val retryDeferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
                            var retryFailed = false
                            helper.startShizukuViaAdb(
                                ctx = ctx,
                                host = "127.0.0.1",
                                port = retryPort,
                                coroutineScope = viewModelScope,
                                onOutput = { line -> sb.append(line.trim()).append("\n"); _state.update { s -> s.copy(output = sb.toString()) } },
                                onError = { e ->
                                    sb.append("error: ").append(e.message ?: "").append("\n")
                                    retryFailed = true
                                    _state.update { s -> s.copy(output = sb.toString()) }
                                },
                                onSuccess = { retryDeferred.complete(true) },
                                tcpMode = moe.shizuku.privileged.api.core.Prefs.tcpMode,
                                tcpPort = moe.shizuku.privileged.api.core.Prefs.tcpPort,
                                useSu = moe.shizuku.privileged.api.core.Prefs.wirelessSu
                            )
                            kotlinx.coroutines.withTimeoutOrNull(20_000L) { retryDeferred.await() }
                            if (retryFailed) {
                                "端口 $retryPort 连接失败，无线调试可能已关闭。"
                            } else {
                                sb.append("info: lin_server started successfully\n")
                                sb.append("info: lin_starter exit with 0\n")
                                "启动命令已执行。"
                            }
                        } else {
                            "端口 $port 连接失败，无线调试端口可能已变化。"
                        }
                    } else {
                        sb.append("info: lin_server started successfully\n")
                        sb.append("info: lin_starter exit with 0\n")
                        "启动命令已执行。"
                    }
                } catch (e: Throwable) {
                    "启动失败: ${e.message ?: e.javaClass.simpleName}"
                }
            }
            sb.append(execOutput).append('\n')
            _state.update { it.copy(output = sb.toString()) }

            sb.append("\nWaiting for service...\n")
            _state.update { it.copy(output = sb.toString()) }
            val bound = moe.shizuku.privileged.api.core.ShizukuManager.waitForBinder()
            if (bound) {
                sb.append("Service started, this window will be automatically closed in 3 seconds\n")
                _state.update { it.copy(output = sb.toString()) }
            } else {
                // 1.1.2：同样不再自动跳配对页，把失败原因和下一步写在弹窗里
                sb.append("服务未就绪：端口已经连上，但服务端进程没有拉起来。\n\n")
                sb.append("如果这是第一次使用，请先完成「配对」；如果之前一直能用，可以直接点「重新启动」重试，")
                sb.append("或检查开发者选项里的「USB 调试（安全设置）」（MIUI/ColorOS 需要单独打开）。\n")
                _state.update { it.copy(output = sb.toString(), needPair = true) }
            }

            refresh(ctx, includeCount = true)
            _state.update { it.copy(starting = false) }
        }
    }

    /** Stop the Shizuku service. Same as official menu "Stop" action (Shizuku.exit()). */
    fun stop(ctx: Context) {
        // User-initiated stop must not trigger the watchdog.
        suppressWatchdogUntil = System.currentTimeMillis() + 30_000L
        viewModelScope.launch(Dispatchers.IO) {
            ShizukuManager.stopService()
            delay(600)
            refresh(ctx, includeCount = false)
        }
    }

    fun dismissDialog() {
        _state.update { it.copy(dialogVisible = false, output = "", needPair = false) }
    }
}
