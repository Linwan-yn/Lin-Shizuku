package moe.shizuku.privileged.api.ui.apps

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.shizuku.privileged.api.core.ShizukuManager
import moe.shizuku.privileged.api.data.AppInfo

/**
 * 电池优化管理。
 *
 * 加载策略（避免长时间卡转圈）：
 * 1. 应用列表 1~2 秒先出（图标并发 + LRU 缓存），列表先可见；
 * 2. 白名单状态后台查询：全量 dumpsys 解析（毫秒级）→ 格式不兼容时
 *    xargs 并行降级（限时 8 秒）→ 仍不行走串行逐应用（后台，不阻塞页面）；
 * 3. 白名单未读完前开关置灰，读完自动点亮，不阻塞列表交互。
 *
 * 记忆恢复：
 *  - 用户手动开关成功 → 期望状态写入记忆（SharedPreferences "battery_memory"）；
 *  - 仅当本次读到的真实白名单非空（可信）时，才对比记忆并按记忆恢复
 *    （系统重启后擅自增删时）；查询失败绝不恢复，避免误操作。
 */
class AppsViewModel(private val context: Context) : ViewModel() {

    private val _apps = MutableStateFlow<List<AppInfo>>(emptyList())
    val apps: StateFlow<List<AppInfo>> = _apps.asStateFlow()

    /** 当前在电池优化白名单中的包名集合。 */
    private val _whitelist = MutableStateFlow<Set<String>>(emptySet())
    val whitelist: StateFlow<Set<String>> = _whitelist.asStateFlow()

    /** 白名单是否已读取完成（未完成前开关置灰）。 */
    private val _whitelistLoaded = MutableStateFlow(false)
    val whitelistLoaded: StateFlow<Boolean> = _whitelistLoaded.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _serviceRunning = MutableStateFlow(false)
    val serviceRunning: StateFlow<Boolean> = _serviceRunning.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** 应用列表内存缓存，10 分钟有效，刷新免重建。 */
    private var appsCache: List<AppInfo>? = null
    private var appsCacheTime = 0L

    private fun getAppsCached(): List<AppInfo>? {
        val now = System.currentTimeMillis()
        val c = appsCache ?: return null
        return if (now - appsCacheTime < 10 * 60 * 1000L) c else null
    }

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _loading.value = true
            _message.value = null
            val running = ShizukuManager.getStatus() == ShizukuManager.Status.RUNNING
            _serviceRunning.value = running

            // 1. 应用列表先加载（缓存优先），列表先可见
            val cached = getAppsCached()
            if (cached != null) {
                _apps.value = cached
            } else {
                val apps = ShizukuManager.getAllAppsLight(context)
                _apps.value = apps
                appsCache = apps
                appsCacheTime = System.currentTimeMillis()
            }
            _loading.value = false

            if (!running) {
                _whitelist.value = emptySet()
                _whitelistLoaded.value = false
                return@launch
            }
            // 70 轮：服务运行但身份非特权（非 root 非 shell uid）——白名单需要 DUMP 权限，
            // 已走特权通道（root 提权），此处提示建议用 Root/无线调试启动服务（query 失败时会覆盖为权限错误提示）
            val serverUid = ShizukuManager.getServerUidCompat()
            if (serverUid != 0 && serverUid != 2000) {
                _message.value = "服务运行在普通应用身份（uid $serverUid），白名单通过 root 提权通道读取；建议用 Root 或无线调试方式启动服务"
            }

            // 2. 白名单后台查询（不阻塞列表）
            _whitelistLoaded.value = false
            val current = queryWhitelist()
            _whitelist.value = current
            _whitelistLoaded.value = true

