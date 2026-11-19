package moe.shizuku.privileged.api.core

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.LruCache
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import moe.shizuku.privileged.api.data.AppInfo
import moe.shizuku.privileged.api.util.Starter
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku

/**
 * Shizuku service manager.
 * Uses the official rikka.shizuku.Shizuku API for binder management
 * and IShizukuService / IRemoteProcess AIDL for privileged operations.
 */
object ShizukuManager {

    private const val TAG = "ShizukuManager"

    /** 90ba：服务状态变化广播——保活/配对/启动等任何使服务恢复就绪的路径发出，
     *  前台（首页状态卡等）收到后立即刷新，无需等待轮询间隔。 */
    const val ACTION_SERVICE_STATE_CHANGED =
        "moe.shizuku.privileged.api.action.SERVICE_STATE_CHANGED"

    /** 90ba：发送服务状态变化广播（App 内各组件互达，setPackage 限本应用）。 */
    fun notifyServiceStateChanged(ctx: Context) {
        try {
            ctx.sendBroadcast(
                Intent(ACTION_SERVICE_STATE_CHANGED).setPackage(ctx.packageName)
            )
        } catch (_: Throwable) {}
    }

    enum class Status { RUNNING, STOPPED, UNKNOWN }

    private const val FLAG_ALLOWED = 1 shl 1
    private const val FLAG_DENIED = 1 shl 2
    private const val MASK_PERMISSION = FLAG_ALLOWED or FLAG_DENIED

    private var serviceBinder: IBinder? = null
    private var serviceConnection: ServiceConnection? = null

    /** Get the Shizuku service interface from the current binder.
     *  A1（90n 实现）：「Shizuku 兼容层」开关关闭时禁用 rikka 兼容通道——所有走
     *  shizuku binder 的执行/查询调用返回 null，应用只使用 Lin-Shizuku 服务端。 */
    private fun getService(): IShizukuService? {
        val compat = try { Prefs.shizukuCompat } catch (_: Throwable) { true }
        if (!compat) return null
        val binder = Shizuku.getBinder() ?: return null
        return try { IShizukuService.Stub.asInterface(binder) } catch (_: Throwable) { null }
    }

    // ---------- Lin-Shizuku 服务端双通道运行判断 ----------
    // server 现在是 Lin-Shizuku 服务端（.lin binder，ILinService 协议）；同时保留 shizuku 兼容通道（.shizuku binder）。
    // 任一通道存活即视为服务运行。
    fun isLinServerRunning(): Boolean = try { lin.Lin.pingBinder() } catch (_: Throwable) { false }

    fun isRunningCompat(): Boolean {
        // A1（90n 实现）：兼容层关闭时 rikka shizuku binder 不算"运行"（应用连不上）
        val compat = try { Prefs.shizukuCompat } catch (_: Throwable) { true }
        return isLinServerRunning() || (compat && try { Shizuku.pingBinder() } catch (_: Throwable) { false })
    }

