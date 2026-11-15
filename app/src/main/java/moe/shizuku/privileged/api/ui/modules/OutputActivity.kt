package moe.shizuku.privileged.api.ui.modules

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.shizuku.privileged.api.core.DiagnosticLog
import moe.shizuku.privileged.api.core.ModuleLogs
import moe.shizuku.privileged.api.core.ModuleStore
import moe.shizuku.privileged.api.core.ShizukuManager
import moe.shizuku.privileged.api.ui.theme.LinShizukuTheme
import moe.shizuku.privileged.api.util.ActivityTransitions

class OutputActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val scriptId = intent.getStringExtra("script_id") ?: ""
        setContent {
            LinShizukuTheme {
                OutputScreen(
                    scriptId = scriptId,
                    onBack = { finishWithTransition() }
                )
            }
        }
    }

    private fun finishWithTransition() {
        ActivityTransitions.finishWithAnimation(this)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OutputScreen(scriptId: String, onBack: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var output by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(true) }
    var savedLog by remember { mutableStateOf("") }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    // 66 轮：流式执行（每行时间戳 + stderr 标红），退出码判定成败，日志自动落盘
    LaunchedEffect(scriptId) {
        val sb = StringBuilder()
        val result = withContext(Dispatchers.IO) {
            try {
                val content = ModuleStore.scriptContent(ctx, scriptId)
                if (content.isBlank()) {
                    sb.appendLine(ModuleLogs.line("!", "脚本文件不存在"))
                    ShizukuManager.StreamResult(true, 1)
                } else {
                    // 68 轮：按全局执行身份执行（跟随服务 / Shell(com.android.shell) / Root）
                    ShizukuManager.runCommandStreamingEx(
                        content,
                        identity = moe.shizuku.privileged.api.core.Prefs.execIdentity,
                        onLine = { line, isErr ->
                            sb.appendLine("[${ModuleLogs.tsShort()}] " + (if (isErr) "[错误] " else "") + line)
                        },
                        timeoutMs = 300_000
                    )
                }
            } catch (e: Throwable) {
                sb.appendLine(ModuleLogs.line("!", "执行异常: ${e.message ?: e.javaClass.simpleName}"))
                ShizukuManager.StreamResult(false, null)
            }
        }
        sb.appendLine(ModuleLogs.line("──", when {
            !result.finished -> "结果：执行超时（5 分钟无输出）\n排查提示：脚本可能等待 su 授权/输入、命令挂起，或执行了 start.sh 类脚本（会重启服务端）；完整输出见本日志"
            result.exitCode != null && result.exitCode != 0 ->
                "结果：失败（退出码 ${result.exitCode}）"
            else -> "结果：成功"
        }))
        savedLog = sb.toString()
        ModuleLogs.save(ctx, "exec_${scriptId}_${ModuleLogs.fileStamp()}.log", savedLog)
        ModuleLogs.appendAuto(ctx, scriptId, ModuleLogs.line("──", "输出页执行 $scriptId") + "\n" + savedLog.trim().take(2000))
        DiagnosticLog.append(
            "[脚本输出] $scriptId: " +
                (if (result.finished && (result.exitCode == null || result.exitCode == 0)) "成功" else "失败/超时") +
                (result.exitCode?.let { "（退出码 $it）" } ?: "")
        )
        output = savedLog
        running = false
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("执行输出", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    // 66 轮：保存日志到用户可见目录
                    IconButton(onClick = {
                        if (savedLog.isBlank()) {
                            Toast.makeText(ctx, "暂无输出可保存", Toast.LENGTH_SHORT).show()
                        } else {
                            val path = ModuleLogs.saveToStorage(
                                ctx, "exec_${scriptId}_${ModuleLogs.fileStamp()}.log", savedLog
                            )
                            Toast.makeText(ctx, "日志已保存: $path", Toast.LENGTH_LONG).show()
                        }
                    }) { Icon(Icons.Filled.Save, "保存日志") }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (running) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            val linkColor = MaterialTheme.colorScheme.primary
            val bodyColor = MaterialTheme.colorScheme.onBackground
            // 91 轮（链接识别优化）：输出改为 Compose 文本——SelectionContainer 保留
            // 长按复制/全选能力，同时输出中的 http(s) 链接可点击（LinkAnnotation），
            // 点击用系统浏览器打开；等宽字体 + 逐行着色与原 TextView 视觉一致。
            SelectionContainer {
                Text(
                    text = coloredOutputText(output, linkColor),
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    color = bodyColor,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState())
                )
            }
        }
    }
}

/** 91 轮：识别输出文本中的 http(s) 链接（trim 掉行尾常见标点），
 *  着色与行前缀标记同权；链接可点击（系统浏览器打开）。 */
private val OUTPUT_URL_REGEX = Regex("""https?://[\w\-._~:/?#\[\]@!$&'()*+,;=%]+""")

@Composable
private fun coloredOutputText(text: String, linkColor: Color): AnnotatedString {
    if (text.isBlank()) return AnnotatedString(text)
    // 在 composable 作用域取好 uriHandler，供链接点击 lambda（非 composable）使用
    val uriHandler = LocalUriHandler.current
    return buildAnnotatedString {
        for (line in text.lineSequence()) {
            val content = line.substringAfter("] ", line).trimStart()
            val lineColor = when {
                line.contains("[错误]") || content.startsWith("!") -> Color(0xFFFF6B6B)
                content.startsWith("✓") -> Color(0xFF4CAF50)
                content.startsWith("──") -> Color(0xFF888899)
                else -> null
            }
            var cursor = 0
            for (m in OUTPUT_URL_REGEX.findAll(line)) {
                if (m.range.first > cursor) {
                    // 非 URL 段：应用行级颜色
                    if (lineColor != null) {
                        pushStyle(SpanStyle(color = lineColor))
                        append(line.substring(cursor, m.range.first))
                        pop()
                    } else {
                        append(line.substring(cursor, m.range.first))
                    }
                }
                val raw = m.value
                val clean = raw.trimEnd('.', ',', ';', ':', '!', '？', '。', '，', '；')
                val tail = raw.removePrefix(clean)
                if (clean.length >= 10) {
                    withLink(
                        LinkAnnotation.Clickable(
                            tag = clean,
                            styles = TextLinkStyles(style = SpanStyle(
                                color = linkColor,
                                fontWeight = FontWeight.SemiBold
                            ))
                        ) {
                            uriHandler.openUri(clean)
                        }
                    ) {
                        append(clean)
                    }
                    if (tail.isNotEmpty()) append(tail)
                } else {
                    if (lineColor != null) {
                        pushStyle(SpanStyle(color = lineColor))
                        append(raw)
                        pop()
                    } else {
                        append(raw)
                    }
                }
                cursor = m.range.first + raw.length
            }
            if (cursor < line.length) {
                if (lineColor != null) {
                    pushStyle(SpanStyle(color = lineColor))
                    append(line.substring(cursor))
                    pop()
                } else {
                    append(line.substring(cursor))
                }
            }
            append("\n")
        }
    }
}
