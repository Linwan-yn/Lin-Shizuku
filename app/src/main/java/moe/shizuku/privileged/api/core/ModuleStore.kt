package moe.shizuku.privileged.api.core

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/** 脚本条目（真实数据，来自 <MODULE_ROOT>/<id>/run.sh）。 */
data class ScriptItem(
    val id: String,
    val name: String,
    val author: String = "",
    val brand: String = "",
    val description: String = "",
    val isToggle: Boolean = false,
    val enabled: Boolean = false,
    val triggerBoot: Boolean = false,
    val triggerPackage: String = ""
)

/** Lin 模块条目（真实数据，来自 <MODULE_ROOT>/<id>/module.json）。 */
data class LinModuleItem(
    val id: String,
    val name: String,
    val description: String = "",
    val version: String = "",
    val author: String = "",
    val enabled: Boolean = false,
    val webui: String = "",
    val hasAction: Boolean = false,
    val hasService: Boolean = false,
    val lastResult: String = "",
    val sizeLabel: String = "—"
)

/** 安装结果（66 轮）：success=是否成功；log=完整逐步日志（含时间戳）；errorCode=错误码；errorText=错误摘要。 */
data class InstallResult(
    val success: Boolean,
    val log: String,
    val errorCode: Int = OpErrorCode.OK,
    val errorText: String = ""
)

/** 安装/执行错误码（失败弹窗展示「错误代码：N」，对齐模块管理器交互）。 */
object OpErrorCode {
    const val OK = 0
    const val UNZIP = 1           // 压缩包读取/解压失败
    const val METADATA = 2        // 模块元数据缺失或非法（缺 module.json/module.prop、无合法 id）
    const val MKDIR = 3           // 模块目录创建失败（shell 执行异常）
    const val WRITE = 4           // 模块文件写入失败（shell 执行异常）
    const val CHMOD = 5           // 权限设置失败
    const val OTHER = 6           // 其他异常
    const val SCRIPT_MISSING = 10 // 脚本/模块内容为空或不存在
    const val TIMEOUT = 11        // 执行超时
    const val EXEC_FAIL = 12      // 命令执行失败（退出码非 0）
}

/**
 * 模块页真实数据层：统一管理 Shell 脚本与 Lin 模块。
 *
 * 存储目录（Shizuku shell 天然可读写）：
 *   - 脚本：/data/local/tmp/Lin-Shizuku/script/<脚本名>.sh（文件名即脚本名，中文友好）
 *   - 模块：/data/local/tmp/Lin-Shizuku/module/<id>/（module.json + run.sh + webroot/）
 *
 * 所有文件操作均通过 Shizuku shell 完成（app 进程无法直接访问 /data/local/tmp）。
 */
object ModuleStore {

    const val MODULE_ROOT = "/data/local/tmp/Lin-Shizuku/module"
    const val SCRIPT_ROOT = "/data/local/tmp/Lin-Shizuku/script"
    // 90af：模块主存储迁到内部私有目录（可 exec、app 零特权写、服务未运行也能安装）。
    // 外部 /storage/emulated/0 为 noexec 挂载（模块含 busybox 等二进制无法执行），故不采用外部路径。
    const val MODULE_HOME = "/data/user/0/moe.shizuku.privileged.api/files/lin-modules"

    // ---------- 90ae：Lin-Shizuku 自有存储路径（外部私有目录） ----------
    /**
     * 脚本存储目录：外部私有目录
     *   /storage/emulated/0/Android/data/moe.shizuku.privileged.api/files/lin-scripts/
     * （90ae 采用；app 自有路径，零特权可写，不依赖 Shizuku/root 服务运行，
     *   文件对用户可见、便于管理/备份）。
     * 兼容读链：外部自有目录 → 内部 filesDir/lin-scripts（90ad 曾保存） → 老路径
     *   /data/local/tmp/Lin-Shizuku/script（1.0.1~1.0.7 历史版本）。
     * 执行侧不受影响：OutputActivity 等均「读内容→内联执行」，不依赖脚本文件路径。
     */
    private fun scriptStoreDir(ctx: Context): File {
        // getExternalFilesDir 为 /storage/emulated/0/Android/data/<pkg>/files
        val external = try { ctx.getExternalFilesDir(null) } catch (_: Throwable) { null }
        return external?.let { File(it, "lin-scripts") }
            ?: File(ctx.filesDir, "lin-scripts") // 外部不可用时兜底内部
    }

    private fun scriptStoreFile(ctx: Context, id: String): File =
        File(scriptStoreDir(ctx), "$id.sh")

    /** 90ad 曾使用的内部私有目录（兼容读，保证已保存脚本不丢）。 */
    private fun internalScriptFile(ctx: Context, id: String): File =
        File(File(ctx.filesDir, "lin-scripts"), "$id.sh")

    /** 统一读取：外部自有目录 → 内部目录（90ad）→ 老路径（1.0.1~1.0.7）兼容读。 */
    private fun readScriptContent(ctx: Context, id: String): String {
        for (f in listOf(scriptStoreFile(ctx, id), internalScriptFile(ctx, id))) {
            try { if (f.isFile) return f.readText() } catch (_: Throwable) {}
        }
        return sh("cat '$SCRIPT_ROOT/$id.sh' 2>/dev/null").trim()
    }

    /** 统一删除：外部 + 内部 + 老路径都清。 */
    private fun removeScriptFiles(ctx: Context, id: String) {
        try { scriptStoreFile(ctx, id).delete() } catch (_: Throwable) {}
        try { internalScriptFile(ctx, id).delete() } catch (_: Throwable) {}
        sh("rm -f '$SCRIPT_ROOT/$id.sh'")
    }