    /**
     * 1.1.2：确保本应用已持有 WRITE_SECURE_SETTINGS。
     *
     * 服务启动命令（Starter.getAdbShellCommand / start.sh）与服务端 ManagerGrantHelper
     * 都授予过一次；这里在应用已连上服务后再兜底补一次，覆盖：
     *  - 服务在安装本版之前就已启动（启动命令没机会执行）；
     *  - 授权时权限尚未在 AndroidManifest 声明（pm grant 静默无效）。
     * 判定以本应用 checkSelfPermission 为准，不看 pm 的退出码。
     */
    suspend fun ensureWriteSecureSettings(ctx: Context): Boolean {
        if (moe.shizuku.privileged.api.util.WirelessDebugController.hasWriteSecureSettings(ctx)) {
            return true
        }
        return try {
            runCommand(
                "/system/bin/pm grant ${ctx.packageName} " +
                    "android.permission.WRITE_SECURE_SETTINGS 2>&1",
                Prefs.execIdentity
            )
            moe.shizuku.privileged.api.util.WirelessDebugController.hasWriteSecureSettings(ctx)
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 撤销 WRITE_SECURE_SETTINGS。设置页开关关闭时调用。
     */
    suspend fun revokeWriteSecureSettings(ctx: Context): Boolean {
        if (!moe.shizuku.privileged.api.util.WirelessDebugController.hasWriteSecureSettings(ctx)) {
            return true
        }
        return try {
            runCommand(
                "/system/bin/pm revoke ${ctx.packageName} " +
                    "android.permission.WRITE_SECURE_SETTINGS 2>&1",
                Prefs.execIdentity
            )
            !moe.shizuku.privileged.api.util.WirelessDebugController.hasWriteSecureSettings(ctx)
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 1.1.2（保活自愈闭环）：本应用前台服务是否真实运行（getRunningServices 高版本
     * 仅返回本应用服务，正好符合判定需求）。用于开关验证/进程级兜底，替代「Prefs 值」
     * 判定，根治「开关显示开、实际没跑」的假开状态。
     */
    fun isServiceRunning(ctx: Context, cls: Class<*>): Boolean = try {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.getRunningServices(Int.MAX_VALUE).any { it.service.className == cls.name }
    } catch (_: Throwable) {
        false
    }

    fun getServerVersionCompat(): Int = try {
        if (isLinServerRunning()) lin.Lin.version else Shizuku.getVersion()
    } catch (_: Throwable) { -1 }

    fun getServerUidCompat(): Int = try {
        if (isLinServerRunning()) lin.Lin.uid else Shizuku.getUid()
    } catch (_: Throwable) { -1 }

    fun stopServerCompat() {
        try {
            if (isLinServerRunning()) {
                lin.Lin.exit()
                return
            }
        } catch (_: Throwable) {}
        try { getService()?.exit() } catch (_: Throwable) {}
    }

    fun getStatus(): Status {
        return try {
            if (isRunningCompat()) Status.RUNNING else {
                if (isShizukuServerRunning()) Status.RUNNING else Status.STOPPED
            }
        } catch (_: Throwable) {
            if (isShizukuServerRunning()) Status.RUNNING else Status.UNKNOWN
        }
    }

    /** Check if shizuku server process exists via pidof / ps. */
    private fun isShizukuServerRunning(): Boolean {
        return try {
            val pidof = Runtime.getRuntime().exec(
                arrayOf("sh", "-c",
                    "pidof shizuku_starter 2>/dev/null || pidof shizuku_server 2>/dev/null || echo ''")
            )
            val pidOutput = pidof.inputStream.bufferedReader().readText().trim()
            pidof.waitFor()
            if (pidOutput.isNotEmpty()) return true

            val proc = Runtime.getRuntime().exec(
                arrayOf("sh", "-c",
                    "ps -A 2>/dev/null | grep -E 'shizuku_starter|shizuku_server' | grep -v grep | wc -l")
            )
            val output = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
            output.isNotEmpty() && output != "0"
        } catch (_: Throwable) {
            false
        }
    }

    fun getServerVersion(): Int = getServerVersionCompat()
    fun getServerUid(): Int = getServerUidCompat()
    fun getSELinuxContext(): String? = try {
        if (isLinServerRunning()) lin.Lin.sELinuxContext else Shizuku.getSELinuxContext()
    } catch (_: Throwable) { null }

    // ---------- 执行身份（68 轮：适配 com.android.shell） ----------

    /**
     * 命令执行身份：
     *  - [AUTO]  跟随服务端（root 服务→root 权限；shell 服务→shell 权限），默认；
     *  - [SHELL] 强制以 Shell (com.android.shell, uid 2000) 身份执行：服务端 root 时
     *            自动降级（setpriv / su 2000），服务端本就是 shell 时直接执行；
     *  - [ROOT]  强制以 Root (uid 0) 身份执行：仅服务端为 root 时可用。
     */
    enum class ExecIdentity(val key: Int, val label: String, val short: String) {
        AUTO(0, "跟随服务", "auto"),
        SHELL(1, "Shell (com.android.shell)", "shell"),
        ROOT(2, "Root", "root");

        companion object {
            fun fromKey(k: Int): ExecIdentity = entries.firstOrNull { it.key == k } ?: AUTO
        }
    }

    /** 服务端实际身份（uid 0→ROOT；uid 2000→SHELL；其他→AUTO）。 */
    fun serverIdentity(): ExecIdentity = when (getServerUidCompat()) {
        0 -> ExecIdentity.ROOT
        2000 -> ExecIdentity.SHELL
        else -> ExecIdentity.AUTO
    }

    /** 降级工具探测缓存（root 服务端降级 shell 用：setpriv 优先，其次 su）。 */
    private var toolChecked = false
    private var hasSetpriv = false
    private var hasSu = false

    private suspend fun ensureShellDowngradeTools(): Boolean = withContext(Dispatchers.IO) {
        if (toolChecked) return@withContext hasSetpriv || hasSu
        val out = runCommandInternal("command -v setpriv 2>/dev/null; command -v su 2>/dev/null")
        hasSetpriv = out.lines().any { it.trim() == "setpriv" }
        hasSu = out.lines().any { it.trim() == "su" }
        toolChecked = true
        hasSetpriv || hasSu
    }

    /** 身份不可用时的错误提示。 */
    private fun identityError(identity: ExecIdentity): String = when (identity) {
        ExecIdentity.SHELL ->
            "无法以 Shell (com.android.shell) 身份执行：当前服务端未提供降级工具（setpriv/su）且不是 Shell 用户"
        ExecIdentity.ROOT ->
            "无法以 Root 身份执行：当前服务运行在 Shell 用户（com.android.shell），请先以 Root 启动服务"
        ExecIdentity.AUTO -> ""
    }

    /**
     * 按目标身份包装命令：返回包装后的命令；null 表示该身份当前不可用。
     * 命令经 `newProcess("sh","-c",cmd)` 直接传参（不经外层 shell 二次解析），
     * 包装层内用 base64 传递原始命令，避免引号/特殊字符嵌套问题。
     */
    private suspend fun wrapForIdentity(cmd: String, identity: ExecIdentity): String? {
        if (identity == ExecIdentity.AUTO) return cmd
        val uid = getServerUidCompat()
        return when (identity) {
            ExecIdentity.AUTO -> cmd
            ExecIdentity.SHELL -> when (uid) {
                2000 -> cmd // 服务端本身就是 shell（com.android.shell），无需包装
                0 -> {
                    if (!ensureShellDowngradeTools()) return null
                    val b64 = android.util.Base64.encodeToString(
                        cmd.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP
                    )
                    val inner = "echo $b64 | base64 -d | sh"
                    if (hasSetpriv) "setpriv --reuid 2000 --regid 2000 --clear-groups sh -c '$inner'"
                    else "su 2000 -c '$inner'"
                }
                else -> null
            }
            ExecIdentity.ROOT -> when (uid) {
                0 -> cmd
                2000 -> null // shell 服务端无法提升到 root
                else -> null
            }
        }
    }

    /**
     * Detect whether a su binary is available on PATH.
     * Migrated from official EnvironmentUtils.isRooted().
     */
    /**
     * Root 探测：扫描 PATH 下是否存在 su。
     * 90o 性能轮：结果进程内缓存（root 环境在进程生命周期内几乎不变，原每次调用
     * 都遍历 PATH 目录做 File.exists，前台 5s 兜底轮询与多处状态刷新会反复触发）。
     */
    @Volatile
    private var rootedCache: Boolean? = null

    fun isRooted(): Boolean {
        rootedCache?.let { return it }
        val r = try {
            System.getenv("PATH")
                ?.split(File.pathSeparatorChar)
                ?.any { File("$it/su").exists() } == true
        } catch (_: Throwable) {
            false
        }
        rootedCache = r
        return r
    }

    /**
     * Poll until the Shizuku binder becomes available (service started), or the timeout is reached.
     * Returns true if the binder is alive when the call finishes.
     */
    suspend fun waitForBinder(timeoutMs: Long = 12_000): Boolean = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (isRunningCompat()) return@withContext true
            delay(300)
        }
        isRunningCompat()
    }

    /**
     * v3（MT 授权超时修复）：等待 **Lin 协议通道**（lin.Lin binder）就绪。
     * 区别于 waitForBinder（lin 或 shizuku 任一通道）：旧协议服务端进程残留时
     * shizuku 兼容通道可能存活但 lin 通道不通，授权确认会送不到——必须等 lin 通道。
     */
    suspend fun waitForLinBinder(timeoutMs: Long = 12_000): Boolean = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (isLinServerRunning()) return@withContext true
            delay(300)
        }
        isLinServerRunning()
    }

