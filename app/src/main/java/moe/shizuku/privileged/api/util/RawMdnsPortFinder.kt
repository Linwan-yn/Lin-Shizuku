package moe.shizuku.privileged.api.util

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException

/**
 * 1.1.1 v5.2：MulticastSocket 直听 mDNS 通道（配对端口 + 连接端口双发现）。
 *
 * 修复根因（用户 Termux python-zeroconf 实测：同一环境 _adb-tls-pairing /
 * _adb-tls-connect 都能 mDNS 发现——组播没被 VPN 掐死，是 App 侧问题）：
 *  1) **MulticastLock**：Android 对 WiFi 接口组播接收做过滤，不持
 *     WifiManager.MulticastLock 就收不到 mDNS 组播响应（adbd 响应走
 *     WiFi 接口）——这是 App mDNS 一直"超时"的真正原因，此前误判为
 *     "VPN 掐组播"而弃用 mDNS 去搞盲扫，越走越偏；
 *  2) **双服务发现**：同时查询 _adb-tls-pairing（配对端口）与
 *     _adb-tls-connect（连接端口）——连接端口官方系同样靠 mDNS 获取，
 *     此前 App 只查配对服务、连接端口靠系统属性/盲扫（二次跑偏）。
 *
 * 本类直接：
 *  1) 加入 mDNS 组播组 224.0.0.251:5353（持 MulticastLock）；
 *  2) 周期交替发送两个 PTR 查询（配对 + 连接）；
 *  3) 解析响应 DNS 报文中的 SRV 记录，按服务名区分回调类型。
 *
 * auto_pair.py 持续监听版：查询不设上限、配对端口命中后不退出——配对弹窗
 * 重开端口轮换时靠 lastPairPort 变化检测重新回调；持续运行直到 stop()。
 * onTimeout 参数保留仅作调用方兼容，不再主动触发。
 */
