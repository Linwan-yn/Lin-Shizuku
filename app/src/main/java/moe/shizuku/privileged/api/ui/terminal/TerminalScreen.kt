package moe.shizuku.privileged.api.ui.terminal

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import moe.shizuku.privileged.api.core.CommandTask
import moe.shizuku.privileged.api.core.CommandTaskRegistry
import moe.shizuku.privileged.api.core.ModuleLogs
import moe.shizuku.privileged.api.core.Prefs
import moe.shizuku.privileged.api.core.ShizukuManager
import moe.shizuku.privileged.api.core.TaskState
import moe.shizuku.privileged.api.core.TerminalHistoryEntry
import moe.shizuku.privileged.api.ui.widget.GlassPanel

@Composable
fun TerminalScreen(onSaveAsScript: (String) -> Unit = {}) {
    // 内容与执行状态提升到进程级全局缓冲：切换页面不清空，完全退出/手动清空才清空
    val lines = TerminalBuffer.lines
    var input by remember { mutableStateOf("") }
    val ctx = LocalContext.current
    // 命令历史：持久化到 Prefs，最新在前；点按回填输入框，可单条删除、加备注、存为脚本
    var history by remember { mutableStateOf(Prefs.terminalHistory) }
    val running = TerminalBuffer.running
    val lastResult = TerminalBuffer.lastResult
    // 输出日志区内部滚动状态（普通 Column + verticalScroll，避免与外层整页 LazyColumn 嵌套同方向滚动）
    val outputScroll = rememberScrollState()
    // 68 轮：执行身份（跟随服务 / Shell(com.android.shell) / Root），与全局设置联动，终端快捷切换
    var identity by remember { mutableStateOf(Prefs.execIdentity) }
    // 服务端实际 uid（0=root、2000=shell、其它=普通 app uid），进入终端页读取一次
    val serverUid = remember { mutableStateOf(ShizukuManager.getServerUidCompat()) }
    LaunchedEffect(Unit) { serverUid.value = ShizukuManager.getServerUidCompat() }

    // 备注编辑弹窗：当前正在编辑的历史条目 + 备注输入
    var noteEdit by remember { mutableStateOf<TerminalHistoryEntry?>(null) }
    var noteText by remember { mutableStateOf("") }

    // 点击身份标签循环切换：AUTO → SHELL → ROOT → AUTO
    fun cycleIdentity() {
        val next = when (identity) {
            ShizukuManager.ExecIdentity.AUTO -> ShizukuManager.ExecIdentity.SHELL
            ShizukuManager.ExecIdentity.SHELL -> ShizukuManager.ExecIdentity.ROOT
            ShizukuManager.ExecIdentity.ROOT -> ShizukuManager.ExecIdentity.AUTO
        }
        identity = next
        Prefs.execIdentity = next
    }

    // AUTO 模式：root/shell 显示括号身份；其它（普通 uid/未知）只显示 auto，不暴露 uid
    val identityLabel = when (identity) {
        ShizukuManager.ExecIdentity.AUTO -> when (serverUid.value) {
            0 -> "auto(root)"
            2000 -> "auto(shell)"
            else -> "auto"
        }
        ShizukuManager.ExecIdentity.SHELL -> "Shell"
        ShizukuManager.ExecIdentity.ROOT -> "Root"
    }

    // 输入框固定多行自动换行；执行调度固定串行排队（上一条完成后自动执行队首），不再提供模式切换

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) outputScroll.scrollTo(outputScroll.maxValue)
    }

    /** 把终端一次命令的输出与结果落盘到日志目录，文件名 exec_terminal_<时间戳>.log，归类为「执行」日志。 */
    fun persistExecLog(task: CommandTask, cmd: String, identity: ShizukuManager.ExecIdentity) {
        try {
            val outText = task.output.joinToString("\n") {
                if (it.isError) "[错误] ${it.text}" else it.text
            }.trimEnd('\n')
            val footer = ModuleLogs.line("──", when (task.state) {
                TaskState.TIMEOUT -> "结果：执行超时（5 分钟无输出）"
                TaskState.FAILED -> "结果：失败（退出码 ${task.exitCode ?: "未知"}）"
                TaskState.STOPPED -> "结果：已手动停止"
                else -> "结果：成功"
            })
            val logContent = ModuleLogs.line("──", "执行: $cmd（身份: ${identity.label}）") + "\n" +
                outText + "\n" + footer
            ModuleLogs.save(ctx, "exec_terminal_${ModuleLogs.fileStamp()}.log", logContent)
        } catch (_: Throwable) {}
    }

    // 执行模式调度：每条命令通过 Shizuku 特权通道（shell/root）运行一次，输出流式实时显示
    // 0=排队：running 时入队，上一条执行完自动执行；1=并发：立即另起执行；2=阻止：提示等待（不清空输入）
    fun launchExec(cmd: String, execId: ShizukuManager.ExecIdentity) {
        TerminalBuffer.running = true
        TerminalBuffer.lastResult = null
        // 新命令开始：清掉上一次残留输出，避免新旧结果混在同一片日志里
        TerminalBuffer.clear()
        // 1.1.5：登记后台任务，进程句柄与输出缓冲挂到任务注册表，统一在终端页面板查看/停止
        val task = CommandTaskRegistry.start(cmd, cmd, "终端", execId)
        TerminalBuffer.currentTaskId = task.id
        TerminalBuffer.execScope.launch {
            val start = System.currentTimeMillis()
            try {
                // 注册停止回调：用户点停止按钮时 destroy 双句柄（兼容通道 + Lin 通道）
                TerminalBuffer.onStop = {
                    if (!task.detached) TerminalBuffer.appendLine("^C 已手动停止", isError = true)
                    CommandTaskRegistry.stop(task.id)
                    TerminalBuffer.running = false
                }
                val ok = ShizukuManager.runCommandStreamingEx(
                    cmd,
                    identity = execId,
                    task = task,
                    onLine = { line, isErr ->
                        // 已转后台的任务不再刷屏当前终端，输出保留在任务缓冲供面板回看
                        if (!task.detached) TerminalBuffer.appendLine(line, isErr)
                        CommandTaskRegistry.append(task, line, isErr)
                    },
                    // 空闲超时放宽到 5 分钟：pm install / logcat 这类长命令不误杀
                    timeoutMs = 300_000
                )
                val elapsedMs = System.currentTimeMillis() - start
                if (!ok.finished) {
                    val msg = "命令执行超时（5 分钟无新输出）· 耗时 $elapsedMs 毫秒"
                    if (!task.detached) TerminalBuffer.appendLine(msg, isError = true)
                    CommandTaskRegistry.append(task, msg, true)
                }
                if (ok.exitCode != null) {
                    val msg = "退出码 ${ok.exitCode} · 耗时 $elapsedMs 毫秒"
                    if (!task.detached) TerminalBuffer.appendLine(msg, isError = ok.exitCode != 0)
                    CommandTaskRegistry.append(task, msg, ok.exitCode != 0)
                }
                val state = when {
                    task.stoppedByUser -> TaskState.STOPPED
                    !ok.finished -> TaskState.TIMEOUT
                    ok.exitCode != null && ok.exitCode != 0 -> TaskState.FAILED
                    else -> TaskState.FINISHED
                }
                TerminalBuffer.lastResult = ExecResult(state, ok.exitCode, elapsedMs, cmd)
                CommandTaskRegistry.finish(task, state, ok.exitCode)
            } catch (_: Throwable) {
                val elapsedMs = System.currentTimeMillis() - start
                val msg = "命令执行失败 · 耗时 $elapsedMs 毫秒"
                if (!task.detached) TerminalBuffer.appendLine(msg, isError = true)
                CommandTaskRegistry.append(task, msg, true)
                TerminalBuffer.lastResult = ExecResult(TaskState.FAILED, null, elapsedMs, cmd)
                CommandTaskRegistry.finish(task, TaskState.FAILED, null)
            }
            // 落盘执行日志：与模块/脚本同口径，无论成败都留档，可在模块→日志列表回看
            persistExecLog(task, cmd, execId)
            TerminalBuffer.running = false
            TerminalBuffer.onStop = null
            if (TerminalBuffer.currentTaskId == task.id) TerminalBuffer.currentTaskId = null
            // 排队模式：自动执行等待队列中的下一条
            val next = TerminalBuffer.pendingQueue.removeFirstOrNull()
            if (next != null) {
                if (!task.detached) TerminalBuffer.appendLine("(开始执行排队命令)", isError = false)
                launchExec(next.first, next.second)
            }
        }
    }

    fun clearTerminal() {
        TerminalBuffer.clear()
        TerminalBuffer.lastResult = null
    }

    /** 写入历史：按命令去重、最新在前、上限 50，持久化到 Prefs。 */
    fun pushHistory(cmd: String) {
        val cleaned = cmd.trim()
        if (cleaned.isEmpty()) return
        val next = (listOf(TerminalHistoryEntry(cleaned, "")) + history.filter { it.cmd != cleaned }).take(50)
        history = next
        Prefs.terminalHistory = next
    }

    fun removeHistory(entry: TerminalHistoryEntry) {
        val next = history.filter { it.cmd != entry.cmd }
        history = next
        Prefs.terminalHistory = next
    }

    fun updateNote(cmd: String, note: String) {
        val next = history.map { if (it.cmd == cmd) it.copy(note = note) else it }
        history = next
        Prefs.terminalHistory = next
    }

    fun clearHistory() {
        history = emptyList()
        Prefs.terminalHistory = emptyList()
    }

    /** 一键复制终端全部输出。 */
    fun copyOutput() {
        val text = lines.joinToString("\n") { it.text }
        if (text.isBlank()) {
            android.widget.Toast.makeText(ctx, "暂无输出", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("终端输出", text))
        android.widget.Toast.makeText(ctx, "输出已复制", android.widget.Toast.LENGTH_SHORT).show()
    }

    fun sendInput() {
        val cmd = input
        if (cmd.isBlank()) return
        pushHistory(cmd)
        input = ""
        if (running) {
            // 串行排队：上一条执行完后自动执行队首
            TerminalBuffer.pendingQueue.addLast(cmd to identity)
            TerminalBuffer.appendLine("(已排队：上一条执行完后自动执行)", isError = false)
            return
        }
        launchExec(cmd, identity)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 16.dp),
        // 底部预留悬浮液态底栏高度（本体 68dp + 间隙），避免最后几条历史被遮挡；系统导航栏 inset 已由 navigationBarsPadding 处理
        contentPadding = PaddingValues(bottom = 72.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 顶栏玻璃条：标题 + 身份 / 复制 / 清空
        item {
            GlassPanel(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "终端",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        OutlinedButton(
                            onClick = { cycleIdentity() },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                Icons.Filled.VerifiedUser,
                                contentDescription = "执行身份",
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(identityLabel, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // 后台任务管理区：终端手敲、脚本、模块开关/动作的任务统一在此查看/停止/回看输出
        item {
            RunningTaskPanel(
                tasks = CommandTaskRegistry.tasks,
                onStop = { id -> CommandTaskRegistry.stop(id) }
            )
        }

        // 输入卡片
        item {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("输入命令或多行脚本") },
                singleLine = false,
                minLines = 2,
                maxLines = 6,
                textStyle = LocalTextStyle.current.copy(
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onBackground
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { sendInput() }),
                shape = RoundedCornerShape(14.dp)
            )
        }

        // 执行 / 停止 / 后台运行：后台运行居左，执行或停止靠右，按钮按内容宽度不撑满
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (running) {
                    OutlinedButton(
                        onClick = {
                            val taskId = TerminalBuffer.currentTaskId
                            val task = CommandTaskRegistry.tasks.firstOrNull { it.id == taskId }
                            if (task != null) {
                                task.detached = true
                                TerminalBuffer.appendLine("(已转入后台运行，可在下方任务面板查看/停止)", isError = false)
                            }
                            TerminalBuffer.running = false
                            TerminalBuffer.onStop = null
                            TerminalBuffer.currentTaskId = null
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Icon(
                            Icons.Filled.ArrowDownward,
                            contentDescription = "后台运行",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("后台运行", fontSize = 13.sp, maxLines = 1, color = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = { TerminalBuffer.onStop?.invoke() },
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Icon(Icons.Filled.Stop, "停止", tint = MaterialTheme.colorScheme.error)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("停止", color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    Button(
                        onClick = { sendInput() },
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
                        enabled = input.isNotBlank()
                    ) {
                        Icon(Icons.Filled.PlayArrow, "执行")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("执行")
                    }
                }
            }
        }

        // 结果状态条：运行中指示 / 完成后状态 · 退出码 · 耗时
        if (running) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("正在执行命令…", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else if (lastResult != null) {
            item {
                ResultStatusBar(result = lastResult!!, onCopy = { copyOutput() }, onClear = { clearTerminal() })
            }
        }

        // 输出卡片：命令 stdout/stderr 保留等宽字体便于对齐阅读
        item {
            GlassPanel(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    if (lines.isEmpty()) {
                        Text(
                            "暂无输出",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .heightIn(min = 80.dp, max = 260.dp)
                                .fillMaxWidth()
                                .verticalScroll(outputScroll),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            lines.forEach { line ->
                                Text(
                                    text = line.text,
                                    color = if (line.isError) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = 16.sp
                                )
                            }
                        }
                    }
                }
            }
        }

        // 历史指令分区
        if (history.isNotEmpty()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.History,
                        null,
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        "历史指令",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = { clearHistory() }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text("清空", fontSize = 12.sp)
                    }
                }
            }
            items(history, key = { "hist-${it.cmd}" }) { entry ->
                HistoryRow(
                    entry = entry,
                    onFill = { input = entry.cmd },
                    onSaveScript = { onSaveAsScript(entry.cmd) },
                    onEditNote = {
                        noteEdit = entry
                        noteText = entry.note
                    },
                    onDelete = { removeHistory(entry) }
                )
            }
        }
    }

    // 备注编辑弹窗
    noteEdit?.let { editing ->
        AlertDialog(
            onDismissRequest = { noteEdit = null },
            title = { Text("编辑备注") },
            text = {
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    placeholder = { Text("备注名（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    updateNote(editing.cmd, noteText.trim())
                    noteEdit = null
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { noteEdit = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun ResultStatusBar(result: ExecResult, onCopy: () -> Unit, onClear: () -> Unit) {
    val (label, color) = when (result.status) {
        TaskState.FINISHED -> "执行成功" to Color(0xFF4CAF50)
        TaskState.FAILED -> "执行失败" to Color(0xFFFF6B6B)
        TaskState.STOPPED -> "已停止" to Color(0xFF888899)
        TaskState.TIMEOUT -> "执行超时" to Color(0xFFFEBC2E)
        TaskState.RUNNING -> "正在执行" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    val meta = buildString {
        if (result.exitCode != null) append("退出码 ${result.exitCode} · ")
        append("耗时 ${result.elapsedMs} 毫秒")
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = color)
            Text(meta, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onClear) {
            Icon(
                Icons.Filled.Delete,
                "清空输出",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
            )
        }
        IconButton(onClick = onCopy) {
            Icon(
                Icons.Filled.ContentCopy,
                "复制输出",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun HistoryRow(
    entry: TerminalHistoryEntry,
    onFill: () -> Unit,
    onSaveScript: () -> Unit,
    onEditNote: () -> Unit,
    onDelete: () -> Unit
) {
    GlassPanel(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Column(Modifier.weight(1f).clickable { onFill() }) {
                if (entry.note.isNotBlank()) {
                    Text(
                        entry.note,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        entry.cmd,
                        fontSize = 12.sp,
                        maxLines = 2,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                    )
                } else {
                    Text(
                        entry.cmd,
                        fontSize = 13.sp,
                        maxLines = 2,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Filled.Delete, "删除",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                )
            }
            IconButton(onClick = onEditNote, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Filled.Edit, "编辑备注",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                )
            }
            IconButton(onClick = onSaveScript, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Filled.Description, "存为脚本",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                )
            }
        }
    }
}
