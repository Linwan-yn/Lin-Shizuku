package moe.shizuku.privileged.api.util

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 1.1.1 v4.9：本机回环端口盲扫兜底（社区文档路线 B）。
 *
 * 场景：mDNS 组播被 VPN / 代理 / 厂商劫持发不出去或收不到时，
 * RawMdnsPortFinder 约 50 秒超时仍未命中——此时直接并发扫描
 * 127.0.0.1 的 30000-50000 端口段（adb 无线配对端口动态分配区间），
 * 命中 adbd 临时监听的 TLS 配对端口。
 *
 * 安全性：只扫本机回环（127.0.0.1），不涉及局域网设备，100% 不触发
 * 系统「扫描局域网设备」安全机制 / 弹窗。配对端口在本机，用户实测
 * 截图（37637 / 38209 / 38973 / 40303 / 46529）均在 30000-50000 区间。
 *
 * 注意：扫到的连通端口不保证一定是配对服务（可能命中其他 App 监听端口），
 * 调用方拿到候选列表后应逐个尝试 TLS+SPAKE2 配对，失败换下一个。
 */
object LocalPortScanner {
    const val TAG = "LocalPortScanner"
    const val START_PORT = 30000
    const val END_PORT = 50000
    // v4.12：15ms → 60ms——回环 connect 正常 <5ms，60ms 足够排除系统时序抖动，
    // 避免配对端口（adbd 刚建立、响应稍慢）被 15ms 超时误判为"非活端口"漏掉
    const val CONNECT_TIMEOUT_MS = 60
    const val CHUNK_SIZE = 100

    /**
     * 并发扫描本机回环端口段，返回前 [limit] 个能 TCP 连通的端口（升序）。
     * 耗时估算：20000 端口 / 100 并发 × ~20ms ≈ 4 秒内完成。
     *
     * v5.1：新增 [exclude]——配对盲扫/预取时排除已知的无线调试连接端口
     * （EnvironmentUtils.getAdbTcpPort 可直读），避免盲扫先命中长期存在的
     * 连接端口、把"连接端口"当成"配对端口"填入（实测配对端口 33881 与
     * 连接端口 33567 同在动态段，先扫到连接端口 → TLS 配对 → Socket is
     * closed 假失败）。配对端口只在系统配对弹窗生命周期内临时监听。
     */
    suspend fun findOpenPorts(
        limit: Int = 8,
        start: Int = START_PORT,
        end: Int = END_PORT,
        exclude: Set<Int> = emptySet()
    ): List<Int> = withContext(Dispatchers.IO) {
        val found = ArrayList<Int>(limit)
        var cursor = start
        while (cursor <= end && found.size < limit) {
            val hi = minOf(cursor + CHUNK_SIZE - 1, end)
            val jobs = (cursor..hi).map { port ->
                async {
                    if (port in exclude) null
                    else if (isOpen(port)) port else null
                }
            }
            // awaitAll 保持顺序 → found 升序
            for (p in jobs.awaitAll()) {
                if (p != null) {
                    found.add(p)
                    if (found.size >= limit) break
                }
            }
            cursor = hi + 1
        }
        Log.i(TAG, "盲扫完成，候选端口: $found（排除: $exclude）")
        found
    }

    /** 15ms 超时 TCP connect 探测（连上立即关闭，仅探活性）。 */
    private fun isOpen(port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), CONNECT_TIMEOUT_MS) }
        true
    } catch (_: Exception) {
        false
    }
}
