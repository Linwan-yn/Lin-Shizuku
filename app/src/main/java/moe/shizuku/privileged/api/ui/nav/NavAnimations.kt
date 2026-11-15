package moe.shizuku.privileged.api.ui.nav

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith

/**
 * Shared navigation transition specifications.
 *
 * Navigation direction model:
 * - Sub-screens (detail pages pushed from home): slide horizontally like Android's standard
 *   "push" — enter from right, exit to right on back, with a subtle depth scale so the
 *   incoming page feels like it is sliding over the previous one.
 * - Bottom-tab switches: cross-fade with subtle scale, no horizontal slide (tabs are peers).
 */
object NavAnimations {

    private const val PUSH_DURATION = 300
    private const val TAB_DURATION = 240

    private val pushSpec = tween<Float>(PUSH_DURATION, easing = FastOutSlowInEasing)
    private val tabSpec = tween<Float>(TAB_DURATION, easing = FastOutSlowInEasing)
    private val pushSlideSpec = tween<androidx.compose.ui.unit.IntOffset>(PUSH_DURATION, easing = FastOutSlowInEasing)

    /** Enter transition for a sub-screen being pushed onto the stack. */
    val pushEnter: EnterTransition =
        slideInHorizontally(animationSpec = pushSlideSpec) { it } +
                fadeIn(animationSpec = pushSpec) +
                scaleIn(initialScale = 0.98f, animationSpec = pushSpec)

    /** Exit transition when a sub-screen is popped (back). */
    val popExit: ExitTransition =
        slideOutHorizontally(animationSpec = pushSlideSpec) { it } +
                fadeOut(animationSpec = pushSpec) +
                scaleOut(targetScale = 0.98f, animationSpec = pushSpec)

    /** Exit transition when a sub-screen is covered by another push. */
    val pushExit: ExitTransition =
        slideOutHorizontally(animationSpec = pushSlideSpec) { -it / 4 } +
                fadeOut(animationSpec = pushSpec)

    /** Enter transition for the underlying screen re-appearing on pop. */
    val popEnter: EnterTransition =
        slideInHorizontally(animationSpec = pushSlideSpec) { -it / 4 } +
                fadeIn(animationSpec = pushSpec)

    /** Tab switch enter. */
    val tabEnter: EnterTransition =
        fadeIn(animationSpec = tabSpec) +
                scaleIn(
                    initialScale = 0.96f,
                    animationSpec = tabSpec
                )

    /** Tab switch exit. */
    val tabExit: ExitTransition =
        fadeOut(animationSpec = tabSpec) +
                scaleOut(
                    targetScale = 0.96f,
                    animationSpec = tabSpec
                )

    /**
     * Build a direction-aware transition spec for [AnimatedContent].
     *
     * @param isSubPush true when the target is a sub-screen push (horizontal slide),
     *                  false for peer tab switches (cross-fade + scale).
     * @param goingBack true when navigating backward (pop), false when forward (push).
     */
    fun transitionSpec(
        isSubPush: Boolean,
        goingBack: Boolean
    ): AnimatedContentTransitionScope<Int>.() -> ContentTransform {
        return {
            if (isSubPush) {
                if (goingBack) {
                    (popEnter togetherWith popExit)
                } else {
                    (pushEnter togetherWith pushExit)
                }
            } else {
                (tabEnter togetherWith tabExit)
            }
        }
    }
}
