package moe.shizuku.privileged.api.util

/**
 * Environment helpers — migrated from Lin-Shizuku `EnvironmentUtils`.
 */
object EnvironmentUtils {

    /**
     * Read the ADB over TCP port configured via system properties
     * (service.adb.tcp.port / persist.adb.tcp.port). Returns -1 when not set.
     *
     * 1.1.1：新增无线调试连接端口读取——无线调试（Android 11+）把随机端口写入
     * `service.adb.wifi.tcp.port` 系统属性；部分 ROM 该属性缺失时再兜底读
     * `Settings.Global.adb_wifi_port`。这样连接端口无需 mDNS（NsdManager）即可获取，
     * 配对/启动流程不再触发 ACCESS_LOCAL_NETWORK 权限请求（本地网络保护）。
     */
    fun getAdbTcpPort(): Int {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val get = clazz.getMethod("getInt", String::class.java, Int::class.javaPrimitiveType)
            var port = get.invoke(null, "service.adb.tcp.port", -1) as Int
            // 90bt：0 = 显式关闭（usb: 后残留），视为未设置，继续兜底无线调试端口
            if (port <= 0) {
                port = get.invoke(null, "persist.adb.tcp.port", -1) as Int
            }
            if (port <= 0) {
                port = get.invoke(null, "service.adb.wifi.tcp.port", -1) as Int
            }
            if (port <= 0) {
                // 兜底：Settings.Global.adb_wifi_port（无线调试端口，应用读取无需权限）
                port = try {
                    val app = Class.forName("android.app.ActivityThread")
                        .getMethod("currentApplication").invoke(null) as? android.content.Context
                    if (app != null) {
                        android.provider.Settings.Global.getInt(
                            app.contentResolver, "adb_wifi_port", -1
                        )
                    } else -1
                } catch (_: Throwable) {
                    -1
                }
            }
            port
        } catch (_: Throwable) {
            -1
        }
    }
}
