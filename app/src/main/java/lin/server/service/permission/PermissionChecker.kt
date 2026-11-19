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
        if (!LinApiConstants.isRecognizedPermission(permission)) {
            return false
        }
        if (uid == OsUtils.uid || pid == OsUtils.pid) {
            return true
        }
        // 归一到服务端存储键：标准 API_V23/V22/API 与 "shizuku" 同属 Shizuku 兼容权限位。
        val key = LinApiConstants.normalizePermissionKey(permission)
        // 90bp：走无锁只读缓存（getPermissionFlag）替代 synchronized find——高频权限检查
        // （Shizuku/Lin 生态应用每次 Binder 调用）不再阻塞 Binder 线程池。
        return when (configManager.getPermissionFlag(uid, key)) {
            ConfigManager.FLAG_GRANTED -> true
            ConfigManager.FLAG_DENIED -> false
            else -> {
                if (LinApiConstants.isRuntimePermission(key)) {
                    clientManager.requireClient(uid, pid).allowedMap[key] ?: false
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
