package moe.shizuku.privileged.api.ui.widget

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 原生液态玻璃底栏（适配版 v6）
 * 对齐官方 LiquidBottomTabs：
 * - 外层左右 28dp（官方 36dp，5 个 tab 略收），避开系统导航栏
 * - 背景玻璃层 68dp，胶囊层 56dp，Capsule 大圆角
 * 三层结构：整栏玻璃衬底 → 选中胶囊玻璃背景 → tap 行（最上层，图标文字直接画在玻璃之上）
 * 交互：
 * - 子项按下即有按压态放大/高亮，长按原地发光；
 * - 长按后拖拽，胶囊实时跟手，命中项同步切换；松手吸附定档。
 */
@Composable
fun OfficialLiquidBottomBar(
    backdrop: LayerBackdrop,
    items: List<GlassNavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val isLightTheme = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    val accentColor =
        if (isLightTheme) Color(0xFF0088FF)
        else Color(0xFF0091FF)
    val glassSurface = if (isLightTheme) Color(0xFFF5F3FA) else Color(0xFF1C1E28)
    val containerColor =
        if (isLightTheme) glassSurface.copy(alpha = 0.6f)
        else glassSurface.copy(alpha = 0.5f)

    val tabsCount = items.size

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 28.dp, end = 28.dp, top = 4.dp, bottom = 6.dp)
    ) {
        BoxWithConstraints(
            contentAlignment = Alignment.CenterStart
        ) {
            val density = LocalDensity.current
            val tabWidth = with(density) {
                (constraints.maxWidth.toFloat() - 8f.dp.toPx()) / tabsCount
            }

            val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
            val animationScope = rememberCoroutineScope()
            val hapticView = LocalView.current

            val currentSelected by rememberUpdatedState(selectedIndex)

            // 胶囊位置（连续索引 0..tabsCount-1）
            val capsuleOffset = remember { Animatable(currentSelected.toFloat()) }

            // 按压 / 长按 / 拖拽 / 拖拽命中预览
            var pressedIndex by remember { mutableIntStateOf(-1) }
            var longPressIndex by remember { mutableIntStateOf(-1) }
            var hoverIndex by remember { mutableIntStateOf(-1) }
            var isDragging by remember { mutableStateOf(false) }
            var hasDragged by remember { mutableStateOf(false) }
            var dragStartX by remember { mutableFloatStateOf(0f) }
            // 拖拽基准：长按成立时锚定的选中索引；胶囊位置 = 基准 + 手指位移增量，避免按手指绝对落点映射造成交界回跳
            var dragBaseIndex by remember { mutableFloatStateOf(0f) }
            // 拖拽中的连续索引（未拖拽为 -1）；直接 state 驱动位置，避免每帧 launch Animatable 造成竞争/瞬闪
            var dragIndex by remember { mutableFloatStateOf(-1f) }
            val dragThresholdPx = with(density) { 12f.dp.toPx() }

            // 拖拽中用 state 直写的连续索引，否则用吸附动画值
            val shownOffset = if (dragIndex >= 0f) dragIndex else capsuleOffset.value

            val pressActive = pressedIndex >= 0 || longPressIndex >= 0 || isDragging
            val pressProgress by animateFloatAsState(
                targetValue = if (pressActive) 1f else 0f,
                animationSpec = spring(0.5f, 300f, 0.001f),
                label = "officialPress"
            )
            val targetScale = when {
                isDragging -> 1.12f
                longPressIndex >= 0 -> 1.15f
                pressedIndex >= 0 -> 1.08f
                else -> 1f
            }
            val capsuleScale by animateFloatAsState(
                targetValue = targetScale,
                animationSpec = spring(0.6f, 250f, 0.001f),
                label = "officialScale"
            )

            LaunchedEffect(currentSelected) {
                if (!isDragging) {
                    capsuleOffset.animateTo(
                        currentSelected.toFloat(),
                        spring(1f, 300f, 0.01f)
                    )
                }
            }

            // 手指位移增量换算成索引（LTR/RTL 对称）；胶囊从基准选中位随手指平移
            fun deltaToIndex(dx: Float): Float {
                val raw = (dragBaseIndex + (if (isLtr) dx else -dx) / tabWidth)
                return raw.fastCoerceIn(0f, (tabsCount - 1).toFloat())
            }

            // 第一层：整栏背景玻璃（不含 tap，仅做 backdrop 衬底）
            Box(
                Modifier
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { RoundedCornerShape(34.dp) },
                        effects = {
                            blur(radius = 16f)
                            lens(24f.dp.toPx(), 24f.dp.toPx())
                        },
                        onDrawSurface = { drawRect(containerColor) }
                    )
                    .height(68f.dp)
                    .fillMaxWidth()
                    .padding(4f.dp)
            )

            // 第二层：选中胶囊背景（纯穿透玻璃，不含图标文字；位于 tap 层之下，不遮挡 tap）
            Box(
                Modifier
                    .padding(horizontal = 4f.dp)
                    .graphicsLayer {
                        translationX =
                            if (isLtr) shownOffset * tabWidth
                            else size.width - (shownOffset + 1f) * tabWidth
                        this.scaleX = capsuleScale
                        this.scaleY = capsuleScale
                    }
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { RoundedCornerShape(28.dp) },
                        effects = {
                            lens(
                                10f.dp.toPx() * pressProgress,
                                14f.dp.toPx() * pressProgress,
                                chromaticAberration = true
                            )
                        },
                        highlight = {
                            Highlight.Default.copy(alpha = pressProgress)
                        },
                        innerShadow = {
                            InnerShadow(
                                radius = 8f.dp * pressProgress,
                                alpha = pressProgress
                            )
                        },
                        onDrawSurface = {
                            val surface = if (isLightTheme) Color.Black else Color.White
                            drawRect(surface.copy(alpha = 0.06f), alpha = 1f - pressProgress)
                            drawRect(Color.Black.copy(alpha = 0.02f * pressProgress))
                        }
                    )
                    .height(56f.dp)
                    .fillMaxWidth(1f / tabsCount)
            )

            // 第三层：tap 行（最上层，图标文字直接画在胶囊玻璃之上，清晰透出）
            Row(
                Modifier
                    .height(68f.dp)
                    .fillMaxWidth()
                    .padding(4f.dp)
                    .pointerInput(tabsCount, tabWidth, isLtr) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { startOffset ->
                                // 长按成立：立即锚定到当前选中位，胶囊位置随手指位移跟手，不设阈值门禁造成跳变
                                isDragging = true
                                hasDragged = false
                                dragStartX = startOffset.x
                                dragBaseIndex = currentSelected.toFloat()
                                dragIndex = currentSelected.toFloat()
                                hoverIndex = currentSelected
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                    hapticView.performHapticFeedback(
                                        HapticFeedbackConstants.LONG_PRESS
                                    )
                                }
                            },
                            onDragEnd = {
                                if (hasDragged) {
                                    val endOffset = dragIndex.takeIf { it >= 0f } ?: capsuleOffset.value
                                    val targetIndex =
                                        endOffset.fastRoundToInt()
                                            .fastCoerceIn(0, tabsCount - 1)
                                    animationScope.launch {
                                        capsuleOffset.snapTo(endOffset)
                                        capsuleOffset.animateTo(
                                            targetIndex.toFloat(),
                                            spring(1f, 300f, 0.01f)
                                        )
                                    }
                                    if (targetIndex != currentSelected) {
                                        onSelect(targetIndex)
                                    }
                                } else {
                                    // 长按后未真正拖动：回弹到原选中项，不切换
                                    animationScope.launch {
                                        capsuleOffset.animateTo(
                                            currentSelected.toFloat(),
                                            spring(1f, 300f, 0.01f)
                                        )
                                    }
                                }
                                isDragging = false
                                hoverIndex = -1
                                hasDragged = false
                                dragIndex = -1f
                            },
                            onDragCancel = {
                                animationScope.launch {
                                    capsuleOffset.animateTo(
                                        currentSelected.toFloat(),
                                        spring(1f, 300f, 0.01f)
                                    )
                                }
                                isDragging = false
                                hoverIndex = -1
                                hasDragged = false
                                dragIndex = -1f
                            }
                        ) { change, _ ->
                            change.consume()
                            val dx = change.position.x - dragStartX
                            // 阈值只标记是否构成拖动（用于松手定档判断），位置每帧跟手，不在阈值处跳变
                            if (abs(dx) > dragThresholdPx) {
                                hasDragged = true
                            }
                            val index = deltaToIndex(dx)
                            dragIndex = index
                            // 命中按就近格取整（与松手定档口径一致），拖过半格即预览右邻，跟手切换
                            val hit = index.fastRoundToInt().fastCoerceIn(0, tabsCount - 1)
                            if (hit != hoverIndex) {
                                hoverIndex = hit
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    hapticView.performHapticFeedback(
                                        HapticFeedbackConstants.TEXT_HANDLE_MOVE
                                    )
                                }
                            }
                        }
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                items.forEachIndexed { index, item ->
                    val selected =
                        if (hoverIndex >= 0) index == hoverIndex else index == currentSelected
                    OfficialLiquidBottomTab(
                        item = item,
                        selected = selected,
                        accentColor = accentColor,
                        onClick = { onSelect(index) },
                        onPressedChange = { pressed ->
                            pressedIndex = if (pressed) index else -1
                        },
                        onLongPressVisualChange = { active ->
                            longPressIndex = if (active) index else -1
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun RowScope.OfficialLiquidBottomTab(
    item: GlassNavItem,
    selected: Boolean,
    accentColor: Color,
    onClick: () -> Unit,
    onPressedChange: (Boolean) -> Unit,
    onLongPressVisualChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    LaunchedEffect(pressed) {
        if (pressed) {
            onPressedChange(true)
            delay(300)
            onLongPressVisualChange(true)
        } else {
            onPressedChange(false)
            onLongPressVisualChange(false)
        }
    }

    val iconScale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = spring(0.6f, 250f, 0.001f),
        label = "officialTabIcon"
    )

    val onSurface = MaterialTheme.colorScheme.onSurface
    val contentColor = if (selected) accentColor else onSurface.copy(alpha = 0.8f)
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = item.label,
            tint = contentColor,
            modifier = Modifier.size(24.dp).graphicsLayer { scaleX = iconScale; scaleY = iconScale }
        )
        Text(
            text = item.label,
            color = contentColor,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}
