package moe.shizuku.privileged.api.ui.modules

import android.content.Intent
import android.graphics.Typeface
import android.util.TypedValue
import android.widget.TextView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.privileged.api.core.DiagnosticLog
import moe.shizuku.privileged.api.core.LinModuleItem
import moe.shizuku.privileged.api.core.ModuleLogs
import moe.shizuku.privileged.api.core.ModuleStore
import moe.shizuku.privileged.api.core.Prefs
import moe.shizuku.privileged.api.core.ScriptItem
import moe.shizuku.privileged.api.core.ShizukuManager
import moe.shizuku.privileged.api.core.CommandTaskRegistry
import moe.shizuku.privileged.api.core.TaskState
import moe.shizuku.privileged.api.ui.browser.BrowserActivity
import moe.shizuku.privileged.api.ui.widget.EmptyState
import moe.shizuku.privileged.api.ui.widget.GlassCard
import moe.shizuku.privileged.api.ui.widget.GlassPanel
import moe.shizuku.privileged.api.ui.widget.pressClickable
import moe.shizuku.privileged.api.ui.widget.pressClickableNoRipple
import moe.shizuku.privileged.api.util.ActivityTransitions

private val PlayButtonColor = Color(0xFF006064)

/**
 * 模块页：Shell 脚本 + Lin 模块，真实数据（filesDir/modules/）与真实执行（Shizuku shell）。
 */
@Composable
fun ModulesScreen(onEditScript: (String?) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(Prefs.modulesTab) }

    var scripts by remember { mutableStateOf(emptyList<ScriptItem>()) }
    var linModules by remember { mutableStateOf(emptyList<LinModuleItem>()) }
    var loading by remember { mutableStateOf(true) }

    // 安装弹窗状态（66 轮：安装页逐步日志 + 失败弹窗）
    var installVisible by remember { mutableStateOf(false) }
    var installTitle by remember { mutableStateOf("安装中...") }
    var installLog by remember { mutableStateOf("") }
    var installDone by remember { mutableStateOf(false) }
    var installSuccess by remember { mutableStateOf<Boolean?>(null) }
    var installErrorCode by remember { mutableStateOf(0) }
    var installErrorText by remember { mutableStateOf("") }
    var showInstallFailDialog by remember { mutableStateOf(false) }

    // 删除确认
    var deleteTarget by remember { mutableStateOf<Pair<String, String>?>(null) } // (type, id)
    var installMsg by remember { mutableStateOf<String?>(null) }
    var showDoc by remember { mutableStateOf(false) }

    fun reload() {
        scope.launch(Dispatchers.IO) {
            val s = ModuleStore.listScripts(ctx)
            // 模块重装/更新检测：mtime 变化 → 停旧进程 + 开关重置为关闭 + 清回显，
            // 保证模块页打开即显示正确开关状态（更新后默认关闭，手动打开才正常运行）
            ModuleStore.checkModulesUpdated(ctx)
            val m = ModuleStore.listModules(ctx)
            withContext(Dispatchers.Main) {
                scripts = s
                linModules = m
                loading = false
            }
        }
    }

    // 从编辑 / 输出页返回时自动刷新
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) reload()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) { reload() }

    // 导入 .sh 脚本
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                val err = ModuleStore.importScript(ctx, uri)
                withContext(Dispatchers.Main) {
                    installMsg = when {
                        err == null -> "脚本导入成功"
                        err.startsWith("脚本已导入") -> err
                        else -> "导入失败: $err"
                    }
                    reload()
                }
            }
        }
    }

    /**
     * 后台执行命令（1.1.5 方案 B）：登记到全局任务注册表，输出进任务缓冲，
     * 在终端页任务面板查看 / 停止 / 回看；模块页不再弹结果页。
     * 执行日志自动落盘（filesDir/logs/exec_<tag>_<ts>.log），成功/失败/超时都留档。
     * onDone 回写模块卡片结果；页面已离开时结果已持久化，回模块页 reload 刷新。
     */
    fun launchBackground(
        title: String,
        cmd: String,
        source: String,
        logTag: String? = null,
        onDone: ((String, Int?) -> Unit)? = null
    ) {
        if (cmd.isBlank()) return
        val identity = Prefs.execIdentity
        val task = CommandTaskRegistry.start(title, cmd, source, identity)
        task.detached = true
        android.widget.Toast.makeText(ctx, "已转入后台运行，可到终端页查看", android.widget.Toast.LENGTH_SHORT).show()
        val start = System.currentTimeMillis()
        CommandTaskRegistry.execute(
            task = task,
            cmd = cmd,
            identity = identity,
            onComplete = { t ->
                val state = t.state
                val code = t.exitCode
                val elapsedMs = System.currentTimeMillis() - start
                val outText = t.output.joinToString("\n") {
                    if (it.isError) "[错误] ${it.text}" else it.text
                }.trimEnd('\n')
                val footer = ModuleLogs.line("──", when (state) {
                    TaskState.TIMEOUT -> "结果：执行超时（5 分钟无输出）\n排查提示：脚本可能等待 su 授权/输入、命令挂起，或执行了 start.sh 类脚本（会重启服务端）；完整输出见 exec_*.log"
                    TaskState.FAILED -> "结果：失败（退出码 ${code ?: "未知"}）"
                    TaskState.STOPPED -> "结果：已手动停止"
                    else -> "结果：成功"
                })
                val logContent = ModuleLogs.line("──", "执行: $title（身份: ${identity.label}）") + "\n" +
                    outText + "\n" + footer
                ModuleLogs.save(ctx, "exec_${(logTag ?: "cmd")}_${ModuleLogs.fileStamp()}.log", logContent)
                DiagnosticLog.append(
                    "[执行] $title（${identity.short}）: ${when (state) {
                        TaskState.FINISHED -> "成功"; TaskState.STOPPED -> "已停止"
                        TaskState.TIMEOUT -> "超时"; else -> "失败/超时"
                    }}${code?.let { "（退出码 $it）" } ?: ""}"
                )
                onDone?.invoke(outText, code)
            }
        )
    }

    /** 开关型脚本 / 模块：执行 on/off 块并持久化状态。 */
    fun runToggle(id: String, name: String, on: Boolean) {
        ModuleStore.setEnabled(ctx, id, on)
        scope.launch(Dispatchers.IO) {
            val block = ModuleStore.toggleBlock(ctx, id, on)
            withContext(Dispatchers.Main) {
                launchBackground(
                    "${if (on) "开启" else "关闭"}: $name",
                    if (block.isBlank()) "echo 脚本缺少 ${if (on) "#on" else "#off"} 块" else block,
                    source = "脚本",
                    logTag = id
                )
            }
        }
    }

    /** 安装模块（SAF 选择 zip / .lin）：全屏安装页逐步日志，失败弹窗带错误代码与保存日志入口（66 轮）。 */
    val installLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            installVisible = true
            installDone = false
            installSuccess = null
            installTitle = "安装中..."
            installLog = ""
            installErrorCode = 0
            installErrorText = ""
            scope.launch(Dispatchers.IO) {
                val result = ModuleStore.installModule(ctx, uri) { line ->
                    withContext(Dispatchers.Main) { installLog += line + "\n" }
                }
                withContext(Dispatchers.Main) {
                    installDone = true
                    installSuccess = result.success
                    installErrorCode = result.errorCode
                    installErrorText = result.errorText
                    installTitle = if (result.success) "安装成功" else "安装失败"
                    if (!result.success) showInstallFailDialog = true
                    reload()
                }
            }
        }
    }

    /** 保存最近一次安装日志到用户可见目录。 */
    fun saveLastInstallLog() {
        if (installLog.isBlank()) return
        val saved = ModuleLogs.saveToStorage(ctx, "install_${ModuleLogs.fileStamp()}.log", installLog)
        android.widget.Toast.makeText(ctx, "日志已保存: $saved", android.widget.Toast.LENGTH_LONG).show()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            // 90c 轮：模块页顶栏不再显示 GlassFallbackColor 底色（用户反馈"不要这块颜色"），
            // 传 containerColor = Transparent 只保留圆角裁剪与内容，融入页面背景
            GlassPanel(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(18.dp),
                containerColor = Color.Transparent
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 90d 轮：脚本/模块/推荐改用分段胶囊 Tab 行（替代 TabRow）：
                    // 90b 的 indicator 自绘条出现遮挡异常（选中项色块覆盖区域不对），
                    // 改用纯基础 API 自绘：浅色轨道 + 选中项整块主题色胶囊，文字渲染完全可控
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        SegmentTab(Modifier.weight(1f), "脚本", tab == 0) { tab = 0; Prefs.modulesTab = 0 }
                        SegmentTab(Modifier.weight(1f), "模块", tab == 1) { tab = 1; Prefs.modulesTab = 1 }
                        SegmentTab(Modifier.weight(1f), "推荐", tab == 2) { tab = 2; Prefs.modulesTab = 2 }
                        SegmentTab(Modifier.weight(1f), "日志", tab == 3) { tab = 3; Prefs.modulesTab = 3 }
                    }
                    IconButton(onClick = { showDoc = true }) {
                        Icon(Icons.Filled.Info, "格式与制作文档", tint = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            Box(modifier = Modifier.fillMaxSize()) {
                if (tab == 0) {
                    ScriptsTab(
                        scripts = scripts,
                        loading = loading,
                        onEdit = { onEditScript(it) },
                        onNew = { onEditScript(null) },
                        onImport = { importLauncher.launch(arrayOf("*/*")) },
                        onRun = { script ->
                            // 方案 B：脚本后台登记执行，输出进终端页任务面板，不再弹独立输出页
                            scope.launch(Dispatchers.IO) {
                                val content = ModuleStore.scriptContent(ctx, script.id)
                                withContext(Dispatchers.Main) {
                                    launchBackground(
                                        "运行: ${script.name}",
                                        content,
                                        source = "脚本",
                                        logTag = script.id
                                    )
                                }
                            }
                        },
                        onToggle = { script, on ->
                            runToggle(script.id, script.name, on)
                            scripts = scripts.map {
                                if (it.id == script.id) it.copy(enabled = on) else it
                            }
                        },
                        onDelete = { script -> deleteTarget = Pair("script", script.id) },
                        onTriggerChange = { script ->
                            scripts = ModuleStore.listScripts(ctx)
                        },
                        onLog = { script ->
                            // 66 轮：脚本日志入口（执行日志查看、保存、删除）
                            val intent = Intent(ctx, ModuleLogActivity::class.java)
                            intent.putExtra("log_type", "script")
                            intent.putExtra("log_id", script.id)
                            intent.putExtra("log_name", script.name)
                            ctx.startActivity(intent)
                            ActivityTransitions.overrideForward(ctx as android.app.Activity)
                        }
                    )
                } else if (tab == 1) {
                    LinModulesTab(
                        modules = linModules,
                        loading = loading,
                        onToggle = { m, on ->
                            // Lin 模块开关 = 状态 + 引擎统一执行：后台执行 #on 块 / stop.sh，
                            // 输出进终端任务面板；完成后结果持久化并回显到卡片
                            ModuleStore.setEnabled(ctx, m.id, on)
                            scope.launch(Dispatchers.IO) {
                                val uiScript = ModuleStore.moduleToggleScript(ctx, m.id, on)
                                withContext(Dispatchers.Main) {
                                    launchBackground(
                                        if (on) "开启: ${m.name}" else "关闭: ${m.name}",
                                        uiScript, source = "模块", logTag = m.id
                                    ) { output, _ ->
                                        ModuleStore.saveModuleResult(ctx, m.id, output)
                                        linModules = linModules.map {
                                            if (it.id == m.id) it.copy(
                                                enabled = on,
                                                lastResult = ModuleStore.getModuleResult(ctx, m.id)
                                            ) else it
                                        }
                                    }
                                }
                            }
                        },
                        onAction = { m ->
                            // 运行按钮：后台执行 run.sh 全文（一次性模块），完成后结果回显到卡片
                            scope.launch(Dispatchers.IO) {
                                val uiScript = ModuleStore.moduleActionScript(ctx, m.id)
                                    .ifBlank { "echo 模块缺少 run.sh / service.sh" }
                                withContext(Dispatchers.Main) {
                                    launchBackground("运行: ${m.name}", uiScript, source = "模块", logTag = m.id) { output, _ ->
                                        ModuleStore.saveModuleResult(ctx, m.id, output)
                                        linModules = linModules.map {
                                            if (it.id == m.id) it.copy(
                                                lastResult = ModuleStore.getModuleResult(ctx, m.id)
                                            ) else it
                                        }
                                    }
                                }
                            }
                        },
                        onWebUI = { m ->
                            val url = ModuleStore.webuiUrl(ctx, m)
                            if (url.isNotBlank()) {
                                val intent = Intent(ctx, BrowserActivity::class.java)
                                intent.putExtra("url", url)
                                intent.putExtra("title", m.name)
                                intent.putExtra("module_id", m.id)
                                ctx.startActivity(intent)
                                ActivityTransitions.overrideForward(ctx as android.app.Activity)
                            }
                        },
                        onDelete = { m -> deleteTarget = Pair("module", m.id) },
                        onInstall = { installLauncher.launch(arrayOf("*/*")) },
                        onLog = { m ->
                            // 66 轮：模块日志入口（安装/执行日志查看、保存、删除）
                            val intent = Intent(ctx, ModuleLogActivity::class.java)
                            intent.putExtra("log_type", "module")
                            intent.putExtra("log_id", m.id)
                            intent.putExtra("log_name", m.name)
                            ctx.startActivity(intent)
                            ActivityTransitions.overrideForward(ctx as android.app.Activity)
                        }
                    )
                }
                if (tab == 2) {
                    RecommendedTab(
                        onImport = { importLauncher.launch(arrayOf("*/*")) },
                        onOpenBrowser = { url ->
                            val intent = Intent(ctx, BrowserActivity::class.java)
                            intent.putExtra("url", url)
                            intent.putExtra("title", "推荐资源")
                            ctx.startActivity(intent)
                            ActivityTransitions.overrideForward(ctx as android.app.Activity)
                        },
                        onInstallScript = { name, content ->
                            scope.launch(Dispatchers.IO) {
                                ModuleStore.saveScript(ctx, "", name, content)
                                withContext(Dispatchers.Main) {
                                    scripts = ModuleStore.listScripts(ctx)
                                    tab = 0
                                }
                            }
                        }
                    )
                }
                if (tab == 3) {
                    // 日志总览：列出 filesDir/logs/ 下所有日志文件
                    AllLogsTab(
                        onOpenLog = { fileName ->
                            val intent = Intent(ctx, ModuleLogActivity::class.java)
                            intent.putExtra("log_type", "script")
                            intent.putExtra("log_id", fileName.removeSuffix(".log"))
                            intent.putExtra("log_name", fileName.removeSuffix(".log"))
                            ctx.startActivity(intent)
                            ActivityTransitions.overrideForward(ctx as android.app.Activity)
                        }
                    )
                }
            }
        }

        if (installVisible) {
            // 66 轮：安装页（逐步日志 + 成功/失败状态 + 保存日志），失败时另弹「错误代码」对话框
            ExecutionOverlay(
                title = installTitle,
                output = installLog,
                done = installDone,
                success = installSuccess,
                onClose = { installVisible = false; showInstallFailDialog = false },
                onSave = { saveLastInstallLog() },
                onCopy = { copyText(ctx, "安装日志", installLog) },
                onSaveAsScript = {}
            )
        }

        if (showDoc) {
            ModuleDocDialog(onDismiss = { showDoc = false })
        }

        deleteTarget?.let { (type, id) ->
            AlertDialog(
                onDismissRequest = { deleteTarget = null },
                title = { Text("删除确认", fontWeight = FontWeight.SemiBold) },
                text = { Text("确定删除该${if (type == "script") "脚本" else "模块"}吗？此操作不可恢复。") },
                confirmButton = {
                    TextButton(onClick = {
                        deleteTarget = null
                        if (type == "script") ModuleStore.deleteScript(ctx, id) else ModuleStore.deleteModule(ctx, id)
                        reload()
                    }) { Text("删除", color = Color(0xFFE57373)) }
                },
                dismissButton = {
                    TextButton(onClick = { deleteTarget = null }) { Text("取消") }
                }
            )
        }

        installMsg?.let { msg ->
            AlertDialog(
                onDismissRequest = { installMsg = null },
                title = { Text("安装结果", fontWeight = FontWeight.SemiBold) },
                text = { Text(msg) },
                confirmButton = {
                    TextButton(onClick = { installMsg = null }) { Text("好") }
                }
            )
        }

        // 66 轮：安装失败弹窗（对齐模块管理器：错误代码 + 请保存并检查日志）
        if (showInstallFailDialog) {
            AlertDialog(
                onDismissRequest = { showInstallFailDialog = false },
                title = { Text("失败", fontWeight = FontWeight.SemiBold, color = Color(0xFFE57373)) },
                text = {
                    Column {
                        Text("错误代码：$installErrorCode", fontWeight = FontWeight.Medium)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("请保存并检查日志。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (installErrorText.isNotBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                installErrorText,
                                fontSize = 12.sp,
                                color = Color(0xFFE57373),
                                maxLines = 3,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        showInstallFailDialog = false
                        saveLastInstallLog()
                    }) {
                        Icon(Icons.Filled.Save, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("保存日志")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showInstallFailDialog = false }) { Text("关闭") }
                }
            )
        }
    }
}

