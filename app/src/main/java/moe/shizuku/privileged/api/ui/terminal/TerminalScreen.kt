package moe.shizuku.privileged.api.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import moe.shizuku.privileged.api.ui.widget.GlassPanel
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.privileged.api.core.Prefs
import moe.shizuku.privileged.api.core.ShizukuManager

@Composable
fun TerminalScreen() {
    val scope = rememberCoroutineScope()
    // 内容与执行状态提升到进程级全局缓冲：切换页面不清空，完全退出/手动清空才清空
    val lines = TerminalBuffer.lines
    var input by remember { mutableStateOf("") }
    val ctx = LocalContext.current
    // 命令历史：持久化到 Prefs，最新在前；点按回填输入框，可单条删除
    var history by remember { mutableStateOf(Prefs.terminalHistory) }
    val running = TerminalBuffer.running
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var terminalHeight by remember { mutableStateOf(0) }
    // 68 轮：执行身份（跟随服务 / Shell(com.android.shell) / Root），与全局设置联动，终端快捷切换
    var identity by remember { mutableStateOf(Prefs.execIdentity) }
    // 服务端实际 uid（0=root、2000=shell、其它=普通 app uid），进入终端页读取一次
    val serverUid = remember { mutableStateOf(ShizukuManager.getServerUidCompat()) }
    LaunchedEffect(Unit) { serverUid.value = ShizukuManager.getServerUidCompat() }

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

    // 终端输入模式：0=超长自动换行（多行），1=单行横向滚动跟随光标（持久化到 Prefs）
    var inputMode by remember { mutableStateOf(Prefs.terminalInputMode) }
    val inputModeLabel = if (inputMode == 0) "换行" else "横滚"
    fun cycleInputMode() {
        inputMode = 1 - inputMode
        Prefs.terminalInputMode = inputMode
    }
    // 终端执行模式：0=排队（上一条完成自动执行），1=并发（立即执行），2=阻止（提示等待）
    var execMode by remember { mutableStateOf(Prefs.terminalExecMode) }
    val execModeLabel = when (execMode) {
        0 -> "排队"
        1 -> "并发"
        else -> "阻止"
    }
    fun cycleExecMode() {
        execMode = (execMode + 1) % 3
        Prefs.terminalExecMode = execMode
    }

    LaunchedEffect(terminalHeight) {
        if (terminalHeight > 0 && lines.isNotEmpty()) {
            kotlinx.coroutines.delay(30)
            listState.scrollToItem(lines.size)
        }
    }

    // 滚动节流：按 20 行为粒度触发，避免每行一次滚动布局拖垮主线程
    LaunchedEffect(lines.size / 20, input) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.size)
    }

    // 横滚模式：输入变化时横向滚动跟随光标（末尾）
    val hScroll = rememberScrollState()
    LaunchedEffect(input, inputMode) {
        if (inputMode == 1) {
            kotlinx.coroutines.delay(20)
            hScroll.scrollTo(hScroll.maxValue)
        }
    }

    // 执行模式调度：每条命令通过 Shizuku 特权通道（shell/root）运行一次，输出流式实时显示
    // 0=排队：running 时入队，上一条执行完自动执行；1=并发：立即另起执行；2=阻止：提示等待（不清空输入）
    fun launchExec(cmd: String, execId: ShizukuManager.ExecIdentity) {
        TerminalBuffer.running = true
        TerminalBuffer.execScope.launch {
            val start = System.currentTimeMillis()
            try {
                // 注册停止回调：用户点停止按钮时 destroy 进程
                TerminalBuffer.onStop = {
                    TerminalBuffer.appendLine("^C 已手动停止", isError = true)
                    // 直接 destroy 进程（ShizukuManager 里的 remote/proc）
                    runCatching { ShizukuManager.destroyLastProcess() }
                    TerminalBuffer.running = false
                }
                val ok = ShizukuManager.runCommandStreamingEx(
                    cmd,
                    identity = execId,
                    onLine = { line, isErr -> TerminalBuffer.appendLine(line, isErr) },
                    // 空闲超时放宽到 5 分钟：pm install / logcat 这类长命令不误杀
                    timeoutMs = 300_000
                )
                val elapsedMs = System.currentTimeMillis() - start
                if (!ok.finished) {
                    TerminalBuffer.appendLine("命令执行超时（5 分钟无新输出）· 耗时 $elapsedMs 毫秒", isError = true)
                }
                // 退出码总是打印（包括 0），方便用户知道结果
                if (ok.exitCode != null) {
                    TerminalBuffer.appendLine("(exit ${ok.exitCode} · 耗时 $elapsedMs 毫秒)", isError = ok.exitCode != 0)
                }
            } catch (_: Throwable) {
                val elapsedMs = System.currentTimeMillis() - start
                TerminalBuffer.appendLine("命令执行失败 · 耗时 $elapsedMs 毫秒", isError = true)
            }
            TerminalBuffer.running = false
            TerminalBuffer.onStop = null
            // 排队模式：自动执行等待队列中的下一条
            val next = TerminalBuffer.pendingQueue.removeFirstOrNull()
            if (next != null) {
                TerminalBuffer.appendLine("(开始执行排队命令)", isError = false)
                launchExec(next.first, next.second)
            }
        }
    }

    fun clearTerminal() {
        TerminalBuffer.clear()
    }

    /** 写入历史：去重、最新在前、上限 50，持久化到 Prefs。 */
    fun pushHistory(cmd: String) {
        val cleaned = cmd.trim()
        if (cleaned.isEmpty()) return
        val next = (listOf(cleaned) + history.filter { it != cleaned }).take(50)
        history = next
        Prefs.terminalHistory = next
    }

    fun removeHistory(cmd: String) {
        val next = history.filter { it != cmd }
        history = next
        Prefs.terminalHistory = next
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
        if (running && execMode == 2) {
            // 阻止模式：不清空输入，红字提示等待
            TerminalBuffer.appendLine("上一条还在执行，请等待完成后再发送", isError = true)
            return
        }
        TerminalBuffer.appendLine("[$identityLabel] $ $cmd")
        pushHistory(cmd)
        input = ""
        if (running && execMode == 0) {
            // 排队模式：入队等待，完成后自动执行
            TerminalBuffer.pendingQueue.addLast(cmd to identity)
            TerminalBuffer.appendLine("(已排队：上一条执行完后自动执行)", isError = false)
            return
        }
        launchExec(cmd, identity)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 16.dp)
    ) {
        // 89 轮：终端顶栏玻璃条（跟随设置页五档材质）——两行布局：第一行标题+清空，第二行功能切换
        GlassPanel(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                // 第一行：标题 + 清空
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
                    // 执行身份快捷切换（跟随服务 / Shell(com.android.shell) / Root），位于清空左侧
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
                        Text(identityLabel, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    OutlinedButton(
                        onClick = { copyOutput() },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            Icons.Filled.ContentCopy,
                            contentDescription = "复制输出",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("复制", fontSize = 13.sp)
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    OutlinedButton(
                        onClick = { clearTerminal() },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "清空",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("清空", fontSize = 13.sp)
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                // 第二行：输入模式 / 执行模式 / 停止（终端标题正下方左对齐）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 输入模式切换：换行（多行）/ 横滚（单行跟随光标）
                    TextButton(
                        onClick = { cycleInputMode() },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            Icons.Filled.Keyboard,
                            contentDescription = "输入模式",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("输入:$inputModeLabel", fontSize = 11.sp)
                    }
                    Spacer(modifier = Modifier.width(2.dp))
                    // 执行模式切换：排队 / 并发 / 阻止
                    TextButton(
                        onClick = { cycleExecMode() },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = "执行模式",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("执行:$execModeLabel", fontSize = 11.sp)
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    // 运行中显示停止按钮
                    if (running) {
                        OutlinedButton(
                            onClick = { TerminalBuffer.onStop?.invoke() },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                Icons.Filled.Stop,
                                contentDescription = "停止",
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("停止", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        // Terminal — 整个输出区即输入区：点击任意位置聚焦，输入行显示在输出末尾提示符后，回车执行
        BasicTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .onGloballyPositioned { terminalHeight = it.size.height }
                .onFocusChanged {
                    if (it.isFocused) {
                        scope.launch {
                            kotlinx.coroutines.delay(30)
                            if (lines.isNotEmpty()) listState.scrollToItem(lines.size)
                        }
                    }
                },
            textStyle = LocalTextStyle.current.copy(
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onBackground
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(
                onSend = { sendInput() }
            ),
            // 输入模式：0=换行（多行，最多 4 行自动换行），1=横滚（单行，超出横向滚动）
            singleLine = inputMode == 1,
            maxLines = if (inputMode == 0) 4 else 1,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            decorationBox = { innerTextField ->
                // 液态玻璃风格终端输出区：跟随主题的半透明玻璃卡片
                GlassPanel(
                    modifier = Modifier.fillMaxSize(),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    // 历史输出 + 末尾输入行（同一滚动区，输入行紧跟最后一条输出）
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(lines) { line ->
                            Text(
                                text = line.text,
                                color = if (line.isError) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 16.sp
                            )
                        }
                        if (history.isNotEmpty()) {
                            item(key = "history-header") {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Filled.History,
                                        null,
                                        modifier = Modifier.size(13.dp),
                                        tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        "历史指令",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                                    )
                                }
                            }
                            items(history, key = { "hist-$it" }) { h ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        h,
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 2,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable { input = h }
                                    )
                                    IconButton(onClick = { removeHistory(h) }, modifier = Modifier.size(28.dp)) {
                                        Icon(
                                            Icons.Filled.Delete,
                                            "删除该条历史",
                                            modifier = Modifier.size(14.dp),
                                            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                                        )
                                    }
                                }
                            }
                        }
                        item(key = "terminal-input-line") {
                            Row(verticalAlignment = Alignment.Top) {
                                Text(
                                    "[$identityLabel] $ ",
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(top = 3.dp)
                                )
                                // 输入模式：横滚=水平滚动跟随光标；换行=自动换行多行显示
                                if (inputMode == 1) {
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .horizontalScroll(hScroll)
                                    ) {
                                        innerTextField()
                                    }
                                } else {
                                    Box(modifier = Modifier.weight(1f)) {
                                        innerTextField()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        )

        Spacer(modifier = Modifier.height(10.dp))
    }
}

@Composable
private fun TerminalCursor() {
    Box(
        modifier = Modifier
            .width(2.dp)
            .height(16.dp)
            .background(Color(0xFFB8C0D0))
    )
}