            // 3. 仅在白名单读取成功（非空、可信）时按记忆恢复
            if (current.isNotEmpty()) {
                restoreMemory(current)
            }
        }
    }

    /**
     * 白名单查询三级降级：
     * ① 全量 dumpsys deviceidle whitelist（一次命令，毫秒级）；
     * ② xargs -P 8 并行逐应用查询（限时 8 秒，toybox 支持时秒级）；
     * ③ 串行 while-read 逐应用查询（后台兜底，已知兼容多厂商）。
     * 全量解析为空时把原始输出片段写入提示，便于适配厂商格式。
     */
    private suspend fun queryWhitelist(): Set<String> = withContext(Dispatchers.IO) {
        // 70 轮：白名单读取走特权通道（服务端 root/shell → su 提权 → 明确提示），
        // 避免普通应用身份执行 dumpsys 出现 Permission Denial（荣耀等厂商无 DUMP 权限）
        val raw = try {
            ShizukuManager.runPrivilegedCommand("dumpsys deviceidle whitelist")
        } catch (_: Throwable) { "" }
        var set = parseWhitelist(raw)
        if (set.isNotEmpty()) return@withContext set

        // 权限拒绝：直接中文提示并跳过逐应用降级（无权限时降级只会重复 Permission Denial）
        if (raw.contains("Permission Denial") || raw.contains("SHIZUKU_NO_PRIVILEGE")) {
            _message.value = if (raw.contains("SHIZUKU_NO_PRIVILEGE")) {
                "无法读取白名单：当前服务身份无 DUMP 权限。请用 Root 或无线调试（Shell）方式启动服务后刷新"
            } else {
                "无法读取白名单：缺少 DUMP 权限（服务身份受限）。请用 Root 或无线调试启动服务，或授予本应用 root 权限"
            }
            return@withContext emptySet()
        }

        // 全量解析失败：提示原始输出前 200 字符（诊断厂商格式）
        val sample = raw.take(200).replace("\n", " ⏎ ")
        _message.value = "白名单全量格式未识别，原始输出：$sample"

        // ② xargs 并行（限时 8 秒）
        val p = try {
            withTimeoutOrNull(8000) { queryByXargsParallel() }
        } catch (_: Throwable) { null }
        if (!p.isNullOrEmpty()) return@withContext p

        // ③ 串行兜底（后台跑，不阻塞）
        try {
            withTimeoutOrNull(25000) { queryOneByOneSerial() } ?: emptySet()
        } catch (_: Throwable) { emptySet() }
    }

    /**
     * 兼容多厂商输出格式的解析：
     * 1) HyperOS/MIUI CSV 格式——`类型,包名,uid`（如 `user,com.miui.gallery,10252`）；
     * 2) AOSP 标准格式——纯包名行（跳过标题行）；
     * 3) 厂商带 uid 格式——包名 (uid=123) / 包名 (123)；
     * 4) 键值格式——package=true / package : true。
     */
    private fun parseWhitelist(out: String): Set<String> {
        if (out.isBlank()) return emptySet()
        val set = mutableSetOf<String>()
        // 1) HyperOS/MIUI CSV：type,package,uid（type ∈ user/system/system-excidle ...）
        val csv = Regex("""^[a-z][a-z-]*,([A-Za-z0-9_.]+),\d+\s*$""")
        for (line in out.lineSequence()) {
            val m = csv.find(line.trim())
            if (m != null) set.add(m.groupValues[1])
        }
        if (set.isNotEmpty()) return set
        // 2) 标准 AOSP：每行一个包名（缩进），过滤标题行
        for (line in out.lineSequence()) {
            val t = line.trim()
            if (t.isEmpty()) continue
            if (t.startsWith("Whitelist") || t.endsWith(":") || t.contains("apps:")) continue
            if (t.all { it.isLetterOrDigit() || it == '.' || it == '_' }) {
                set.add(t)
            }
        }
        if (set.isNotEmpty()) return set
        // 3) 厂商带 uid 格式：com.foo (uid=123) / com.foo (123)
        for (re in listOf(
            Regex("""([A-Za-z0-9_.]+)\s*\(uid=\d+\)"""),
            Regex("""([A-Za-z0-9_.]+)\s*\(\d+\)""")
        )) {
            for (m in re.findAll(out)) set.add(m.groupValues[1])
            if (set.isNotEmpty()) return set
        }
        // 4) 键值格式：com.foo=true / com.foo = true / com.foo: true
        val kv = Regex("""([A-Za-z][A-Za-z0-9_.]{2,})\s*[=:]\s*(true|enabled)""")
        for (m in kv.findAll(out)) {
            set.add(m.groupValues[1])
        }
        return set
    }

    /** 降级②：xargs -P 8 并行逐应用查询。 */
    private suspend fun queryByXargsParallel(): Set<String> = withContext(Dispatchers.IO) {
        val set = mutableSetOf<String>()
        try {
            val out = ShizukuManager.runPrivilegedCommand(
                "pm list packages | sed 's/package://' | xargs -P 8 -I{} sh -c 'r=\$(dumpsys deviceidle whitelist \"={}\" 2>/dev/null); [ \"\$r\" = \"true\" ] && echo \"{}\"'"
            )
            for (line in out.lineSequence()) {
                val p = line.trim()
                if (p.isNotEmpty() && p.all { it.isLetterOrDigit() || it == '.' || it == '_' }) {
                    set.add(p)
                }
            }
        } catch (_: Throwable) {}
        set
    }

    /** 降级③：串行 while-read 逐应用查询（兼容性最好）。 */
    private suspend fun queryOneByOneSerial(): Set<String> = withContext(Dispatchers.IO) {
        val set = mutableSetOf<String>()
        try {
            val out = ShizukuManager.runPrivilegedCommand(
                "pm list packages | sed 's/package://' | while read -r p; do r=\$(dumpsys deviceidle whitelist \"=\$p\" 2>/dev/null); [ \"\$r\" = \"true\" ] && echo \"\$p\"; done"
            )
            for (line in out.lineSequence()) {
                val p = line.trim()
                if (p.isNotEmpty() && p.all { it.isLetterOrDigit() || it == '.' || it == '_' }) {
                    set.add(p)
                }
            }
        } catch (_: Throwable) {}
        set
    }

    /** 加入 / 移出电池优化白名单（乐观更新，确认成功后写入记忆）。 */
    fun toggle(app: AppInfo) {
        if (!_serviceRunning.value) {
            _message.value = "Shizuku 服务未运行，无法修改白名单"
            return
        }
        if (!_whitelistLoaded.value) {
            _message.value = "白名单状态还在读取中，请稍候再切换"
            return
        }
        val inList = _whitelist.value.contains(app.packageName)
        _whitelist.value = if (inList) _whitelist.value - app.packageName else _whitelist.value + app.packageName
        viewModelScope.launch(Dispatchers.IO) {
            val cmd = if (inList) {
                "dumpsys deviceidle whitelist -${app.packageName}"
            } else {
                "dumpsys deviceidle whitelist +${app.packageName}"
            }
            val out = try { ShizukuManager.runPrivilegedCommand(cmd) } catch (_: Throwable) { "" }
            val newSet = try {
                parseWhitelist(ShizukuManager.runPrivilegedCommand("dumpsys deviceidle whitelist"))
            } catch (_: Throwable) { emptySet() }
            var success = true
            if (newSet.isNotEmpty()) {
                val changed = if (inList) !newSet.contains(app.packageName) else newSet.contains(app.packageName)
                _whitelist.value = newSet
                if (!changed) {
                    _whitelist.value = if (inList) _whitelist.value + app.packageName else _whitelist.value - app.packageName
                    _message.value = "${if (inList) "移出" else "加入"}白名单失败（部分厂商系统会限制 deviceidle 命令）：$out"
                    success = false
                }
            } else if (out.isBlank()) {
                _whitelist.value = if (inList) _whitelist.value + app.packageName else _whitelist.value - app.packageName
                _message.value = "${if (inList) "移出" else "加入"}白名单失败（命令无响应）：$out"
                success = false
            }
            if (success) saveMemory(app.packageName, !inList)
        }
    }

    /** 记忆恢复：对比记忆与当前真实白名单 current，不一致按记忆执行恢复。 */
    private suspend fun restoreMemory(current: Set<String>) {
        val mem = memoryPrefs()
        val entries = mem.all.filterKeys { it.startsWith("mem_") }
        if (entries.isEmpty()) return
        val toRestore = mutableListOf<Pair<String, Boolean>>()
        for ((k, v) in entries) {
            val pkg = k.removePrefix("mem_")
            if (pkg.isEmpty()) continue
            val wantIn = v as? Boolean ?: continue
            if (wantIn && pkg !in current) toRestore.add(pkg to true)
            if (!wantIn && pkg in current) toRestore.add(pkg to false)
        }
        if (toRestore.isEmpty()) return
        var restored = 0
        withContext(Dispatchers.IO) {
            for ((pkg, wantIn) in toRestore) {
                val cmd = if (wantIn) "dumpsys deviceidle whitelist +$pkg" else "dumpsys deviceidle whitelist -$pkg"
                try { ShizukuManager.runPrivilegedCommand(cmd); restored++ } catch (_: Throwable) {}
            }
        }
        if (restored > 0) {
            val newSet = try {
                parseWhitelist(ShizukuManager.runPrivilegedCommand("dumpsys deviceidle whitelist"))
            } catch (_: Throwable) { emptySet() }
            if (newSet.isNotEmpty()) _whitelist.value = newSet
            _message.value = "检测到系统改动了白名单，已按记忆恢复 ${restored} 项"
        }
    }

    private fun memoryPrefs(): android.content.SharedPreferences =
        context.getSharedPreferences("battery_memory", Context.MODE_PRIVATE)

    private fun saveMemory(pkg: String, wantIn: Boolean) {
        try {
            memoryPrefs().edit().putBoolean("mem_$pkg", wantIn).apply()
        } catch (_: Throwable) {}
    }

    fun clearMessage() {
        _message.value = null
    }
}
