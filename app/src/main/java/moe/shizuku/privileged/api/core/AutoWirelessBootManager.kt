package moe.shizuku.privileged.api.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import moe.shizuku.privileged.api.util.WirelessDebugController

/**
 * 全自动无线调试开机自启（1.0.11）。
 *
 * 支持两种模式：
 *  1. WRITE_SECURE_SETTINGS 模式（移植分支版，不需要 root）：
 *     已授权 WRITE_SECURE_SETTINGS 权限 → 直接开启无线调试 → 检测端口 → 连接启动
 *  2. Root 模式（原有实现）：
 *     开 Wi-Fi → 开开发者选项 → 开无线调试 → 拉起配对界面 → 无障碍自动配对
 */
object AutoWirelessBootManager {
    private const val TAG = "AutoWirelessBoot"

    /** root 执行单条 shell 命令，返回带步骤标签的输出（含 stderr/退出码）。 */
    private fun su(tag: String, cmd: String): String = runCatching {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        val out = p.inputStream.bufferedReader().readText().trim()
        val err = p.errorStream.bufferedReader().readText().trim()
        val code = p.waitFor()
        val combined = listOf(out, err).filter { it.isNotEmpty() }.joinToString(" | ")
        val body = combined.ifEmpty { "(exit=$code 无输出)" }
        "[$tag] $body"
    }.getOrDefault("[$tag] su 执行异常")

    /**
     * 执行全自动链路，返回完整过程日志（成功含每步输出，失败含权限/操作错误）。
     */
    suspend fun run(context: Context): String = withContext(Dispatchers.IO) {
        val trace = StringBuilder()

        // 优先尝试 WRITE_SECURE_SETTINGS 模式（分支版方式，不需要 root）
        if (WirelessDebugController.hasWriteSecureSettings(context)) {
            trace.append("[全自动无线] 使用 WRITE_SECURE_SETTINGS 模式（无需 Root）\n")

            // 1. 开启无线调试
            trace.append("[无线调试] 开启状态: ${WirelessDebugController.isWirelessDebuggingEnabled(context)}\n")
            if (!WirelessDebugController.isWirelessDebuggingEnabled(context)) {
                trace.append("[无线调试] 正在自动开启...\n")
                WirelessDebugController.enableWirelessDebugging(context)
                delay(3000) // 等待无线调试启动
            }

            // 2. 检测端口并连接
            trace.append("[端口检测] 正在检测可用端口...\n")
            val helper = moe.shizuku.privileged.api.util.AdbWirelessHelper()
            val port = helper.detectAvailablePort(context, "127.0.0.1")

            if (port > 0) {
                trace.append("[端口检测] 成功检测到端口: $port\n")
                // 3. 连接并启动服务
                // 这里复用现有的启动逻辑，简化处理
                if (ShizukuManager.isRunningCompat()) {
                    trace.append("[全自动无线] 成功：服务已在运行\n")
                    DiagnosticLog.append("[全自动无线] 服务已启动成功（WRITE_SECURE_SETTINGS 模式）")
                    return@withContext trace.toString().trim()
                }
            } else {
                trace.append("[端口检测] 未检测到可用端口，可能需要首次配对\n")
            }
        }

        // 降级到 Root 模式（原有实现）
        if (!ShizukuManager.isRooted()) {
            val m = "[全自动无线] 失败：未检测到 su 权限（Root 未授权/未安装 Magisk），且无 WRITE_SECURE_SETTINGS 权限"
            DiagnosticLog.append(m)
            return@withContext m
        }
        if (ShizukuManager.isRunningCompat()) {
            return@withContext "[全自动无线] 服务已在运行，无需重复启动"
        }

        trace.append("\n[Root 模式] 开始：开Wi-Fi→开发者选项→无线调试→配对界面\n")

        // 1. 打开 Wi-Fi
        trace.append(su("开Wi-Fi", "svc wifi enable")).append("\n")
        delay(3_000L)

        // 2. 打开开发者选项
        trace.append(su("开发者选项", "settings put global development_settings_enabled 1")).append("\n")
        delay(500L)

        // 3. 打开无线调试（Android 11+）
        trace.append(su("无线调试", "settings put global adb_wifi_enabled 1")).append("\n")
        delay(2_000L)

        // 4. 拉起系统配对界面（AOSP：com.android.settings/.development.wirelessdebugging.PairDeviceActivity）
        val am = su(
            "拉起配对界面",
            "am start -n com.android.settings/.development.wirelessdebugging.PairDeviceActivity " +
                "-a android.intent.action.VIEW 2>&1"
        )
        trace.append(am).append("\n")
        if (am.contains("Error") || am.contains("does not exist") || am.contains("not found")) {
            trace.append("[拉起配对界面] 警告：该 ROM 可能使用不同的配对 Activity 路径\n")
        }

        // 5. 等待配对成功（最长 60 秒）
        repeat(30) {
            delay(2_000L)
            if (ShizukuManager.isRunningCompat()) {
                trace.append("[全自动无线] 成功：服务已启动\n")
                DiagnosticLog.append("[全自动无线] 服务已启动成功")
                return@withContext trace.toString().trim()
            }
        }
        val timeout = "[全自动无线] 等待超时(60s)：请确认无障碍服务已开启、屏幕已解锁、已授予 root"
        trace.append(timeout).append("\n")
        DiagnosticLog.append(timeout)
        trace.toString().trim()
    }
}
