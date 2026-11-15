package moe.shizuku.privileged.api.ui.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import moe.shizuku.privileged.api.core.Prefs

/**
 * 89c 收敛版：全局面板玻璃组件——不再使用 drawBackdrop 真玻璃渲染。
 *
 * 背景：89 轮引入真玻璃（blur + lens 折射 + 高光 + 内阴影）后，实机在
 * RenderThread 首帧 Skia prepare（SkiaGpuPipeline::pinImages）阶段出现 native
 * SIGSEGV（Digest ae913047…，SEGV_ACCERR，连续两次同点崩溃 + 系统自动重启循环），
 * UI 进程首帧即死（“进入闪退但有通知”：服务进程独立存活）。为彻底清除崩溃路径，
 * 89c 将 UI 进程内除底部导航（LiquidGlassBottomBar，1.0.3 已实机验证稳定）外的
 * 所有玻璃面收敛为五档降级色面板：
 *
 *   跟随设置页五档材质（普通/穿透/高斯/毛玻璃/液态）的半透明实色 + 细描边，
 *   零 blur、零采样、零图层录制，RenderThread 不再参与任何背景模糊绘制。
 *
 * [LocalGlassBackdrop] / [ProvideGlassBackdrop] 随 89c 一并移除；调用方签名不变
 * （modifier / shape / glassMode / content），各页面顶栏、弹窗调用处无需改动。
 */
@Composable
internal fun MaterialThemeIsDark(): Boolean =
    MaterialTheme.colorScheme.surface.luminance() < 0.5f

/**
 * 通用面板：把 [content] 渲染为五档降级色面板（跟随设置页材质）。
 * [shape] 圆角形状；[glassMode] 缺省跟随设置页五档选择；
 * [containerColor] 非空时覆盖面板底色（用于"不要这块颜色"的场景，如模块页顶栏改透明，
 * 此时描边同步隐藏，仅保留圆角裁剪与内容）。
 */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(18.dp),
    glassMode: Int = Prefs.glassMode,
    containerColor: Color? = null,
    content: @Composable () -> Unit
) {
    val bg = containerColor ?: GlassFallbackColor(glassMode)
    Box(
        modifier = modifier
            .clip(shape)
            .background(bg)
            .border(
                width = 1.dp,
                color = if (containerColor == null) {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                } else {
                    Color.Transparent
                },
                shape = shape
            ),
        contentAlignment = Alignment.CenterStart
    ) { content() }
}

/** 五档降级底色（半透明实色，深浅主题适配）。 */
@Composable
internal fun GlassFallbackColor(glassMode: Int): Color {
    val isDark = MaterialThemeIsDark()
    val base = if (isDark) Color(0xFF12121C) else Color(0xFFF5F3FA)
    return when (glassMode) {
        0 -> base.copy(alpha = if (isDark) 0.90f else 0.85f)
        1 -> base.copy(alpha = if (isDark) 0.72f else 0.68f)
        2 -> base.copy(alpha = if (isDark) 0.84f else 0.78f)
        3 -> base.copy(alpha = if (isDark) 0.80f else 0.74f)
        else -> base.copy(alpha = if (isDark) 0.74f else 0.68f)
    }
}
