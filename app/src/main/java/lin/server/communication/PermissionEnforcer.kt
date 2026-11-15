package lin.server.communication

import android.os.Binder
import android.system.Os
import lin.LinApiConstants
import lin.server.ClientManager
import lin.server.ConfigManager
import lin.server.util.Logger
import lin.server.util.OsUtils
import lin.server.util.UserHandleCompat.getAppId

class PermissionEnforcer(
    private val clientManager: ClientManager,
    private val configManager: ConfigManager,
    private val managerAppId: Int
) {
    companion object {
        private val LOGGER = Logger("PermissionEnforcer")
    }

    fun isManager(caller: CallerContext): Boolean = getAppId(caller.uid) == managerAppId

    fun isSelf(caller: CallerContext): Boolean = caller.uid == OsUtils.uid || caller.pid == Os.getpid()

    fun hasPermission(
        caller: CallerContext,
        permission: String = LinApiConstants.PERMISSION_LIN
    ): Boolean {
        if (isSelf(caller) || isManager(caller)) return true

        val clientRecord = clientManager.findClient(caller.uid, caller.pid) ?: return false

        if (LinApiConstants.isRuntimePermission(permission)) {
            return clientRecord.allowedMap[permission] == true
        }

        // 90bp：非 runtime 权限走无锁只读缓存（getPermissionFlag），避免高频 Binder 调用阻塞配置锁
        return configManager.getPermissionFlag(caller.uid, permission) == ConfigManager.FLAG_GRANTED
    }

    fun enforceManager(caller: CallerContext, func: String) {
        if (isSelf(caller) || isManager(caller)) return

        val msg = "Permission Denial: $func from pid=${caller.pid}, uid=${caller.uid} is not manager"
        LOGGER.w(msg)
        throw SecurityException(msg)
    }

    fun enforcePermission(
        caller: CallerContext,
        func: String,
        permission: String = LinApiConstants.PERMISSION_LIN
    ) {
        if (isSelf(caller) || isManager(caller)) return

        val clientRecord = clientManager.findClient(caller.uid, caller.pid)
        if (clientRecord == null) {
            val msg = "Permission Denial: $func from pid=${caller.pid}, uid=${caller.uid} is not an attached client"
            LOGGER.w(msg)
            throw SecurityException(msg)
        }

        if (clientRecord.allowedMap[permission] != true) {
            val msg = "Permission Denial: $func from pid=${caller.pid}, uid=${caller.uid} requires permission $permission"
            LOGGER.w(msg)
            throw SecurityException(msg)
        }
    }
}
