package moe.shizuku.privileged.api.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.privileged.api.ui.theme.LinShizukuTheme
import moe.shizuku.privileged.api.util.ActivityTransitions
import lin.manager.LinManagerProvider

/**
 * 服务端日志页：展示 Lin-Shizuku 服务端通过 provider 回传的日志（Verbose/Debug/Info/Warn/Error 全级别），
 * 逐条条目视图（Tag + 时间 + 消息），支持搜索过滤、级别筛选、复制与清空。
 */
class ServerLogActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LinShizukuTheme {
                ServerLogScreen(onBack = {
                    ActivityTransitions.finishWithAnimation(this)
                })
            }
        }
    }
}

/** 日志级别筛选：五级标签（Verbose/Debug/Info/Warn/Error） */
private val LEVEL_NAMES = listOf("Verbose", "Debug", "Info", "Warn", "Error")
private val LEVEL_COLORS = listOf(
    Color(0xFF9E9E9E), // Verbose 灰
    Color(0xFF4CAF50), // Debug 绿
    Color(0xFF2196F3), // Info 蓝
    Color(0xFFFF9800), // Warn 橙
    Color(0xFFE91E63)  // Error 粉
)

/** 日志行解析：MM-dd HH:mm:ss.SSS X/Tag: message */
private val LOG_LINE_RE =
    Regex("""^(\d{2}-\d{2} \d{2}:\d{2}:\d{2})\.\d{3} ([VDIWEF])/([^:]+): (.*)$""")

private fun levelIndexOf(raw: String): Int = when (LOG_LINE_RE.find(raw)?.groupValues?.get(2)) {
    "V" -> 0
    "D" -> 1
    "I" -> 2
    "W" -> 3
    "E" -> 4
    else -> -1
}

/** 逐条日志条目：Tag + 时间 + 消息 */
private data class LogItem(
    val raw: String,
    val time: String,
    val level: Int,
    val tag: String,
    val message: String
)

