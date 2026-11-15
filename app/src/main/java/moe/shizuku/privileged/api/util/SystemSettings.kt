package moe.shizuku.privileged.api.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import moe.shizuku.privileged.api.core.Prefs
import moe.shizuku.privileged.api.core.ShizukuManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 即时应用需要在 Activity 级执行的系统设置（隐藏后台、高刷新率）。
 * MainActivity.onCreate 与设置页开关切换共用，保证切换即时生效且无需重建 Activity。
 */
object SystemSettings {

    private const val TAG = "SystemSettings"

    /** 76 轮：是否小米/红米（MIUI/HyperOS）机型。 */
    fun isMiuiHyperOs(): Boolean {
        val m = Build.MANUFACTURER?.lowercase() ?: ""
        return m.contains("xiaomi") || m.contains("redmi")
    }

    /** 76 轮：自启动设置入口——MIUI/HyperOS 跳安全中心自启动管理，其余回退应用详情页。 */
    fun openAutostartSettings(ctx: Context) {
        val ok = try {
            if (isMiuiHyperOs()) {
                val intent = Intent().apply {
                    component = android.content.ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity"
                    )
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(intent)
                true
            } else false
        } catch (e: Throwable) {
            Log.w(TAG, "自启动页跳转失败，回退应用详情页", e)
            false
        }
        if (!ok) openAppDetails(ctx)
    }

    /** 76/78 轮：电池优化豁免申请——无条件优先发 ACTION_REQUEST_IGNORE（HyperOS3 上直达「电量详情/省电策略」页），
     *  与设置页「电池优化管理」入口行为一致；不再先判断白名单后跳列表页（HyperOS3 会把列表页解析成「应用的电池使用情况」）。 */
    fun openBatteryOptimization(ctx: Context) {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            return
        } catch (_: Throwable) {}
        // 申请入口不可用 → 打开通用电池优化设置列表
        try {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
        } catch (e: Throwable) {
            Log.w(TAG, "电池优化设置打开失败，回退应用详情页", e)
            openAppDetails(ctx)
        }
    }

    /** 76 轮：应用详情页（回退兜底入口）。 */
    fun openAppDetails(ctx: Context) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
        } catch (e: Throwable) {
            Log.w(TAG, "应用详情页打开失败", e)
        }
    }

    fun applySystem(ctx: Context) {
        if (ctx !is Activity) return

        // 隐藏后台：从最近任务列表排除本应用；关闭时恢复显示
        try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.appTasks.forEach { it.setExcludeFromRecents(Prefs.hideRecents) }
        } catch (_: Throwable) {}

        // 高刷新率：按开关与档位应用窗口级 + 系统级刷新率
        try {
            val lp = ctx.window.attributes
            val display = ctx.window.windowManager.defaultDisplay
            val modes = display.supportedModes?.toList() ?: emptyList()
            val currentMode = display.mode
            val currentRate = currentMode.refreshRate

            // 按档位挑选显示模式：优先同分辨率，缺失再回退任意分辨率
            val best = pickDisplayMode(modes, currentMode, Prefs.highRefreshLevel)
            val maxRate = best?.refreshRate?.takeIf { it > 0f } ?: currentRate

            Log.d(
                TAG,
                "current id=${currentMode.modeId} rate=$currentRate; best id=${best?.modeId} " +
                    "rate=$maxRate; level=${Prefs.highRefreshLevel}"
            )

            if (Prefs.highRefresh) {
                // 应用窗口级刷新率
                if (best != null) lp.preferredDisplayModeId = best.modeId
                lp.preferredRefreshRate = maxRate
                ctx.window.attributes = lp

                // 系统级：通过 Shizuku 写全 ROM 刷新率设置，强制系统层面跑满
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val cmd = buildRefreshCommand(maxRate, on = true)
                        Log.d(TAG, "exec: $cmd")
                        ShizukuManager.runCommand(cmd)
                    } catch (e: Throwable) {
                        Log.w(TAG, "system refresh cmd failed", e)
                    }
                }
            } else {
                // 复位窗口级刷新率约束，回到系统默认
                lp.preferredDisplayModeId = 0
                lp.preferredRefreshRate = 0f
                ctx.window.attributes = lp

                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        ShizukuManager.runCommand(buildRefreshCommand(0f, on = false))
                    } catch (_: Throwable) {}
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "high refresh apply failed", e)
        }
    }

    /**
     * 按档位挑选显示模式。
     * level：0=设备最高；1/2/3=120/90/60Hz（优先不低于目标且最接近，缺失则取最接近）。
     * 优先在与当前同分辨率的模式中选择，避免切到不同分辨率的高刷模式。
     */
    private fun pickDisplayMode(
        modes: List<android.view.Display.Mode>,
        current: android.view.Display.Mode,
        level: Int
    ): android.view.Display.Mode? {
        if (modes.isEmpty()) return null
        val sameRes = modes.filter {
            it.refreshRate > 0f &&
                it.physicalWidth == current.physicalWidth &&
                it.physicalHeight == current.physicalHeight
        }
        val pool = sameRes.ifEmpty { modes.filter { it.refreshRate > 0f } }
        if (pool.isEmpty()) return modes.maxByOrNull { it.refreshRate }

        val target = when (level) {
            1 -> 120f
            2 -> 90f
            3 -> 60f
            else -> return pool.maxByOrNull { it.refreshRate }
        }
        val above = pool.filter { it.refreshRate >= target - 1f }
            .minByOrNull { kotlin.math.abs(it.refreshRate - target) }
        return above ?: pool.minByOrNull { kotlin.math.abs(it.refreshRate - target) }
    }

    /** 需要随刷新率档位一起写入/删除的系统设置键（namespace → key）。 */
    private fun refreshRateKeys(): List<Pair<String, String>> = listOf(
        "system" to "peak_refresh_rate",
        "system" to "min_refresh_rate",
        "global" to "peak_refresh_rate",
        "global" to "min_refresh_rate",
        "secure" to "miui_refresh_rate",
        "secure" to "user_refresh_rate",
        "system" to "user_refresh_rate",
        "secure" to "refresh_rate_mode",
        "secure" to "oplus_customize_screen_refresh_rate"
    )

    /** 构造开启/关闭系统刷新率设置的 shell 命令。 */
    private fun buildRefreshCommand(rate: Float, on: Boolean): String {
        val value = rate.toInt().coerceAtLeast(1)
        val parts = refreshRateKeys().map { (ns, key) ->
            if (on) "settings put $ns $key $value" else "settings delete $ns $key"
        }.toMutableList()
        parts += if (on) {
            listOf("settings put system speed_mode 1", "settings put secure speed_mode_enable 1")
        } else {
            listOf("settings delete system speed_mode", "settings delete secure speed_mode_enable")
        }
        return parts.joinToString(" && ")
    }
}
