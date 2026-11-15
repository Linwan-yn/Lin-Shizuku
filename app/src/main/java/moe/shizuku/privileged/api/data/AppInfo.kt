package moe.shizuku.privileged.api.data

import android.graphics.drawable.Drawable

data class AppInfo(
    val packageName: String,
    val name: String,
    val icon: Drawable?,
    val state: Int,  // 0=unknown, 1=granted/running, 2=asking, -1=denied
    val uid: Int
) {
    val isGranted: Boolean get() = state > 0
    val stateLabel: String
        get() = when (state) {
            1 -> "允许"
            2 -> "询问"
            -1 -> "拒绝"
            else -> "未知"
        }
}