    /** 脚本文件名清洗：保留中文/括号/字母数字._-，其余转下划线（防 shell 注入）。 */
    private fun sanitizeScriptName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9_.\\-（）()\u4e00-\u9fa5]"), "_")
            .trim('_', ' ', '.')
            .ifBlank { "script" }

    /** 90af：模块目录 sh 路径——内部自有路径存在则用内部，否则回退老路径（历史模块兼容）。 */
    fun moduleShPath(id: String): String =
        if (File(MODULE_HOME, id).exists()) "$MODULE_HOME/$id" else "$MODULE_ROOT/$id"

    /** 90af：模块目录 File（app 视角）——内部自有路径优先，老路径兼容。 */
    fun moduleFileDir(ctx: Context, id: String): File =
        if (File(ctx.filesDir, "lin-modules/$id").isDirectory) File(ctx.filesDir, "lin-modules/$id")
        else File(MODULE_ROOT, id)

    private fun sh(cmd: String): String = runBlocking { ShizukuManager.runCommand(cmd) }

    private fun shOk(cmd: String): Boolean = sh(cmd).trim() == "1"

    // ---------- 开关状态持久化 ----------
    private const val PREFS = "module_states"

    fun isEnabled(ctx: Context, id: String): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(id, false)

    fun setEnabled(ctx: Context, id: String, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(id, on).apply()
    }

    fun clearEnabled(ctx: Context, id: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(id).apply()
    }

    // ---------- 模块更新检测（覆盖安装后默认关闭） ----------
    // 模块「定义文件」最新 mtime 变化 = 模块被更新/重装：停旧进程、清状态、开关重置为关闭，
    // 避免「开关残留开启但 monitor 仍是旧进程/已死」的脱节。
    // v49：stamp key 更换为内容指纹版，避免升级 App 时旧目录 mtime stamp 与新值不同而误重置。
    // v50：stamp 范围收窄为「模块定义文件」——只有 module.json/module.prop/run.sh/service.sh/
    //       stop.sh/uninstall.sh 参与指纹。覆盖安装/外部更新必然重写这些文件（保留「更新后默认关闭」），
    //       而模块运行写入的数据/状态/缓存/WebUI 动态文件不再参与，根治「开关打开一段时间后自己关闭」
    //       （48 轮靠排除后缀仍堵不完运行写入的任意后缀文件，50 轮回归根因）。
    private const val STAMP_PREFIX = "module_content_stamp_"

    private fun moduleDirStamp(ctx: Context, id: String): String {
        // 90af：内部自有路径模块用 app File 读 mtime（服务未运行也可检测）；老路径走 sh find
        val internal = File(ctx.filesDir, "lin-modules/$id")
        if (internal.isDirectory) {
            return runCatching {
                val names = setOf("module.json", "module.prop", "run.sh", "service.sh", "stop.sh", "uninstall.sh")
                internal.listFiles()
                    ?.filter { it.isFile && it.name in names }
                    ?.mapNotNull { it.lastModified() }
                    ?.maxOrNull()?.toString()
            }.getOrNull() ?: ""
        }
        return runCatching {
            sh(
                "find '$MODULE_ROOT/$id' -maxdepth 1 -type f " +
                    "\\( -name 'module.json' -o -name 'module.prop' -o -name 'run.sh' " +
                    "-o -name 'service.sh' -o -name 'stop.sh' -o -name 'uninstall.sh' \\) " +
                    "-exec stat -c %Y {} \\; 2>/dev/null | sort -nr | head -1"
            )
        }.getOrNull()?.trim() ?: ""
    }

    /**
     * 检测模块是否被更新/重装（定义文件 mtime 变化）。
     * 返回 true 表示已检测到更新并完成重置：停旧进程 + enabled=false + 清结果回显。
     * 首次记录（无历史 stamp）只登记不重置。
     */
    fun checkModuleUpdated(ctx: Context, id: String): Boolean {
        val stamp = moduleDirStamp(ctx, id)
        if (stamp.isBlank()) return false
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val old = prefs.getString(STAMP_PREFIX + id, "") ?: ""
        if (old.isEmpty()) {
            prefs.edit().putString(STAMP_PREFIX + id, stamp).apply()
            return false
        }
        if (old != stamp) {
            prefs.edit().putString(STAMP_PREFIX + id, stamp).apply()
            runCatching { stopModule(ctx, id) }      // 停旧 monitor（含 pkill 兜底 + 清 .pid）
            setEnabled(ctx, id, false)                // 更新后默认关闭
            clearModuleResult(ctx, id)
            return true
        }
        return false
    }

    /**
     * 模块页/引擎加载时统一检测：对全部已安装模块做更新检测。
     * 模块被重装/更新（定义文件 mtime 变化）→ 停旧进程 + 开关重置为关闭 + 清结果回显，
     * 保证「更新后默认关闭、用户手动打开才正常运行」。返回被重置的模块 id 摘要。
     * 82 轮：一次 sh 批量取全部模块 stamp（@@S:<id>=<stamp>），替代逐模块 find。
     */
    fun checkModulesUpdated(ctx: Context): String {
        if (!ShizukuManager.isRunningCompat()) return ""
        val raw = sh(
            "for d in '$MODULE_ROOT'/*/; do [ -d \"\$d\" ] || continue; " +
                "id=\$(basename \"\$d\"); " +
                "[ -f \"\$d/module.json\" -o -f \"\$d/module.prop\" ] || continue; " +
                // 90t：存量模块权限幂等修正——早期版本安装时非 .sh 文件被统一 644，
                // busybox 等内置二进制无执行位 → 运行报「busybox: inaccessible or not found」。
                // 90u：追加 chcon shell_exec——/data/local/tmp 默认 shell_data_file 类型在
                // SELinux enforcing 下禁止 shell 域 exec 原生 ELF（chmod 修不了），
                // 标为 shell_exec 后 shell 域可执行。失败静默（无权限时至少 chmod 生效）
                "chmod -R 755 \"\$d\" 2>/dev/null; " +
                "chcon -R u:object_r:shell_exec:s0 \"\$d\" 2>/dev/null; " +
                "st=\$(find \"\$d\" -maxdepth 1 -type f " +
                "\\( -name 'module.json' -o -name 'module.prop' -o -name 'run.sh' " +
                "-o -name 'service.sh' -o -name 'stop.sh' -o -name 'uninstall.sh' \\) " +
                "-exec stat -c %Y {} \\; 2>/dev/null | sort -nr | head -1); " +
                "printf '@@S:%s=%s\\n' \"\$id\" \"\$st\"; done"
        )
        val reset = StringBuilder()
        for (part in raw.split("@@S:").drop(1)) {
            val id = part.substringBefore('=').trim()
            val stamp = part.substringAfter('=').trim()
            if (id.isBlank()) continue
            if (checkModuleUpdatedStamp(ctx, id, stamp)) {
                if (reset.isNotEmpty()) reset.append("；")
                reset.append(id)
            }
        }
        // 90af：内部自有路径模块（sh 批量命令扫不到）逐模块 File stamp 检测
        val home = File(ctx.filesDir, "lin-modules")
        if (home.isDirectory) {
            home.listFiles()?.filter { it.isDirectory }?.forEach { d ->
                val id = d.name
                if (reset.contains(id)) return@forEach
                val stamp = moduleDirStamp(ctx, id)
                if (stamp.isNotBlank() && checkModuleUpdatedStamp(ctx, id, stamp)) {
                    if (reset.isNotEmpty()) reset.append("；")
                    reset.append(id)
                }
            }
        }
        return if (reset.isNotEmpty()) "已检测模块更新并重置为关闭: $reset" else ""
    }

    /** 82 轮：纯 SharedPreferences 比较 + 重置（无 shell 往返），供批量检测复用。 */
    private fun checkModuleUpdatedStamp(ctx: Context, id: String, stamp: String): Boolean {
        if (stamp.isBlank()) return false
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val old = prefs.getString(STAMP_PREFIX + id, "") ?: ""
        if (old.isEmpty()) {
            prefs.edit().putString(STAMP_PREFIX + id, stamp).apply()
            return false
        }
        if (old != stamp) {
            prefs.edit().putString(STAMP_PREFIX + id, stamp).apply()
            runCatching { stopModule(ctx, id) }      // 停旧 monitor（含 pkill 兜底 + 清 .pid）
            setEnabled(ctx, id, false)                // 更新后默认关闭
            clearModuleResult(ctx, id)
            return true
        }
        return false
    }

    // ---------- 模块执行结果回显（引擎 / 开关 / 运行 每次执行后写入） ----------
    private const val RESULT_PREFIX = "module_result_"

    fun saveModuleResult(ctx: Context, id: String, msg: String) {
        val ts = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        val trimmed = msg.trim().replace('\n', ' ').take(200)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(RESULT_PREFIX + id, "$ts|$trimmed").apply()
    }

    fun getModuleResult(ctx: Context, id: String): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(RESULT_PREFIX + id, "") ?: ""

    /**
     * 90z 轮：读取模块运行时最新状态（实时，不依赖宿主操作记录）。
     * 优先日志文件（官方规范 .runtime/.log、实测风格 .monitor.log、兜底 .log），
     * 无日志再回退状态文件（.runtime/.state / .state）。
     * 返回格式与 lastResult 一致："时间戳|消息"；无可用文件时返回 ""。
     */
    fun moduleLiveState(ctx: Context, id: String): String {
        val d = moduleShPath(id)
        val out = sh(
            "d='$d'; for f in .runtime/.log .monitor.log .log .runtime/.state .state; do " +
                "if [ -s \"\$d/\$f\" ]; then tail -n 1 \"\$d/\$f\" 2>/dev/null; break; fi; done"
        ).trim()
        if (out.isEmpty()) return ""
        val m = Regex("""^\[(\d{4})-(\d{2})-(\d{2}) (\d{2}:\d{2}:\d{2})\] (.*)$""").find(out)
        return if (m != null) {
            val ts = "${m.groupValues[2]}-${m.groupValues[3]} ${m.groupValues[4]}"
            "$ts|${m.groupValues[5].trim().take(200)}"
        } else {
            "最近|${out.take(200)}"
        }
    }

    fun clearModuleResult(ctx: Context, id: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(RESULT_PREFIX + id).apply()
    }

    // ---------- 触发条件持久化 ----------
    private const val TRIGGER_BOOT_PREFIX = "trigger_boot_"
    private const val TRIGGER_PKG_PREFIX = "trigger_pkg_"

    fun getTriggerBoot(ctx: Context, id: String): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(TRIGGER_BOOT_PREFIX + id, false)

    fun setTriggerBoot(ctx: Context, id: String, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(TRIGGER_BOOT_PREFIX + id, on).apply()
    }

    fun getTriggerPackage(ctx: Context, id: String): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(TRIGGER_PKG_PREFIX + id, "") ?: ""

    fun setTriggerPackage(ctx: Context, id: String, pkg: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(TRIGGER_PKG_PREFIX + id, pkg).apply()
    }

    /** 列出所有开机触发脚本 id。 */
    fun listBootTriggers(ctx: Context): List<String> {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return sp.all.filter { (k, v) -> k.startsWith(TRIGGER_BOOT_PREFIX) && v == true }
            .map { it.key.removePrefix(TRIGGER_BOOT_PREFIX) }
    }

    /** 列出所有应用打开触发：id -> packageName。 */
    fun listPackageTriggers(ctx: Context): Map<String, String> {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return sp.all.filter { (k, v) -> k.startsWith(TRIGGER_PKG_PREFIX) && (v as? String).isNullOrBlank().not() }
            .mapKeys { it.key.removePrefix(TRIGGER_PKG_PREFIX) }
            .mapValues { it.value as String }
    }

    // ---------- 元数据解析（字符串版，对齐 ScriptType 的 File 版） ----------
    private fun parseMetadataText(content: String): Map<String, String> {
        val meta = mutableMapOf<String, String>()
        var inMeta = false
        for (line in content.lines()) {
            val t = line.trim()
            if (t == "#---") {
                if (inMeta) break
                inMeta = true
                continue
            }
            if (inMeta && t.startsWith("#")) {
                val c = t.removePrefix("#").trim()
                val idx = c.indexOf(':')
                if (idx > 0) {
                    meta[c.substring(0, idx).trim()] = c.substring(idx + 1).trim()
                }
            }
        }
        return meta
    }

    private fun isToggleText(content: String): Boolean {
        val lines = content.lines()
        return lines.any { it.trim().equals("#on", ignoreCase = true) } &&
            lines.any { it.trim().equals("#off", ignoreCase = true) }
    }

    /** 提取 #on / #off 块。 */
    fun extractBlockText(content: String, on: Boolean): String {
        val lines = content.lines()
        val block = StringBuilder()
        var inBlock = false
        val marker = if (on) "#on" else "#off"
        val other = if (on) "#off" else "#on"
        for (line in lines) {
            val t = line.trim()
            if (t.equals(marker, ignoreCase = true)) { inBlock = true; continue }
            if (inBlock && t.equals(other, ignoreCase = true)) break
            if (inBlock) block.appendLine(line)
        }
        return if (block.isBlank()) content else block.toString()
    }

    // ---------- Shell 脚本 ----------
    /**
     * 82 轮：批量读取全部脚本内容（base64 安全分隔，一次 sh 调用），替代「逐脚本 sh(cat)」。
     * 输出格式：@@F:<路径>\n<base64 内容>\n@@E\n
     */
    private const val BATCH_READ_SCRIPTS_CMD =
        "for f in '$SCRIPT_ROOT'/*.sh; do [ -f \"\$f\" ] || continue; " +
            "printf '@@F:%s\\n' \"\$f\"; base64 -w0 < \"\$f\" 2>/dev/null; printf '\\n@@E\\n'; done"

    /**
     * 迁移旧结构到 script/ 目录（幂等，listScripts 时执行）：
     * ① module/<id>/run.sh（无 module.json 的旧脚本）→ script/<名>.sh。
     * ② 脚本文件名与 #name 元数据不一致（早期中文被过滤成 script/script(N)）→ 重命名检查并入
     *    listScripts 的批量读取（82 轮：一次 sh 批量 mv，不再逐脚本 cat）。
     */
    private fun migrateLegacyModuleDirs(ctx: Context) {
        val out = sh("ls -d $MODULE_ROOT/*/ 2>/dev/null")
        for (d in out.lines().map { it.trim().removeSuffix("/") }.filter { it.startsWith("$MODULE_ROOT/") }) {
            val id = d.substringAfterLast('/')
            if (!shOk("test -f '$d/run.sh' && echo 1")) continue
            if (shOk("test -f '$d/module.json' && echo 1")) continue
            // Magisk/KernelSU 兼容：含 module.prop 的目录视为模块，不迁移
            if (shOk("test -f '$d/module.prop' && echo 1")) continue
            val content = sh("cat '$d/run.sh' 2>/dev/null")
            if (content.isBlank()) continue
            val meta = parseMetadataText(content)
            var name = sanitizeScriptName((meta["name"] ?: "").trim().ifBlank { id })
            var final = name
            var n = 1
            while (shOk("test -f '$SCRIPT_ROOT/$final.sh' && echo 1")) { n++; final = "$name($n)" }
            sh("mkdir -p '$SCRIPT_ROOT' && mv '$d/run.sh' '$SCRIPT_ROOT/$final.sh' 2>/dev/null && rm -rf '$d'")
            if (isEnabled(ctx, id)) { setEnabled(ctx, final, true); clearEnabled(ctx, id) }
        }
    }

    /** 82 轮：批量读取 + 单次解析全部脚本（含 #name 重命名检查，按需批量 mv）。 */
    fun listScripts(ctx: Context): List<ScriptItem> {
        migrateLegacyModuleDirs(ctx)
        val raw = sh(BATCH_READ_SCRIPTS_CMD)
        val items = ArrayList<ScriptItem>()
        val renameOps = ArrayList<Pair<String, String>>() // (fromPath, toPath)
        for (part in raw.split("@@F:").drop(1)) {
            val nl = part.indexOf('\n')
            if (nl <= 0) continue
            val path = part.substring(0, nl).trim()
            if (!path.startsWith("$SCRIPT_ROOT/") || !path.endsWith(".sh")) continue
            val end = part.indexOf("@@E", nl)
            if (end < 0) continue
            val b64 = part.substring(nl + 1, end).trim()
            if (b64.isBlank()) continue
            val content = try {
                String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)
            } catch (_: Throwable) { continue }
            if (content.isBlank()) continue
            val id = path.substringAfterLast('/').removeSuffix(".sh")
            val meta = parseMetadataText(content)
            var finalId = id
            val nm = (meta["name"] ?: "").trim()
            if (nm.isNotBlank()) {
                val renamed = sanitizeScriptName(nm)
                if (renamed.isNotBlank() && renamed != id) {
                    finalId = renamed
                    renameOps.add(path to "$SCRIPT_ROOT/$renamed.sh")
                }
            }
            items.add(
                ScriptItem(
                    id = finalId,
                    name = meta["name"] ?: finalId,
                    author = meta["author"] ?: "",
                    brand = meta["brand"] ?: "",
                    description = meta["description"] ?: "",
                    isToggle = isToggleText(content),
                    enabled = isEnabled(ctx, id),
                    triggerBoot = getTriggerBoot(ctx, id),
                    triggerPackage = getTriggerPackage(ctx, id)
                )
            )
        }
        // 批量执行重命名（等价原 migrate 第二段：目标不存在才 mv；一次 sh 完成全部）
        if (renameOps.isNotEmpty()) {
            sh(
                renameOps.joinToString(" && ") { (from, to) -> "(test -f '$to' || mv '$from' '$to')" } +
                    "; echo RENAME_DONE"
            )
            for ((from, to) in renameOps) {
                val oldId = from.substringAfterLast('/').removeSuffix(".sh")
                val newId = to.substringAfterLast('/').removeSuffix(".sh")
                if (isEnabled(ctx, oldId)) { setEnabled(ctx, newId, true); clearEnabled(ctx, oldId) }
            }
        }
        // 90ae：追加自有路径（外部 / Android/data/.../files/lin-scripts + 内部 filesDir/lin-scripts）
        // 中的脚本——新版保存的脚本在此，同名去重（老路径优先）
        val existingIds = items.map { it.id }.toHashSet()
        val storeDirs = listOf(scriptStoreDir(ctx), File(ctx.filesDir, "lin-scripts"))
        try {
            for (dir in storeDirs) {
                if (!dir.isDirectory) continue
                dir.listFiles()
                    ?.filter { it.isFile && it.name.endsWith(".sh") }
                    ?.sortedBy { it.name.lowercase() }
                    ?.forEach { f ->
                        val id = f.name.removeSuffix(".sh")
                        if (id in existingIds) return@forEach // 已有同名，跳过
                        val content = try { f.readText() } catch (_: Throwable) { "" }
                        if (content.isBlank()) return@forEach
                        val meta = parseMetadataText(content)
                        items.add(
                            ScriptItem(
                                id = id,
                                name = meta["name"] ?: id,
                                author = meta["author"] ?: "",
                                brand = meta["brand"] ?: "",
                                description = meta["description"] ?: "",
                                isToggle = isToggleText(content),
                                enabled = isEnabled(ctx, id),
                                triggerBoot = getTriggerBoot(ctx, id),
                                triggerPackage = getTriggerPackage(ctx, id)
                            )
                        )
                        existingIds.add(id)
                    }
            }
        } catch (_: Throwable) {}
        return items.sortedBy { it.name.lowercase() }
    }

    fun deleteScript(ctx: Context, id: String) {
        removeScriptFiles(ctx, id)
        clearEnabled(ctx, id)
    }

    /**
     * 从文件选择器导入 .sh 脚本：读取文件内容 → 写入 <SCRIPT_ROOT>/<名>.sh。
     * 文件名取自原文件名（保留中文）。返回 null=成功，否则错误/提示信息。
     */
    fun importScript(ctx: Context, uri: Uri): String? {
        val displayName = try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && !c.isNull(idx)) c.getString(idx) else null
            }
        } catch (_: Throwable) { null }
            ?: uri.lastPathSegment // 兜底：OpenDocument 的 content uri 常可从 lastPathSegment 取到文件名
            ?: "script.sh"
        val id = sanitizeScriptName(
            displayName.substringAfterLast('/').removeSuffix(".sh").removeSuffix(".SH")
        )
        // 同名已存在时不覆盖：自动追加序号改名（如 系统信息(1)、系统信息(2)...）
        var finalId = id
        var n = 1
        while (shOk("test -f '$SCRIPT_ROOT/$finalId.sh' && echo 1") ||
            scriptStoreFile(ctx, finalId).isFile ||
            internalScriptFile(ctx, finalId).isFile
        ) {
            n++
            finalId = "$id($n)"
        }
        val bytes = try {
            ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (e: Throwable) { null }
            ?: return "读取文件失败"
        if (bytes.isEmpty()) return "文件内容为空"
        // 90ad：统一导入到自有路径（app 私有目录 File IO，无需特权/服务）
        return try {
            val dir = scriptStoreDir(ctx).apply { mkdirs() }
            scriptStoreFile(ctx, finalId).writeBytes(bytes)
            if (finalId != id) "脚本已导入（同名已存在，已保存为 $finalId）" else null
        } catch (e: Throwable) {
            "写入失败: ${e.message}"
        }
    }

    /** 脚本 on/off 块内容（开关型脚本）。 */
    fun toggleBlock(ctx: Context, id: String, on: Boolean): String {
        val content = readScriptContent(ctx, id)
        return if (content.isBlank()) "" else extractBlockText(content, on)
    }

    /** 90bf：模块脚本执行去重节流——服务恢复瞬间（管理器进程重启 / 服务端
     * attachApplication）会多次触发同一模块启动（如 joyse_jank_opt 12s 内跑两遍），
     * 造成 chmod/chcon/看门狗反复执行的高负载风暴。同一 id 10s 内重复调用直接跳过。 */
    private val lastRunAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val RUN_DEBOUNCE_MS = 10_000L

    /** 执行指定脚本（开机/应用触发用，非交互）。输出落盘滚动日志与统一日志（66 轮）。 */
    fun runScriptById(ctx: Context, id: String): String {
        val nowMs = System.currentTimeMillis()
        val last = lastRunAt[id]
        if (last != null && nowMs - last < RUN_DEBOUNCE_MS) {
            val msg = "脚本正在执行中（防重复触发，${(RUN_DEBOUNCE_MS - (nowMs - last)) / 1000}s 内请勿重复操作）"
            ModuleLogs.appendAuto(ctx, id, ModuleLogs.line("!", "脚本执行 $id：$msg"))
            return msg
        }
        lastRunAt[id] = nowMs
        return try {
            val content = readScriptContent(ctx, id)
            if (content.isBlank()) {
                val msg = "脚本不存在: $id"
                ModuleLogs.appendAuto(ctx, id, ModuleLogs.line("!", "脚本执行 $id：$msg"))
                DiagnosticLog.append("[脚本执行] $id: $msg")
                return msg
            }
            val block = if (isToggleText(content)) {
                // 开关型脚本：已开启则执行 on 块
                if (isEnabled(ctx, id)) extractBlockText(content, true) else ""
            } else {
                content
            }
            if (block.isBlank()) {
                val msg = "脚本为空"
                ModuleLogs.appendAuto(ctx, id, ModuleLogs.line("!", "脚本执行 $id：$msg"))
                return msg
            }
            // 68 轮：按全局执行身份执行（跟随服务 / Shell(com.android.shell) / Root），日志标注身份
            val identity = Prefs.execIdentity
            val msg = runBlocking { ShizukuManager.runCommand(block, identity) }
            ModuleLogs.appendAuto(ctx, id, ModuleLogs.line("──", "脚本执行 $id（身份: ${identity.label}）") + "\n" + msg.trim().take(2000))
            DiagnosticLog.append("[脚本执行] $id（${identity.short}）: ${msg.trim().take(100)}")
            msg
        } catch (e: Throwable) {
            val msg = "执行失败: ${e.message}"
            ModuleLogs.appendAuto(ctx, id, ModuleLogs.line("!", "脚本执行 $id 失败：${e.message}"))
            DiagnosticLog.append("[脚本执行] $id 失败: ${e.message}")
            msg
        }
    }

    /** 读取脚本内容（自有路径 File 读 + 老路径 shell cat 兼容，见 [readScriptContent]）。 */
    fun scriptContent(ctx: Context, id: String): String =
        readScriptContent(ctx, id)

    /**
     * 保存脚本（新建或编辑）。自动维护 #name 元数据；文件名=脚本名（中文友好）。
     * 90ad：统一保存到 Lin-Shizuku 自有路径（app 私有 filesDir/lin-scripts，零特权可写，
     * 服务未运行也能保存，不再依赖 /data/local/tmp 特权目录）。
     * 返回：Result.success(最终脚本 id)（可能与入参不同，如重名加序号或改名）；
     * 失败：Result.failure(带具体原因)。
     */
    fun saveScript(ctx: Context, id: String, name: String, content: String): Result<String> {
        var finalId = sanitizeScriptName(name)
        // 重名去重：外部自有路径 + 内部路径 + 老路径均查重（仅当目标文件存在且不是正在编辑的自身时加序号）
        if (id != finalId) {
            var n = 1
            while (scriptStoreFile(ctx, finalId).isFile ||
                internalScriptFile(ctx, finalId).isFile ||
                shOk("test -f '$SCRIPT_ROOT/$finalId.sh' && echo 1")
            ) {
                n++
                finalId = "$finalId($n)"
            }
        }
        val final = if (content.contains("#name:")) {
            content.replace(Regex("#name:.*"), "#name: $name")
        } else {
            "#---\n#name: $name\n#---\n\n$content"
        }
        // 90ad：直接写自有路径（app 私有目录 File IO，无需任何特权/服务）
        return try {
            val dir = scriptStoreDir(ctx).apply { mkdirs() }
            if (!dir.isDirectory) return Result.failure(IllegalStateException("保存失败: 无法创建私有目录"))
            scriptStoreFile(ctx, finalId).writeText(final, Charsets.UTF_8)
            // 旧文件清理（改名场景：旧文件路径与新名不同才删，避免误删新内容）
            if (id.isNotBlank() && id != finalId) {
                removeScriptFiles(ctx, id)
                sh("rm -rf '$MODULE_ROOT/$id'")
            }
            if (isEnabled(ctx, id)) {
                setEnabled(ctx, finalId, true)
                clearEnabled(ctx, id)
            }
            Result.success(finalId)
        } catch (e: Throwable) {
            Result.failure(IllegalStateException("保存失败: ${e.message}"))
        }
    }

    /**
     * 停止模块：开关关闭时调用，真正清理模块的后台进程、不允许其继续运行。
     * 90e 轮：改为统一执行 [stopScript] 完整停止序列（stop.sh → 看门狗 → .pid → pkill 兜底），
     * 修复「模块停用无效，进程依旧」——旧实现有 stop.sh 时只执行 stop.sh，stop.sh 不彻底时
     * worker/看门狗残留。
     */
    fun stopModule(ctx: Context, id: String): String {
        return try {
            sh(stopScript(ctx, id))
        } catch (e: Throwable) {
            "停止模块失败: ${e.message}"
        }
    }

    /** 90e 轮：模块完整停止脚本（stop.sh → 看门狗 → .pid → pkill 路径兜底）。
     *  修复「模块停用无效，进程依旧」：旧实现停用仅执行 stop.sh，stop.sh 不彻底时 worker
     *  残留；且看门狗 monitor 的 cmdline 含 [id] 字面量（防自匹配），普通路径正则匹配不到，
     *  monitor 永不退出。本序列递进清理：
     *   1) stop.sh（第一手段：精确停调度器、清理标志文件）；
     *   2) .wd.pid 精确杀看门狗（旧/新 monitor 均可靠；旧版从不删 .wd.pid，设备上必在）；
     *   3) tag 字符类 pkill 兜底看门狗（新 monitor 带无方括号 tag，见 [spawnModuleWatchdog]）；
     *   4) .pid 精确杀模块主进程；
     *   5) 路径字符类 pkill 兜底（stop.sh 只停主进程时残留 worker 一并清）。
     *  全程使用 [X] 拆分防自匹配（[j] 字符类只命中无方括号的路径/tag 文本，不会命中执行
     *  本脚本的 shell 自身 cmdline 中的 [j] 字面量），不引入转义正则（`\[j\]` 会匹配到
     *  执行 shell 自身 cmdline 中的普通模式文本导致命令中断）。 */
    private fun stopScript(ctx: Context, id: String): String {
        val f = id.firstOrNull() ?: 'x'
        val rest = id.drop(1)
        val dir = moduleShPath(id)
        return buildString {
            // 1) 模块自带 stop.sh（第一手段，精确停调度器/清理 pid/标志文件）；
            //    90f 轮：timeout 5 包裹，stop.sh 挂起（while 循环/等进程）自动跳过，不阻塞后续兜底
            //    90s 轮：subshell cd 到模块目录再执行——stop.sh 内相对路径依赖 CWD 为模块根目录
            append("test -f '$dir/stop.sh' && (cd '$dir' && timeout 5 sh stop.sh 2>&1) || true; ")
            // 2) 看门狗：.wd.pid 精确杀（旧/新 monitor 均可靠）→ 3) tag 字符类 pkill 兜底（新 monitor）
            append("wd=\$(cat '$dir/.wd.pid' 2>/dev/null); ")
            append("case \"\$wd\" in ''|*[!0-9]*) : ;; *) kill \"\$wd\" 2>/dev/null;; esac; ")
            append("rm -f '$dir/.wd.pid'; ")
            append("pkill -f 'lin_shizuku_wd_[$f]$rest' 2>/dev/null; ")
            // 4) 模块 .pid 精确杀
            append("pid=\$(cat '$dir/.pid' 2>/dev/null); ")
            append("case \"\$pid\" in ''|*[!0-9]*) : ;; *) kill \"\$pid\" 2>/dev/null;; esac; ")
            append("rm -f '$dir/.pid'; ")
            // 5) pkill 路径兜底（[X] 拆分防自匹配）：stop.sh 只停主进程时残留 worker 一并清
            append("pkill -f '${moduleShPath(id)}/[$f]$rest' 2>/dev/null; ")
            append("echo \"已停止模块进程\"")
        }
    }

    // ---------- Lin 模块 ----------

    /** Magisk/KernelSU module.prop 键值解析（模块元数据兼容层）。 */
    private fun parseModuleProp(content: String): Map<String, String> {
        val map = HashMap<String, String>()
        content.lines().forEach { line ->
            val idx = line.indexOf('=')
            if (idx > 0) {
                val key = line.substring(0, idx).trim()
                val value = line.substring(idx + 1).trim()
                if (key.isNotEmpty()) map[key] = value
            }
        }
        return map
    }

    /** 82 轮：批量采集全部模块元数据（base64 分隔，一次 sh 调用），替代逐模块多次 sh()。
     *  输出格式：@@M:<id>\njson=1|0\nprop=1|0\nmetab64=<base64 元数据>\ntoggle=1|0\nwebui=<path>\nscript=1|0\nsize=<kb>\n@@E\n */
    private val BATCH_READ_MODULES_CMD = run {
        val root = MODULE_ROOT
        "for d in '$root'/*/; do [ -d \"\$d\" ] || continue; " +
            "id=\$(basename \"\$d\"); " +
            "[ -f \"\$d/module.json\" -o -f \"\$d/module.prop\" ] || continue; " +
            "printf '@@M:%s\\n' \"\$id\"; " +
            "if [ -f \"\$d/module.json\" ]; then printf 'json=1\\nprop=0\\nmetab64='; base64 -w0 < \"\$d/module.json\" 2>/dev/null; " +
            "else printf 'json=0\\nprop=1\\nmetab64='; base64 -w0 < \"\$d/module.prop\" 2>/dev/null; fi; printf '\\n'; " +
            "printf 'toggle='; grep -qiE '^[[:space:]]*#on[[:space:]]*$' \"\$d/run.sh\" 2>/dev/null && " +
            "grep -qiE '^[[:space:]]*#off[[:space:]]*$' \"\$d/run.sh\" 2>/dev/null && echo 1 || echo 0; " +
            "printf 'webui='; [ -f \"\$d/webroot/index.html\" ] && echo 'webroot/index.html' || echo ''; " +
            "printf 'script='; [ -f \"\$d/run.sh\" -o -f \"\$d/service.sh\" ] && echo 1 || echo 0; " +
            "printf 'size='; du -sk \"\$d\" 2>/dev/null | cut -f1; " +
            "printf '@@E\\n'; done"
    }

    fun listModules(ctx: Context): List<LinModuleItem> {
        val result = ArrayList<LinModuleItem>()
        val seen = HashSet<String>()
        // 老路径（历史模块）：sh 批量扫描
        val raw = sh(BATCH_READ_MODULES_CMD)
        for (block in raw.split("@@M:").drop(1)) {
            val end = block.indexOf("@@E")
            if (end < 0) continue
            val body = block.substring(0, end).trim()
            if (body.isBlank()) continue
            val lines = body.lines()
            val id = lines.first().trim()
            if (id.isBlank()) continue
            var jsonMode = false
            var metaB64 = ""
            var toggle = false
            var webui = ""
            var hasScript = false
            var sizeKb = 0L
            for (line in lines.drop(1)) {
                when {
                    line.startsWith("json=") -> jsonMode = line.substringAfter('=').trim() == "1"
                    line.startsWith("metab64=") -> metaB64 = line.substringAfter('=').trim()
                    line.startsWith("toggle=") -> toggle = line.substringAfter('=').trim() == "1"
                    line.startsWith("webui=") -> webui = line.substringAfter('=').trim()
                    line.startsWith("script=") -> hasScript = line.substringAfter('=').trim() == "1"
                    line.startsWith("size=") -> sizeKb = line.substringAfter('=').trim().toLongOrNull() ?: 0L
                }
            }
            if (metaB64.isBlank()) continue
            val meta = try {
                String(Base64.decode(metaB64, Base64.DEFAULT), Charsets.UTF_8)
            } catch (_: Throwable) { continue }
            if (meta.isBlank()) continue
            val json = if (jsonMode) {
                try { JSONObject(meta) } catch (_: Throwable) { continue } // 解析失败跳过该模块
            } else null
            val prop = if (json == null) parseModuleProp(meta) else emptyMap()
            val name = json?.optString("name")?.takeIf { it.isNotBlank() } ?: prop["name"] ?: id
            result.add(
                LinModuleItem(
                    id = json?.optString("id")?.takeIf { it.isNotBlank() } ?: prop["id"] ?: id,
                    name = name,
                    description = json?.optString("description") ?: prop["description"] ?: "",
                    version = json?.optString("version") ?: prop["version"] ?: "",
                    author = json?.optString("author") ?: prop["author"] ?: "",
                    enabled = isEnabled(ctx, id),
                    // 与原逻辑一致：json 模块 webui 只读 module.json 的 webui 字段；prop 模块回退 webroot/index.html
                    webui = if (json != null) json.optString("webui", "") else webui,
                    hasAction = if (json != null) json.optBoolean("action", false) else hasScript,
                    hasService = if (json != null) json.optBoolean("service", false) || toggle else hasScript,
                    lastResult = getModuleResult(ctx, id),
                    sizeLabel = formatSize(sizeKb)
                )
            )
            seen.add(id)
        }
        // 90af：内部自有路径（新装模块）——app File 扫描，服务未运行也能列出
        scanInternalModules(ctx, result, seen)
        return result.sortedBy { it.name.lowercase() }
    }

    /** 90af：扫描内部自有目录 files/lin-modules/ 下的模块（零特权安装的模块在此）。
     *  老路径同名去重（老路径优先）；元数据/开关/WebUI/大小全部 app File 读取。 */
    private fun scanInternalModules(ctx: Context, result: MutableList<LinModuleItem>, seen: MutableSet<String>) {
        val home = File(ctx.filesDir, "lin-modules")
        if (!home.isDirectory) return
        home.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name.lowercase() }?.forEach { d ->
            val id = d.name
            if (id in seen) return@forEach
            val jsonFile = File(d, "module.json")
            val propFile = File(d, "module.prop")
            val json = if (jsonFile.isFile) {
                try { JSONObject(jsonFile.readText()) } catch (_: Throwable) { null }
            } else null
            val prop = if (json == null && propFile.isFile) parseModuleProp(propFile.readText()) else emptyMap()
            if (json == null && prop.isEmpty()) return@forEach
            val runFile = File(d, "run.sh")
            val toggle = try {
                val c = runFile.readText()
                c.contains(Regex("^[ \t]*#on[ \t]*$", RegexOption.MULTILINE)) &&
                    c.contains(Regex("^[ \t]*#off[ \t]*$", RegexOption.MULTILINE))
            } catch (_: Throwable) { false }
            val webui = if (File(d, "webroot/index.html").isFile) "webroot/index.html" else ""
            val hasScript = runFile.isFile || File(d, "service.sh").isFile
            var sizeKb = 0L
            try {
                d.walkTopDown().filter { it.isFile }.forEach { sizeKb += it.length() }
                sizeKb /= 1024
            } catch (_: Throwable) {}
            result.add(
                LinModuleItem(
                    id = json?.optString("id")?.takeIf { it.isNotBlank() } ?: prop["id"] ?: id,
                    name = json?.optString("name")?.takeIf { it.isNotBlank() } ?: prop["name"] ?: id,
                    description = json?.optString("description") ?: prop["description"] ?: "",
                    version = json?.optString("version") ?: prop["version"] ?: "",
                    author = json?.optString("author") ?: prop["author"] ?: "",
                    enabled = isEnabled(ctx, id),
                    webui = if (json != null) json.optString("webui", "") else webui,
                    hasAction = if (json != null) json.optBoolean("action", false) else hasScript,
                    hasService = if (json != null) json.optBoolean("service", false) || toggle else hasScript,
                    lastResult = getModuleResult(ctx, id),
                    sizeLabel = formatSize(sizeKb)
                )
            )
            seen.add(id)
        }
    }

    fun deleteModule(ctx: Context, id: String) {
        // 卸载流程（90f 轮重构，修复「确认删除后模块仍在路径和列表」）：
        // 旧实现把 stop.sh → uninstall.sh → pkill → rm -rf 串在一条 sh 命令链里，而
        // runCommandInternal 用 waitFor() 无超时——stop.sh/uninstall.sh 一旦挂起
        // （while 循环 / 等待进程退出 / pkill 自杀执行链），rm -rf 永远不会执行，
        // 目录与模块列表残留。现在拆为两步：
        //   ① 独立快速杀进程（.wd.pid / .pid / pkill，不依赖模块脚本）；
        //      stop.sh / uninstall.sh 用 timeout 5 包裹尽力清理，挂起自动跳过；
        //   ② rm -rf 单独一条命令执行，无前置依赖，目录必删。
        val dir = moduleShPath(id)
        try {
            sh(
                buildString {
                    val f = id.firstOrNull() ?: 'x'
                    val rest = id.drop(1)
                    // 看门狗 / 模块主进程：先精确 kill，不依赖模块脚本
                    append("wd=\$(cat '$dir/.wd.pid' 2>/dev/null); ")
                    append("case \"\$wd\" in ''|*[!0-9]*) : ;; *) kill \"\$wd\" 2>/dev/null;; esac; ")
                    append("rm -f '$dir/.wd.pid'; ")
                    append("pkill -f 'lin_shizuku_wd_[$f]$rest' 2>/dev/null; ")
                    append("pid=\$(cat '$dir/.pid' 2>/dev/null); ")
                    append("case \"\$pid\" in ''|*[!0-9]*) : ;; *) kill \"\$pid\" 2>/dev/null;; esac; ")
                    append("rm -f '$dir/.pid'; ")
                    append("pkill -f '$dir/[$f]$rest' 2>/dev/null; ")
                    // 模块自带清理脚本：timeout 5 包裹，挂起自动跳过（绝不阻塞后续删除）
                    append("test -f '$dir/stop.sh' && timeout 5 sh '$dir/stop.sh' 2>&1 || true; ")
                    append("test -f '$dir/uninstall.sh' && timeout 5 sh '$dir/uninstall.sh' 2>&1 || true; ")
                    append("pkill -f '$dir/[$f]$rest' 2>/dev/null; true")
                }
            )
        } catch (_: Throwable) {}
        // ② 目录删除独立执行，不受上面任何脚本挂起/异常影响；老路径 + 内部自有路径都清
        try {
            sh("rm -rf '$MODULE_ROOT/$id'")
        } catch (_: Throwable) {}
        try {
            File(ctx.filesDir, "lin-modules/$id").deleteRecursively()
        } catch (_: Throwable) {}
        clearEnabled(ctx, id)
        clearModuleResult(ctx, id)
    }

    /** 模块 action 脚本内容：run.sh 优先，Magisk 模块兼容 service.sh。 */
    fun moduleActionScript(ctx: Context, id: String): String {
        val dir = moduleShPath(id)
        val run = sh("cat '$dir/run.sh' 2>/dev/null")
        if (run.isNotBlank()) return "cd '$dir' && $run"
        val svc = sh("cat '$dir/service.sh' 2>/dev/null")
        if (svc.isNotBlank()) return "cd '$dir' && $svc"
        return ""
    }

    // ---------- 模块引擎（开关 = 状态 + 引擎统一执行） ----------

    /**
     * 应用单个模块开关状态（引擎统一入口，开关拨动与引擎恢复共用）：
     * on=true  → 执行 run.sh 的 #on 块（无 #on 则执行全文，用于启动模块服务）；
     * on=false → 停止模块（stopModule：stop.sh → .pid → pkill 兜底）。
     * 执行结果写入模块结果回显（卡片可见），并落盘滚动日志与统一诊断日志（66 轮）。
     */
    fun applyModuleState(ctx: Context, id: String, on: Boolean): String {
        val op = if (on) "开启" else "关闭"
        return try {
            val msg = if (on) {
                val script = moduleActionScript(ctx, id)
                if (script.isBlank()) {
                    "模块缺少 run.sh / service.sh"
                } else {
                    val block = extractBlockText(script, true)
                    if (block.isBlank()) {
                        "脚本缺少 #on 块"
                    } else {
                        // 90s：先 cd 到模块目录再执行——脚本用相对路径（如 . ./lib/core.sh）时
                        // 依赖 CWD 为模块根目录，否则报 "No such file or directory"（退出码 1）
                        // 90u：执行前确保可执行环境（chmod 恢复执行位 + chcon shell_exec 允许
                        // SELinux enforcing 下 shell 域 exec 原生 ELF，如 busybox）
                        // 90af：目录可能是内部自有路径（新装）或老路径（历史模块），统一 moduleShPath
                        val dir = moduleShPath(id)
                        val r = sh(
                            "chmod -R 755 '$dir' 2>/dev/null; " +
                                "chcon -R u:object_r:shell_exec:s0 '$dir' 2>/dev/null; " +
                                "cd '$dir' && $block"
                        )
                        // 85 轮：挂卸载自清理监控——Lin-Shizuku 被卸载/强杀（进程全部消失）后，
                        // 自动 pkill 本模块目录残留进程，避免卸载后模块进程仍在后台运行
                        spawnModuleWatchdog(ctx, id)
                        r
                    }
                }
            } else {
                stopModule(ctx, id)
            }
            saveModuleResult(ctx, id, msg)
            ModuleLogs.appendAuto(ctx, id, ModuleLogs.line("──", "开关$op $id") + "\n" + msg.trim().take(1500))
            DiagnosticLog.append("[模块$op] $id: ${msg.trim().take(100)}")
            msg
        } catch (e: Throwable) {
            val msg = "执行失败: ${e.message}"
            saveModuleResult(ctx, id, msg)
            ModuleLogs.appendAuto(ctx, id, ModuleLogs.line("!", "开关$op $id 失败：${e.message}"))
            DiagnosticLog.append("[模块$op] $id 失败: ${e.message}")
            msg
        }
    }

    /**
     * 85 轮：模块卸载自清理监控（watchdog）。
     * 模块进程是独立 shell 后台进程，卸载 Lin-Shizuku 时系统不会给被卸载应用自身发
     * PACKAGE_REMOVED 广播，stopModule 无人调用 → 模块进程残留。这里在模块开启后挂一个
     * 后台 monitor：检测不到任何 Lin-Shizuku 进程（卸载/强杀）连续 3 分钟后，
     * 自动 pkill 本模块目录下的残留进程，实现「卸载即最终清理」（3 分钟缓冲防升级误杀）。
     */
    private fun spawnModuleWatchdog(ctx: Context, id: String) {
        try {
            // [X] 拆分防自匹配：正则以字面量形式出现在 monitor 自身命令行中，pgrep/pkill 不会匹配自身
            val pkgPattern = "moe[.]shizuku[.]privileged[.]api"
            val tagPattern = "lin_shizuku_wd_[${id.firstOrNull() ?: 'x'}]${id.drop(1)}"
            val dir = moduleShPath(id)
            val dirPattern = "$dir/[${id.firstOrNull() ?: 'x'}]${id.drop(1)}"
            // 先清旧 monitor 防堆积，再挂新 monitor（nohup 后台，PID 记入模块目录 .wd.pid）
            sh("pkill -f '$tagPattern' 2>/dev/null")
            sh(
                "nohup sh -c '" +
                    "# lin_shizuku_wd_$id" +  // 90e：无方括号 tag，供停用/卸载时字符类 pkill 兜底清理（防自匹配不误杀执行 shell）
                    "miss=0; " +
                    "while :; do " +
                    "if pgrep -f \"$pkgPattern\" >/dev/null 2>&1; then miss=0; " +
                    "else miss=\$((miss+1)); " +
                    "if [ \$miss -ge 3 ]; then pkill -f \"$dirPattern\" 2>/dev/null; exit 0; fi; " +
                    "fi; " +
                    "sleep 60; " +
                    "done' " +
                    ">/dev/null 2>&1 & " +
                    "echo \$! > '$dir/.wd.pid'"
            )
            DiagnosticLog.append("[模块看门狗] $id: 卸载自清理监控已挂载")
        } catch (e: Throwable) {
            DiagnosticLog.append("[模块看门狗] $id: 挂载失败 ${e.message}")
        }
    }

    /**
     * 85 轮：应用/服务就绪时清理孤儿模块进程——模块开关为关闭但后台进程仍在运行
     * （开关关闭后残留 / 上次运行异常退出前未清理）的情况，统一 stopModule 兜底。
     */
    fun cleanupOrphanModules(ctx: Context) {
        try {
            if (!ShizukuManager.isRunningCompat()) return
            for (m in listModules(ctx)) {
                if (!m.enabled) {
                    runCatching { stopModule(ctx, m.id) }
                    DiagnosticLog.append("[孤儿清理] ${m.id}: 未启用残留已清理")
                }
            }
        } catch (e: Throwable) {
            DiagnosticLog.append("[孤儿清理] 失败: ${e.message}")
        }
    }

    /**
     * 模块引擎：Lin_server 服务就绪时调用，对所有 enabled 模块统一启动（幂等）。
     * 只启动已启用模块；disabled 模块的残留清理由「开关关闭」与「卸载」负责。
     */
    fun applyEnabledModules(ctx: Context): String {
        if (!ShizukuManager.isRunningCompat()) return "服务未就绪，跳过模块引擎"
        val results = StringBuilder()
        for (m in listModules(ctx)) {
            // 模块被更新/重装：停旧进程并默认关闭（覆盖安装后不残留开启状态）
            if (checkModuleUpdated(ctx, m.id)) {
                results.appendLine("${m.id}: 模块已更新，已重置为关闭")
                DiagnosticLog.append("[模块引擎] ${m.id}: 模块已更新，已重置为关闭")
                continue
            }
            if (m.enabled) {
                val r = applyModuleState(ctx, m.id, true)
                results.appendLine("${m.id}: ${r.trim().take(60)}")
            }
        }
        val summary = results.toString().ifBlank { "无已启用模块" }
        if (results.isNotEmpty()) DiagnosticLog.append("[模块引擎] 引擎汇总: ${summary.trim().take(300)}")
        return summary
    }

    /** 模块开关 UI 要真实执行的脚本：#on 块（开）；[stopScript] 完整停止序列（关）。供终端 runReal 展示执行。 */
    fun moduleToggleScript(ctx: Context, id: String, on: Boolean): String {
        return if (on) {
            // 90s：独立取原始全文提取 #on 块（moduleActionScript 已带 cd 前缀，不能直接用于块提取），
            // 并先 cd 到模块目录——脚本相对路径（如 . ./lib/core.sh）依赖 CWD 为模块根目录
            val dir = moduleShPath(id)
            val raw = sh("cat '$dir/run.sh' 2>/dev/null")
                .ifBlank { sh("cat '$dir/service.sh' 2>/dev/null") }
            val block = extractBlockText(raw, true)
            if (block.isBlank()) "echo 脚本缺少 #on 块"
            else
                // 90s：先 cd 到模块目录（脚本相对路径依赖 CWD）；
                // 90u：执行前确保可执行环境（chmod + chcon shell_exec，SELinux enforcing 下
                // shell 域才能 exec 原生 ELF 如 busybox）
                "chmod -R 755 '$dir' 2>/dev/null; " +
                    "chcon -R u:object_r:shell_exec:s0 '$dir' 2>/dev/null; " +
                    "cd '$dir' && $block"
        } else {
            stopScript(ctx, id)
        }
    }

    /**
     * 解析模块 WebUI 地址：
     * https:// 直接返回；http:// 明文拒绝（P1-1 修复）；
     * 本地路径（如 webroot/index.html）通过 shell 同步到
     * app 缓存目录（WebView 只能读 app 私有文件），返回 file:// 绝对路径。
     */
    fun webuiUrl(ctx: Context, module: LinModuleItem): String {
        val raw = module.webui
        if (raw.isBlank()) return ""
        // P1-1 修复：仅允许 https 远程 WebUI；http 明文可被中间人劫持投毒
        if (raw.startsWith("https://")) return raw
        if (raw.startsWith("http://")) return ""
        val dirName = raw.substringBeforeLast('/').ifBlank { "." }
        val dir = moduleShPath(module.id)
        val out = sh(
            "cd '$dir' 2>/dev/null && " +
                "find '$dirName' -type f 2>/dev/null | while read f; do " +
                "echo \"\$f|\$(base64 -w0 < \"\$f\" 2>/dev/null)\"; done"
        )
        if (out.isBlank()) return raw
        val dest = File(ctx.cacheDir, "webui_${module.id}")
        dest.deleteRecursively()
        dest.mkdirs()
        for (line in out.lines()) {
            if (line.isBlank()) continue
            val idx = line.indexOf('|')
            if (idx <= 0) continue
            val rel = line.substring(0, idx)
            val b64 = line.substring(idx + 1)
            try {
                val f = File(dest, rel)
                f.parentFile?.mkdirs()
                f.writeBytes(Base64.decode(b64, Base64.DEFAULT))
            } catch (_: Throwable) {}
        }
        return File(dest, raw).toURI().toString()
    }

    /**
     * 从 zip（.lin / .zip）安装 Lin 模块（66 轮：逐步日志 + 错误码 + 日志落盘）。
     * 要求包内包含 module.json（含合法 id）。
     *
     * 安装流程（每步通过 [onLog] 实时回传日志行，成功/失败都会落盘与写入统一日志）：
     *   - Copying zip to temp directory        app 解压到缓存
     *   - Installing <文件名>                   开始安装
     *   - Checking module metadata              校验 module.json / module.prop
     *   - Creating module directory             目录一次建好
     *   - Writing N files                       文件分批 base64 写入
     *   - Setting permissions                   chmod
     *   ✓ 安装完成 / ! 安装失败（错误代码 N）
     */
    suspend fun installModule(ctx: Context, uri: Uri, onLog: suspend (String) -> Unit = {}): InstallResult {
        val log = StringBuilder()
        val fileName = queryDisplayName(ctx, uri) ?: "模块包"
        suspend fun emit(prefix: String, text: String) {
            val l = ModuleLogs.line(prefix, text)
            log.appendLine(l)
            onLog(l)
        }
        suspend fun finishFail(code: Int, text: String): InstallResult {
            emit("!", "安装失败（错误代码 $code）：$text")
            val result = InstallResult(false, log.toString(), code, text)
            ModuleLogs.save(ctx, "install_${ModuleLogs.fileStamp()}.log", result.log)
            DiagnosticLog.append("[模块安装] 失败(错误代码 $code) $fileName：$text")
            return result
        }
        val tmp = File(ctx.cacheDir, "mod_install_${System.currentTimeMillis()}")
        try {
            // 1) 复制压缩包到临时目录并解压
            emit("-", "Copying zip to temp directory")
            tmp.mkdirs()
            try {
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    ZipInputStream(input).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            if (!entry.isDirectory) {
                                val outFile = File(tmp, entry.name)
                                outFile.parentFile?.mkdirs()
                                outFile.outputStream().use { out -> zis.copyTo(out) }
                            }
                            zis.closeEntry()
                            entry = zis.nextEntry
                        }
                    }
                } ?: run { throw java.io.IOException("无法读取所选文件") }
            } catch (e: Throwable) {
                emit("!", "Unzip error: ${e.message ?: e.javaClass.simpleName}")
                return finishFail(OpErrorCode.UNZIP, "解压失败: ${e.message ?: e.javaClass.simpleName}")
            }

            emit("-", "Installing $fileName")
            // 2) 元数据校验（module.json：Lin 原生 / module.prop：Magisk/KernelSU 兼容）
            emit("-", "Checking module metadata (module.json / module.prop)")
            val metaFile = listOf(
                File(tmp, "module.json"),
                File(tmp, "module/module.json"),
                File(tmp, "module.prop"),
                File(tmp, "module/module.prop")
            ).firstOrNull { it.exists() } ?: run {
                emit("!", "Module metadata not found (need module.json or module.prop)")
                return finishFail(OpErrorCode.METADATA, "模块包中未找到 module.json 或 module.prop")
            }

            val id: String
            val contentRoot: File
            if (metaFile.name == "module.json") {
                val json = try {
                    JSONObject(metaFile.readText())
                } catch (e: Throwable) {
                    emit("!", "module.json parse error: ${e.message ?: e.javaClass.simpleName}")
                    return finishFail(OpErrorCode.METADATA, "module.json 解析失败: ${e.message ?: e.javaClass.simpleName}")
                }
                id = json.optString("id", "").trim()
                if (id.isBlank() || !id.matches(Regex("[A-Za-z0-9_.-]+"))) {
                    emit("!", "Invalid module id in module.json")
                    return finishFail(OpErrorCode.METADATA, "module.json 缺少合法 id")
                }
                contentRoot = if (metaFile.parentFile.name == "module") metaFile.parentFile else tmp
            } else {
                val prop = parseModuleProp(metaFile.readText())
                id = prop["id"]?.trim().orEmpty()
                if (id.isBlank() || !id.matches(Regex("[A-Za-z0-9_.-]+"))) {
                    emit("!", "Invalid module id in module.prop")
                    return finishFail(OpErrorCode.METADATA, "module.prop 缺少合法 id")
                }
                contentRoot = if (metaFile.parentFile.name == "module") metaFile.parentFile else tmp
            }

            // 3) 90af：模块落盘到内部自有目录（app File 拷贝，零特权——服务未运行也能安装）。
            //    目录 = /data/user/0/<pkg>/files/lin-modules/<id>/（/data 分区可 exec）。
            val target = File(ctx.filesDir, "lin-modules/$id")
            emit("-", "Creating module directory: $target.absolutePath")
            try {
                target.deleteRecursively()
                target.mkdirs()
                contentRoot.walkTopDown().forEach { f ->
                    if (f.isDirectory) {
                        File(target, f.relativeTo(contentRoot).path).mkdirs()
                    }
                }
                val files = contentRoot.walkTopDown().filter { it.isFile }.toList()
                emit("-", "Writing ${files.size} file(s)")
                for (f in files) {
                    val rel = f.relativeTo(contentRoot).path
                    File(target, rel).writeBytes(f.readBytes())
                }
            } catch (e: Throwable) {
                emit("!", "File write failed: ${e.message ?: e.javaClass.simpleName}")
                return finishFail(OpErrorCode.WRITE, "模块文件写入失败: ${e.message ?: e.javaClass.simpleName}")
            }

            // 4) 权限设置（尽力而为：root 模式下 chmod/chcon 生效，shell 模式静默失败不阻断安装）
            // 90t 修复：旧逻辑「非 .sh 文件一律 chmod 644」会把模块内置二进制（busybox/ELF
            // 可执行文件通常无 .sh 后缀）的执行位剥掉 → 运行时报
            // 「sh: busybox: inaccessible or not found」。改为：全部先 755（目录+所有文件，
            // 含无扩展名二进制），仅对明确的数据/资源类扩展名收回 644，保证可执行文件能跑、
            // 数据文件保持不可执行。
            // 90u：追加 chcon shell_exec——SELinux enforcing 下 shell 域 exec 原生 ELF（busybox）
            // 需要 shell_exec 类型。
            emit("-", "Setting permissions")
            val chmodResult = sh(
                "chmod -R 755 '$MODULE_HOME/$id'; " +
                    "find '$MODULE_HOME/$id' -type f \\( " +
                    "-name '*.sh' -o -name '*.json' -o -name '*.conf' -o -name '*.cfg' " +
                    "-o -name '*.ini' -o -name '*.txt' -o -name '*.md' -o -name '*.prop' " +
                    "-o -name '*.list' -o -name '*.rules' -o -name '*.rc' -o -name '*.xml' " +
                    "-o -name '*.html' -o -name '*.css' -o -name '*.js' -o -name '*.png' " +
                    "-o -name '*.jpg' -o -name '*.jpeg' -o -name '*.gif' -o -name '*.svg' " +
                    "-o -name '*.webp' -o -name '*.woff' -o -name '*.woff2' -o -name '*.ttf' " +
                    "-o -name '*.ico' -o -name '*.db' -o -name '*.log' \\) " +
                    "-exec chmod 644 {} \\; ; " +
                    "chcon -R u:object_r:shell_exec:s0 '$MODULE_HOME/$id' 2>/dev/null; " +
                    "echo INSTALL_DONE"
            )
            if (!chmodResult.contains("INSTALL_DONE")) {
                // shell 模式可能无权限 chmod，不阻断安装（执行前 applyModuleState 会再尝试）
                DiagnosticLog.append("[模块安装] $id: 权限设置未完成（服务未运行/无权限），稍后执行时再尝试: ${chmodResult.takeLast(120)}")
            }

            emit("✓", "Module installed: $id ($fileName)")
            val result = InstallResult(true, log.toString(), OpErrorCode.OK, "")
            ModuleLogs.save(ctx, "install_${ModuleLogs.fileStamp()}.log", result.log)
            DiagnosticLog.append("[模块安装] 成功 $id ($fileName)")
            return result
        } catch (e: Throwable) {
            emit("!", "Unexpected error: ${e.message ?: e.javaClass.simpleName}")
            return finishFail(OpErrorCode.OTHER, "安装失败: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            tmp.deleteRecursively()
        }
    }

    /** 从 SAF uri 查询原始文件名（安装日志展示「- Installing XXX.zip」用）。 */
    private fun queryDisplayName(ctx: Context, uri: Uri): String? {
        return try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && !c.isNull(idx)) c.getString(idx) else null
            }
        } catch (_: Throwable) { null }
            ?: uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun formatSize(kb: Long): String = when {
        kb >= 1024 -> "%.1f MB".format(kb / 1024.0)
        kb >= 1 -> "$kb KB"
        else -> "—"
    }
}
