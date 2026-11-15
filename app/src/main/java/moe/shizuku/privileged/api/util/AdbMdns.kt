package moe.shizuku.privileged.api.util

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.IOException
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.util.concurrent.Executors

/**
 * 1.1.1 v5.6：mDNS 端口发现（配对 + 连接双服务）——NsdManager 主通道 + MulticastSocket 兜底。
 *
 * 依据（用户 Stellar 对照实测 17:58/17:59）：无线调试打开（不点「使用配对码配对设备」）
 * 即应显示连接端口 46501 可用——Stellar 用 NsdManager discoverServices(_adb-tls-connect)
 * 秒级发现。历史「NsdManager 弹窗」结论用户已澄清非 NsdManager 本身（早期可能传非标准
 * serviceType 触发系统列表弹窗）；本版照 Stellar 标准 DNS-SD 格式（PROTOCOL_DNS_SD +
 * _adb-tls-connect._tcp / _adb-tls-pairing._tcp）实现，并加 isLocalService 本机过滤
 * + ServerSocket 验活，规避无关服务/弹窗。
 *
 * 双通道（任一命中即可）：
 *  - NsdManager 主通道：系统级 mDNS（Stellar 实证有效）；
 *  - RawMdnsPortFinder 兜底：MulticastSocket（v5.4 多接口绑定，规避个别 ROM 的
 *    NsdManager 异常）。
 *
 * 回调语义（auto_pair.py 持续监听版）：
 *  - 配对端口命中 → onFound("127.0.0.1" to port)，端口变化（弹窗重开轮换）才重复回调；
 *    配对 listener 常驻，靠 onServiceLost 重置 serviceFound 重新等待；
 *  - 连接端口命中 → onConnectFound(port)，持续刷新保持最新（配对后可能轮换）；
 *  - 监听约 100 秒 → onMaxRefresh 仅提示（不停止、不转盲扫）——持续监听直到 stop()。
 */
