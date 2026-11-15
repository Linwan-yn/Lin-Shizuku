package moe.shizuku.privileged.api.ui.widget

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A status dot: green when active, red when inactive.
 * 90i 轮：移除无限脉冲动画（rememberInfiniteTransition 每帧重组 + 绘制，
 * 前台常驻时持续消耗 CPU/GPU）——改为静态实心点，颜色语义不变。
 */
@Composable
fun StatusDot(
    active: Boolean,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 12.dp
) {
    val color = if (active) Color(0xFF4CAF50) else Color(0xFFE53935)
    Box(
        modifier = modifier
            .size(size * 0.8f)
            .clip(CircleShape)
            .background(color)
    )
}

/**
 * Theme-adaptive code/command block with optional copy button.
 * Uses a dark terminal-style background in both themes for readability.
 */
@Composable
fun CodeBlock(
    code: String,
    modifier: Modifier = Modifier,
    showCopy: Boolean = true
) {
    val ctx = LocalContext.current
    val darkTheme = isSystemInDarkTheme()
    // 浅色模式用浅色终端底色，深色模式保留深色
    val bgTop = if (darkTheme) Color(0xFF1E1E2E) else Color(0xFFF2F2F7)
    val bgBottom = if (darkTheme) Color(0xFF161622) else Color(0xFFE8E8EE)
    val borderColor = if (darkTheme) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.08f)
    val codeTextColor = if (darkTheme) Color(0xFFB8C0D0) else Color(0xFF3A3A4A)
    val copyTint = if (darkTheme) Color(0xFF8888AA) else Color(0xFF666688)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(bgTop, bgBottom)
                )
            )
            .border(
                width = 0.5.dp,
                color = borderColor,
                shape = RoundedCornerShape(10.dp)
            )
    ) {
        // Title bar with dots
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFFF5F57))
            )
            Spacer(modifier = Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFFEBC2E))
            )
            Spacer(modifier = Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF28C840))
            )
            Spacer(modifier = Modifier.weight(1f))
            if (showCopy) {
                IconButton(
                    onClick = {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("command", code))
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.Filled.ContentCopy,
                        null,
                        tint = copyTint,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
        // Code content
        Text(
            text = code,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.5.sp,
            color = codeTextColor,
            lineHeight = 18.sp,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
        )
    }
}

/**
 * A dialog showing scrollable command output with a terminal-style appearance.
 * Used for service startup output, diagnostics, etc.
 */
@Composable
fun OutputDialog(
    title: String,
    output: String,
    loading: Boolean = false,
    loadingText: String = "执行中...",
    onDismiss: () -> Unit,
    onDeveloperOptions: (() -> Unit)? = null,
    developerOptionsLabel: String = "开发者选项"
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontWeight = FontWeight.SemiBold)
                if (loading) {
                    Spacer(modifier = Modifier.width(10.dp))
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                }
            }
        },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color(0xFF1E1E2E), Color(0xFF161622))
                        )
                    )
                    .border(
                        width = 0.5.dp,
                        color = Color.White.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(10.dp)
                    )
                    .verticalScroll(rememberScrollState())
            ) {
                if (output.isEmpty() && loading) {
                    Text(
                        loadingText,
                        color = Color(0xFF8888AA),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(14.dp)
                    )
                } else {
                    Text(
                        output,
                        color = Color(0xFFA8D8A8),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }
        },
        confirmButton = {
            Row {
                if (onDeveloperOptions != null) {
                    TextButton(onClick = onDeveloperOptions) { Text(developerOptionsLabel) }
                }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        }
    )
}

/**
 * A compact icon container with rounded background, used in list cards.
 */
@Composable
fun IconContainer(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 44.dp,
    iconSize: androidx.compose.ui.unit.Dp = 24.dp,
    containerColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primaryContainer,
    iconColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(14.dp))
            .background(containerColor),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier.size(iconSize)
        )
    }
}
