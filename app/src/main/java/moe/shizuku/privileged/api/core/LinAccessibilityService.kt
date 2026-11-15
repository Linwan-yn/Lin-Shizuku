package moe.shizuku.privileged.api.core

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 无障碍服务：自启/保活 + 自动化配对。
 *
 * 1.1.1 v4.2（202705 后续）：对照官方 thedjchi 13.7.0 重写抓取链路——
 *  1) 轻量化：只读「事件源单节点文本」做正则匹配（thedjchi 同款，主线程零开销），
 *     彻底消除 v4 整树扫描导致的主线程 ANR（用户实测「应用没有响应」弹窗）；
 *  2) 格式兼容：配对码支持 HyperOS「074855」独立成行（^\d{6}$ 全匹配）
 *     与 AOSP「配对码 123456」行内格式（含关键词节点内 find）；
 *     端口支持「192.168.0.5:46529」IP:port 任意文本匹配 + 端口关键词兜底；
 *  3) 跨事件累积：码/端口在配对弹窗生命周期内跨事件累积（实例字段），
 *     码先到、端口后到（或反之）也能凑齐后统一触发，不再依赖单事件同时命中；
 *  4) 兜底扫描放后台线程（仅新窗口 STATE_CHANGED 且单节点无果时，
 *     扫 source 子树前 4 层，量级远小于整树），主线程永不做树遍历。
 */
class LinAccessibilityService : AccessibilityService() {

    /** 配对弹窗生命周期内跨事件累积（码/端口任一先到都暂存；90 秒过期自动重置）。 */
    private var capturedCode: String? = null
    private var capturedPort: String? = null
    private var capturedTime: Long = 0L

    /** v4.4：跨事件累积限同窗口——「无线调试」设置页与「与设备配对」弹窗各有独立
     *  windowId；设置页的连接端口、其他窗口文本不会污染配对码/端口配对。 */
    private var capturedWindowId = -1