@RequiresApi(Build.VERSION_CODES.R)
class AdbMdns(
    private val context: Context,
    @Suppress("UNUSED_PARAMETER") private val serviceType: String,
    private val onFound: (Pair<String, Int>) -> Unit,
    private val onConnectFound: ((Int) -> Unit)? = null,
    private val onMaxRefresh: (() -> Unit)? = null
) {

    @Volatile
    private var running = false
    @Volatile
    private var serviceFound = false
    @Volatile
    private var resolving = false
    /** 最近一次回调的配对端口：端口变化（弹窗关闭重开）时重新回调 */
    @Volatile
    private var lastPairPort = 0
    private var refreshCount = 0

    private val nsdManager: NsdManager = context.getSystemService(NsdManager::class.java)
    private var pairListener: NsdManager.DiscoveryListener? = null
    private var connectListener: NsdManager.DiscoveryListener? = null
    private var rawFinder: RawMdnsPortFinder? = null

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 周期兜底检查（NsdManager 常驻不重启，避免每次 discover 都弹系统弹窗）。 */
    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            refreshCount++
            // NsdManager listener 常驻，不 stop/start——Android 15+ 每次 discoverServices 都会弹系统弹窗

            // auto_pair.py 同款：监听窗口必须覆盖用户点开配对弹窗的时刻。
            // 达到阈值只提示，不停止——发现逻辑持续运行直到 stop()。
            if (refreshCount == MAX_REFRESH_COUNT) {
                Log.i(TAG, "mDNS 已持续监听约 ${MAX_REFRESH_COUNT * CHECK_INTERVAL_MILLIS / 1000}s，继续等待配对弹窗")
                onMaxRefresh?.invoke()
            }
            mainHandler.postDelayed(this, CHECK_INTERVAL_MILLIS)
        }
    }

    fun start() {
        if (running) return
        running = true
        refreshCount = 0
        serviceFound = false
        resolving = false
        startDiscoveryInternal()
        mainHandler.postDelayed(refreshRunnable, INITIAL_DELAY_MILLIS)
        // MulticastSocket 兜底（v5.4 多接口绑定）——与 NsdManager 双通道互备
        if (rawFinder == null) {
            rawFinder = RawMdnsPortFinder(
                context = context,
                onFound = { port, isPairing ->
                    if (running) {
                        mainHandler.post {
                            if (running) {
                                if (isPairing) {
                                    if (isPortAvailable(port)) {
                                        Log.i(TAG, "MulticastSocket 发现配对服务 127.0.0.1:$port")
                                        this@AdbMdns.onFound("127.0.0.1" to port)
                                    } else {
                                        Log.v(TAG, "MulticastSocket 配对端口 $port 未被监听，忽略")
                                    }
                                } else {
                                    Log.i(TAG, "MulticastSocket 发现连接服务端口 $port")
                                    this@AdbMdns.onConnectFound?.invoke(port)
                                }
                            }
                        }
                    }
                },
                onTimeout = {
                    Log.v(TAG, "MulticastSocket 查询超时，转 NsdManager 单通道")
                    // NsdManager 仍在跑，无需额外动作
                }
            ).also { it.start() }
        }
    }

    /** 启动 NsdManager 双服务发现（配对 + 连接）。两个 listener 都常驻，只启动一次不重启。 */
    private fun startDiscoveryInternal() {
        if (!running) return

        // 配对服务：listener 常驻
        if (pairListener == null) {
            pairListener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(type: String) {}
                override fun onDiscoveryStopped(type: String) {}
                override fun onServiceFound(info: NsdServiceInfo) = resolve(info, true)
                override fun onServiceLost(info: NsdServiceInfo) {
                    serviceFound = false
                    lastPairPort = 0
                    Log.i(TAG, "配对服务消失，重新等待发现")
                }
                override fun onStartDiscoveryFailed(type: String, errorCode: Int) {}
                override fun onStopDiscoveryFailed(type: String, errorCode: Int) {}
            }
            runCatching {
                nsdManager.discoverServices(TLS_PAIRING, NsdManager.PROTOCOL_DNS_SD, pairListener)
            }.onFailure { Log.v(TAG, "配对服务发现启动失败") }
        }

        // 连接服务：listener 常驻（只启动一次，不反复 stop/start——避免每次 discover 都弹系统弹窗）
        if (connectListener == null) {
            connectListener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(type: String) {}
                override fun onDiscoveryStopped(type: String) {}
                override fun onServiceFound(info: NsdServiceInfo) = resolve(info, false)
                override fun onServiceLost(info: NsdServiceInfo) {}
                override fun onStartDiscoveryFailed(type: String, errorCode: Int) {}
                override fun onStopDiscoveryFailed(type: String, errorCode: Int) {}
            }
            runCatching {
                nsdManager.discoverServices(TLS_CONNECT, NsdManager.PROTOCOL_DNS_SD, connectListener)
            }.onFailure { Log.v(TAG, "连接服务发现启动失败") }
        }
    }

    /** resolve 服务并校验：本机地址 + 端口本机监听中 → 回调。 */
    private fun resolve(info: NsdServiceInfo, isPairing: Boolean) {
        if (!running || resolving || serviceFound && isPairing) return
        resolving = true
        runCatching {
            nsdManager.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    resolving = false
                }

                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    resolving = false
                    executor.execute {
                        val host = resolved.host?.hostAddress ?: return@execute
                        if (!isLocalService(host)) {
                            Log.v(TAG, "非本机服务 $host，忽略")
                            return@execute
                        }
                        val port = resolved.port
                        if (port !in 1..65535) return@execute
                        if (!isPortAvailable(port)) {
                            Log.v(TAG, "端口 $port 未被监听，忽略")
                            return@execute
                        }
                        mainHandler.post {
                            if (!running) return@post
                            if (isPairing) {
                                // 端口变化（弹窗重开/轮换）时重新回调；同端口不重复
                                if (port != lastPairPort) {
                                    lastPairPort = port
                                    serviceFound = true
                                    Log.i(TAG, "NsdManager 发现配对服务 127.0.0.1:$port")
                                    onFound("127.0.0.1" to port)
                                }
                            } else {
                                Log.i(TAG, "NsdManager 发现连接服务端口 $port")
                                onConnectFound?.invoke(port)
                            }
                        }
                    }
                }
            })
        }.onFailure { resolving = false }
    }

    /** host 是否为本机任一接口地址（无线调试服务 adbd 监听本机）。 */
    private fun isLocalService(host: String): Boolean = try {
        NetworkInterface.getNetworkInterfaces()?.asSequence()?.any { ni ->
            ni.inetAddresses.asSequence().any { host == it.hostAddress }
        } ?: false
    } catch (_: Exception) {
        false
    }

    /** 端口已被本机监听（ServerSocket bind 失败）→ 有效 adb 服务端口。 */
    private fun isPortAvailable(port: Int): Boolean = try {
        ServerSocket().use { it.bind(InetSocketAddress("127.0.0.1", port), 1); false }
    } catch (_: IOException) {
        true
    }

    fun stop() {
        if (!running) return
        running = false
        mainHandler.removeCallbacks(refreshRunnable)
        pairListener?.let { runCatching { nsdManager.stopServiceDiscovery(it) } }
        connectListener?.let { runCatching { nsdManager.stopServiceDiscovery(it) } }
        pairListener = null
        connectListener = null
        runCatching { rawFinder?.stop() }
        rawFinder = null
    }

    fun destroy() {
        stop()
        executor.shutdown()
    }

    companion object {
        const val TLS_PAIRING = "_adb-tls-pairing._tcp"
        const val TLS_CONNECT = "_adb-tls-connect._tcp"
        const val TAG = "AdbMdns"
        const val CHECK_INTERVAL_MILLIS = 2000L
        const val INITIAL_DELAY_MILLIS = 500L
        const val MAX_REFRESH_COUNT = 50
    }
}
