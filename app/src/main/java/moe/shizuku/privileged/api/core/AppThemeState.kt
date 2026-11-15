package moe.shizuku.privileged.api.core

import androidx.compose.runtime.mutableStateOf

/**
 * 全局主题/外观状态：设置页修改后直接更新 Compose State，
 * MainActivity 收集后必然重组，实现开关切换即时生效（无需重启/recreate）。
 * 初始值从 Prefs 读取，保证冷启动即正确。
 */
object AppThemeState {
    // 暴露 MutableState，MainActivity 以 by 订阅实现即时重组
    val darkModeOption = mutableStateOf(Prefs.darkModeOption)
    val amoled = mutableStateOf(Prefs.amoled)
    val dynamicColor = mutableStateOf(Prefs.dynamicColor)
    val glassMode = mutableStateOf(Prefs.glassMode)
    // 系统暗色模式（由 MainActivity 的 Configuration 回调维护）
    val systemNight = mutableStateOf(false)
}