    /** v3 自愈节流锁：30 秒内只触发一次服务端重启，避免授权风暴/循环重启。 */
    private val selfHealLock = Any()
    @Volatile
    private var lastSelfHealAt = 0L

    /**
     * v3（MT 授权超时修复）：服务端协议自愈。
     * 旧版本服务端进程（如 1.0.8 StellarService，Lin_server 同名）在 APK 覆盖升级后
     * 不会随应用重启而退出——它会继续响应第三方应用的 attach/授权弹窗，但管理器
     * 的 lin 通道收不到它的 binder，授权确认因此丢失（MT 管理器"请求 Shell 权限超时"）。
     *
     * 本方法：root 设备上检测到 lin 通道不通时，执行启动脚本（先启后停：
     * 启动当前 LinService 并清理旧 Lin_server 进程），然后等待 lin 通道就绪。
     * 成功返回 true。带 30 秒节流。
     */
    suspend fun selfHealServerIfNeeded(ctx: Context, timeoutMs: Long = 6_000): Boolean = withContext(Dispatchers.IO) {
        if (!isRooted()) return@withContext false
        if (isLinServerRunning()) return@withContext true
        synchronized(selfHealLock) {
            val now = System.currentTimeMillis()
            if (now - lastSelfHealAt < 30_000L) return@withContext isLinServerRunning()
            lastSelfHealAt = now
        }
        val out = runCatching { startService(ctx) }.getOrElse { "" }
        if (out.contains("RUNNING-OK") || out.contains("启动成功")) {
            waitForLinBinder(timeoutMs)
        } else {
            false
        }
    }

