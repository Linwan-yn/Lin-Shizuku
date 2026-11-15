package moe.shizuku.privileged.api.ui.widget

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import moe.shizuku.privileged.api.core.Prefs

/**
 * A clickable modifier with press-scale feedback and ripple indication.
 */
fun Modifier.pressClickable(
    enabled: Boolean = true,
    role: Role? = null,
    onClick: () -> Unit
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val view = LocalView.current
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.95f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "pressScale"
    )
    this
        .scale(scale)
        .clickable(
            interactionSource = interactionSource,
            indication = androidx.compose.material3.ripple(
                bounded = true,
                radius = androidx.compose.ui.unit.Dp.Unspecified
            ),
            enabled = enabled,
            role = role,
            onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Prefs.hapticEnabled) {
                    view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                }
                onClick()
            }
        )
}

/**
 * A clickable modifier with press-scale feedback but NO ripple (for glass / custom surfaces).
 * 液态玻璃风格：按下 0.95 弹簧缩放 + 震动，无涟漪。
 */
fun Modifier.pressClickableNoRipple(
    enabled: Boolean = true,
    onClick: () -> Unit
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val view = LocalView.current
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.95f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "pressScaleNoRipple"
    )
    this
        .scale(scale)
        .clickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Prefs.hapticEnabled) {
                    view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                }
                onClick()
            }
        )
}
