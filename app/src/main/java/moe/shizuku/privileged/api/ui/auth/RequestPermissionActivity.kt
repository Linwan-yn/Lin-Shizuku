package moe.shizuku.privileged.api.ui.auth

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.LocalIndication
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import moe.shizuku.privileged.api.core.ShizukuManager

/**
 * 授权请求弹窗：当 Shizuku 兼容应用首次请求使用 Shizuku 权限时，
 * 由官方 server（shizuku-server.jar）通过
 * `moe.shizuku.privileged.api.intent.action.REQUEST_PERMISSION` 启动本 Activity。
 *
 * 用户选择「始终允许 / 仅此一次 / 拒绝」后，将结果回复给服务端
 * （dispatchPermissionConfirmationResult），并同步本地持久化授权状态。
 */
class RequestPermissionActivity : ComponentActivity() {

    private var uid: Int = -1
    private var pid: Int = -1
    private var requestCode: Int = -1
    private var permission: String = "shizuku"
    private var appInfo: ApplicationInfo? = null
    private var appLabel: String = "该应用"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyIntent(intent)
        if (uid < 0) {
            // 1.1.2：缺 uid 的请求无法回复，直接收掉，避免留一个空弹窗挡后续请求
            finish()
            return
        }
        renderDialog()
    }

    /**
     * 1.1.2（授权弹窗缺失修复）：本 Activity 是 singleInstance，已在任务栈中时
     * 新的授权请求会复用同一实例、不再走 onCreate。原实现没有 onNewIntent，
     * 复用时会继续用上一次的 uid/requestCode 展示，甚至因为字段没刷新看起来"没弹"。
     * 这里刷新字段并重建内容。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyIntent(intent)
        if (uid < 0) {
            finish()
            return
        }
        renderDialog()
    }

    private fun applyIntent(intent: Intent) {
        uid = intent.getIntExtra("uid", -1)
        pid = intent.getIntExtra("pid", -1)
        requestCode = intent.getIntExtra("requestCode", -1)
        permission = intent.getStringExtra("permission") ?: "shizuku"
        appInfo = if (android.os.Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra("applicationInfo", ApplicationInfo::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra("applicationInfo")
        }
        appLabel = appInfo?.let { ai ->
            try {
                packageManager.getApplicationLabel(ai).toString()
            } catch (_: Throwable) { ai.packageName }
        } ?: "该应用"
    }

    private fun renderDialog() {
        setContent {
            RequestPermissionDialog(
                icon = appInfo?.let {
                    try { packageManager.getApplicationIcon(it).toBitmap().asImageBitmap() } catch (_: Throwable) { null }
                },
                appLabel = appLabel,
                onAlwaysAllow = { reply(true, onetime = false) },
                onOnce = { reply(true, onetime = true) },
                onDeny = { reply(false, onetime = false) }
            )
        }
    }

    private fun reply(allowed: Boolean, onetime: Boolean) {
        // 仅此一次不持久化；始终允许/拒绝同步到本地，未激活也能保留决定
        if (!onetime) {
            ShizukuManager.setAppPermission(uid, allowed, this)
        }
        // 回复服务端（按请求的权限名回复，shizuku 兼容应用传 "shizuku"，lin 客户端传 "lin"）
        ShizukuManager.dispatchPermissionResult(this, uid, pid, requestCode, allowed, onetime, permission)
        finish()
    }
}

@Composable
private fun RequestPermissionDialog(
    icon: androidx.compose.ui.graphics.ImageBitmap?,
    appLabel: String,
    onAlwaysAllow: () -> Unit,
    onOnce: () -> Unit,
    onDeny: () -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    val bg = MaterialTheme.colorScheme.surface
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    Dialog(
        onDismissRequest = { onDeny() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        // 自适应：横屏（宽>高）时按钮横排、弹窗更宽；竖屏时竖排，始终限制最大宽度
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            val landscape = maxWidth > maxHeight
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = bg,
                shadowElevation = 24.dp,
                modifier = Modifier
                    .widthIn(max = if (landscape) 560.dp else 400.dp)
                    .fillMaxWidth()
                    .padding(horizontal = if (landscape) 56.dp else 32.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // 应用图标
                    if (icon != null) {
                        Image(
                            bitmap = icon,
                            contentDescription = null,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(16.dp))
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(primary),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                appLabel.first().toString(),
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))

                    Text(
                        "授权请求",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = onSurface
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "要允许 $appLabel 使用 shizuku 吗？",
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        color = onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(24.dp))

                    if (landscape) {
                        // 横屏：主按钮 + 次按钮横排，拒绝独立一行（全宽）
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            DialogActionButton(
                                label = "始终允许",
                                container = primary,
                                content = MaterialTheme.colorScheme.onPrimary,
                                onClick = onAlwaysAllow,
                                modifier = Modifier.weight(1f)
                            )
                            DialogActionButton(
                                label = "仅此一次",
                                container = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                content = onSurface,
                                onClick = onOnce,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        DialogActionButton(
                            label = "拒绝",
                            container = Color.Transparent,
                            content = MaterialTheme.colorScheme.error,
                            onClick = onDeny,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        // 竖屏：三个按钮竖排，每个 52dp 高、全宽可点
                        DialogActionButton(
                            label = "始终允许",
                            container = primary,
                            content = MaterialTheme.colorScheme.onPrimary,
                            onClick = onAlwaysAllow,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(10.dp))
                        DialogActionButton(
                            label = "仅此一次",
                            container = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            content = onSurface,
                            onClick = onOnce,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(10.dp))
                        DialogActionButton(
                            label = "拒绝",
                            container = Color.Transparent,
                            content = MaterialTheme.colorScheme.error,
                            onClick = onDeny,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

/**
 * 弹窗动作按钮：整个 52dp 区域可点击（大触控范围），按下有缩放反馈。
 */
@Composable
private fun DialogActionButton(
    label: String,
    container: Color,
    content: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, label = "btnScale")
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = container,
        modifier = modifier
            .height(52.dp)
            .scale(scale)
            .clip(RoundedCornerShape(16.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick
            )
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Text(label, color = content, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        }
    }
}