    fun bindService(ctx: Context, onConnected: () -> Unit = {}, onDisconnected: () -> Unit = {}) {
        try {
            val intent = Intent()
            intent.component = ComponentName(ctx, "moe.shizuku.privileged.api.core.EngineService")
            serviceConnection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    serviceBinder = service
                    onConnected()
                    // 服务连接成功 = Lin_server/Shizuku 服务就绪 → 触发模块引擎（幂等，App 打开即补齐）
                    triggerModuleEngine(ctx)
                }
                override fun onServiceDisconnected(name: ComponentName?) {
                    serviceBinder = null
                    onDisconnected()
                }
            }
            ctx.bindService(intent, serviceConnection!!, Context.BIND_AUTO_CREATE)
        } catch (_: Throwable) {}
    }

    fun unbindService(ctx: Context) {
        serviceConnection?.let { ctx.unbindService(it) }
        serviceConnection = null
        serviceBinder = null
    }

    /**
     * Run a shell command via Shizuku service (privileged: shell or root uid).
     * Returns stdout+stderr combined. Falls back to local sh if Shizuku unavailable.
     */
    /** 流式执行结果：finished=是否正常结束（未超时）；exitCode=结束时的退出码（超时/未知为 null）。 */
    data class StreamResult(val finished: Boolean, val exitCode: Int?)

    /** 最近一次执行的进程引用，供用户点停止时 destroy。 */
    @Volatile
    private var lastRemoteProcess: IRemoteProcess? = null

    /** 手动停止当前正在执行的命令（点停止按钮调用）。 */
    fun destroyLastProcess() {
        runCatching { lastRemoteProcess?.destroy() }
        lastRemoteProcess = null
    }

    /**
     * 流式执行单条命令：逐行回调 stdout（isError=false）/ stderr（isError=true），
     * 长驻脚本的输出也能实时显示；超时自动销毁进程。
     *
     * 超时语义为「空闲超时」：只有当进程持续 [timeoutMs] 毫秒没有任何新输出时才
     * 判定为卡死并销毁；只要仍在产出输出就继续等待。这样打包/备份等长任务即使
     * 运行几分钟也不会被误杀，而真正挂起的进程会在无输出一段时间后自动终止。
     */
    /** 流式执行（68 轮扩展）：按目标身份执行；额外返回退出码，供失败原因精准定位。 */
    suspend fun runCommandStreamingEx(
        cmd: String,
        identity: ExecIdentity = ExecIdentity.AUTO,
        onLine: suspend (String, Boolean) -> Unit,
        timeoutMs: Long = 30_000,
        task: CommandTask? = null
    ): StreamResult = withContext(Dispatchers.IO) {
        val final = wrapForIdentity(cmd, identity)
        if (final == null) {
            onLine(identityError(identity), true)
            return@withContext StreamResult(true, 1)
        }
        // Lin-Shizuku 服务端优先（manager 直连 lin 协议）
        if (isLinServerRunning()) {
            try {
                val proc = lin.Lin.newProcess(arrayOf("sh", "-c", final), null, null)
                return@withContext streamProcess(proc, onLine, timeoutMs, task)
            } catch (_: Throwable) {}
        }
        // Shizuku 兼容通道
        try {
            val service = getService()
            if (service != null && Shizuku.pingBinder()) {
                val remote = service.newProcess(arrayOf("sh", "-c", final), null, null)
                if (remote != null) {
                    return@withContext streamRemote(remote, onLine, timeoutMs, task)
                }
            }
        } catch (_: Throwable) {}
        StreamResult(false, null)
    }

    private suspend fun streamRemote(
        remote: IRemoteProcess,
        onLine: suspend (String, Boolean) -> Unit,
        timeoutMs: Long,
        task: CommandTask? = null
    ): StreamResult = coroutineScope {
        lastRemoteProcess = remote
        task?.attach(remote, null)
        val lastOutput = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
        val outJob = launch { streamLines(ParcelFileDescriptor.AutoCloseInputStream(remote.inputStream), false, onLine, lastOutput) }
        val errJob = launch { streamLines(ParcelFileDescriptor.AutoCloseInputStream(remote.errorStream), true, onLine, lastOutput) }
        var finished = false
        while (true) {
            val alive = try { remote.alive() } catch (_: Throwable) { false }
            if (!alive) { finished = true; break }
            // 空闲超时：无新输出达到 timeoutMs 才终止；有输出则继续等待
            if (System.currentTimeMillis() - lastOutput.get() >= timeoutMs) break
            delay(100)
        }
        if (!finished) runCatching { remote.destroy() }
        outJob.join(); errJob.join()
        val code = if (finished) try { remote.exitValue() } catch (_: Throwable) { null } else null
        StreamResult(finished, code)
    }

    private suspend fun streamProcess(
        proc: Process,
        onLine: suspend (String, Boolean) -> Unit,
        timeoutMs: Long,
        task: CommandTask? = null
    ): StreamResult = coroutineScope {
        task?.attach(null, proc)
        val lastOutput = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
        val outJob = launch { streamLines(proc.inputStream, false, onLine, lastOutput) }
        val errJob = launch { streamLines(proc.errorStream, true, onLine, lastOutput) }
        // 用 alive() 轮询等待（官方 API 全版本兼容），不用 Process.waitFor(timeout)：
        // LinRemoteProcess 未实现该方法，低版本设备会抛 NoSuchMethodError 导致命令被误杀
        // 空闲超时：无新输出达到 timeoutMs 才终止；有输出则继续等待
        var finished = false
        while (true) {
            val alive = try {
                if (proc is lin.LinRemoteProcess) proc.alive() else proc.isAlive
            } catch (_: Throwable) {
                false
            }
            if (!alive) { finished = true; break }
            if (System.currentTimeMillis() - lastOutput.get() >= timeoutMs) break
            delay(100)
        }
        if (!finished) runCatching { proc.destroy() }
        outJob.join(); errJob.join()
        val code = if (finished) try { proc.exitValue() } catch (_: Throwable) { null } else null
        StreamResult(finished, code)
    }

    private suspend fun streamLines(
        input: java.io.InputStream,
        isError: Boolean,
        onLine: suspend (String, Boolean) -> Unit,
        lastOutput: java.util.concurrent.atomic.AtomicLong
    ) {
        try {
            val reader = input.bufferedReader()
            while (true) {
                val line = reader.readLine() ?: break
                lastOutput.set(System.currentTimeMillis())
                withContext(Dispatchers.Main.immediate) { onLine(line, isError) }
            }
        } catch (_: Throwable) {}
    }

    /**
     * 按目标身份执行命令并返回 stdout+stderr。
     * 默认 [ExecIdentity.AUTO]（跟随服务端）——数据层/文件操作沿用此默认，不受全局身份设置影响；
     * 模块/脚本/终端等「执行类」显式传入目标身份。
     */
    suspend fun runCommand(cmd: String, identity: ExecIdentity = ExecIdentity.AUTO): String = withContext(Dispatchers.IO) {
        val final = wrapForIdentity(cmd, identity)
        if (final == null) return@withContext identityError(identity)
        runCommandInternal(final)
    }

    /**
     * 特权命令执行（70 轮：电池优化白名单等需要 DUMP/CHANGE_DEVICE_IDLE 权限的命令）。
     * 服务端运行在普通应用身份（非 root 非 shell）时 dumpsys 会 Permission Denial，
     * 按以下优先级取得特权：
     *  ① 服务端通道执行（root/shell 身份有 DUMP 权限），输出无权限拒绝即返回；
     *  ② 输出含 Permission Denial 或服务端不可用，且设备已 root → 本地 `su -c` 提权通道；
     *  ③ 均不可用 → 返回 `SHIZUKU_NO_PRIVILEGE:` 前缀错误文本（含 uid 与指引），供界面给出中文提示。
     */
    suspend fun runPrivilegedCommand(cmd: String): String = withContext(Dispatchers.IO) {
        // ① 服务端通道（AUTO：跟随服务端身份；root/shell 有 DUMP 权限）
        val viaServer = try {
            if (isRunningCompat()) runCommandInternal(cmd) else ""
        } catch (_: Throwable) { "" }
        if (viaServer.isNotBlank() && !viaServer.contains("Permission Denial")) {
            return@withContext viaServer
        }
        // ② 权限被拒或服务端不可用 → 设备已 root 时走本地 su 提权（首次需在 root 管理器授权本应用）
        if (isRooted()) {
            try {
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
                val out = p.inputStream.bufferedReader().readText()
                val err = p.errorStream.bufferedReader().readText()
                val ok = p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
                if (!ok) p.destroy()
                val all = out + err
                if (ok && all.isNotBlank() && !all.contains("Permission Denial")) return@withContext all
                if (all.isNotBlank()) return@withContext all // su 通道也失败：保留原始输出供诊断
            } catch (_: Throwable) {}
        }
        // ③ 明确错误：服务身份无权限且无提权通道
        "SHIZUKU_NO_PRIVILEGE: 服务运行身份（uid ${getServerUidCompat()}）无 DUMP 权限且无可用 root 提权通道，请用 Root 或无线调试（Shell）方式启动服务"
    }

    /** 无身份包装的原始执行（内部：探测命令、身份包装后的最终命令）。 */
    private suspend fun runCommandInternal(cmd: String): String = withContext(Dispatchers.IO) {
        // Lin-Shizuku 服务端优先（manager 直连 lin 协议）
        if (isLinServerRunning()) {
            try {
                val proc = lin.Lin.newProcess(arrayOf("sh", "-c", cmd), null, null)
                val stdout = proc.inputStream.bufferedReader().readText()
                val stderr = proc.errorStream.bufferedReader().readText()
                proc.waitFor()
                return@withContext (stdout + stderr)
            } catch (_: Throwable) {}
        }
        // Try Shizuku second
        try {
            val service = getService()
            if (service != null && Shizuku.pingBinder()) {
                val remote: IRemoteProcess? = service.newProcess(
                    arrayOf("sh", "-c", cmd), null, null
                )
                if (remote != null) {
                    val stdout = try {
                        ParcelFileDescriptor.AutoCloseInputStream(remote.inputStream)
                            .bufferedReader().readText()
                    } catch (_: Throwable) { "" }
                    val stderr = try {
                        ParcelFileDescriptor.AutoCloseInputStream(remote.errorStream)
                            .bufferedReader().readText()
                    } catch (_: Throwable) { "" }
                    try { remote.waitFor() } catch (_: Throwable) {}
                    return@withContext (stdout + stderr).ifEmpty { "" }
                }
            }
        } catch (_: Throwable) {}

        // Fallback: local sh (unprivileged)
        try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val out = p.inputStream.bufferedReader().readText()
            val err = p.errorStream.bufferedReader().readText()
            p.waitFor()
            out + err
        } catch (e: Throwable) {
            "Error: ${e.message}"
        }
    }

    suspend fun getShizukuApps(ctx: Context): List<AppInfo> = withContext(Dispatchers.IO) {
        val pm = ctx.packageManager
        // 局部数据类：解析 packagesJson 用（避免引入完整 LinConfig 依赖）
        class LinConfigPkgEntry {
            @com.google.gson.annotations.SerializedName("permissions")
            var permissions: MutableMap<String, Int>? = null
        }
        // 服务端授权记录的 uid→state 映射（byServerAuth 填充）
        var serverAuthStateMap: Map<Int, Int> = emptyMap()
        // 生态应用标准特征：声明的权限中包含 Shizuku API 权限（新版 V23 或旧版 V22/API）
        val shizukuPerms = setOf(
            "moe.shizuku.manager.permission.API_V23",
            "moe.shizuku.manager.permission.API_V22",
            "moe.shizuku.manager.permission.API"
        )
        // 1) 权限声明匹配（正常 ROM 的标准路径）
        val byPerm = runCatching {
            pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
                .filter { it.requestedPermissions?.any { p -> p in shizukuPerms } == true }
        }.getOrDefault(emptyList())

        // 90by：补充「服务端有授权记录但未声明权限」的应用（rish 模式等）
        // Scene 等用 rish（远程 shell）调 Shizuku，manifest 不声明 API_V23 权限，
        // byPerm 过滤不到；但服务端 ConfigManager.packages 有它的授权记录。
        // 直接读 Lin_server prefs 的 packagesJson（服务端持久化的授权 uid 表），
        // 避免遍历所有应用调 getFlagForUid（未记录 uid 也返回 FLAG_ASK=0，会误匹配全部应用）。
        // 同时解析 permissions 状态：rish 模式应用可能不主动请求权限，getAppPermissionState
        // 通过 Binder 查询时返回 0（未知），但 packagesJson 里有它的记录，用这里的状态兜底。
        val byServerAuth = runCatching {
            val packagesJson = ctx.getSharedPreferences("Lin_server", Context.MODE_PRIVATE)
                .getString("packagesJson", null) ?: return@runCatching emptyList()
            val type = object : com.google.gson.reflect.TypeToken<MutableMap<Int, LinConfigPkgEntry>>() {}.type
            val uidMap: MutableMap<Int, LinConfigPkgEntry> = com.google.gson.Gson().fromJson(packagesJson, type) ?: return@runCatching emptyList()
            val authUids = uidMap.keys.toSet()
            if (authUids.isEmpty()) return@runCatching emptyList()
            // 建立 uid → state 映射（从 permissions map 解析）
            serverAuthStateMap = uidMap.mapValues { (_, entry) ->
                val perms = entry.permissions ?: mutableMapOf()
                val shizukuFlag = perms[lin.server.shizuku.ShizukuApiConstants.PERMISSION_NAME] ?: 0
                val linFlag = perms[lin.LinApiConstants.PERMISSION_LIN] ?: 0
                when {
                    shizukuFlag == lin.server.shizuku.ShizukuApiConstants.FLAG_GRANTED -> 1
                    linFlag == lin.server.ConfigManager.FLAG_GRANTED -> 1
                    shizukuFlag == lin.server.shizuku.ShizukuApiConstants.FLAG_DENIED -> -1
                    linFlag == lin.server.ConfigManager.FLAG_DENIED -> -1
                    else -> 2  // 有记录但未决定 → 询问
                }
            }
            pm.getInstalledPackages(0)
                .filter { it.applicationInfo?.uid in authUids }
        }.getOrDefault(emptyList())

        // 合并去重（按包名）
        val merged = (byPerm + byServerAuth).distinctBy { it.packageName }

        // 2) 本地授权记录 uid 兜底：部分 ROM 剥离 requestedPermissions 时，
        //    仍按"用户曾授权/设置过的应用"显示，避免列表为空或混入全部已安装应用
        val packages = if (merged.isNotEmpty()) {
            merged
        } else {
            val authUids = ctx.getSharedPreferences("auth_state", Context.MODE_PRIVATE)
                .all.keys.mapNotNull { it.removePrefix("uid_").toIntOrNull() }
                .toSet()
            runCatching {
                pm.getInstalledApplications(PackageManager.GET_META_DATA)
                    .filter { it.uid in authUids }
                    .mapNotNull { pi -> pm.getPackageInfo(pi.packageName, 0) }
            }.getOrDefault(emptyList())
        }

        // 按包名去重：同一应用（HyperOS 双开/分身副本 uid 不同但包名相同）只显示一次，
        // 优先保留服务端已授权（允许）的条目，避免重复条目与计数虚增
        val deduped = packages.groupBy { it.applicationInfo?.packageName ?: "" }
            .map { (_, list) ->
                list.maxByOrNull { pi ->
                    when (getAppPermissionState(pi.applicationInfo?.uid ?: -1, ctx)) {
                        1 -> 3   // 允许：优先保留（副本 uid 已授权时也要显示为允许）
                        2 -> 2   // 询问
                        -1 -> 1  // 拒绝
                        else -> 0
                    }
                } ?: list.first()
            }

        deduped.mapNotNull { packageInfo ->
            val appInfo = packageInfo.applicationInfo ?: return@mapNotNull null
            // 1.1.2：以服务端实时 flags 为准，快照（packagesJson）只做兜底。
            // 反过来（快照优先）在覆盖安装后会读到过期快照，整片列表显示成「询问」，
            // 与实际授权（应用 attach 时拿到的 granted）自相矛盾。
            val liveState = getAppPermissionState(appInfo.uid, ctx)
            val state = if (liveState != 0) liveState else (serverAuthStateMap[appInfo.uid] ?: 0)
            AppInfo(
                packageName = appInfo.packageName,
                name = pm.getApplicationLabel(appInfo).toString(),
                icon = try { pm.getApplicationIcon(appInfo) } catch (_: Throwable) { null },
                state = state,
                uid = appInfo.uid
            )
        }.sortedBy { it.name.lowercase() }
    }

    /** 应用图标内存缓存（避免每次刷新重复加载 462 个图标）。 */
    private val iconCache = LruCache<String, Drawable>(512)

    /**
     * 轻量版应用列表（电池优化白名单页专用）：
     * - 不查询权限状态（省去每个应用 1~2 次 Binder IPC，白名单页不显示权限状态）
     * - 图标并发加载 + LRU 内存缓存（首次 1~2 秒，之后刷新秒级）
     * 返回按 label 排序的 AppInfo（state=0，图标缓存命中时即时返回）。
     */
    suspend fun getAllAppsLight(ctx: Context): List<AppInfo> = withContext(Dispatchers.IO) {
        val pm = ctx.packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        coroutineScope {
            apps.map { appInfo ->
                async {
                    val name = try { pm.getApplicationLabel(appInfo).toString() } catch (_: Throwable) { appInfo.packageName }
                    val icon = iconCache.get(appInfo.packageName) ?: runCatching {
                        pm.getApplicationIcon(appInfo)
                    }.getOrNull()?.also { iconCache.put(appInfo.packageName, it) }
                    AppInfo(
                        packageName = appInfo.packageName,
                        name = name,
                        icon = icon,
                        state = 0,
                        uid = appInfo.uid
                    )
                }
            }.awaitAll().sortedBy { it.name.lowercase() }
        }
    }

    /**
     * Permission state: 0=unknown, 1=granted, 2=asking, -1=denied
     *
     * 服务运行时以服务端 flags 为准；服务未运行时回退到本地持久化的授权记录
     * （本应用即 Shizuku 服务端，授权状态本地留存，未激活也能显示真实状态）。
     */
    fun getAppPermissionState(uid: Int, ctx: Context? = null): Int {
        // Lin-Shizuku 服务端优先（服务端 configManager 是唯一权威）；shizuku 兼容通道次之；最后本地
        if (isLinServerRunning()) {
            try {
                // 优先读 shizuku 兼容权限（大多数兼容应用）。
                // 注意：服务端 LinService 对 "shizuku" 权限返回 shizuku 位掩码协议
                // （FLAG_GRANTED=2 / FLAG_DENIED=4 / FLAG_ASK=0），不能与 lin 数值（1/2/0）混用。
                val shizukuFlag = lin.Lin.getFlagForUid(uid, lin.server.shizuku.ShizukuApiConstants.PERMISSION_NAME)
                if (shizukuFlag != 0) {
                    return when (shizukuFlag) {
                        lin.server.shizuku.ShizukuApiConstants.FLAG_GRANTED -> 1
                        lin.server.shizuku.ShizukuApiConstants.FLAG_DENIED -> -1
                        else -> 2
                    }
                }
                return when (lin.Lin.getFlagForUid(uid, lin.LinApiConstants.PERMISSION_LIN)) {
                    lin.server.ConfigManager.FLAG_GRANTED -> 1
                    lin.server.ConfigManager.FLAG_DENIED -> -1
                    else -> 2
                }
            } catch (_: Throwable) {
                // 1.1.2：服务端查询失败（刚重启、binder 还没就绪）时，不要继续往下掉进
                // Shizuku 兼容分支——那条分支在服务端未连接时读不到真实 flags，会返回 0，
                // 授权列表就会整片显示成「询问」。这里直接用本地留存的授权记录兜底。
                return localAuthPrefs(ctx)?.getInt("uid_$uid", 0) ?: 0
            }
        }
        if (Shizuku.pingBinder()) {
            try {
                val flags = Shizuku.getFlagsForUid(uid, MASK_PERMISSION)
                when {
                    (flags and FLAG_ALLOWED) != 0 -> 1
                    (flags and FLAG_DENIED) != 0 -> -1
                    else -> 2
                }
            } catch (_: Throwable) {
                localAuthPrefs(ctx)?.getInt("uid_$uid", 0) ?: 0
            }
        }
        return localAuthPrefs(ctx)?.getInt("uid_$uid", 0) ?: 0
    }

    /** 本地持久化的授权记录（uid -> 1 允许 / -1 拒绝）。 */
    private fun localAuthPrefs(ctx: Context?): android.content.SharedPreferences? {
        return ctx?.getSharedPreferences("auth_state", Context.MODE_PRIVATE)
    }

    fun setAppPermission(uid: Int, allowed: Boolean, ctx: Context? = null) {
        // 先写本地持久化（服务未运行时也能保留授权决定）
        localAuthPrefs(ctx)?.edit()?.putInt("uid_$uid", if (allowed) 1 else -1)?.apply()
        // Lin-Shizuku 服务端（configManager 权威）优先：双写 lin + shizuku 两个权限名，
        // 保证 Lin-Shizuku 客户端（查 "lin"）与 Shizuku 兼容应用（查 "shizuku"）都生效。
        // 注意：服务端 LinService 对 "shizuku" 权限要求 shizuku 位掩码（GRANTED=2/DENIED=4），
        // 对 "lin" 权限要求 lin 数值（GRANTED=1/DENIED=2），两者协议不同，不能混用。
        if (isLinServerRunning()) {
            val linFlag = if (allowed) lin.server.ConfigManager.FLAG_GRANTED
            else lin.server.ConfigManager.FLAG_DENIED
            val shizukuFlag = if (allowed) lin.server.shizuku.ShizukuApiConstants.FLAG_GRANTED
            else lin.server.shizuku.ShizukuApiConstants.FLAG_DENIED
            try {
                lin.Lin.updateFlagForUid(uid, lin.LinApiConstants.PERMISSION_LIN, linFlag)
                lin.Lin.updateFlagForUid(uid, lin.server.shizuku.ShizukuApiConstants.PERMISSION_NAME, shizukuFlag)
                return
            } catch (_: Throwable) {}
        }
        try {
            if (!Shizuku.pingBinder()) return
            val service = getService() ?: return
            val value = if (allowed) FLAG_ALLOWED else FLAG_DENIED
            service.updateFlagsForUid(uid, MASK_PERMISSION, value)
        } catch (_: Throwable) {}
    }

    /** 恢复「询问」状态：清除服务端权限标志与本地持久化记录。 */
    fun setAppPermissionAsk(uid: Int, ctx: Context? = null) {
        localAuthPrefs(ctx)?.edit()?.remove("uid_$uid")?.apply()
        if (isLinServerRunning()) {
            try {
                lin.Lin.updateFlagForUid(uid, lin.LinApiConstants.PERMISSION_LIN, lin.server.ConfigManager.FLAG_ASK)
                lin.Lin.updateFlagForUid(uid, lin.server.shizuku.ShizukuApiConstants.PERMISSION_NAME, lin.server.shizuku.ShizukuApiConstants.FLAG_ASK)
                return
            } catch (_: Throwable) {}
        }
        try {
            if (!Shizuku.pingBinder()) return
            val service = getService() ?: return
            service.updateFlagsForUid(uid, MASK_PERMISSION, 0)
        } catch (_: Throwable) {}
    }

    /**
     * 将授权请求弹窗的用户决定回复给服务端（官方 server 等待的
     * dispatchPermissionConfirmationResult 回调）。
     */
    fun dispatchPermissionResult(
        ctx: Context,
        uid: Int, pid: Int, requestCode: Int, allowed: Boolean, onetime: Boolean,
        permission: String = "shizuku"
    ) {
        // v4（MT 授权超时修复）：双通道回传——lin 通道（LinService）与 Shizuku 兼容通道
        // （服务端 intercept 105）都发送，任一通道送达服务端即完成授权确认（服务端两处
        // 处理等价且幂等：updatePermission 同值覆盖、notify 重复回调由客户端幂等消化）。
        // 覆盖 lin 通道 ping 通过但事务异常、桥接 binder 失效、1.0.8 遗留服务端等场景，
        // 不再因单一通道静默失败导致 MT 管理器收不到授权回调而超时。
        if (isLinServerRunning()) {
            try {
                lin.Lin.dispatchPermissionConfirmationResult(
                    uid, pid, requestCode, allowed, onetime, permission
                )
            } catch (_: Throwable) {
                // v5：lin 通道瞬时失败（ping 通过但 transact 抛异常）时后台延迟重试一次，
                // 覆盖"授权确认刚发出、服务端/桥接 binder 恰好抖动"导致确认丢失的场景。
                GlobalScope.launch(Dispatchers.IO) {
                    try {
                        kotlinx.coroutines.delay(600)
                        lin.Lin.dispatchPermissionConfirmationResult(
                            uid, pid, requestCode, allowed, onetime, permission
                        )
                    } catch (_: Throwable) {}
                }
            }
        }
        try {
            val service = getService() ?: run {
                // 两个通道都没有可用 binder：root 下异步自愈服务端后重试
                // （救"旧协议服务端进程残留导致管理器无 lin 通道"场景）
                if (isRooted()) {
                    GlobalScope.launch(Dispatchers.IO) {
                        try {
                            if (selfHealServerIfNeeded(ctx)) {
                                lin.Lin.dispatchPermissionConfirmationResult(
                                    uid, pid, requestCode, allowed, onetime, permission
                                )
                                return@launch
                            }
                        } catch (_: Throwable) {}
                        try {
                            val svc = getService() ?: return@launch
                            val d = android.os.Bundle().apply {
                                putBoolean(
                                    rikka.shizuku.ShizukuApiConstants.REQUEST_PERMISSION_REPLY_ALLOWED,
                                    allowed
                                )
                                putBoolean(
                                    rikka.shizuku.ShizukuApiConstants.REQUEST_PERMISSION_REPLY_IS_ONETIME,
                                    onetime
                                )
                            }
                            svc.dispatchPermissionConfirmationResult(uid, pid, requestCode, d)
                        } catch (_: Throwable) {}
                    }
                }
                return
            }
            val data = android.os.Bundle().apply {
                putBoolean(
                    rikka.shizuku.ShizukuApiConstants.REQUEST_PERMISSION_REPLY_ALLOWED,
                    allowed
                )
                putBoolean(
                    rikka.shizuku.ShizukuApiConstants.REQUEST_PERMISSION_REPLY_IS_ONETIME,
                    onetime
                )
            }
            service.dispatchPermissionConfirmationResult(uid, pid, requestCode, data)
        } catch (_: Throwable) {}
    }

    suspend fun getAuthorizedCount(ctx: Context): Int = withContext(Dispatchers.IO) {
        // 优先基于"去重后的生态应用列表"统计：列表每个应用的状态（允许/询问/拒绝）
        // 由服务端授权表实时得出（getAppPermissionState），与授权管理页口径完全一致；
        // 同时天然规避"同一应用双开/分身副本 uid 重复授权导致计数虚增"的问题。
        try {
            val apps = getShizukuApps(ctx)
            if (apps.isNotEmpty()) {
                return@withContext apps.count { it.state == 1 }
            }
        } catch (_: Throwable) {}
        // 列表不可用（未授予应用列表权限等）：回退服务端授权表计数
        if (isLinServerRunning()) {
            try {
                val n = lin.Lin.getAuthorizedCount()
                if (n >= 0) return@withContext n
            } catch (_: Throwable) {}
        }
        // 兜底：本地已允许（ALLOWED）记录数
        try {
            val prefs = localAuthPrefs(ctx) ?: return@withContext 0
            prefs.all.values.count { it is Int && it == 1 }
        } catch (_: Throwable) { 0 }
    }

    /**
     * Start Shizuku via root (su).
     * Returns the console output from the start script.
     */
    suspend fun startService(ctx: Context): String = withContext(Dispatchers.IO) {
        if (!isRooted()) {
            return@withContext "错误：未检测到 su，无法以 root 启动。\n请确认设备已获取 root 且 su 可用（如 Magisk）。"
        }
        try {
            val cmd = Starter.getSdcardCommand(ctx)
            // A3（90n 实现）：「降权激活」开启时，root 启动的服务端进程降权到 Shell(uid 2000) 运行。
            // 优先 setpriv（干净地 reuid/regid），不可用则用 su 2000 切换用户执行；
            // 两者仅影响服务端进程身份，不影响启动结果校验（RUNNING-OK 由 pgrep 判定）。
            // 1.0.11d：与"执行身份"统一——选 Shell 时自动降权，跟随服务/Root 不降权。
            var finalCmd = cmd
            if (Prefs.execIdentity == ExecIdentity.SHELL) {
                val toolsReady = ensureShellDowngradeTools()
                if (hasSetpriv) {
                    finalCmd = "setpriv --reuid=2000 --regid=2000 --init-groups sh -c '$cmd'"
                    appendStartupLog("降权激活：使用 setpriv 将服务端降权到 Shell(uid 2000) 运行")
                } else if (toolsReady && hasSu) {
                    finalCmd = "su 2000 -c '$cmd'"
                    appendStartupLog("降权激活：无 setpriv，使用 su 2000 降权到 Shell(uid 2000) 运行")
                } else {
                    appendStartupLog("降权激活：未找到 setpriv/su 降级工具，保持 Root 身份运行")
                }
            }
            appendStartupLog("Root 启动命令: $finalCmd")
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", finalCmd))
            val out = proc.inputStream.bufferedReader().readText()
            val err = proc.errorStream.bufferedReader().readText()
            proc.waitFor()
            var result = (out + err).ifEmpty { "启动命令已执行（无输出）" }
            result.lines().forEach { appendStartupLog(it) }
            // 启动成功标志与 start.sh / fallbackCommand 输出对齐：
            // "启动成功：Lin_server 服务正在运行" 或 "RUNNING-OK"；未命中才拉取服务端日志辅助定位
            val started = result.contains("启动成功") || result.contains("RUNNING-OK")
            if (!started) {
                try {
                    val logProc = Runtime.getRuntime().exec(arrayOf("su", "-c", "echo \"── 服务端日志(/data/local/tmp) ──\"; cat /data/local/tmp/Lin_server.log 2>/dev/null | tail -60; echo \"── 诊断日志(专属目录) ──\"; cat /storage/emulated/0/Android/data/moe.shizuku.privileged.api/files/日志.log 2>/dev/null | tail -60"))
                    val logOut = logProc.inputStream.bufferedReader().readText()
                    logProc.waitFor()
                    if (logOut.isNotBlank()) {
                        result += "\n\n── 服务端日志 ──\n" + logOut
                        logOut.lines().forEach { appendStartupLog("服务端日志: $it") }
                    }
                } catch (_: Throwable) {}
            }
            result
        } catch (e: Throwable) {
            appendStartupLog("Root 启动异常: ${e.message ?: e.javaClass.simpleName}")
            "启动失败: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    fun startService(ctx: Context, callback: (String) -> Unit) {
        GlobalScope.launch(Dispatchers.IO) {
            val result = startService(ctx)
            withContext(Dispatchers.Main) { callback(result) }
            // 服务就绪 → 触发模块引擎（拉起所有已启用模块，幂等）
            if (result.contains("启动成功") || result.contains("RUNNING-OK")) {
                triggerModuleEngine(ctx)
            }
        }
    }

    /**
     * 触发模块引擎：服务就绪（root / adb / 无线调试 / bind 成功）后统一拉起已启用模块。
     * 幂等：模块 start.sh 用 kill -0 判断已在跑则跳过。
     */
    fun triggerModuleEngine(ctx: Context) {
        GlobalScope.launch(Dispatchers.IO) {
            // 76 轮：服务就绪后统一应用「自动开启 USB 调试」（保持 adb_enabled 开启）
            try { applyAutoUsbDebug(ctx) } catch (_: Throwable) {}
            // 1.1.2（保活底座）：服务就绪后自动申请电池优化白名单 + RUN_IN_BACKGROUND AppOps，
            // 降低服务端/守护进程被系统静默清理的概率（用户反馈三种启动方式服务都会挂）
            try { applyKeepAliveBase(ctx) } catch (_: Throwable) {}
            try {
                // 85 轮：先清理未启用模块的孤儿进程（开关已关但后台仍在跑的残留），再统一拉起已启用模块
                ModuleStore.cleanupOrphanModules(ctx)
                ModuleStore.applyEnabledModules(ctx)
            } catch (_: Throwable) {}
        }
    }

    /** 76 轮：自动开启 USB 调试——开启时保持 `settings put global adb_enabled 1`。
     *  服务端为 root/shell 身份时一般可直接写入；失败时走本地 su 提权。异步执行，不阻塞调用方。 */
    fun applyAutoUsbDebug(ctx: Context) {
        if (!Prefs.autoUsbDebug) return
        GlobalScope.launch(Dispatchers.IO) {
            val result = runPrivilegedCommand("settings put global adb_enabled 1")
            if (result.startsWith("SHIZUKU_NO_PRIVILEGE:")) {
                Log.w(TAG, "applyAutoUsbDebug 无特权: $result")
            } else {
                appendStartupLog("自动开启 USB 调试: $result")
                Log.d(TAG, "applyAutoUsbDebug: $result")
            }
        }
    }

    /**
     * 1.1.2（保活底座自动申请）：服务就绪后自动执行：
     *  1) dumpsys deviceidle whitelist +包名 —— 电池优化白名单（Doze 不再批量冻结本应用进程）；
     *  2) cmd appops set 包名 RUN_IN_BACKGROUND allow —— 允许后台运行（国产 ROM 常单独限制）。
     * 经服务端（shell/root 身份）执行；无特权时记录诊断日志不阻断。异步执行。
     */
    fun applyKeepAliveBase(ctx: Context) {
        GlobalScope.launch(Dispatchers.IO) {
            val pkg = ctx.packageName
            val cmds = arrayOf(
                "dumpsys deviceidle whitelist +$pkg",
                "cmd appops set $pkg RUN_IN_BACKGROUND allow"
            )
            for (cmd in cmds) {
                try {
                    val result = runPrivilegedCommand(cmd)
                    if (result.startsWith("SHIZUKU_NO_PRIVILEGE:")) {
                        DiagnosticLog.append("[保活底座] $cmd 无特权，跳过")
                    } else {
                        DiagnosticLog.append("[保活底座] $cmd → ${result.trim().ifEmpty { "ok" }.take(120)}")
                    }
                } catch (e: Throwable) {
                    DiagnosticLog.append("[保活底座] $cmd 异常: ${e.message?.take(80)}")
                }
            }
        }
    }

    fun stopService() {
        stopServerCompat()
    }

    // ---------- 启动诊断日志 ----------
    private val startupLog = java.util.ArrayDeque<String>()

    /** 格式化器单例（90o 性能轮：原每次 append new 一个 SimpleDateFormat；synchronized 保护下线程安全）。 */
    private val startupLogFormat = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault())

    /** 追加一条启动日志（时间戳 + 内容），全局静态可访问（无线调试/ADB 路径也可写入）。
     *  同时写入统一诊断日志（应用专属目录 files/日志.log）。 */
    fun appendStartupLog(line: String) {
        synchronized(startupLog) {
            startupLog.addLast(startupLogFormat.format(java.util.Date()) + " " + line)
            while (startupLog.size > 200) startupLog.removeFirst()
        }
        DiagnosticLog.append("[启动] " + line)
    }
}
