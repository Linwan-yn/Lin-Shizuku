package lin.server.service.permission

import lin.LinApiConstants
import lin.server.ClientManager
import lin.server.ConfigManager
import lin.server.util.OsUtils

class PermissionChecker(
    private val clientManager: ClientManager,
    private val configManager: ConfigManager
) {
    fun checkSelfPermission(uid: Int, pid: Int, permission: String): Boolean {
        if (!LinApiConstants.PERMISSIONS.contains(permission)) {
            return false
        }
        if (uid == OsUtils.uid || pid == OsUtils.pid) {
            return true
        }
        // 90bp：走无锁只读缓存（getPermissionFlag）替代 synchronized find——高频权限检查
        // （Shizuku/Lin 生态应用每次 Binder 调用）不再阻塞 Binder 线程池。
        return when (configManager.getPermissionFlag(uid, permission)) {
            ConfigManager.FLAG_GRANTED -> true
            ConfigManager.FLAG_DENIED -> false
            else -> {
                if (LinApiConstants.isRuntimePermission(permission)) {
                    clientManager.requireClient(uid, pid).allowedMap[permission] ?: false
                } else {
                    false
                }
            }
        }
    }

    fun shouldShowRequestPermissionRationale(uid: Int): Boolean =
        configManager.getPermissionFlag(uid, LinApiConstants.PERMISSION_LIN) ==
            ConfigManager.FLAG_DENIED
}
