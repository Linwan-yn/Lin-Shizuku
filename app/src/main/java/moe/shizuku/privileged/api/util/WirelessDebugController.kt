package moe.shizuku.privileged.api.util

import android.content.Context
import android.provider.Settings
import android.util.Log

/**
 * 无线调试控制器（移植自 Shizuku 分支版）。
 *
 * 通过 WRITE_SECURE_SETTINGS 权限直接控制无线调试开关，不需要 root。
 * 首次需要通过 ADB 授权一次：
 *   adb shell pm grant moe.shizuku.privileged.api android.permission.WRITE_SECURE_SETTINGS
 */
object WirelessDebugController {
    private const val TAG = "WirelessDebugController"

    /** 检查是否有 WRITE_SECURE_SETTINGS 权限。 */
    fun hasWriteSecureSettings(ctx: Context): Boolean {
        return try {
            ctx.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (e: Throwable) {
            Log.w(TAG, "检查 WRITE_SECURE_SETTINGS 失败", e)
            false
        }
    }

    /** 检查无线调试是否已开启。 */
    fun isWirelessDebuggingEnabled(ctx: Context): Boolean {
        return try {
            Settings.Global.getInt(ctx.contentResolver, "adb_wifi_enabled", 0) == 1
        } catch (e: Throwable) {
            Log.w(TAG, "读取 adb_wifi_enabled 失败", e)
            false
        }
    }

    /**
     * 开启无线调试（需要 WRITE_SECURE_SETTINGS 权限）。
     * @return true 表示成功设置，false 表示失败（无权限或异常）
     */
    fun enableWirelessDebugging(ctx: Context): Boolean {
        if (!hasWriteSecureSettings(ctx)) {
            Log.w(TAG, "无 WRITE_SECURE_SETTINGS 权限，无法开启无线调试")
            return false
        }
        return try {
            Settings.Global.putInt(ctx.contentResolver, "adb_wifi_enabled", 1)
            Log.i(TAG, "已通过 WRITE_SECURE_SETTINGS 开启无线调试")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "开启无线调试失败", e)
            false
        }
    }

    /**
     * 关闭无线调试（需要 WRITE_SECURE_SETTINGS 权限）。
     */
    fun disableWirelessDebugging(ctx: Context): Boolean {
        if (!hasWriteSecureSettings(ctx)) return false
        return try {
            Settings.Global.putInt(ctx.contentResolver, "adb_wifi_enabled", 0)
            true
        } catch (e: Throwable) {
            Log.e(TAG, "关闭无线调试失败", e)
            false
        }
    }

    /**
     * 确保无线调试已开启。
     * 如果已有 WRITE_SECURE_SETTINGS 权限但无线调试未开启，自动开启。
     * @return true 表示无线调试现在已开启（或本来就已开启）
     */
    fun ensureWirelessDebuggingEnabled(ctx: Context): Boolean {
        if (isWirelessDebuggingEnabled(ctx)) {
            return true
        }
        // 未开启，尝试通过 WRITE_SECURE_SETTINGS 开启
        return enableWirelessDebugging(ctx)
    }
}
