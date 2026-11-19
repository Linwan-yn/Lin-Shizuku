package moe.shizuku.privileged.api.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import moe.shizuku.privileged.api.core.CommandTask
import moe.shizuku.privileged.api.core.CommandTaskRegistry
import moe.shizuku.privileged.api.core.TaskState
import moe.shizuku.privileged.api.ui.widget.GlassPanel

/**
 * 后台任务管理区（1.1.5 由模块页迁入终端页）。
 *
 * 所有命令执行入口——终端手敲、脚本运行、模块开关/动作——统一登记到 [CommandTaskRegistry]，
 * 在终端页集中查看：运行中逐条停止、展开输出回看；已结束的历史任务可一键清除。
 */
@Composable
internal fun RunningTaskPanel(
    tasks: List<CommandTask>,
    onStop: (Long) -> Unit
) {
    // 仅展示转入后台且仍在运行的任务；前台命令不进入，结束任务已由注册表移除
    val visible = tasks.filter { it.detached && it.state == TaskState.RUNNING }
    if (visible.isEmpty()) return

    // 每秒刷新一次计时展示
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }

    GlassPanel(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(18.dp),
        containerColor = Color.Transparent
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Terminal, "后台任务",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text("后台任务", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (visible.isNotEmpty()) "运行中 ${visible.size}" else "无运行中任务",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            visible.forEach { t ->
                TaskRow(task = t, now = now, onStop = onStop)
            }
        }
    }
}

@Composable
private fun TaskRow(
    task: CommandTask,
    now: Long,
    onStop: (Long) -> Unit
) {
    var expanded by remember(task.id) { mutableStateOf(false) }
    val isRunning = task.state == TaskState.RUNNING
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .clickable { expanded = !expanded }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(task.title, maxLines = 1, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Spacer(Modifier.height(2.dp))
            Text(
                statusLine(task, now),
                fontSize = 11.sp,
                color = statusColor(task.state).let {
                    if (it != null) it else MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
        if (isRunning) {
            IconButton(onClick = { onStop(task.id) }) {
                Icon(
                    Icons.Filled.Stop, "停止运行",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
    if (expanded) {
        val preview = task.output.joinToString("\n") {
            if (it.isError) "[错误] ${it.text}" else it.text
        }.ifBlank { "（暂无输出）" }
        Text(
            preview,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

private fun statusLine(task: CommandTask, now: Long): String {
    val meta = "${task.source} · ${task.identity.label}"
    val elapsed = task.elapsedMs()
    val elapsedText = if (elapsed < 1000) "${elapsed}ms" else "${elapsed / 1000}s"
    return when (task.state) {
        TaskState.RUNNING -> "$meta · 已运行 ${(now - task.startedAt) / 1000}s"
        TaskState.FINISHED -> "$meta · 成功 · $elapsedText"
        TaskState.STOPPED -> "$meta · 已停止"
        TaskState.TIMEOUT -> "$meta · 超时"
        TaskState.FAILED -> "$meta · 失败${task.exitCode?.let { "（退出码 $it）" } ?: ""}"
    }
}

private fun statusColor(state: TaskState): Color? = when (state) {
    TaskState.RUNNING -> null
    TaskState.FINISHED -> Color(0xFF4CAF50)
    TaskState.STOPPED -> Color(0xFF888899)
    TaskState.TIMEOUT -> Color(0xFFFEBC2E)
    TaskState.FAILED -> Color(0xFFFF6B6B)
}