class RawMdnsPortFinder(
    private val context: Context,
    private val onFound: (port: Int, isPairing: Boolean) -> Unit,
    private val onTimeout: (() -> Unit)? = null
) {

    @Volatile
    private var running = false
    /** 已发现配对端口（MulticastSocket 无 lost 事件，仅作状态记录） */
    @Volatile
    private var pairingFound = false
    /** 最近一次回调的配对端口：端口变化（弹窗关闭重开）时重新回调 */
    @Volatile
    private var lastPairPort = 0
    private var thread: Thread? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun start() {
        if (running) return
        running = true
        // v5.2：Android WiFi 组播过滤——不持锁收不到组播响应（Termux 的
        // python-zeroconf 能收是因为 Termux 应用持有锁/网络栈差异）
        runCatching {
            val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifiManager.createMulticastLock("lin-mdns").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        thread = Thread(::runLoop, "raw-mdns-finder").apply { isDaemon = true; start() }
    }

    private fun runLoop() {
        var socket: MulticastSocket? = null
        try {
            val group = InetAddress.getByName(MDNS_GROUP)
            socket = try {
                MulticastSocket(MDNS_PORT).apply { reuseAddress = true }
            } catch (_: Exception) {
                // 5353 被系统 mDNS 占用时用随机端口（加入组后仍可收组播）
                MulticastSocket(0).apply { reuseAddress = true }
            }
            // v5.4（用户 15:30 实测端口不显示）：joinGroup(null) 只绑默认路由接口
            // （VPN 常开时 = 虚拟接口，收不到 WiFi 接口的 mDNS 组播）——对齐
            // Termux python-zeroconf 的 InterfaceChoice.All，枚举本机所有非回环
            // 接口逐一 joinGroup，WiFi 接口的组播响应才能进 socket。
            val ifaces = runCatching {
                java.net.NetworkInterface.getNetworkInterfaces()?.toList()
                    ?.filter { it.isUp && !it.isLoopback } ?: emptyList()
            }.getOrDefault(emptyList())
            if (ifaces.isEmpty()) {
                socket.joinGroup(InetSocketAddress(group, MDNS_PORT), null)
            } else {
                ifaces.forEach { iface ->
                    runCatching { socket.joinGroup(InetSocketAddress(group, MDNS_PORT), iface) }
                }
                // 兜底默认接口（含 VPN 接口，双网卡都收）
                runCatching { socket.joinGroup(InetSocketAddress(group, MDNS_PORT), null) }
            }
            socket.soTimeout = RECEIVE_TIMEOUT_MILLIS

            val pairingQuery = buildPtrQuery("_adb-tls-pairing")
            val connectQuery = buildPtrQuery("_adb-tls-connect")
            var queryCount = 0
            val buf = ByteArray(4096)

            // auto_pair.py 同款：持续查询直到 stop()，不设上限。
            // 配对端口已找到后仍继续发配对查询——MulticastSocket 无 lost 事件，
            // 弹窗重开端口轮换时靠 lastPairPort != 新端口 检测并重新回调。
            while (running) {
                queryCount++
                Log.v(TAG, "发送 mDNS 查询 (第 $queryCount 次)")
                runCatching { socket.send(DatagramPacket(pairingQuery, pairingQuery.size, group, MDNS_PORT)) }
                runCatching { socket.send(DatagramPacket(connectQuery, connectQuery.size, group, MDNS_PORT)) }
                // 收到响应或超时（超时则重发查询）
                while (running) {
                    try {
                        val packet = DatagramPacket(buf, buf.size)
                        socket.receive(packet)
                        val found = parseSrvPort(packet.data, packet.length)
                        // v5.2：显式判空后智能转换（构建兼容）
                        if (found != null && found.first in 1..65535) {
                            if (found.second) {
                                // 配对端口：端口变化才回调（弹窗重开端口轮换可再发现）
                                if (found.first != lastPairPort) {
                                    lastPairPort = found.first
                                    pairingFound = true
                                    Log.i(TAG, "MulticastSocket 发现配对端口 ${found.first}")
                                    onFound(found.first, true)
                                }
                            } else {
                                Log.i(TAG, "MulticastSocket 发现连接端口 ${found.first}")
                                onFound(found.first, false)
                            }
                        }
                    } catch (_: SocketTimeoutException) {
                        break // 超时 → 重发查询
                    }
                }
                Thread.sleep(QUERY_INTERVAL_MILLIS.toLong())
            }
        } catch (e: Exception) {
            Log.e(TAG, "MulticastSocket 监听异常", e)
        } finally {
            runCatching { socket?.leaveGroup(InetAddress.getByName(MDNS_GROUP)) }
            runCatching { socket?.close() }
            releaseLock()
            running = false
        }
    }

    private fun releaseLock() {
        runCatching { multicastLock?.release() }
        multicastLock = null
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
        releaseLock()
    }

    /** 构建 mDNS PTR 查询：header + QNAME(<service>._tcp.local) + QTYPE=PTR(12) + QCLASS=IN(1) */
    private fun buildPtrQuery(serviceName: String): ByteArray {
        val out = ByteArrayOutputStream()
        val id = (System.currentTimeMillis() and 0xFFFF).toInt()
        out.write((id shr 8) and 0xFF); out.write(id and 0xFF)
        // flags=0x0000（标准查询）
        out.write(0); out.write(0)
        // QDCOUNT=1, AN/NS/AR=0
        out.write(0); out.write(1)
        out.write(0); out.write(0)
        out.write(0); out.write(0)
        out.write(0); out.write(0)
        // QNAME: <service>._tcp.local
        writeLabel(out, serviceName)
        writeLabel(out, "_tcp")
        writeLabel(out, "local")
        out.write(0)
        // QTYPE=PTR(12), QCLASS=IN(1)
        out.write(0); out.write(12)
        out.write(0); out.write(1)
        return out.toByteArray()
    }

    private fun writeLabel(out: ByteArrayOutputStream, label: String) {
        val bytes = label.toByteArray(Charsets.US_ASCII)
        out.write(bytes.size)
        out.write(bytes)
    }

    /**
     * 解析 DNS-SD 响应：找 SRV(Type=33) 记录，返回 (端口, 是否配对服务)。
     * 记录名含 _adb-tls-pairing → 配对；含 _adb-tls-connect → 连接。
     */
    private fun parseSrvPort(data: ByteArray, length: Int): Pair<Int, Boolean>? {
        if (length < 12) return null
        var pos = 12
        val qd = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)
        val an = ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)
        if (an == 0) return null
        // 跳过 question 区
        repeat(qd.coerceAtMost(4)) {
            pos = skipName(data, pos) ?: return null
            pos += 4 // QTYPE + QCLASS
        }
        // 遍历 answer
        repeat(an.coerceAtMost(64)) {
            val nameInfo = readName(data, pos) ?: return null
            pos = nameInfo.first
            if (pos + 10 > length) return null
            val type = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF); pos += 2
            pos += 2 // class
            pos += 4 // ttl
            val rdlen = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF); pos += 2
            if (pos + rdlen > length) return null
            val name = nameInfo.second
            if (type == 33 && rdlen >= 6 &&
                (name.contains("_adb-tls-pairing") || name.contains("_adb-tls-connect"))
            ) {
                // SRV RDATA: priority(2) weight(2) port(2) target
                val port = ((data[pos + 4].toInt() and 0xFF) shl 8) or (data[pos + 5].toInt() and 0xFF)
                if (port in 1..65535) return port to name.contains("_adb-tls-pairing")
            }
            pos += rdlen
        }
        return null
    }

    /** 跳过（不展开）一个压缩名，返回结束位置（含指针/终止符）。 */
    private fun skipName(data: ByteArray, start: Int): Int? {
        var pos = start
        var guard = 0
        while (pos < data.size && guard++ < 64) {
            val len = data[pos].toInt() and 0xFF
            if (len == 0) return pos + 1
            if (len and 0xC0 == 0xC0) return pos + 2
            pos += 1 + len
        }
        return null
    }

    /** 展开压缩名，返回 (结束位置, 完整名字)。支持 0xC0 指针（最多跳一次）。 */
    private fun readName(data: ByteArray, start: Int): Pair<Int, String>? {
        var pos = start
        var end = -1
        var jumped = false
        val labels = ArrayList<String>(4)
        var guard = 0
        while (pos < data.size && guard++ < 64) {
            val len = data[pos].toInt() and 0xFF
            if (len == 0) {
                if (end == -1) end = pos + 1
                break
            }
            if (len and 0xC0 == 0xC0) {
                if (pos + 1 >= data.size) return null
                val off = ((len and 0x3F) shl 8) or (data[pos + 1].toInt() and 0xFF)
                if (end == -1) end = pos + 2
                if (jumped || off >= data.size) break
                jumped = true
                pos = off
                continue
            }
            if (pos + 1 + len > data.size) return null
            if (end == -1) end = pos + 1 + len
            labels.add(String(data, pos + 1, len, Charsets.US_ASCII))
            pos += 1 + len
        }
        return end to labels.joinToString(".")
    }

    companion object {
        const val TAG = "RawMdnsPortFinder"
        const val MDNS_GROUP = "224.0.0.251"
        const val MDNS_PORT = 5353
        const val RECEIVE_TIMEOUT_MILLIS = 2000
        const val QUERY_INTERVAL_MILLIS = 2000
    }
}
