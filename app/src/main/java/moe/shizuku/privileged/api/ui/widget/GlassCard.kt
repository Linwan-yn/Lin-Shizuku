package moe.shizuku.privileged.api.ui.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import moe.shizuku.privileged.api.core.Prefs

/**
 * 统一的新 UI 卡片。
 *
 * 89 轮曾升级为真玻璃（drawBackdrop）；89b 修复：卡片改回**轻量玻璃色面板**——
 * 滚动列表/卡片数量多，真玻璃每帧全屏采样+模糊在滚动容器内是崩溃与掉帧高危点。
 * 89c 收敛版：顶栏 GlassPanel 也一并收敛为降级色面板，UI 进程仅底部导航
 * （LiquidGlassBottomBar，1.0.3 实机验证稳定）保留真玻璃，彻底清除 RenderThread
 * 首帧 pinImages SIGSEGV 崩溃路径。
 * 现用五档玻璃降级色（跟随设置页材质）+ 细边框呈现玻璃质感，零 blur、零采样，稳定不掉帧。
 * 调用方签名不变（onClick 可空 = 不可点卡片）。
 * 90q 轮：点击反馈由默认 ripple 改为 pressClickableNoRipple（Interaction.kt）——
 * 玻璃表面无涟漪水波纹 + 120ms 按压缩放（0.96），按压感更贴液态玻璃风格，点击不遮挡内容。
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    var cardModifier = modifier
        .fillMaxWidth()
    if (onClick != null) {
        // 按下缩放放在 clip 前面，不然放大被裁掉
        cardModifier = cardModifier.pressClickableNoRipple(onClick = onClick)
    }
    cardModifier = cardModifier.clip(shape)
    Box(
        modifier = cardModifier
            .background(GlassFallbackColor(Prefs.glassMode))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                shape = shape
            )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            content()
        }
    }
}
