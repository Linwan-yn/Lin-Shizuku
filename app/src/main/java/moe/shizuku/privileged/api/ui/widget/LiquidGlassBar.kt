package moe.shizuku.privileged.api.ui.widget

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ripple
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import moe.shizuku.privileged.api.core.Prefs

/**
 * 液态玻璃悬浮底栏 v4.0
 * - 选中胶囊是药丸形（圆角矩形），包住图标+文字整个
 * - 胶囊半透明黑玻璃质感（不是纯黑），弹簧滑动跟随
 * - 参考 KernelSU / 独立应用商店底栏效果
 */
@Composable
fun LiquidGlassBottomBar(
    backdrop: LayerBackdrop,
    items: List<GlassNavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    glassMode: Int = Prefs.glassMode
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val isDarkTheme = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val unselectedIcon = when {
        !isDarkTheme -> onSurface.copy(alpha = 0.6f)
        glassMode > 0 -> Color.White.copy(alpha = 0.6f)
        else -> onSurface
    }
    // 深色基色用与卡片同档的深灰蓝（不再用近黑 0E0E16），避免在纯黑背景上叠出黑板；浅色不变。
    val glassSurface = if (isDarkTheme) Color(0xFF1C1E28) else Color(0xFFF5F3FA)
    val glassShape = RoundedCornerShape(percent = 50)
    val itemCount = items.size
    // 按下哪个 Tab（-1=没按）
    var pressedIndex by remember { mutableStateOf(-1) }
    // 91 轮：长按视觉态索引（按下保持 300ms 后上报）——胶囊滑动预览到长按项 + 放大发光
    var longPressIndex by remember { mutableStateOf(-1) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 6.dp)
    ) {
        val isLiquid = glassMode >= 4
        val isPlain = glassMode == 0
        val isSeeThrough = glassMode == 1
        val isGaussian = glassMode == 2

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(68.dp)
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { glassShape },
                    effects = {
                        blur(radius = when (glassMode) {
                            0 -> 12f
                            1 -> 8f
                            2 -> 30f
                            3 -> 24f
                            else -> 16f
                        })
                        if ((!isPlain && !isSeeThrough && !isGaussian) &&
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                        ) {
                            lens(
                                refractionHeight = 3.dp.toPx(),
                                refractionAmount = if (isLiquid) 12.dp.toPx() else 7.dp.toPx(),
                                depthEffect = true,
                                chromaticAberration = false
                            )
                        }
                    },
                    highlight = {
                        when (glassMode) {
                            0 -> null
                            1 -> Highlight(width = 0.8.dp, alpha = 0.5f)
                            2 -> Highlight(width = 0.8.dp, alpha = 0.25f)
                            3 -> Highlight(width = 1.dp, alpha = 0.6f)
                            else -> Highlight(width = 1.2.dp, alpha = 1.0f)
                        }
                    },
                    innerShadow = {
                        when (glassMode) {
                            0 -> InnerShadow(8.dp, DpOffset(0.dp, 3.dp), Color.Black.copy(0.25f))
                            1 -> InnerShadow(6.dp, DpOffset(0.dp, 2.dp), Color.Black.copy(0.2f))
                            2 -> InnerShadow(10.dp, DpOffset(0.dp, 4.dp), Color.Black.copy(0.3f))
                            else -> InnerShadow(12.dp, DpOffset(0.dp, 4.dp), Color.Black.copy(0.35f))
                        }
                    },
                    onDrawSurface = {
                        drawRect(
                            color = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                when (glassMode) {
                                    0 -> glassSurface.copy(alpha = 0.92f)
                                    1 -> glassSurface.copy(alpha = if (isDarkTheme) 0.5f else 0.5f)
                                    2 -> glassSurface.copy(alpha = if (isDarkTheme) 0.68f else 0.66f)
                                    3 -> glassSurface.copy(alpha = if (isDarkTheme) 0.62f else 0.62f)
                                    else -> glassSurface.copy(alpha = if (isDarkTheme) 0.50f else 0.55f)
                                }
                            } else {
                                glassSurface.copy(alpha = 0.92f)
                            }
                        )
                    }
                )
        )

        if (glassMode == 0) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(68.dp)
                    .clip(glassShape)
                    .border(0.8.dp, onSurface.copy(0.15f), glassShape)
            )
        }

        // 上滑快捷面板状态（外层）
        var showQuickPanel by remember { mutableStateOf(false) }
        val quickPanelOffsetY by animateDpAsState(
            targetValue = if (showQuickPanel) 0.dp else 160.dp,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMediumLow
            ),
            label = "quickPanelOffsetY"
        )
        val quickPanelAlpha by animateFloatAsState(
            targetValue = if (showQuickPanel) 1f else 0f,
            animationSpec = spring(),
            label = "quickPanelAlpha"
        )

        // 上滑快捷面板（悬浮在底栏上方）
        if (quickPanelAlpha > 0.01f) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = quickPanelOffsetY)
                    .alpha(quickPanelAlpha)
                    .padding(bottom = 80.dp)
            ) {
                GlassPanel(
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        QuickActionButton(icon = Icons.Default.PlayArrow, label = "启动")
                        QuickActionButton(icon = Icons.Default.Refresh, label = "刷新")
                        QuickActionButton(icon = Icons.Default.Build, label = "模块")
                        QuickActionButton(icon = Icons.Default.Settings, label = "设置")
                    }
                }
            }
        }

        // BoxWithConstraints 量宽度，单个胶囊 offset 弹簧跟随
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(68.dp)
                .padding(horizontal = 8.dp)
        ) {
            val totalWidth = maxWidth
            val itemWidth = totalWidth / itemCount
            // 胶囊尺寸：扁药丸形，包住图标+文字整个
            val pillWidth = itemWidth - 4.dp
            val pillHeight = 48.dp
            val pillCorner = 24.dp
            val pillTargetXDp = itemWidth * selectedIndex + 2.dp
            val density = LocalDensity.current
            var dragOffsetXPx by remember { mutableStateOf<Float?>(null) }
            val isDragging = dragOffsetXPx != null
            var dragStartPx by remember { mutableStateOf(0f) }
            var dragBasePx by remember { mutableStateOf(0f) }
            var lastPointerItem by remember { mutableStateOf(-1) }
            var hasDragged by remember { mutableStateOf(false) }
            var longPressEndTime by remember { mutableStateOf(0L) }

            val pillMinXPx = with(density) { 2.dp.toPx() }
            val pillMaxXPx = with(density) { (totalWidth - pillWidth - 2.dp).toPx() }
            val itemWidthPx = with(density) { itemWidth.toPx() }
            val dragThresholdPx = with(density) { 12.dp.toPx() }

            val currentSelectedIndex by rememberUpdatedState(selectedIndex)
            val animScope = rememberCoroutineScope()

            // 用 Animatable<Float>（px）控制胶囊位置
            val pillOffsetAnim = remember {
                androidx.compose.animation.core.Animatable(with(density) { pillTargetXDp.toPx() })
            }
            LaunchedEffect(selectedIndex) {
                val targetXPx = with(density) { (itemWidth * selectedIndex + 2.dp).toPx() }
                if (!isDragging) {
                    pillOffsetAnim.animateTo(
                        targetXPx,
                        animationSpec = androidx.compose.animation.core.tween(
                            durationMillis = 250,
                            easing = androidx.compose.animation.core.FastOutSlowInEasing
                        )
                    )
                }
            }

            val displayX: Dp = if (dragOffsetXPx != null) {
                with(density) { dragOffsetXPx!!.toDp() }
            } else {
                with(density) { pillOffsetAnim.value.toDp() }
            }
            val hapticView = LocalView.current

            // 按下放大镜效果：按哪个 Tab 胶囊放大凸出去（超过底栏高度）；
            // 91 轮：长按（视觉态）任意项轻微放大，长按选中项叠加到 1.3
            val pillScale by animateFloatAsState(
                targetValue = when {
                    isDragging -> 1.15f
                    pressedIndex == selectedIndex -> 1.3f
                    longPressIndex >= 0 -> 1.18f
                    else -> 1f
                },
                animationSpec = androidx.compose.animation.core.tween(
                    durationMillis = 200,
                    easing = androidx.compose.animation.core.FastOutSlowInEasing
                ),
                label = "pillScale"
            )

            // 拖拽/长按预览时胶囊背景透明度变化
            val pillAlpha by animateFloatAsState(
                targetValue = if (isDragging || longPressIndex >= 0) 0.85f else 1f,
                animationSpec = spring(),
                label = "pillAlpha"
            )

            // 91 轮：长按/拖拽激活时胶囊发光（描边增亮 + 外发光渐变）
            val pillActivated = isDragging || longPressIndex >= 0
            val pillBorderColor = when {
                !pillActivated ->
                    if (isDarkTheme) Color.White.copy(alpha = 0.1f)
                    else primaryColor.copy(alpha = 0.2f)
                isDarkTheme -> Color.White.copy(alpha = 0.4f)
                else -> primaryColor.copy(alpha = 0.5f)
            }

            // 高亮胶囊（独立一层，offset 从左边开始算，垂直自动居中）
            Box(
                modifier = Modifier
                    .offset(x = displayX)
                    .size(width = pillWidth, height = pillHeight)
                    .scale(pillScale)
                    .alpha(pillAlpha)
                    .align(Alignment.CenterStart)
                    .clip(RoundedCornerShape(pillCorner))
                    .background(
                        if (isDarkTheme)
                            Color.Black.copy(alpha = 0.35f)
                        else
                            primaryColor.copy(alpha = 0.15f)
                    )
                    .border(
                        if (pillActivated) 1.2.dp else 1.dp,
                        pillBorderColor,
                        RoundedCornerShape(pillCorner)
                    )
                    .drawBehind {
                        val h = size.height
                        drawRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = 0.1f),
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.1f)
                                ),
                                startY = 0f,
                                endY = h
                            )
                        )
                        // 长按/拖拽：外发光（柔化描边高亮）
                        if (pillActivated) {
                            drawRect(
                                brush = Brush.verticalGradient(
                                    colors = listOf(
                                        pillBorderColor.copy(alpha = 0.25f),
                                        Color.Transparent
                                    ),
                                    startY = 0f,
                                    endY = 8f
                                ),
                                topLeft = androidx.compose.ui.geometry.Offset(0f, 0f),
                                size = androidx.compose.ui.geometry.Size(size.width, 8f)
                            )
                        }
                    }
            )

            // 图标文字层（垂直居中，和胶囊对齐）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.Center)
                    .pointerInput(itemCount, itemWidth, pillWidth, totalWidth, density) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { startOffset ->
                                dragStartPx = startOffset.x
                                dragBasePx = with(density) {
                                    (itemWidth * currentSelectedIndex + 2.dp).toPx()
                                }
                                dragOffsetXPx = dragBasePx
                                lastPointerItem =
                                    ((startOffset.x - pillMinXPx) / itemWidthPx).toInt().coerceIn(0, itemCount - 1)
                                hasDragged = false
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                    hapticView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                }
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                val deltaPx = change.position.x - dragStartPx
                                if (kotlin.math.abs(deltaPx) > dragThresholdPx) {
                                    hasDragged = true
                                }
                                val newXPx = (dragBasePx + deltaPx).coerceIn(pillMinXPx, pillMaxXPx)
                                dragOffsetXPx = newXPx
                                val pointerItem =
                                    ((change.position.x - pillMinXPx) / itemWidthPx).toInt().coerceIn(0, itemCount - 1)
                                if (pointerItem != lastPointerItem) {
                                    lastPointerItem = pointerItem
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        hapticView.performHapticFeedback(HapticFeedbackConstants.TEXT_HANDLE_MOVE)
                                    }
                                }
                            },
                            onDragEnd = {
                                if (hasDragged) {
                                    val currentXPx = dragOffsetXPx
                                        ?: with(density) { (itemWidth * currentSelectedIndex + 2.dp).toPx() }
                                    val targetIndex =
                                        ((currentXPx - pillMinXPx + itemWidthPx / 2f) / itemWidthPx)
                                            .toInt().coerceIn(0, itemCount - 1)
                                    // 关键：先把动画起点 snap 到拖拽终点，避免跳回旧位置再滑过去
                                    animScope.launch {
                                        pillOffsetAnim.snapTo(currentXPx)
                                    }
                                    onSelect(targetIndex)
                                }
                                dragOffsetXPx = null
                                hasDragged = false
                                lastPointerItem = -1
                                longPressEndTime = System.currentTimeMillis()
                            },
                            onDragCancel = {
                                // 取消时 snap 回原位置
                                val originalXPx = with(density) { (itemWidth * currentSelectedIndex + 2.dp).toPx() }
                                animScope.launch {
                                    pillOffsetAnim.animateTo(originalXPx, androidx.compose.animation.core.tween(150))
                                }
                                dragOffsetXPx = null
                                hasDragged = false
                                lastPointerItem = -1
                                longPressEndTime = System.currentTimeMillis()
                            }
                        )
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                items.forEachIndexed { index, item ->
                    val isHighlighted = index == selectedIndex
                    GlassNavItem(
                        icon = item.icon,
                        label = item.label,
                        selected = isHighlighted,
                        primaryColor = primaryColor,
                        unselectedIcon = unselectedIcon,
                        onPressedChange = { pressed ->
                            pressedIndex = if (pressed) index else -1
                        },
                        onLongPressVisualChange = { active ->
                            longPressIndex = if (active) index else -1
                        },
                        onClick = {
                            if (System.currentTimeMillis() - longPressEndTime < 500) return@GlassNavItem
                            onSelect(index)
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun GlassNavItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    primaryColor: Color,
    unselectedIcon: Color,
    onPressedChange: (Boolean) -> Unit,
    onLongPressVisualChange: (Boolean) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val view = LocalView.current

    // 把按下状态传给上层（胶囊放大用）
    androidx.compose.runtime.LaunchedEffect(pressed) {
        if (pressed) {
            onPressedChange(true)
            // 94 轮：按下保持 300ms 后上报"长按视觉态"——
            // 胶囊原地放大发光 + 目标项高亮（不跨位滑动）；快速点击不触发，松开清除。
            delay(300)
            onLongPressVisualChange(true)
        } else {
            onPressedChange(false)
            onLongPressVisualChange(false)
        }
    }

    val iconScale by animateFloatAsState(
        targetValue = when {
            pressed -> 0.88f
            selected -> 1.05f
            else -> 1f
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "iconScale"
    )

    Box(
        modifier = modifier
            .clip(CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    }
                    onClick()
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (selected) Color.White else unselectedIcon,
                modifier = Modifier
                    .size(22.dp)
                    .scale(iconScale)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = label,
                color = if (selected) Color.White else unselectedIcon,
                fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            )
        }
    }
}

data class GlassNavItem(
    val icon: ImageVector,
    val label: String
)

@Composable
private fun QuickActionButton(
    icon: ImageVector,
    label: String
) {
    // 91 轮（体验优化）：快捷面板按钮补按压反馈——按下轻微下沉缩放 + 触觉震动
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val view = LocalView.current
    val btnScale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "quickActionScale"
    )
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .scale(btnScale)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    }
                }
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}