@Composable
private fun ExecutionOverlay(
    title: String,
    output: String,
    done: Boolean,
    success: Boolean?,
    onClose: () -> Unit,
    onSave: () -> Unit,
    onCopy: () -> Unit,
    onSaveAsScript: () -> Unit,
    onBackground: (() -> Unit)? = null,
    onStop: (() -> Unit)? = null
) {
    val scrollState = rememberScrollState()
    // 输出 TextView 引用：点击弹窗任意位置时折叠文本选中，关闭系统选择工具条
    val outputTextRef = remember { mutableStateOf<TextView?>(null) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .pointerInput(Unit) {
                detectTapGestures {
                    outputTextRef.value?.let { tv ->
                        collapseTextSelection(tv)
                    }
                }
            }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 状态标题行：执行中=转圈，成功=绿色对勾，超时=红色感叹
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                when {
                    !done -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.5.dp,
                            color = Color(0xFF4CAF50)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                    }
                    success == true -> {
                        Icon(Icons.Filled.CheckCircle, "执行成功", tint = Color(0xFF4CAF50), modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                    }
                    else -> {
                        Icon(Icons.Filled.Error, "执行超时", tint = Color(0xFFFF6B6B), modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                    }
                }
                Text(
                    title,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = when {
                        !done -> MaterialTheme.colorScheme.onBackground
                        success == true -> Color(0xFF4CAF50)
                        else -> Color(0xFFFF6B6B)
                    },
                    maxLines = 2
                )
                Spacer(modifier = Modifier.weight(1f))
                if (done) {
                    IconButton(onClick = onCopy) {
                        Icon(Icons.Filled.ContentCopy, "复制结果", tint = MaterialTheme.colorScheme.onBackground)
                    }
                }
                if (!done) {
                    onBackground?.let { bg ->
                        IconButton(onClick = bg) {
                            Icon(Icons.Filled.ArrowDownward, "后台运行", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    onStop?.let { st ->
                        IconButton(onClick = st) {
                            Icon(Icons.Filled.Stop, "停止运行", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, "关闭", tint = MaterialTheme.colorScheme.onBackground)
                    }
                }
            }

            // 终端风格输出面板
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF14141C))
                    .border(0.5.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
            ) {
                // 面板标题栏：mac 三色点 + 缩放提示
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.size(9.dp).clip(CircleShape).background(Color(0xFFFF5F57)))
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(modifier = Modifier.size(9.dp).clip(CircleShape).background(Color(0xFFFEBC2E)))
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(modifier = Modifier.size(9.dp).clip(CircleShape).background(Color(0xFF28C840)))
                    Spacer(modifier = Modifier.weight(1f))
                    Text("sh", color = Color(0xFF666680), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("双指缩放", color = Color(0xFF666680), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
                // 输出区：横向滚动 + 双指缩放 + 错误行红色
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(scrollState)
                        // 底部留白避开悬浮胶囊导航（首页/模块/终端/电池/设置），末行不被遮挡
                        .padding(start = 12.dp, end = 12.dp, bottom = 96.dp)
                ) {
                    if (output.isEmpty() && !done) {
                        Text("等待输出...", color = Color(0xFF666680), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    } else {
                        AndroidView(
                            modifier = Modifier.fillMaxWidth(),
                            factory = { ctx ->
                                // 横向可滚动 + 双指缩放字号的 TextView（终端风格）
                                android.widget.HorizontalScrollView(ctx).apply {
                                    isFillViewport = true
                                    setHorizontalScrollBarEnabled(true)
                                    addView(
                                        TextView(ctx).apply {
                                            typeface = Typeface.MONOSPACE
                                            // 默认 11sp（小字号，一屏看更多行、长行内容更完整）；双指缩放 10~24sp
                                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                                            setTextColor(android.graphics.Color.parseColor("#B8C0D0"))
                                            setTextIsSelectable(true)
                                            setHorizontallyScrolling(true)
                                            // 双指缩放：10sp~24sp，缩放后长行自动重排，可整体看全或放大细读
                                            val detector = android.view.ScaleGestureDetector(
                                                ctx,
                                                object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                                                    override fun onScale(d: android.view.ScaleGestureDetector): Boolean {
                                                        val density = ctx.resources.displayMetrics.scaledDensity
                                                        val cur = textSize / density
                                                        val next = (cur * d.scaleFactor).coerceIn(10f, 24f)
                                                        setTextSize(TypedValue.COMPLEX_UNIT_SP, next)
                                                        return true
                                                    }
                                                }
                                            )
                                            // 单击文本取消选择：仅当「按下时已有选中 + 短按(非长按) + 无位移」才折叠，
                                            // 避免把长按选词的松手误判成点击而刚高亮就消失
                                            var downX = 0f
                                            var downY = 0f
                                            var moved = false
                                            var hadSelectionAtDown = false
                                            setOnTouchListener { _, event ->
                                                detector.onTouchEvent(event)
                                                when (event.actionMasked) {
                                                    android.view.MotionEvent.ACTION_DOWN -> {
                                                        downX = event.x
                                                        downY = event.y
                                                        moved = false
                                                        // 记录按下瞬间是否已有选中：长按首次选词时按下时无选中，松手不折叠
                                                        hadSelectionAtDown =
                                                            selectionStart >= 0 && selectionEnd > selectionStart
                                                    }
                                                    android.view.MotionEvent.ACTION_MOVE -> {
                                                        if (kotlin.math.abs(event.x - downX) > 10f ||
                                                            kotlin.math.abs(event.y - downY) > 10f
                                                        ) moved = true
                                                    }
                                                    android.view.MotionEvent.ACTION_UP -> {
                                                        val isShortTap = event.eventTime - event.downTime < 400L
                                                        if (isShortTap && !moved && !detector.isInProgress &&
                                                            hadSelectionAtDown &&
                                                            selectionStart >= 0 && selectionEnd > selectionStart
                                                        ) {
                                                            collapseTextSelection(this)
                                                        }
                                                    }
                                                }
                                                false // 不消费事件，滚动/长按选择照常工作
                                            }
                                            outputTextRef.value = this
                                        },
                                        android.view.ViewGroup.LayoutParams(
                                            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                                            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                                        )
                                    )
                                }
                            },
                            update = { hsv ->
                                val tv = hsv.getChildAt(0) as TextView
                                tv.text = coloredOutput(output)
                            }
                        )
                    }
                }
            }

            if (done) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 100.dp),
                    contentAlignment = Alignment.BottomEnd
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 66 轮：保存日志（安装/执行失败也能留存完整报错，便于定位）
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.tertiaryContainer)
                                .clickable { onSaveAsScript() }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.Description, null,
                                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "存为脚本",
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.secondaryContainer)
                                .clickable { onSave() }
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.Save, null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "保存日志",
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .clickable { onClose() }
                                .padding(horizontal = 24.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.Close, null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "关闭",
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 通用剪贴板复制：结果/日志一键复制整段文本，空内容提示。 */
private fun copyText(ctx: android.content.Context, label: String, text: String) {
    if (text.isBlank()) {
        android.widget.Toast.makeText(ctx, "内容为空", android.widget.Toast.LENGTH_SHORT).show()
        return
    }
    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    cm.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
    android.widget.Toast.makeText(ctx, "已复制", android.widget.Toast.LENGTH_SHORT).show()
}

/** 输出逐行着色：含 [错误] 或 ! 开头=红（失败/报错），✓ 开头=绿（成功），── 开头=暗淡（分隔），其余默认。
 *  91 轮：行内 http(s) 链接识别为 URLSpan（链接色+下划线，一眼可辨；TextView 可选模式下不拦截点击）。 */
private val MODULE_OUTPUT_URL_REGEX = Regex("""https?://[\w\-._~:/?#\[\]@!$&'()*+,;=%]+""")

private fun coloredOutput(text: String): CharSequence {
    if (text.isBlank()) return text
    if (!text.contains("[错误]") && !text.contains('!') && !text.contains('✓') && !text.contains('─')) {
        return text
    }
    val spannable = android.text.SpannableString(text)
    var idx = 0
    for (line in text.lineSequence()) {
        val start = idx
        val end = (start + line.length).coerceAtMost(spannable.length)
        // 日志行带时间戳前缀（[HH:mm:ss] / [yyyy-MM-dd HH:mm:ss]），先跳过再匹配前缀标记
        val content = line.substringAfter("] ", line).trimStart()
        val color = when {
            line.contains("[错误]") || content.startsWith("!") ->
                android.graphics.Color.parseColor("#FF6B6B")
            content.startsWith("✓") ->
                android.graphics.Color.parseColor("#4CAF50")
            content.startsWith("──") ->
                android.graphics.Color.parseColor("#888899")
            else -> null
        }
        if (color != null) {
            spannable.setSpan(
                android.text.style.ForegroundColorSpan(color),
                start, end,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        // 行内 URL 高亮（URLSpan：系统链接色 + 下划线）
        for (m in MODULE_OUTPUT_URL_REGEX.findAll(line)) {
            val raw = m.value
            val clean = raw.trimEnd('.', ',', ';', ':', '!', '？', '。', '，', '；')
            if (clean.length < 10) continue
            val from = start + m.range.first
            val to = (start + m.range.first + clean.length).coerceAtMost(end)
            if (to > from) {
                spannable.setSpan(
                    android.text.style.URLSpan(clean),
                    from, to,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        idx = end + 1
    }
    return spannable
}

/** 折叠文本选中（关闭系统「翻译/搜索/复制」工具条）。此定制 SDK 的 TextView 缺失
 *  setSelection 实例方法，用等效的 Selection 静态方法实现：把选中区间收缩到光标。 */
private fun collapseTextSelection(tv: TextView) {
    if (tv.selectionStart < 0 || tv.selectionEnd <= tv.selectionStart) return
    val text = tv.text
    if (text is android.text.Spannable) {
        android.text.Selection.setSelection(text, tv.selectionStart)
    }
}

@Composable
private fun LinModulesTab(
    modules: List<LinModuleItem>,
    loading: Boolean,
    onToggle: (LinModuleItem, Boolean) -> Unit,
    onAction: (LinModuleItem) -> Unit,
    onWebUI: (LinModuleItem) -> Unit,
    onDelete: (LinModuleItem) -> Unit,
    onInstall: () -> Unit,
    onLog: (LinModuleItem) -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (loading) {
            EmptyState(
                title = "加载中...",
                subtitle = "",
                modifier = Modifier.padding(bottom = 100.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())
            )
        } else if (modules.isEmpty()) {
            EmptyState(
                title = "暂无 Lin 模块",
                subtitle = "点击右下角安装模块（.lin / .zip 包）",
                modifier = Modifier.padding(bottom = 100.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(top = 16.dp, bottom = 100.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(modules, key = { it.id }) { module ->
                    LinModuleCard(
                        module = module,
                        onToggle = { onToggle(module, it) },
                        onAction = { onAction(module) },
                        onWebUI = { onWebUI(module) },
                        onDelete = { onDelete(module) },
                        onLog = { onLog(module) }
                    )
                }
            }
        }

        ExtendedFloatingActionButton(
            onClick = onInstall,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 100.dp),
            icon = { Icon(Icons.Filled.Add, null) },
            text = { Text("安装模块") }
        )
    }
}

@Composable
private fun LinModuleCard(
    module: LinModuleItem,
    onToggle: (Boolean) -> Unit,
    onAction: () -> Unit,
    onWebUI: () -> Unit,
    onDelete: () -> Unit,
    onLog: () -> Unit
) {
    val ctx = LocalContext.current
    var enabled by remember { mutableStateOf(module.enabled) }
    LaunchedEffect(module.enabled) { enabled = module.enabled }
    // 90z 轮：实时读取模块运行时状态（日志/状态文件），不依赖宿主操作记录
    var liveState by remember(module.id) { mutableStateOf("") }
    LaunchedEffect(module.id) {
        val live = withContext(Dispatchers.IO) { ModuleStore.moduleLiveState(ctx, module.id) }
        if (live.isNotEmpty()) liveState = live
    }

    GlassCard {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(module.sizeLabel, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (module.webui.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (enabled) PlayButtonColor
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                            )
                            .then(if (enabled) Modifier.pressClickableNoRipple { onWebUI() } else Modifier)
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            "WEBUI",
                            fontSize = 11.sp,
                            color = if (enabled) Color.White
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                if (module.hasAction) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (enabled) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                            )
                            .then(if (enabled) Modifier.pressClickableNoRipple { onAction() } else Modifier)
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            "运行",
                            fontSize = 11.sp,
                            color = if (enabled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                // 仅服务型模块（#on/#off 或 module.json service:true）显示开关；运行型只有「运行」按钮
                if (module.hasService) {
                    Switch(
                        checked = enabled,
                        onCheckedChange = {
                            enabled = it
                            onToggle(it)
                        }
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(module.name, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            if (module.version.isNotEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text("版本: ${module.version}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (module.author.isNotEmpty()) {
                Text("作者: ${module.author}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("ID: ${module.id}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (module.description.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(module.description, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
            }
            // 最近执行结果回显：90z 起优先实时模块状态（日志/状态文件），
            // 无实时状态时回退宿主操作记录（引擎 / 开关 / 运行 写入）
            val shown = if (liveState.isNotEmpty()) liveState else module.lastResult
            if (shown.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                val ts = shown.substringBefore('|')
                val msg = shown.substringAfter('|', shown)
                Text(
                    "最近 [$ts] $msg",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 2
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (module.hasAction) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .then(if (enabled) Modifier.pressClickable { onAction() } else Modifier)
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.Terminal, "执行",
                            tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                if (module.webui.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .then(if (enabled) Modifier.pressClickable { onWebUI() } else Modifier)
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.Language, "WebUI",
                            tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                // 66 轮：日志入口（查看本模块安装/执行日志）
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .pressClickable { onLog() }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Description, "日志", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .pressClickable { onDelete() }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Delete, "删除", tint = Color(0xFFE57373), modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

/** 推荐资源 Tab：展示推荐脚本/模块与社区资源入口。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecommendedTab(
    onImport: () -> Unit,
    onOpenBrowser: (String) -> Unit,
    onInstallScript: (name: String, content: String) -> Unit
) {
    val ctx = LocalContext.current
    // Hyper OS 专区：游戏启动动画加速（debug.game.video.* setprop，小米/红米三倍速进游戏）
    var gameBoostAccel by remember { mutableStateOf(Prefs.gameBoostAccel) }
    var gameBoostBusy by remember { mutableStateOf(false) }
    // HyperOS 专区：触摸预加载（settings put system touchprestartoptconfig）与黑名单应用管理
    var touchPreload by remember { mutableStateOf(Prefs.touchPreload) }
    var blacklist by remember { mutableStateOf(emptyList<String>()) }
    var touchBusy by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var newPkg by remember { mutableStateOf("") }
    // HyperOS 专区：隐藏手势提示线（settings put global hide_gesture_line，1=隐藏 0=显示）
    var hideGestureLine by remember { mutableStateOf(Prefs.hideGestureLine) }
    var hideGestureBusy by remember { mutableStateOf(false) }
    // HyperOS 专区：最近任务样式（0=纵向排布 1=横向排布 2=堆叠后台 3=堆叠后台）
    var recentsStyle by remember { mutableStateOf(Prefs.recentsStyle) }
    var recentsBusy by remember { mutableStateOf(false) }
    // ColorOS 专区：AI电池助手（settings put global oplus_battery_agent_debug_force_entry 1=强开 0=关）
    var aiBattery by remember { mutableStateOf(Prefs.aiBatteryAgent) }
    var aiBatteryBusy by remember { mutableStateOf(false) }
    val recentsOptions = remember { listOf("纵向排布" to 0, "横向排布" to 1, "堆叠后台" to 2, "堆叠后台" to 3) }
    val scope = rememberCoroutineScope()

    // 应用激活专区：黑阈 / Scene / BatteryRecorder 一键激活命令（原样执行，输出 Toast 展示）
    val CMD_BREVENT = """output=$(pm path me.piebridge.brevent); export CLASSPATH=${'$'}{output#*:}; app_process /system/bin me.piebridge.brevent.server.BreventServer bootstrap; /system/bin/sh /data/local/tmp/brevent.sh"""
    val CMD_BATTERY = """/data/app/~~-_UtXwjnndQIEYXV2DV6Sw==/yangfentuozi.batteryrecorder-V_8WdXAud1lIbmIjeXu2Gw==/lib/arm64/libstarter.so"""
    // 对应应用包名（用于安装检测：pm path 有返回值=已安装）
    val PKG_BREVENT = "me.piebridge.brevent"
    // Scene6（现行）/ Scene5（旧版 com.lsdroid.ceres）：up.sh 位于各自外部数据目录
    val PKG_SCENE6 = "com.omarea.vtools"
    val PKG_SCENE5 = "com.lsdroid.ceres"
    val PKG_BATTERY = "yangfentuozi.batteryrecorder"

    /**
     * Scene 激活复合命令：固定以 Shell 身份（uid 2000、u:r:shell:s0）执行——
     * Scene daemon 校验 uid 与 SELinux 域，跑成 root/高权限身份会被拒绝。
     * 顺序：① 检查 up.sh 是否存在；② 回显当前 uid/域；③ 执行 up.sh；④ 回读 scene-daemon 进程。
     */
    fun sceneActivateCommand(pkg: String): String {
        val up = "/storage/emulated/0/Android/data/$pkg/up.sh"
        return buildString {
            append("UP='$up'; ")
            append("if [ ! -f \"\$UP\" ]; then echo 'up.sh 不存在：请先打开一次 Scene 生成脚本后重试'; exit 1; fi; ")
            append("echo \"身份: uid=\$(id -u) ctx=\$(id -Z 2>/dev/null)\"; ")
            append("sh \"\$UP\"; sleep 2; ")
            append("D=\$(ps -A -o PID,UID,CMD 2>/dev/null | grep -i scene-daemon | grep -v grep); ")
            append("if [ -n \"\$D\" ]; then echo \"daemon: \$D\"; else echo '未检测到 scene-daemon（脚本可能仍在初始化或被 SELinux 拦截）'; fi")
        }
    }

    // 应用激活专区安装检测状态（null=检测中，true=已安装，false=未安装）
    var breventInstalled by remember { mutableStateOf<Boolean?>(null) }
    var sceneInstalled by remember { mutableStateOf<Boolean?>(null) }
    var scene5Installed by remember { mutableStateOf<Boolean?>(null) }
    var batteryInstalled by remember { mutableStateOf<Boolean?>(null) }

    // 并行检测四个应用是否安装；全部未安装则隐藏整个专区。
    // 用 Shizuku 执行 pm path（有返回值=已安装），失败按未安装处理。
    fun refreshActivationStates() {
        scope.launch(Dispatchers.IO) {
            kotlinx.coroutines.coroutineScope {
                launch(Dispatchers.IO) {
                    breventInstalled = runCatching {
                        ShizukuManager.runCommand("pm path $PKG_BREVENT", Prefs.execIdentity).isNotBlank()
                    }.getOrDefault(false)
                }
                launch(Dispatchers.IO) {
                    sceneInstalled = runCatching {
                        ShizukuManager.runCommand("pm path $PKG_SCENE6", Prefs.execIdentity).isNotBlank()
                    }.getOrDefault(false)
                }
                launch(Dispatchers.IO) {
                    scene5Installed = runCatching {
                        ShizukuManager.runCommand("pm path $PKG_SCENE5", Prefs.execIdentity).isNotBlank()
                    }.getOrDefault(false)
                }
                launch(Dispatchers.IO) {
                    batteryInstalled = runCatching {
                        ShizukuManager.runCommand("pm path $PKG_BATTERY", Prefs.execIdentity).isNotBlank()
                    }.getOrDefault(false)
                }
            }
        }
    }

    // 应用安装/卸载/替换广播实时监听：装完立刻显示按钮，卸载立即隐藏，无需切页
    val pkgReceiver = remember {
        object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                refreshActivationStates()
            }
        }
    }
    DisposableEffect(Unit) {
        val filter = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_PACKAGE_ADDED)
            addAction(android.content.Intent.ACTION_PACKAGE_REMOVED)
            addAction(android.content.Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        androidx.core.content.ContextCompat.registerReceiver(
            ctx, pkgReceiver, filter, androidx.core.content.ContextCompat.RECEIVER_EXPORTED
        )
        onDispose { ctx.unregisterReceiver(pkgReceiver) }
    }

    // 进入页面时检测一次 + 从后台恢复时兜底刷新（双保险，覆盖非广播触达的安装路径）
    LaunchedEffect(Unit) { refreshActivationStates() }

    fun runActivation(label: String, cmd: String) {
        scope.launch(Dispatchers.IO) {
            val out = runCatching {
                ShizukuManager.runCommand(cmd, Prefs.execIdentity)
            }.getOrElse { it.message ?: "执行失败" }
            withContext(Dispatchers.Main) {
                val brief = out.trim().take(180)
                android.widget.Toast.makeText(
                    ctx,
                    "「$label」" + if (brief.isBlank()) "执行完成" else "\n" + brief,
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // Scene 专用激活：强制 Shell 身份（Scene 校验 uid=2000 / u:r:shell:s0，高权限身份会被拒），
    // 命令内含脚本存在性、身份回显与 daemon 状态回读；结果仍走 Toast 短回显。
    fun runSceneActivation(label: String, pkg: String) {
        scope.launch(Dispatchers.IO) {
            val out = runCatching {
                ShizukuManager.runCommand(
                    sceneActivateCommand(pkg),
                    ShizukuManager.ExecIdentity.SHELL
                )
            }.getOrElse { it.message ?: "执行失败" }
            withContext(Dispatchers.Main) {
                val brief = out.trim().take(220)
                android.widget.Toast.makeText(
                    ctx,
                    "「$label」" + if (brief.isBlank()) "执行完成" else "\n" + brief,
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // touchprestartopt 设置项 key 存在三种历史拼写（带/不带下划线），真机逐个探测，
    // 取第一个有实际值的；全部为空则默认 touchprestartopt_config（标准写法）
    val TOUCH_KEYS = listOf(
        "touchprestartopt_config",
        "touch_prestart_opt_config",
        "touchprestartoptconfig"
    )

    suspend fun resolveTouchKey(): String {
        for (k in TOUCH_KEYS) {
            val v = runCatching {
                ShizukuManager.runCommand("settings get system $k", Prefs.execIdentity).trim()
            }.getOrDefault("")
            if (v.isNotEmpty() && v != "null") return k
        }
        return "touchprestartopt_config"
    }

    // 构造 touchprestartopt_config 标准 JSON 值：featureDisable=false 启用、true 禁用；
    // bgExceptionInterceptDisable 固定 false（保持文档默认）；touchDownPreStartBlackList 始终保留当前黑名单
    fun buildTouchCfg(on: Boolean, list: List<String>): String {
        val black = if (list.isEmpty()) "" else "[" + list.joinToString(",") { "\"$it\"" } + "]"
        return "{\"featureDisable\":${!on},\"bgExceptionInterceptDisable\":false,\"touchDownPreStartBlackList\":$black}"
    }

    // 解析 touchprestartopt 配置（兼容标准 JSON 与历史无引号键写法）：featureDisable=false=启用
    fun parseTouchOn(cfg: String): Boolean =
        cfg.isNotEmpty() && cfg != "null" &&
            Regex("\"?featureDisable\"?:\\s*(true|false)").find(cfg)?.groupValues?.get(1) == "false"

    // 保存触摸预加载配置（开关/黑名单增删共用）——写入后查值确认：
    // 再读同一 key 解析 featureDisable 与黑名单列表，与目标一致才算成功，否则 UI 回弹并提示真实值
    fun saveTouchCfg(on: Boolean, list: List<String>, label: String) {
        touchBusy = true
        scope.launch(Dispatchers.IO) {
            val key = resolveTouchKey()
            val cmd = "settings put system $key \"" + buildTouchCfg(on, list) + "\""
            runCatching {
                ShizukuManager.runCommand(cmd, Prefs.execIdentity)
            }
            Thread.sleep(300)
            val cfg = runCatching {
                ShizukuManager.runCommand("settings get system $key", Prefs.execIdentity).trim()
            }.getOrDefault("")
            val nowOn = parseTouchOn(cfg)
            val m = Regex("touchDownPreStartBlackList:\\s*\\[([^\\]]*)\\]").find(cfg)
            val nowList = if (m == null || m.groupValues[1].isBlank()) emptyList()
            else m.groupValues[1].split(",").map { it.trim().trim('"') }.filter { it.isNotBlank() }
            val ok = nowOn == on && nowList.toSet() == list.toSet()
            withContext(Dispatchers.Main) {
                if (ok) {
                    Prefs.touchPreload = on
                    touchPreload = on
                    blacklist = list
                    android.widget.Toast.makeText(
                        ctx,
                        "「$label」设置成功",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                } else {
                    android.widget.Toast.makeText(
                        ctx,
                        "「$label」设置未生效（当前预加载:$nowOn，黑名单 ${nowList.size} 项）",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
                touchBusy = false
            }
        }
    }

    // 保存隐藏手势提示线（1=隐藏 0=显示）——写入后查值确认
    fun saveGesture(on: Boolean) {
        hideGestureBusy = true
        scope.launch(Dispatchers.IO) {
            val cmd = "settings put global hide_gesture_line " + (if (on) "1" else "0")
            runCatching {
                ShizukuManager.runCommand(cmd, Prefs.execIdentity)
            }
            Thread.sleep(300)
            val now = runCatching {
                ShizukuManager.runCommand("settings get global hide_gesture_line", Prefs.execIdentity).trim()
            }.getOrDefault("")
            val target = if (on) "1" else "0"
            val ok = now == target
            withContext(Dispatchers.Main) {
                if (ok) {
                    Prefs.hideGestureLine = on
                    hideGestureLine = on
                    android.widget.Toast.makeText(
                        ctx,
                        if (on) "手势提示线已隐藏" else "手势提示线已显示",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                } else {
                    android.widget.Toast.makeText(
                        ctx,
                        "设置未生效（当前值 $now）",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
                hideGestureBusy = false
            }
        }
    }

    // 保存最近任务样式（0=纵向排布 1=横向排布 2=堆叠后台 3=堆叠后台）——写入后查值确认
    fun saveRecents(v: Int) {
        recentsBusy = true
        scope.launch(Dispatchers.IO) {
            val cmd = "settings put global task_stack_view_layout_style $v"
            runCatching {
                ShizukuManager.runCommand(cmd, Prefs.execIdentity)
            }
            Thread.sleep(300)
            val now = runCatching {
                ShizukuManager.runCommand("settings get global task_stack_view_layout_style", Prefs.execIdentity).trim()
            }.getOrDefault("")
            val ok = now == v.toString()
            val label = recentsOptions.firstOrNull { it.second == v }?.first ?: "样式$v"
            withContext(Dispatchers.Main) {
                if (ok) {
                    Prefs.recentsStyle = v
                    recentsStyle = v
                    android.widget.Toast.makeText(
                        ctx,
                        "最近任务样式已切换为「$label」",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                } else {
                    android.widget.Toast.makeText(
                        ctx,
                        "设置未生效（当前值 $now）",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
                recentsBusy = false
            }
        }
    }

    // 保存 ColorOS AI电池助手（1=强开 0=关）——写入后查值确认
    fun saveAiBattery(on: Boolean) {
        aiBatteryBusy = true
        scope.launch(Dispatchers.IO) {
            val cmd = "settings put global oplus_battery_agent_debug_force_entry " + (if (on) "1" else "0")
            runCatching { ShizukuManager.runCommand(cmd, Prefs.execIdentity) }
            Thread.sleep(300)
            val now = runCatching {
                ShizukuManager.runCommand("settings get global oplus_battery_agent_debug_force_entry", Prefs.execIdentity).trim()
            }.getOrDefault("")
            val target = if (on) "1" else "0"
            val ok = now == target
            withContext(Dispatchers.Main) {
                if (ok) {
                    Prefs.aiBatteryAgent = on
                    aiBattery = on
                    android.widget.Toast.makeText(
                        ctx,
                        if (on) "AI电池助手已强开" else "AI电池助手已关闭",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                } else {
                    android.widget.Toast.makeText(
                        ctx,
                        "设置未生效（当前值 $now）",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
                aiBatteryBusy = false
            }
        }
    }

    // 保存游戏启动动画加速——写入后 getprop 查值确认
    fun saveGameBoost(on: Boolean) {
        gameBoostBusy = true
        scope.launch(Dispatchers.IO) {
            val cmd = if (on) {
                "setprop debug.game.video.support true && setprop debug.game.video.speed true"
            } else {
                "setprop debug.game.video.support false && setprop debug.game.video.speed false"
            }
            runCatching {
                ShizukuManager.runCommand(cmd, Prefs.execIdentity)
            }
            Thread.sleep(300)
            val now = runCatching {
                ShizukuManager.runCommand("getprop debug.game.video.speed", Prefs.execIdentity).trim()
            }.getOrDefault("")
            val target = if (on) "true" else "false"
            val ok = now == target || (target == "true" && now == "1")
            withContext(Dispatchers.Main) {
                if (ok) {
                    Prefs.gameBoostAccel = on
                    gameBoostAccel = on
                    android.widget.Toast.makeText(
                        ctx,
                        if (on) "游戏启动动画加速已开启" else "游戏启动动画加速已关闭",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                } else {
                    android.widget.Toast.makeText(
                        ctx,
                        "设置未生效（当前值 $now）",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
                gameBoostBusy = false
            }
        }
    }

    // 查值刷新 HyperOS 专区开关状态（进入页面 / onResume 从后台恢复 / 切回推荐页时调用；
    // 任一设置操作进行中则跳过，避免覆盖正在执行的结果）
    fun refreshHyperOsState() {
        if (gameBoostBusy || touchBusy || hideGestureBusy || recentsBusy || aiBatteryBusy) return
        scope.launch(Dispatchers.IO) {
            val real = runCatching {
                ShizukuManager.runCommand("getprop debug.game.video.speed", Prefs.execIdentity).trim()
            }.getOrDefault("")
            // 触摸预加载配置：featureDisable=false=启用，touchDownPreStartBlackList:[...]=黑名单
            val cfg = runCatching {
                ShizukuManager.runCommand("settings get system ${resolveTouchKey()}", Prefs.execIdentity).trim()
            }.getOrDefault("")
            val parsedOn = parseTouchOn(cfg)
            val m = Regex("touchDownPreStartBlackList:\\s*\\[([^\\]]*)\\]").find(cfg)
            val parsedList = if (m == null || m.groupValues[1].isBlank()) emptyList()
            else m.groupValues[1].split(",").map { it.trim().trim('"') }.filter { it.isNotBlank() }
            // 隐藏手势提示线：1=隐藏 0=显示
            val gesture = runCatching {
                ShizukuManager.runCommand("settings get global hide_gesture_line", Prefs.execIdentity).trim()
            }.getOrDefault("")
            // 最近任务样式：0=纵向 1=横向 2/3=堆叠
            val styleRaw = runCatching {
                ShizukuManager.runCommand("settings get global task_stack_view_layout_style", Prefs.execIdentity).trim()
            }.getOrDefault("")
            // ColorOS AI电池助手：1=强开 0=关
            val aiBat = runCatching {
                ShizukuManager.runCommand("settings get global oplus_battery_agent_debug_force_entry", Prefs.execIdentity).trim()
            }.getOrDefault("")
            withContext(Dispatchers.Main) {
                if (real == "true" || real == "1") {
                    gameBoostAccel = true; Prefs.gameBoostAccel = true
                } else if (real.isNotEmpty()) {
                    gameBoostAccel = false; Prefs.gameBoostAccel = false
                }
                if (cfg.isNotEmpty() && cfg != "null") {
                    touchPreload = parsedOn; Prefs.touchPreload = parsedOn
                    blacklist = parsedList
                }
                if (gesture == "1") {
                    hideGestureLine = true; Prefs.hideGestureLine = true
                } else if (gesture == "0") {
                    hideGestureLine = false; Prefs.hideGestureLine = false
                }
                val styleVal = styleRaw.toIntOrNull()
                if (styleVal != null && styleVal in 0..3) {
                    recentsStyle = styleVal; Prefs.recentsStyle = styleVal
                }
                if (aiBat == "1") {
                    aiBattery = true; Prefs.aiBatteryAgent = true
                } else if (aiBat == "0") {
                    aiBattery = false; Prefs.aiBatteryAgent = false
                }
            }
        }
    }

    // 首次进入组合时刷新一次
    LaunchedEffect(Unit) { refreshHyperOsState() }
    // 从后台恢复（如去系统设置 / 玄戒工具箱修改后返回）时自动重新查值刷新；同时兜底刷新应用激活安装状态
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshHyperOsState()
                refreshActivationStates()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // 动态检测设备最高刷新率
    val maxRefresh = remember {
        try {
            val display = (ctx.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay
            display.supportedModes.maxOfOrNull { it.refreshRate.toInt() } ?: 120
        } catch (_: Throwable) { 120 }
    }
    val minRefresh = maxRefresh

    val recommendations = listOf(
        RecommendedScript(
            "高刷新率解锁",
            "通过 Shizuku shell 设置系统级刷新率（设备最高 $maxRefresh Hz）",
            "高刷新率解锁",
            "#---\n#name: 高刷新率解锁\n#description: 设置系统级最高刷新率 ($maxRefresh Hz)\n#---\n\n#on\nsettings put system peak_refresh_rate $maxRefresh\nsettings put system min_refresh_rate $minRefresh\nsettings put secure miui_refresh_rate $maxRefresh\nsettings put secure user_refresh_rate $maxRefresh\nsettings put system speed_mode 1\necho \"高刷新率已开启 ($maxRefresh Hz)\"\n#off\nsettings put system peak_refresh_rate 60\nsettings put system min_refresh_rate 60\necho \"高刷新率已关闭\""
        ),
        RecommendedScript(
            "电池优化白名单",
            "将常用应用加入电池优化白名单，防止后台被杀",
            "电池优化白名单",
            "#---\n#name: 电池优化白名单\n#description: 电池优化白名单管理（按需修改包名）\n#---\n\n#on\n# 取消下面注释并修改为你的常用应用包名：\n# dumpsys deviceidle whitelist +com.tencent.mm\n# dumpsys deviceidle whitelist +com.eg.android.AlipayGphone\necho 请编辑脚本添加你的常用应用包名\n#off\n# dumpsys deviceidle whitelist -com.tencent.mm\necho 已关闭"
        ),
        RecommendedScript(
            "前台服务管理",
            "查看当前运行的前台服务",
            "前台服务管理",
            "#---\n#name: 前台服务管理\n#description: 查看前台服务\n#---\n\ndumpsys activity services"
        ),
        RecommendedScript(
            "存储清理",
            "清理系统缓存",
            "存储清理",
            "#---\n#name: 存储清理\n#description: 清理系统缓存\n#---\n\npm trim-caches 500M\necho 缓存已清理"
        )
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 100.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 应用激活专区：检测到对应应用安装才显示按钮；全部未安装则整块隐藏
        if (breventInstalled == true || sceneInstalled == true || scene5Installed == true || batteryInstalled == true) {
            item {
                Text(
                    "应用激活专区",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            item {
                GlassCard {
                    // FlowRow：按钮按内容宽度排列，排不下自动折行，绝不压缩变形
                    FlowRow(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (breventInstalled == true) {
                            OutlinedButton(
                                onClick = { runActivation("激活黑阈", CMD_BREVENT) },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                            ) {
                                Text("黑阈", fontSize = 13.sp, maxLines = 1)
                            }
                        }
                        if (sceneInstalled == true) {
                            OutlinedButton(
                                onClick = { runSceneActivation("激活Scene", PKG_SCENE6) },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                            ) {
                                Text("Scene", fontSize = 13.sp, maxLines = 1)
                            }
                        }
                        if (scene5Installed == true) {
                            OutlinedButton(
                                onClick = { runSceneActivation("激活Scene(旧版)", PKG_SCENE5) },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                            ) {
                                Text("Scene(旧版)", fontSize = 13.sp, maxLines = 1)
                            }
                        }
                        if (batteryInstalled == true) {
                            OutlinedButton(
                                onClick = { runActivation("激活BatteryRecorder", CMD_BATTERY) },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                            ) {
                                Text("BatteryRecorder", fontSize = 13.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
        item {
            Text(
                "ColorOS 专区",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
        item {
            GlassCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("AI电池助手", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        Text(
                            "强开 OPPO AI 电池助手",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    Switch(
                        checked = aiBattery,
                        enabled = !aiBatteryBusy,
                        onCheckedChange = { saveAiBattery(it) }
                    )
                }
            }
        }
        item {
            GlassCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("久用保养", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        Text(
                            "OPPO 手机管家久用保养入口",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    TextButton(onClick = {
                        runActivation("久用保养",
                            "am start -n com.coloros.phonemanager/com.oplus.phonemanager.sysmaint.SysMaintActivity -f 0x10000000")
                    }) {
                        Text("打开")
                    }
                }
            }
        }
        item {
            Text(
                "HyperOS 专区",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
        item {
            GlassCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("游戏启动动画加速", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    }
                    Switch(
                        checked = gameBoostAccel,
                        enabled = !gameBoostBusy,
                        onCheckedChange = { checked -> saveGameBoost(checked) }
                    )
                }
            }
        }
        item {
            GlassCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("触摸预加载", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        Switch(
                            checked = touchPreload,
                            enabled = !touchBusy,
                            onCheckedChange = { checked -> saveTouchCfg(checked, blacklist, "触摸预加载") }
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("黑名单应用管理", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { showAddDialog = true }, enabled = !touchBusy) {
                            Icon(Icons.Filled.Add, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("添加应用")
                        }
                    }
                    if (blacklist.isEmpty()) {
                        Text(
                            "暂无黑名单应用（禁止特定应用使用触摸预加载）",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        blacklist.forEach { pkg ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    pkg,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = { saveTouchCfg(touchPreload, blacklist - pkg, "黑名单应用") },
                                    enabled = !touchBusy
                                ) {
                                    Icon(Icons.Filled.Close, "移除", tint = Color(0xFFE57373), modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            GlassCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("隐藏手势提示线", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "使用全面屏手势时隐藏底部的提示线",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = hideGestureLine,
                        enabled = !hideGestureBusy,
                        onCheckedChange = { checked -> saveGesture(checked) }
                    )
                }
            }
        }
        item {
            GlassCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("最近任务样式", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        recentsOptions.forEach { (label, v) ->
                            val selected = recentsStyle == v
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
                                    )
                                    .border(
                                        1.dp,
                                        if (selected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
                                        RoundedCornerShape(10.dp)
                                    )
                                    .clickable(enabled = !recentsBusy) { saveRecents(v) }
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    label,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    color = if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
        item { Spacer(modifier = Modifier.height(16.dp)) }
        item {
            Text(
                "推荐脚本",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
        items(recommendations) { rec ->
            GlassCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(rec.title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(rec.desc, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { onInstallScript(rec.name, rec.content) }) {
                            Icon(Icons.Filled.Add, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("一键导入")
                        }
                    }
                }
            }
        }
        item { Spacer(modifier = Modifier.height(16.dp)) }
        item {
            GlassCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("导入本地脚本", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("从手机存储导入 .sh 脚本或 .lin 模块包", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = onImport) {
                        Icon(Icons.Filled.FileOpen, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("导入文件")
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false; newPkg = "" },
            title = { Text("添加黑名单应用") },
            text = {
                OutlinedTextField(
                    value = newPkg,
                    onValueChange = { newPkg = it },
                    label = { Text("应用包名") },
                    singleLine = true,
                    placeholder = { Text("如 com.example.app1") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val pkg = newPkg.trim()
                    if (pkg.isNotEmpty() && !blacklist.contains(pkg)) {
                        saveTouchCfg(touchPreload, blacklist + pkg, "黑名单应用")
                    } else if (blacklist.contains(pkg)) {
                        android.widget.Toast.makeText(ctx, "该应用已在黑名单中", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    showAddDialog = false; newPkg = ""
                }) { Text("添加") }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false; newPkg = "" }) { Text("取消") }
            }
        )
    }
}

private data class RecommendedScript(
    val title: String,
    val desc: String,
    val name: String,
    val content: String
)

/** 90d 轮：脚本/模块/推荐分段 Tab 项（选中 = 主题色胶囊背景 + 加粗主题色文字）。
 *  [modifier] 由调用方在 RowScope 内传入（含 weight 等）。 */
@Composable
private fun SegmentTab(
    modifier: Modifier = Modifier,
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            fontSize = 14.sp,
            maxLines = 1,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary
                   else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ScriptsTab(
    scripts: List<ScriptItem>,
    loading: Boolean,
    onEdit: (String) -> Unit,
    onNew: () -> Unit,
    onImport: () -> Unit,
    onRun: (ScriptItem) -> Unit,
    onToggle: (ScriptItem, Boolean) -> Unit,
    onDelete: (ScriptItem) -> Unit,
    onTriggerChange: (ScriptItem) -> Unit,
    onLog: (ScriptItem) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val q = query.trim().lowercase()
    val filtered = scripts.filter { s ->
        q.isBlank() ||
            s.name.lowercase().contains(q) ||
            s.description.lowercase().contains(q) ||
            s.author.lowercase().contains(q) ||
            s.brand.lowercase().contains(q)
    }
    Box(modifier = Modifier.fillMaxSize()) {
        if (loading) {
            EmptyState(
                title = "加载中...",
                subtitle = "",
                modifier = Modifier.padding(bottom = 100.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())
            )
        } else if (scripts.isEmpty()) {
            EmptyState(
                title = "暂无 Shell 脚本",
                subtitle = "点击右下角按钮创建新脚本",
                modifier = Modifier.padding(bottom = 100.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())
            )
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("搜索脚本") },
                    leadingIcon = { Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
                if (filtered.isEmpty()) {
                    EmptyState(
                        title = "未找到匹配脚本",
                        subtitle = "换个关键词试试",
                        modifier = Modifier.padding(top = 40.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 16.dp),
                        contentPadding = PaddingValues(top = 8.dp, bottom = 100.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(filtered, key = { it.id }) { script ->
                            ScriptCard(
                                script = script,
                                onEdit = { onEdit(script.id) },
                                onDelete = { onDelete(script) },
                                onRun = { onRun(script) },
                                onToggle = { on -> onToggle(script, on) },
                                onTriggerChange = onTriggerChange,
                                onLog = { onLog(script) }
                            )
                        }
                    }
                }
            }
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 100.dp),
            horizontalAlignment = Alignment.End
        ) {
            FloatingActionButton(
                onClick = onImport,
                modifier = Modifier.size(48.dp),
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f)
            ) {
                Icon(Icons.Filled.FileOpen, "导入 .sh", tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(modifier = Modifier.height(10.dp))
            ExtendedFloatingActionButton(
                onClick = onNew,
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("新建Shell脚本") }
            )
        }
    }
}

@Composable
private fun ScriptCard(
    script: ScriptItem,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onRun: (ScriptItem) -> Unit,
    onToggle: (Boolean) -> Unit,
    onTriggerChange: (ScriptItem) -> Unit,
    onLog: () -> Unit
) {
    var enabled by remember { mutableStateOf(script.enabled) }
    var showTriggerDialog by remember { mutableStateOf(false) }

    GlassCard {
        Column {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(script.name, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                    Spacer(modifier = Modifier.height(2.dp))
                    if (script.author.isNotEmpty()) {
                        Text("作者: ${script.author}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (script.brand.isNotEmpty()) {
                        Text("适用品牌: ${script.brand}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (script.description.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(script.description, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                if (script.isToggle) {
                    Switch(
                        checked = enabled,
                        onCheckedChange = {
                            enabled = it
                            onToggle(it)
                        },
                        modifier = Modifier.padding(top = 4.dp)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .pressClickableNoRipple { onRun(script) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.PlayArrow, "执行", tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(22.dp))
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (script.isToggle) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text("开关型", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text("脚本", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                // 触发状态标签
                if (script.triggerBoot) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFFE3F2FD).copy(alpha = 0.3f))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text("开机启动", fontSize = 11.sp, color = Color(0xFF90CAF9))
                    }
                }
                if (script.triggerPackage.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFFF3E5F5).copy(alpha = 0.3f))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text("跟随应用", fontSize = 11.sp, color = Color(0xFFCE93D8))
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = { showTriggerDialog = true }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Schedule, "触发条件", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = onLog, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Description, "日志", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Edit, "编辑", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Delete, "删除", tint = Color(0xFFE57373), modifier = Modifier.size(20.dp))
                }
            }
        }
    }

    if (showTriggerDialog) {
        TriggerConfigDialog(
            script = script,
            onDismiss = { showTriggerDialog = false },
            onChanged = {
                showTriggerDialog = false
                onTriggerChange(script)
            }
        )
    }
}

@Composable
private fun TriggerConfigDialog(
    script: ScriptItem,
    onDismiss: () -> Unit,
    onChanged: () -> Unit
) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var bootTrigger by remember { mutableStateOf(script.triggerBoot) }
    var pkgInput by remember { mutableStateOf(script.triggerPackage) }
    var followTrigger by remember { mutableStateOf(script.triggerPackage.isNotEmpty()) }
    var usageGranted by remember { mutableStateOf(false) }

    fun checkUsageAccess() {
        usageGranted = try {
            val appOps = ctx.getSystemService(android.app.AppOpsManager::class.java)
            val mode = appOps?.unsafeCheckOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                ctx.packageName
            )
            mode == android.app.AppOpsManager.MODE_ALLOWED
        } catch (_: Throwable) { false }
    }

    // 初始化 + 从设置页返回时自动刷新权限状态
    DisposableEffect(lifecycleOwner) {
        checkUsageAccess()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) checkUsageAccess()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun openUsageAccessSettings() {
        val intent = android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("触发条件: ${script.name}") },
        text = {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("开机自动运行", modifier = Modifier.weight(1f))
                    Switch(checked = bootTrigger, onCheckedChange = { bootTrigger = it })
                }
                Text(
                    "开机后自动执行一次该脚本，无需打开应用。需系统广播权限（默认已授予）。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("跟随应用打开运行", fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Switch(
                        checked = followTrigger,
                        onCheckedChange = { followTrigger = it; if (!it) pkgInput = "" }
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                if (followTrigger) {
                    OutlinedTextField(
                        value = pkgInput,
                        onValueChange = { pkgInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("包名，如 com.tencent.mm", fontSize = 13.sp) },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    // 权限状态提示 + 跳转按钮
                    if (pkgInput.trim().isNotEmpty() && !usageGranted) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFFFFEBEE).copy(alpha = 0.3f))
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "未授予使用情况访问权限",
                                fontSize = 12.sp,
                                color = Color(0xFFEF9A9A),
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { openUsageAccessSettings() }) {
                                Text("去授权", fontSize = 12.sp)
                            }
                        }
                    } else {
                        Text(
                            "填写目标应用包名：每次打开该应用时执行此脚本；留空则不跟随。需要授予「使用情况访问权限」。",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Text(
                        "关闭后不跟随任何应用打开执行。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                ModuleStore.setTriggerBoot(ctx, script.id, bootTrigger)
                ModuleStore.setTriggerPackage(ctx, script.id, if (followTrigger) pkgInput.trim() else "")
                // 启动/停止监测服务
                val hasPkgTrigger = followTrigger && pkgInput.trim().isNotEmpty()
                if (hasPkgTrigger) {
                    moe.shizuku.privileged.api.core.AppTriggerService.start(ctx)
                } else {
                    // 检查是否还有其他脚本需要监测
                    val others = ModuleStore.listPackageTriggers(ctx).filterValues { it.isNotEmpty() }
                    if (others.isEmpty()) {
                        moe.shizuku.privileged.api.core.AppTriggerService.stop(ctx)
                    }
                }
                onChanged()
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

private val ModuleDocText = """
Shell 脚本格式
────────────────
存储目录：/data/local/tmp/Lin-Shizuku/script/<脚本名>.sh
自动识别：含 #on 与 #off 标记为开关型，否则为普通脚本。

【普通脚本（RUN）】
顶部可用 #--- 包围的元数据块描述脚本：
#---
#name: 脚本名称
#description: 功能描述
#author: 作者
#brand: 适用品牌
#---
其余内容为 shell 命令，点击运行整文件执行，
输出在“执行输出”页实时显示。

【开关型脚本（TOGGLE）】
含 #on 与 #off 两个标记，标记之间为对应命令块：
#on
开启时执行的命令
#off
关闭时执行的命令
列表中显示为开关，切换即执行对应块。

【触发条件】
每个脚本可在「触发条件」中配置自动执行方式：
· 开机自动运行：开机后自动执行一次，无需打开应用；
· 跟随应用打开运行：开启开关并填写目标应用包名，每次打开该应用时执行
  （需授予「使用情况访问权限」）。
自动触发时脚本输出写入日志，可在终端页查看。

【完整示例（开关型）】
#---
#name: 清理缓存
#description: 一键清理本机缓存目录
#author: me
#brand: 通用
#---
#on
rm -rf /data/cache/*
rm -rf /cache/*
echo "缓存已清理"
#off
echo "已关闭缓存清理（无恢复操作）"

【新建 / 编辑 / 导出】
右下角「新建Shell脚本」→ 填写名称与内容 → 保存；
支持导出 .sh 文件分享。

Lin 模块制作
────────────────
模块 = zip 包（.lin / .zip），安装后解压到
filesDir/modules/<id>/。目录结构：

<id>/
├─ module.json    必填：模块元数据
├─ run.sh         可选：action 脚本（含 #on/#off 可为开关）
├─ stop.sh        可选：停止脚本（开关关闭时执行，用于清理模块后台进程）
├─ uninstall.sh   可选：卸载脚本（模块删除前自动执行，用于清理后台进程/运行时文件）
└─ web/           可选：WebUI 静态资源（默认入口 web/index.html）

【module.json 字段】
{
  "id": "模块唯一ID，仅限字母数字._-",
  "name": "模块名称",
  "version": "版本号",
  "author": "作者",
  "description": "功能描述",
  "webui": "web/index.html 或 http(s)://远程地址",
  "action": true
}

【模块能力】
· 运行：action 为 true 时卡片显示「运行」，执行 run.sh
· 开关：开 → 执行 run.sh 的 #on 块（无 #on 则执行全文，用于启动模块服务）；
  关 → 执行 stop.sh（无 stop.sh 则清理引用模块目录的后台进程），让模块真正停止运行
· WebUI：webui 字段指向本地 web/index.html 或远程地址，
  卡片显示「WEBUI」按钮，浏览器打开
· 删除：删除前自动执行 uninstall.sh（若有），再移除整个目录

【打包】
将以上文件压成 zip（module.json 在包根目录），
重命名为 xxx.lin，在模块页「安装模块」选择即可。
""".trimIndent()

@Composable
private fun ModuleDocDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("脚本与模块文档", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .height(420.dp)
            ) {
                Text(
                    ModuleDocText,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("好") }
        }
    )
}

/** 日志条目元信息：来源 + 类别 + 错误统计 + 摘要 + 内容读取器。 */
private data class LogEntry(
    val name: String,
    val kind: String,
    val kindColor: Color,
    val source: String,
    val sourceColor: Color,
    val sizeText: String,
    val timeText: String,
    val errCount: Int,
    val firstErr: String,
    val summary: String,
    val loader: () -> String,
    /** 1.1.2：删除该日志文件（本地 File.delete / 服务端 rm）。null 表示不可删除。 */
    val deleteAction: (() -> Unit)? = null
)

/** 扫描一个本地日志目录（cache/Logs 等）为条目列表。 */
private fun scanLocalLogDir(dir: java.io.File, source: String, sourceColor: Color): List<LogEntry> {
    if (!dir.exists() || !dir.isDirectory) return emptyList()
    return dir.listFiles()
        ?.filter { it.isFile && (it.name.endsWith(".log") || it.name == "crash_log.txt") }
        ?.sortedByDescending { it.lastModified() }
        ?.mapNotNull { f ->
            runCatching {
                val content = f.readText()
                val errs = moe.shizuku.privileged.api.core.LogDiagnostics.errLines(content)
                val kind = when {
                    f.name.startsWith("install_") -> "安装"
                    f.name.startsWith("exec_") -> "执行"
                    f.name.startsWith("auto_") -> "自动执行"
                    f.name.startsWith("crash") -> "崩溃"
                    else -> "日志"
                }
                LogEntry(
                    name = f.name,
                    kind = kind,
                    kindColor = when (kind) {
                        "安装" -> Color(0xFF42A5F5)
                        "执行" -> Color(0xFF66BB6A)
                        "自动执行" -> Color(0xFFAB47BC)
                        "崩溃" -> Color(0xFFEF5350)
                        else -> Color(0xFF90A4AE)
                    },
                    source = source,
                    sourceColor = sourceColor,
                    sizeText = "${"%.1f".format(f.length() / 1024.0)} KB",
                    timeText = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date(f.lastModified())),
                    errCount = errs.size,
                    firstErr = errs.firstOrNull()?.trim()?.take(80) ?: "",
                    summary = content.lines().lastOrNull { it.isNotBlank() }?.trim()?.take(80) ?: "",
                    loader = { f.readText() },
                    deleteAction = { f.delete() }
                )
            }.getOrNull()
        } ?: emptyList()
}

/** 扫描诊断日志（外部专属 files/日志.log、内部 filesDir/日志.log；公共根仅作旧版残留兼容读取）。 */
private fun scanDiagnosticLogs(ctx: android.content.Context): List<LogEntry> {
    val candidates = listOf(
        ctx.getExternalFilesDir(null)?.let { java.io.File(it, "日志.log") },
        java.io.File(ctx.filesDir, "日志.log"),
        java.io.File("/storage/emulated/0/日志.log")
    ).filterNotNull().filter { it.exists() && it.isFile && it.length() > 0 }
    return candidates.mapNotNull { f ->
        runCatching {
            val content = f.readText()
            val errs = moe.shizuku.privileged.api.core.LogDiagnostics.errLines(content)
            LogEntry(
                name = f.name,
                kind = "诊断日志",
                kindColor = Color(0xFF26C6DA),
                source = "诊断",
                sourceColor = Color(0xFF26C6DA),
                sizeText = "${"%.1f".format(f.length() / 1024.0)} KB",
                timeText = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(f.lastModified())),
                errCount = errs.size,
                firstErr = errs.firstOrNull()?.trim()?.take(80) ?: "",
                summary = content.lines().lastOrNull { it.isNotBlank() }?.trim()?.take(80) ?: "",
                loader = { f.readText() }
            )
        }.getOrNull()
    }
}

/** 扫描服务端日志（/data/local/tmp/Lin-Shizuku/Lin/log/ + /data/local/tmp/Lin_server.log），经 Shizuku shell 读取。 */
private suspend fun scanServerLogs(): List<LogEntry> {
    val entries = mutableListOf<LogEntry>()
    val ls = runCatching {
        moe.shizuku.privileged.api.core.ShizukuManager.runCommand(
            "ls -t /data/local/tmp/Lin-Shizuku/Lin/log/ 2>/dev/null",
            moe.shizuku.privileged.api.core.Prefs.execIdentity
        ).trim()
    }.getOrDefault("")
    val names = ls.lines().map { it.trim() }
        .filter { it.isNotBlank() && !it.contains(" ") && !it.startsWith("total") }
        .take(20)
    names.forEach { name ->
        runCatching {
            val content = moe.shizuku.privileged.api.core.ShizukuManager.runCommand(
                "cat /data/local/tmp/Lin-Shizuku/Lin/log/$name 2>/dev/null | tail -200",
                moe.shizuku.privileged.api.core.Prefs.execIdentity
            )
            val errs = moe.shizuku.privileged.api.core.LogDiagnostics.errLines(content)
            entries += LogEntry(
                name = name,
                kind = "服务端",
                kindColor = Color(0xFF66BB6A),
                source = "服务端",
                sourceColor = Color(0xFF8BC34A),
                sizeText = "远程",
                timeText = "",
                errCount = errs.size,
                firstErr = errs.firstOrNull()?.trim()?.take(80) ?: "",
                summary = content.lines().lastOrNull { it.isNotBlank() }?.trim()?.take(80) ?: "",
                loader = {
                    kotlinx.coroutines.runBlocking {
                        moe.shizuku.privileged.api.core.ShizukuManager.runCommand(
                            "cat /data/local/tmp/Lin-Shizuku/Lin/log/$name 2>/dev/null | tail -500",
                            moe.shizuku.privileged.api.core.Prefs.execIdentity
                        )
                    }
                },
                deleteAction = {
                    kotlinx.coroutines.runBlocking {
                        moe.shizuku.privileged.api.core.ShizukuManager.runCommand(
                            "rm -f /data/local/tmp/Lin-Shizuku/Lin/log/$name",
                            moe.shizuku.privileged.api.core.Prefs.execIdentity
                        )
                    }
                }
            )
        }
    }
    runCatching {
        val content = moe.shizuku.privileged.api.core.ShizukuManager.runCommand(
            "cat /data/local/tmp/Lin_server.log 2>/dev/null | tail -200",
            moe.shizuku.privileged.api.core.Prefs.execIdentity
        )
        if (content.isNotBlank()) {
            val errs = moe.shizuku.privileged.api.core.LogDiagnostics.errLines(content)
            entries += LogEntry(
                name = "Lin_server.log",
                kind = "服务端",
                kindColor = Color(0xFF66BB6A),
                source = "服务端",
                sourceColor = Color(0xFF8BC34A),
                sizeText = "远程",
                timeText = "",
                errCount = errs.size,
                firstErr = errs.firstOrNull()?.trim()?.take(80) ?: "",
                summary = content.lines().lastOrNull { it.isNotBlank() }?.trim()?.take(80) ?: "",
                loader = {
                    kotlinx.coroutines.runBlocking {
                        moe.shizuku.privileged.api.core.ShizukuManager.runCommand(
                            "cat /data/local/tmp/Lin_server.log 2>/dev/null | tail -500",
                            moe.shizuku.privileged.api.core.Prefs.execIdentity
                        )
                    }
                },
                deleteAction = {
                    kotlinx.coroutines.runBlocking {
                        moe.shizuku.privileged.api.core.ShizukuManager.runCommand(
                            "rm -f /data/local/tmp/Lin_server.log",
                            moe.shizuku.privileged.api.core.Prefs.execIdentity
                        )
                    }
                }
            )
        }
    }
    return entries
}

/** 日志总览 Tab：聚合本地日志（cache/Logs + crash_log.txt）+ 诊断日志（日志.log）+ 服务端日志（/data/local/tmp），
 *  分类徽标 + 来源标记 + 错误统计 + 摘要 + 错误行高亮弹窗。 */
@Composable
private fun AllLogsTab(onOpenLog: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var showLogDialog by remember { mutableStateOf<Pair<String, String>?>(null) }
    // 1.1.2：删除/清空后靠 refreshKey 重新扫描（原实现 remember{} 只扫一次，删了不会消失）
    var refreshKey by remember { mutableIntStateOf(0) }
    var pendingDelete by remember { mutableStateOf<LogEntry?>(null) }
    var confirmClearAll by remember { mutableStateOf(false) }
    // 本地 + 诊断日志（同步扫描）
    val localEntries = remember(refreshKey) {
        buildList {
            addAll(scanLocalLogDir(java.io.File(ctx.externalCacheDir ?: ctx.cacheDir, "Logs"), "本地", Color(0xFF90A4AE)))
            addAll(scanDiagnosticLogs(ctx))
        }
    }
    // 服务端日志（异步经 Shizuku shell 读取）
    var serverEntries by remember(refreshKey) { mutableStateOf(emptyList<LogEntry>()) }
    var serverLoading by remember(refreshKey) { mutableStateOf(true) }
    LaunchedEffect(refreshKey) {
        val r = withContext(Dispatchers.IO) { scanServerLogs() }
        serverEntries = r
        serverLoading = false
    }
    val allEntries = localEntries + serverEntries
    if (allEntries.isEmpty() && !serverLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("暂无日志文件", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 100.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1.1.2：日志管理工具条——文件数量 + 全部清空（带二次确认）
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "共 ${allEntries.size} 个日志文件",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = { confirmClearAll = true },
                enabled = allEntries.isNotEmpty()
            ) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.size(4.dp))
                Text("全部清空", fontSize = 13.sp)
            }
        }
        if (serverLoading) {
            Text(
                "正在读取服务端日志...",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        allEntries.forEach { e ->
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (e.errCount > 0)
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                ),
                onClick = {
                    showLogDialog = Pair(e.name, runCatching { e.loader() }.getOrDefault("(读取失败)"))
                }
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 来源徽标
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(e.sourceColor.copy(alpha = 0.15f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(e.source, color = e.sourceColor, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        }
                        Spacer(Modifier.size(6.dp))
                        // 类型徽标
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(e.kindColor.copy(alpha = 0.15f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(e.kind, color = e.kindColor, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        }
                        Spacer(Modifier.size(8.dp))
                        Text(
                            e.name.removeSuffix(".log"),
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (e.errCount > 0) {
                            Text(
                                "${e.errCount} 处错误",
                                color = Color(0xFFEF5350),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        if (e.deleteAction != null) {
                            IconButton(
                                onClick = { pendingDelete = e },
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = "删除 ${e.name}",
                                    modifier = Modifier.size(17.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (e.errCount > 0) "⚠ ${e.firstErr}" else e.summary,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        color = if (e.errCount > 0) Color(0xFFEF5350) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (e.timeText.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${e.sizeText} · ${e.timeText}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
    // 日志内容弹窗：错误行红色高亮 + 一键复制
    showLogDialog?.let { (name, content) ->
        AlertDialog(
            onDismissRequest = { showLogDialog = null },
            title = { Text(name, fontSize = 16.sp) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    if (content.isBlank()) {
                        Text("(空日志)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        content.lines().forEach { line ->
                            Text(
                                line.ifBlank { " " },
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                                fontFamily = FontFamily.Monospace,
                                color = if (moe.shizuku.privileged.api.core.LogDiagnostics.isErrLine(line)) Color(0xFFFF5252)
                                else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLogDialog = null }) { Text("关闭") }
            },
            dismissButton = {
                TextButton(onClick = {
                    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText(name, content))
                    android.widget.Toast.makeText(ctx, "日志已复制", android.widget.Toast.LENGTH_SHORT).show()
                }) { Text("复制") }
            }
        )
    }
    // 1.1.2：单条删除确认（日志删除不可恢复，避免误触）
    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除日志", fontSize = 16.sp) },
            text = {
                Text("确定删除「${entry.name}」？删除后不可恢复。", fontSize = 13.sp)
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = entry
                    pendingDelete = null
                    scope.launch(Dispatchers.IO) {
                        val ok = runCatching { target.deleteAction?.invoke(); true }.getOrDefault(false)
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(
                                ctx,
                                if (ok) "已删除 ${target.name}" else "删除失败，请确认服务是否在运行",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                            if (ok) refreshKey++
                        }
                    }
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }
    // 1.1.2：全部清空确认
    if (confirmClearAll) {
        val removable = allEntries.filter { it.deleteAction != null }
        AlertDialog(
            onDismissRequest = { confirmClearAll = false },
            title = { Text("清空全部日志", fontSize = 16.sp) },
            text = {
                Text(
                    "将删除 ${removable.size} 个日志文件（本地 + 服务端），删除后不可恢复；正在运行的服务会重新生成新文件。",
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClearAll = false
                    scope.launch(Dispatchers.IO) {
                        var failed = 0
                        removable.forEach { e ->
                            val ok = runCatching { e.deleteAction?.invoke(); true }.getOrDefault(false)
                            if (!ok) failed++
                        }
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(
                                ctx,
                                if (failed == 0) "已清空 ${removable.size} 个日志文件"
                                else "已清空，${failed} 个失败",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                            refreshKey++
                        }
                    }
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearAll = false }) { Text("取消") }
            }
        )
    }
}
