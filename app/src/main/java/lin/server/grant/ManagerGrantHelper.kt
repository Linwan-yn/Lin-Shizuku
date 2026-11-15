package lin.server.grant

import android.Manifest
import android.content.pm.PackageManager
import android.provider.Settings
import rikka.hidden.compat.PermissionManagerApis
import lin.server.ServerConstants.MANAGER_APPLICATION_ID
import lin.server.util.Logger

object ManagerGrantHelper {
    private val LOGGER = Logger("ManagerGrantHelper")

    fun grantWriteSecureSettings(managerAppId: Int) {
        try {
            if (isGranted(managerAppId)) {
                LOGGER.i("Manager already has WRITE_SECURE_SETTINGS")
                return
            }

            LOGGER.i("Granting WRITE_SECURE_SETTINGS to manager...")
            var granted = false
            var lastResult = ""
            // 1.1.2：原实现只 exec 一次且不检查退出码，失败也记 "completed"——
            // 权限没拿到却看不出来。改为绝对路径 + 退出码 + 授权结果复核 + 两次重试。
            for (attempt in 1..2) {
                val (code, out) = execGrant(
                    "/system/bin/pm", "grant", MANAGER_APPLICATION_ID,
                    Manifest.permission.WRITE_SECURE_SETTINGS
                )
                lastResult = "exit=$code ${out.take(120)}"
                // 1.1.2（关键修正）：pm 包装脚本在 SecurityException 时也可能返回 0，
                // 退出码不可信——权限未在 AndroidManifest 声明时就是「exit=0 但始终 DENIED」，
                // 日志照样打印 completed，界面上却一直未授予。一律以系统查询结果为准。
                if (isGranted(managerAppId)) {
                    granted = true
                    break
                }
                LOGGER.w("pm grant 第 %d 次未生效: %s", attempt, lastResult)
                Thread.sleep(600)
            }
            // Root 兜底：pm grant 在个别 ROM 的 shell 域会被 SELinux 拒绝
            if (!granted) {
                val (code, out) = execGrant(
                    "/system/bin/su", "-c",
                    "/system/bin/pm grant $MANAGER_APPLICATION_ID ${Manifest.permission.WRITE_SECURE_SETTINGS}"
                )
                if (isGranted(managerAppId)) {
                    granted = true
                } else {
                    LOGGER.w("su grant 未生效: exit=%d %s", code, out.take(120))
                }
            }

            if (granted) LOGGER.i("WRITE_SECURE_SETTINGS grant completed")
            else LOGGER.e("WRITE_SECURE_SETTINGS grant failed: $lastResult")
        } catch (e: Throwable) {
            LOGGER.e(e, "Failed to grant WRITE_SECURE_SETTINGS")
        }
    }

    /** 当前是否已持有权限（授权后重新查询，避免只信退出码）。 */
    private fun isGranted(uid: Int): Boolean = try {
        PermissionManagerApis.checkPermission(
            Manifest.permission.WRITE_SECURE_SETTINGS, uid
        ) == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    /** 执行命令并取回退出码与合并输出（redirectErrorStream 防止读取死锁）。 */
    private fun execGrant(vararg cmd: String): Pair<Int, String> {
        val process = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText().trim()
        return process.waitFor() to out
    }

    fun grantAccessibilityService() {
        try {
            // 90ba：服务名必须与 AndroidManifest 实际注册的无障碍服务一致。
            // 旧值 .service.LinAccessibilityService 是不存在的类——写入后系统
            // 不会启用任何无障碍服务，「无障碍自启」配置开了也无效。
            val serviceName = "$MANAGER_APPLICATION_ID/.core.LinAccessibilityService"

            val process = Runtime.getRuntime().exec(
                arrayOf(
                    "settings", "get", "secure",
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                )
            )
            val currentServices = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()

            val services = currentServices
                .split(":")
                .map { it.trim() }
                .filter { it.isNotEmpty() && it != "null" }
                .toMutableSet()

            if (services.contains(serviceName)) {
                LOGGER.i("Accessibility service already enabled")
                return
            }

            services.add(serviceName)
            val newServices = services.joinToString(":")

            LOGGER.i("Granting accessibility service...")
            Runtime.getRuntime().exec(
                arrayOf(
                    "settings", "put", "secure",
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    newServices
                )
            ).waitFor()
            Runtime.getRuntime().exec(
                arrayOf(
                    "settings", "put", "secure",
                    "accessibility_enabled",
                    "1"
                )
            ).waitFor()
            LOGGER.i("Accessibility service granted")
        } catch (e: Throwable) {
            LOGGER.e(e, "Failed to grant accessibility service")
        }
    }
}