    override fun onCreate() {
        super.onCreate()
        // 1.1.2 修复 crash：lateinit sp has not been initialized。
        // 系统在 direct boot 阶段（用户未解锁）就 bind 无障碍服务，此时
        // ShizukuApp.onCreate 里的 Prefs.init 会因 credential-encrypted storage
        // 不可用而抛异常并被 catch 吞掉，导致 sp 一直未初始化；onAccessibilityEvent
        // 一访问 Prefs.autoPairEnabled 就 crash。这里兜底再 init 一次，
        // 成功路径（已解锁）直接 OK，失败路径（仍在 direct boot）用空 prefs 降级。
        runCatching { Prefs.init(this) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        // 1.1.2 修复 crash：direct boot 阶段 sp 未初始化时直接返回，不访问 Prefs。
        if (!Prefs.isReady) return
        // 自动化配对开关关闭 → 跳过
        if (!Prefs.autoPairEnabled) return
        // 跳过自身包的窗口事件（本应用 UI 事件频率最高，与系统配对弹窗无关）。
        // v4.5：不再限定系统设置包——实测 HyperOS「与设备配对」弹窗的窗口归属无法
        // 保证是 com.android.settings（白名单会挡掉真实弹窗导致完全抓不到）；
        // 防误抓改由「成对判定」承担（见 updateCaptured）：单码/单端口只暂存同窗口，
        // 只有码+端口成对（配对弹窗特征）才写入并广播，MT 版本号等干扰永不落库。
        val pkg = event.packageName?.toString()
        if (pkg == packageName) return
        val now = System.currentTimeMillis()
        val windowId = event.windowId
        // 窗口切换（设置页 → 配对弹窗 / 反向）→ 重置跨事件累积，
        // 防止旧窗口残留的连接端口/码污染新窗口的配对识别
        if (windowId != capturedWindowId) {
            capturedWindowId = windowId
            capturedCode = null
            capturedPort = null
            capturedTime = 0L
        }

        when (event.eventType) {
            // 轻量主路径：文本变化 / 窗口切换时，只读事件源节点文本（单次正则，主线程微秒级）
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (now - lastScanTime < 1000L) return
                lastScanTime = now
                val text = event.source?.text?.toString()
                val code = text?.let { extractCode(it) }
                val port = text?.let { extractPort(it) }
                if (code != null || port != null) {
                    updateCaptured(code, port)
                    // v4.4：码已识别、端口未识别 → 后台扫同窗口子树补齐配对端口
                    //（配对弹窗内「IP 地址和端口 192.168.0.5:38209」在子节点），
                    // 补齐后全量广播自动配对；补不到则由配对页主动消费（手动填端口）。
                    if (code != null && port == null) {
                        val source = event.source
                        if (source != null) {
                            Thread {
                                runCatching {
                                    val info = findPairingInfoShallow(source, depth = 0)
                                    if (info.port != null) updateCaptured(null, info.port)
                                }
                            }.start()
                        }
                    }
                    return
                }
                // 单节点无果且是新窗口弹出（STATE_CHANGED）：后台线程扫 source 子树（前 4 层），
                // 主线程不做任何树遍历，杜绝「应用没有响应」ANR
                if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                    val source = event.source ?: return
                    Thread {
                        runCatching {
                            val info = findPairingInfoShallow(source, depth = 0)
                            if (info.code != null || info.port != null) {
                                updateCaptured(info.code, info.port)
                            }
                        }
                    }.start()
                }
            }
            else -> {}
        }
    }

    /** 累积码/端口（限当前窗口）：v4.5「成对判定」——只有码+端口齐才写入 prefs 并广播；
     *  单码/单端口仅暂存（90 秒过期），MT 应用信息页「版本号 202706」等 6 位数字干扰
     *  永远只停留在暂存层，不会误填进配对页。 */
    private fun updateCaptured(code: String?, port: String?) {
        // 1.1.2：本应用版本号（202732）本身也是 6 位数字，出现在其他 App 的界面里
        // （对话截图、应用信息页「版本号 202732」）时会被 extractCode 当成配对码暂存，
        // 之后同窗口一旦识别到端口就会拼成一个错误的配对码并自动配对。
        // 版本号只可能是本机当前版本，直接丢弃，不进暂存层。
        val filteredCode = code?.takeIf {
            it != moe.shizuku.privileged.api.BuildConfig.VERSION_CODE.toString()
        }
        // 90 秒过期重置（配对弹窗生命周期短，防旧弹窗的码/端口残留污染新弹窗）
        if (System.currentTimeMillis() - capturedTime > 90_000L) {
            capturedCode = null
            capturedPort = null
        }
        filteredCode?.let { capturedCode = it }
        port?.let { capturedPort = it }
        capturedTime = System.currentTimeMillis()
        val c = capturedCode ?: return
        val p = capturedPort
        if (p == null) {
            // 只有码没有端口：不写 prefs 不广播（无法确证是配对弹窗；版本号干扰即属此类），
            // 等待同窗口后续事件/后台补齐端口后成对再触发；补不到则由 mDNS 通道/手动兜底。
            DiagnosticLog.append("[配对] 无障碍识别到疑似配对码 $c（同窗口未发现端口，等待补齐或由 mDNS/手动兜底）")
            return
        }
        Prefs.pendingPairCode = c
        Prefs.pendingPairPort = p
        DiagnosticLog.append("[配对] 无障碍检测到系统配对码 $c + 配对端口 $p，自动配对中")
        runCatching {
            sendBroadcast(Intent(ACTION_AUTO_PAIR_CODE).setPackage(packageName))
        }
    }

    /** 码：优先整段 6 位数字（HyperOS「074855」独立成行）；否则含配对关键词节点内 6 位数字（AOSP 行内格式）。 */
    private fun extractCode(text: String): String? {
        val t = text.trim()
        if (Regex("""^\d{6}$""").matches(t)) return t
        if (t.contains("配对") || t.contains("pairing") || t.contains("code") || t.contains("Code")) {
            Regex("""\d{6}""").find(t)?.let { return it.value }
        }
        return null
    }

    /** 端口：完整 IP:port（"192.168.0.5:46529" / "127.0.0.1: 46529"）；否则含端口关键词节点结尾 2-5 位数字。 */
    private fun extractPort(text: String): String? {
        Regex("""(?:\d{1,3}\.){3}\d{1,3}:\s*(\d{2,5})""").find(text)?.let { return it.groupValues[1] }
        if (text.contains("端口") || text.contains("port") || text.contains("Port")) {
            Regex("""(\d{2,5})\s*$""").find(text.trim())?.let { return it.groupValues[1] }
        }
        return null
    }

    /** 兜底子树扫描（后台线程）：只扫 source 子树前 4 层，量级远小于整树。 */
    private fun findPairingInfoShallow(node: AccessibilityNodeInfo?, depth: Int): PairInfo {
        if (node == null || depth > 4) return PairInfo(null, null)
        var code: String? = null
        var port: String? = null
        node.text?.toString()?.let { text ->
            extractCode(text)?.let { code = it }
            extractPort(text)?.let { port = it }
            if (code != null && port != null) return PairInfo(code, port)
        }
        for (i in 0 until node.childCount) {
            val child = findPairingInfoShallow(node.getChild(i), depth + 1)
            if (child.code != null) code = child.code
            if (child.port != null) port = child.port
            if (code != null && port != null) return PairInfo(code, port)
        }
        return PairInfo(code, port)
    }

    /** 无障碍扫描结果：配对码 + 配对端口（任一可为 null）。 */
    private data class PairInfo(val code: String?, val port: String?)

    override fun onInterrupt() {}

    override fun onServiceConnected() {
        super.onServiceConnected()
        // 兜底：Service 被系统直接 bind 时（direct boot），Application 可能还没跑过
        // Prefs.init，这里再试一次；失败则后续逻辑由 isReady 守卫。
        runCatching { Prefs.init(this) }
        runCatching {
            if (!Prefs.isReady) return@runCatching
            // 90bb：服务被系统（重新）拉起 → 立即恢复强力保活（15s 无障碍心跳 + 闹钟兜底），
            // 防止服务被 ColorOS 强杀后保活服务也不在跑、心跳断档
            if (Prefs.strongKeepAlive) {
                StrongKeepAliveService.start(this)
            }
            if (!Prefs.startOnBoot) return@runCatching
            if (!Prefs.accessibilityBoot) return@runCatching
            if (ShizukuManager.isRunningCompat()) return@runCatching
            // 触发共享开机启动序列（BootReceiver.tryAutoStart 内部 IO 协程 + 防重入）
            BootReceiver.tryAutoStart(this)
        }
    }

    /** 90bb：服务被系统关闭（强杀/省电回收）时尽力立即回写 Settings.Secure——
     *  进程被 kill 时 onDestroy 不一定触发，但触发时能提前恢复，不必等 15s 心跳。 */
    override fun onDestroy() {
        runCatching {
            val serviceName = packageName + "/.core.LinAccessibilityService"
            val current = android.provider.Settings.Secure.getString(
                contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            val services = current.split(":")
                .map { it.trim() }
                .filter { it.isNotEmpty() && it != "null" }
                .toMutableSet()
            if (services.contains(serviceName)) return@runCatching
            services.add(serviceName)
            android.provider.Settings.Secure.putString(
                contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                services.joinToString(":")
            )
            android.provider.Settings.Secure.putString(contentResolver, "accessibility_enabled", "1")
            // UI 联动：恢复成功 → 广播通知前台刷新
            runCatching {
                ShizukuManager.notifyServiceStateChanged(this)
            }
        }
        super.onDestroy()
    }

    companion object {
        /** 自动化配对广播：无障碍服务提取到配对码后发出，配对页接收后自动配对。 */
        const val ACTION_AUTO_PAIR_CODE =
            "moe.shizuku.privileged.api.action.AUTO_PAIR_CODE"

        /** 扫描限流时间戳——配对码检测无需高频，限制开销防拖慢系统 UI。 */
        @Volatile
        private var lastScanTime: Long = 0L
    }
}
