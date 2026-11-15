package moe.shizuku.privileged.api.ui.modules

import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.privileged.api.core.ModuleLogs
import moe.shizuku.privileged.api.ui.theme.LinShizukuTheme
import moe.shizuku.privileged.api.util.ActivityTransitions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 模块 / 脚本日志查看页（66 轮）。
 * 展示该模块/脚本的全部安装与执行日志（app 私有目录 logs/ 持久化）：
 *   - 手动执行：exec_<id>_<ts>.log（含时间戳输出与退出码）
 *   - 引擎/自动执行：auto_<id>.log（滚动日志）
 *   - 模块安装：install_*.log（模块 Tab 下展示全部安装日志）
 * 支持：查看全文、保存到存储（/storage/emulated/0/Lin-Shizuku/logs/）、单条删除、全部清空。
 */
class ModuleLogActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val type = intent.getStringExtra("log_type") ?: "module"
        val id = intent.getStringExtra("log_id") ?: ""
        val name = intent.getStringExtra("log_name") ?: id
        setContent {
            LinShizukuTheme {
                ModuleLogScreen(type = type, id = id, name = name, onBack = {
                    ActivityTransitions.finishWithAnimation(this)
                })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModuleLogScreen(type: String, id: String, name: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var logs by remember { mutableStateOf<List<ModuleLogs.LogMeta>>(emptyList()) }
    var viewMeta by remember { mutableStateOf<ModuleLogs.LogMeta?>(null) }
    var viewContent by remember { mutableStateOf("") }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    fun refresh() {
        scope.launch(Dispatchers.IO) {
            val all = ModuleLogs.list(ctx)
            val matched = all.filter { meta ->
                when (type) {
                    "module" -> meta.name.startsWith("exec_${id}_") ||
                        meta.name == "auto_$id.log" ||
                        meta.name.startsWith("install_")
                    else -> meta.name.startsWith("exec_${id}_") || meta.name == "auto_$id.log"
                }
            }
            withContext(Dispatchers.Main) { logs = matched }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("日志 · $name", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        if (logs.isNotEmpty()) {
                            logs.forEach { ModuleLogs.delete(ctx, it.name) }
                            refresh()
                            Toast.makeText(ctx, "本项日志已清空", Toast.LENGTH_SHORT).show()
                        }
                    }) { Icon(Icons.Filled.DeleteSweep, "清空本项日志") }
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
            if (logs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.Description, null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(52.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text("暂无日志", fontSize = 16.sp, fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "安装、执行模块或脚本后，日志将自动记录\n可在此查看、保存到存储或删除",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            } else {
                Text(
                    "共 ${logs.size} 份日志（安装/执行无论成败均自动记录）",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(logs, key = { it.name }) { meta ->
                        LogFileRow(
                            meta = meta,
                            onOpen = {
                                viewContent = ModuleLogs.read(ctx, meta.name)
                                viewMeta = meta
                            },
                            onDelete = {
                                ModuleLogs.delete(ctx, meta.name)
                                refresh()
                            }
                        )
                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        )
                    }
                }
            }
        }
    }

    viewMeta?.let { meta ->
        AlertDialog(
            onDismissRequest = { viewMeta = null },
            title = {
                Text(meta.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            text = {
                Column(modifier = Modifier.height(420.dp)) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { c ->
                            TextView(c).apply {
                                typeface = Typeface.MONOSPACE
                                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                                setTextColor(android.graphics.Color.parseColor("#B8C0D0"))
                                setTextIsSelectable(true)
                            }
                        },
                        update = { tv -> tv.text = viewContent }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val saved = ModuleLogs.saveToStorage(ctx, meta.name, viewContent)
                    Toast.makeText(ctx, "已保存: $saved", Toast.LENGTH_LONG).show()
                }) {
                    Icon(Icons.Filled.Save, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(4.dp))
                    Text("保存到存储")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewMeta = null }) { Text("关闭") }
            }
        )
    }
}

/** 单条日志：类型图标 + 文件名 + 时间 + 大小，点击查看全文，尾部删除。 */
@Composable
private fun LogFileRow(
    meta: ModuleLogs.LogMeta,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    val isError = meta.name.startsWith("install_") && meta.size == 0L
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    RoundedCornerShape(8.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.Description, "日志",
                tint = if (isError) Color(0xFFE57373) else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.size(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                meta.name,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "${fmtTime(meta.time)} · ${fmtSize(meta.size)}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                fontFamily = FontFamily.Monospace
            )
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.DeleteSweep, "删除", tint = Color(0xFFE57373), modifier = Modifier.size(18.dp))
        }
    }
}

private fun fmtTime(ms: Long): String =
    SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ms))

private fun fmtSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
