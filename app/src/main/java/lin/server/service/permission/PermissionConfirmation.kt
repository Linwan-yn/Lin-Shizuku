package lin.server.service.permission

import android.content.Intent
import android.content.pm.UserInfo
import android.os.Build
import rikka.hidden.compat.ActivityManagerApis
import rikka.hidden.compat.UserManagerApis
import lin.LinApiConstants
import lin.server.ClientRecord
import lin.server.ServerConstants
import lin.server.ServerConstants.MANAGER_APPLICATION_ID
import lin.server.util.Logger
import lin.server.util.PackageManagerCompat

class PermissionConfirmation {
    companion object {
        private val LOGGER = Logger("PermissionConfirmation")
    }

    fun showPermissionConfirmation(
        requestCode: Int,
        clientRecord: ClientRecord,
        callingUid: Int,
        callingPid: Int,
        userId: Int,
        permission: String = LinApiConstants.PERMISSION_LIN
    ) {
        val ai = PackageManagerCompat.getApplicationInfo(clientRecord.packageName, 0, userId)
            ?: return

        val pi = PackageManagerCompat.getPackageInfo(MANAGER_APPLICATION_ID, 0, userId)
        val userInfo = UserManagerApis.getUserInfo(userId)
        val isWorkProfileUser = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            userInfo.userType == "android.os.usertype.profile.MANAGED"
        } else {
            userInfo.flags and UserInfo.FLAG_MANAGED_PROFILE != 0
        }

        if (pi == null && !isWorkProfileUser) {
            LOGGER.w("在非工作配置文件用户 $userId 中未找到管理器，撤销权限")
            clientRecord.dispatchRequestPermissionResult(
                requestCode,
                allowed = false,
                onetime = false,
                permission = permission
            )
            return
        }

        val intent = Intent(ServerConstants.REQUEST_PERMISSION_ACTION)
            .setPackage(MANAGER_APPLICATION_ID)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
            .putExtra("uid", callingUid)
            .putExtra("pid", callingPid)
            .putExtra("requestCode", requestCode)
            .putExtra("applicationInfo", ai)
            .putExtra(
                "denyOnce",
                (System.currentTimeMillis() - (clientRecord.lastDenyTimeMap[permission] ?: 0)) > 10000
            )
            .putExtra("permission", permission)

        // 1.1.2：留一条可追溯的日志——「弹窗被系统拦掉」和「根本没弹」从此能区分
        LOGGER.i(
            "请求授权: uid=%d, pid=%d, pkg=%s, permission=%s, requestCode=%d, userId=%d, workProfile=%b",
            callingUid, callingPid, clientRecord.packageName, permission, requestCode, userId, isWorkProfileUser
        )
        ActivityManagerApis.startActivityNoThrow(intent, null, if (isWorkProfileUser) 0 else userId)
    }
}