private fun parseItem(raw: String): LogItem {
    val m = LOG_LINE_RE.find(raw)
    if (m != null) {
        return LogItem(
            raw = raw,
            time = m.groupValues[1].substring(6), // 仅 HH:mm:ss
            level = when (m.groupValues[2]) {
                "V" -> 0; "D" -> 1; "I" -> 2; "W" -> 3; else -> 4
            },
            tag = m.groupValues[3],
            message = m.groupValues[4]
        )
    }
    return LogItem(raw, "", -1, "", raw)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerLogScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var logs by remember { mutableStateOf<List<String>>(emptyList()) }
    var keyword by remember { mutableStateOf("") }
    // 五级筛选：默认全部打开，点击标签切换（至少保留一个选中）
    var selected by remember { mutableStateOf(listOf(true, true, true, true, true)) }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    fun refresh() {
        scope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                try {
                    // 截断保护：只保留最近 5000 条，防止服务长跑后日志无限增长拖垮解析与渲染
                    LinManagerProvider.snapshotLogs().takeLast(5000)
                } catch (_: Throwable) { null } // 快照失败（如服务端断开）返回 null，保留现有日志不崩溃
            }
            if (snapshot != null) logs = snapshot
        }
    }

    LaunchedEffect(Unit) { refresh() }
    // 3 秒轮询：服务运行期间日志持续产生，页面自动跟上
    DisposableEffect(Unit) {
        val job = scope.launch {
            while (true) {
                delay(3000)
                refresh()
            }
        }
        onDispose { job.cancel() }
    }

    // 过滤：级别 + 关键字
    val filteredRaw = remember(logs, keyword, selected) {
        logs.filter { raw ->
            val lv = levelIndexOf(raw)
            val keepLevel = lv == -1 || (lv in 0..4 && selected[lv])
            val keepKeyword = keyword.isBlank() || raw.contains(keyword, ignoreCase = true)
            keepLevel && keepKeyword
        }
    }
    val items = remember(filteredRaw) { filteredRaw.map { parseItem(it) } }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("服务日志", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        // 72 轮：joinToString 与剪贴板写入都移到 IO 线程，防止全量复制时 UI 线程 ANR
                        val count = filteredRaw.size
                        if (count == 0) {
                            Toast.makeText(ctx, "没有可复制的日志", Toast.LENGTH_SHORT).show()
                        } else {
                            scope.launch {
                                val text = withContext(Dispatchers.IO) {
                                    filteredRaw.joinToString("\n")
                                }
                                withContext(Dispatchers.IO) {
                                    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("服务日志", text))
                                }
                                Toast.makeText(ctx, "已复制 $count 条日志", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }) { Icon(Icons.Filled.ContentCopy, "复制") }
                    IconButton(onClick = {
                        LinManagerProvider.clearSnapshotLogs()
                        refresh()
                        Toast.makeText(ctx, "日志已清空", Toast.LENGTH_SHORT).show()
                    }) { Icon(Icons.Filled.Delete, "清空") }
                    IconButton(onClick = { refresh() }) {
                        Icon(Icons.Filled.Refresh, "刷新")
                    }
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
            // 搜索框
            OutlinedTextField(
                value = keyword,
                onValueChange = { keyword = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("搜索日志...", fontSize = 14.sp) },
                leadingIcon = { Icon(Icons.Filled.Search, null, modifier = Modifier.size(18.dp)) },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp),
                shape = RoundedCornerShape(20.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                )
            )
            // 级别筛选标签：开启＝级别色填充胶囊，关闭＝灰色描边胶囊；
            // 等宽胶囊填满整行，间隔 4dp；背景/描边需覆盖 padding 区域（background 在 padding 前），否则胶囊退化成细长方形
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                LEVEL_NAMES.forEachIndexed { index, name ->
                    val active = selected[index]
                    val color = LEVEL_COLORS[index]
                    val shape = RoundedCornerShape(50)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clickable {
                                if (selected.count { it } > 1 || !active) {
                                    selected = selected.toMutableList().also { it[index] = !it[index] }
                                }
                            }
                            .background(
                                if (active) color
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                shape
                            )
                            .border(
                                1.dp,
                                if (active) color
                                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f),
                                shape
                            )
                            .padding(vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            name,
                            fontSize = 13.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (active) Color.White
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            // 筛选结果统计：错误 / 警告条数（基于当前级别与关键字筛选）
            val (errCnt, warnCnt) = remember(items) {
                var e = 0
                var w = 0
                items.forEach { it ->
                    if (it.level == 4 || moe.shizuku.privileged.api.core.LogDiagnostics.isErrLine(it.message)) e++
                    else if (it.level == 3 || moe.shizuku.privileged.api.core.LogDiagnostics.isWarnLine(it.message)) w++
                }
                e to w
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "错误 $errCnt 条",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (errCnt > 0) Color(0xFFFF5252) else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.size(16.dp))
                Text(
                    "警告 $warnCnt 条",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (warnCnt > 0) Color(0xFFFFB74D) else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(2.dp))
            // 日志条目列表 / 空状态
            if (items.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Filled.Search,
                        null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(56.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "暂无日志",
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "日志将在服务运行时显示",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            } else {
                // 72 轮修复：原 key={ it.raw } 用完整日志行做 key，服务端日志大量重复行
                // （UID idle/sendBinder/客户端计数等文本完全相同）→ 重复 key 在滑动重组时抛异常闪退。
                // 改用 itemsIndexed 以 index 为唯一 key（日志行无状态，index key 安全且稳定）。
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(items) { _, item ->
                        LogRow(item)
                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        )
                    }
                }
            }
        }
    }
}

/** 单条日志：第一行 Tag（左，级别色）+ 时间（右），第二行消息全文（错误行红色高亮 + ⚠ 前缀） */
@Composable
private fun LogRow(item: LogItem) {
    val isErr = item.level == 4 || moe.shizuku.privileged.api.core.LogDiagnostics.isErrLine(item.message)
    val isWarn = !isErr && (item.level == 3 || moe.shizuku.privileged.api.core.LogDiagnostics.isWarnLine(item.message))
    val msgColor = when {
        isErr -> Color(0xFFFF5252)
        isWarn -> Color(0xFFFFB74D)
        else -> MaterialTheme.colorScheme.onSurface
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val tagColor = if (item.level in 0..4) LEVEL_COLORS[item.level]
            else MaterialTheme.colorScheme.onSurfaceVariant
            Text(
                item.tag,
                color = tagColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.size(8.dp))
            Text(
                item.time,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            (if (isErr) "⚠ " else "") + item.message,
            color = msgColor,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}
