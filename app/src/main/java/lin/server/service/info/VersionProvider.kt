package lin.server.service.info

import android.content.pm.PackageInfo
import android.os.Build
import lin.server.ServerConstants.MANAGER_APPLICATION_ID
import lin.server.util.PackageManagerCompat

class VersionProvider {
    // 90bp：管理器 PackageInfo 启动缓存一次——getVersionName/getVersionCode 是高频 Binder
    // 调用（管理器首页/生态应用每次读取都触发），原实现每次调跨进程 PMS 查询（getPackageInfo），
    // 是服务端 CPU/事务热点。服务重启（或管理器更新触发服务重启）后自动刷新，无陈旧风险。
    @Volatile
    private var cachedPackageInfo: PackageInfo? = null

    private fun getManagerPackageInfo(): PackageInfo? {
        cachedPackageInfo?.let { return it }
        return try {
            PackageManagerCompat.getPackageInfo(MANAGER_APPLICATION_ID, 0, 0)?.also {
                cachedPackageInfo = it
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun getVersionName(): String = getManagerPackageInfo()?.versionName ?: "unknown"

    fun getVersionCode(): Int {
        val pi = getManagerPackageInfo() ?: return -1
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pi.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                pi.versionCode
            }
        } catch (_: Exception) {
            -1
        }
    }
}
