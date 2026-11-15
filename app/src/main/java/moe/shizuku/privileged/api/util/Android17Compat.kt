package moe.shizuku.privileged.api.util

import android.content.Context
import android.os.Build
import android.os.IBinder
import android.os.ServiceManager
import java.lang.reflect.Method

/**
 * Android 17 (API 37) 兼容层
 *
 * 处理 Android 17 上的隐藏 API 变更和权限机制：
 * 1. ACCESS_LOCAL_NETWORK 权限检查与请求
 * 2. IPermissionManager.grantRuntimePermission 新增 deviceId 参数
 * 3. Settings.Global.ADB_ENABLED 假值读取问题
 */
object Android17Compat {

    private const val PERMISSION_ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

    /**
     * 检查是否需要请求本地网络权限（Android 17+）
     */
    fun needsLocalNetworkPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 37) return false
        return context.checkSelfPermission(PERMISSION_ACCESS_LOCAL_NETWORK) != android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /**
     * 请求本地网络权限
     * @return true 如果已授权或不需要请求，false 如果需要用户授权
     */
    fun requestLocalNetworkPermissionIfNeeded(activity: android.app.Activity, requestCode: Int): Boolean {
        if (!needsLocalNetworkPermission(activity)) return true
        if (Build.VERSION.SDK_INT >= 37) {
            try {
                val permissions = arrayOf(PERMISSION_ACCESS_LOCAL_NETWORK)
                activity.requestPermissions(permissions, requestCode)
            } catch (_: Throwable) {
                // 系统不支持该权限，忽略
            }
        }
        return false
    }

    /**
     * 安全获取 PermissionManager 服务
     * Android 17 上 IPermissionManager 接口有变更
     */
    fun getPermissionManager(): IBinder? {
        return try {
            ServiceManager.getService("permissionmgr")
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 安全调用 grantRuntimePermission
     * Android 17 上该方法新增了 deviceId 参数，通过反射兼容
     */
    fun grantRuntimePermission(
        packageName: String,
        permissionName: String,
        userId: Int
    ): Boolean {
        return try {
            val pm = getPermissionManager() ?: return false
            val clazz = Class.forName("android.content.pm.IPermissionManager\$Stub")
            val asInterface: Method = clazz.getMethod("asInterface", IBinder::class.java)
            val service = asInterface.invoke(null, pm) ?: return false

            // 尝试 Android 17+ 版本（带 deviceId 参数）
            try {
                val method = service.javaClass.getMethod(
                    "grantRuntimePermission",
                    String::class.java,
                    String::class.java,
                    Int::class.java,
                    Int::class.java  // deviceId
                )
                method.invoke(service, packageName, permissionName, userId, 0)
                true
            } catch (_: NoSuchMethodException) {
                // 回退到旧版本（不带 deviceId）
                val method = service.javaClass.getMethod(
                    "grantRuntimePermission",
                    String::class.java,
                    String::class.java,
                    Int::class.java
                )
                method.invoke(service, packageName, permissionName, userId)
                true
            }
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 安全读取 ADB_ENABLED 设置
     * Android 17 安全更新后，第三方应用读取 Settings.Global.ADB_ENABLED
     * 会被系统强制返回假值 0，即使持有 WRITE_SECURE_SETTINGS 权限。
     *
     * 解决方案：不依赖该值判断 USB 调试是否启用，
     * 而是直接尝试连接 ADB 端口，连接成功即说明已启用。
     */
    fun isAdbEnabled(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 37) {
            // Android 17+ 不再信任 Settings.Global.ADB_ENABLED 的读取结果
            // 直接返回 true，让后续连接逻辑自行判断
            return true
        }
        return try {
            android.provider.Settings.Global.getInt(
                context.contentResolver,
                android.provider.Settings.Global.ADB_ENABLED,
                0
            ) == 1
        } catch (_: Throwable) {
            false
        }
    }
}
