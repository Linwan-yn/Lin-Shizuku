package moe.shizuku.privileged.api.util

import android.app.Activity
import moe.shizuku.privileged.api.R

/**
 * Unified activity transition animations.
 *
 * Convention (iOS-style push/pop):
 * - Forward (push):  new page slides in from the RIGHT, old page fades slightly.
 * - Backward (pop): current page slides out to the RIGHT, old page fades back in.
 *
 * All secondary activities (browser, script editor, output) use this pair
 * so the direction is consistent everywhere in the app.
 */
object ActivityTransitions {

    /** Call immediately after [Activity.startActivity] for a forward push. */
    fun overrideForward(activity: Activity) {
        activity.overridePendingTransition(
            R.anim.activity_slide_in_right,
            R.anim.activity_fade_out
        )
    }

    /** Call immediately before/after [Activity.finish] for a backward pop. */
    fun overrideBackward(activity: Activity) {
        activity.overridePendingTransition(
            R.anim.activity_fade_in,
            R.anim.activity_slide_out_right
        )
    }

    /** Finish the activity with the backward pop animation. */
    fun finishWithAnimation(activity: Activity) {
        activity.finish()
        overrideBackward(activity)
    }
}
