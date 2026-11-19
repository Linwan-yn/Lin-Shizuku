package lin.server.service.permission

import android.os.Bundle
import rikka.hidden.compat.ActivityManagerApis
import rikka.hidden.compat.PermissionManagerApis
import lin.LinApiConstants
import lin.server.ClientManager
import lin.server.ConfigManager
import lin.server.util.Logger
import lin.server.util.UserHandleCompat.getUserId

class PermissionManager(
    private val clientManager: ClientManager,
    private val configManager: ConfigManager
) {
    private val confirmation = PermissionConfirmation()
    private val checker = PermissionChecker(clientManager, configManager)
    private val requester = PermissionRequester(clientManager, configManager, confirmation)

    companion object {
        private val LOGGER = Logger("PermissionManager")
    }

    fun checkSelfPermission(uid: Int, pid: Int, permission: String): Boolean =
        checker.checkSelfPermission(uid, pid, permission)

    fun requestPermission(uid: Int, pid: Int, permission: String, requestCode: Int) {
        requester.requestPermission(uid, pid, getUserId(uid), permission, requestCode)
    }

    fun shouldShowRequestPermissionRationale(uid: Int): Boolean =
        checker.shouldShowRequestPermissionRationale(uid)

    fun getSupportedPermissions(): Array<String> =
        arrayOf(LinApiConstants.PERMISSION_LIN, LinApiConstants.PERMISSION_SHIZUKU)

    fun dispatchPermissionResult(
        requestUid: Int,
        requestPid: Int,
        requestCode: Int,
        data: Bundle
    ) = requester.dispatchPermissionResult(requestUid, requestPid, requestCode, data)

    fun getFlagForUid(uid: Int, permission: String): Int =
        // 90bp：无锁只读缓存（getPermissionFlag）——Shizuku 生态高频 getFlagsForUid 不再阻塞配置锁
        configManager.getPermissionFlag(uid, LinApiConstants.normalizePermissionKey(permission))

    fun updateFlagForUid(uid: Int, permission: String, newFlag: Int) {
        val key = LinApiConstants.normalizePermissionKey(permission)
        for (record in clientManager.findClients(uid)) {
            val shouldStopApp = LinApiConstants.isRuntimePermission(key) &&
                    record.allowedMap[key] == true &&
                    newFlag != ConfigManager.FLAG_GRANTED

            if (shouldStopApp) {
                ActivityManagerApis.forceStopPackageNoThrow(record.packageName, getUserId(uid))
            }

            record.allowedMap[key] = newFlag == ConfigManager.FLAG_GRANTED
            record.onetimeMap[key] = false
        }

        configManager.updatePermission(uid, key, newFlag)
    }

    fun grantRuntimePermission(packageName: String, permissionName: String, userId: Int) {
        try {
            PermissionManagerApis.grantRuntimePermission(packageName, permissionName, userId)
        } catch (e: Exception) {
            throw RuntimeException("授予权限失败: ${e.message}", e)
        }
    }

    fun revokeRuntimePermission(packageName: String, permissionName: String, userId: Int) {
        try {
            PermissionManagerApis.revokeRuntimePermission(packageName, permissionName, userId)
        } catch (e: Exception) {
            throw RuntimeException("撤销权限失败: ${e.message}", e)
        }
    }
}
