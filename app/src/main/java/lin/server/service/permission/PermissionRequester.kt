package lin.server.service.permission

import android.os.Bundle
import rikka.hidden.compat.PackageManagerApis
import lin.LinApiConstants
import lin.server.ClientManager
import lin.server.ClientRecord
import lin.server.ConfigManager
import lin.server.util.Logger

class PermissionRequester(
    private val clientManager: ClientManager,
    private val configManager: ConfigManager,
    private val confirmation: PermissionConfirmation
) {
    companion object {
        private val LOGGER = Logger("PermissionRequester")
    }
    fun requestPermission(
        uid: Int,
        pid: Int,
        userId: Int,
        permission: String,
        requestCode: Int
    ) {
        // 1.1.2（授权弹窗缺失修复）：attachApplication 与 requestPermission 是两个独立的
        // Binder 事务，应用刚拿到 Binder 就点「允许」时，attach 可能还在别的 Binder 线程里
        // 没登记完。原实现 requireClient(uid, pid) 匹配不上就抛 IllegalStateException，
        // 应用侧的 requestPermission 会把这个异常吞掉 → 用户看到「询问」却永远不弹窗，
        // 只有先切一次允许/拒绝（触发补发 Binder、应用重新 attach）后再请求才正常。
        // 这里按「精确 pid → 同 uid 任一记录 → 临时记录」三级兜底，保证任何时序下都能弹窗。
        val clientRecord = clientManager.findClient(uid, pid)
            ?: clientManager.findClients(uid).firstOrNull()
            ?: run {
                val pkg = PackageManagerApis.getPackagesForUidNoThrow(uid).firstOrNull()
                if (pkg == null) {
                    LOGGER.w(
                        "requestPermission: uid=%d, pid=%d 无 attach 记录且取不到包名，忽略请求",
                        uid, pid
                    )
                    return
                }
                LOGGER.w(
                    "requestPermission: uid=%d, pid=%d 尚无 attach 记录，用临时记录弹窗 (pkg=%s)",
                    uid, pid, pkg
                )
                // 临时记录只用于弹窗（showPermissionConfirmation 只用 packageName 与
                // lastDenyTimeMap），不注册进 clientManager，避免污染 attach 状态。
                ClientRecord(uid, pid, null, pkg, 0)
            }

        if (!LinApiConstants.isRecognizedPermission(permission)) {
            LOGGER.w("requestPermission: 未知权限 %s, uid=%d", permission, uid)
            clientRecord.dispatchRequestPermissionResult(
                requestCode,
                allowed = false,
                onetime = false,
                permission
            )
            return
        }
        val key = LinApiConstants.normalizePermissionKey(permission)
        val flag = configManager.find(uid)?.permissions?.get(key)
        LOGGER.d(
            "requestPermission: uid=%d, pid=%d, permission=%s, key=%s, requestCode=%d, flag=%s",
            uid, pid, permission, key, requestCode, flag?.toString() ?: "null"
        )
        when (flag) {
            ConfigManager.FLAG_GRANTED -> {
                clientRecord.dispatchRequestPermissionResult(
                    requestCode,
                    allowed = true,
                    onetime = false,
                    permission
                )
            }
            ConfigManager.FLAG_DENIED -> {
                clientRecord.dispatchRequestPermissionResult(
                    requestCode,
                    allowed = false,
                    onetime = false,
                    permission
                )
            }
            else -> {
                confirmation.showPermissionConfirmation(
                    requestCode,
                    clientRecord,
                    uid,
                    pid,
                    userId,
                    permission
                )
            }
        }
    }

    fun dispatchPermissionResult(
        requestUid: Int,
        requestPid: Int,
        requestCode: Int,
        data: Bundle
    ) {
        val allowed = data.getBoolean(LinApiConstants.REQUEST_PERMISSION_REPLY_ALLOWED)
        val onetime = data.getBoolean(LinApiConstants.REQUEST_PERMISSION_REPLY_IS_ONETIME)
        val permission = data.getString(
            LinApiConstants.REQUEST_PERMISSION_REPLY_PERMISSION,
            LinApiConstants.PERMISSION_LIN
        )

        LOGGER.i(
            "dispatchPermissionResult: uid=$requestUid, pid=$requestPid, " +
                    "requestCode=$requestCode, allowed=$allowed, onetime=$onetime, permission=$permission"
        )

        val records = clientManager.findClients(requestUid)
        val packages = ArrayList<String>()
        val key = LinApiConstants.normalizePermissionKey(permission)

        if (records.isEmpty()) {
            LOGGER.w("dispatchPermissionResult: 未找到 uid $requestUid 的客户端")
            packages.addAll(PackageManagerApis.getPackagesForUidNoThrow(requestUid))
        } else {
            for (record in records) {
                packages.add(record.packageName)
                if (LinApiConstants.isRuntimePermission(key)) {
                    record.allowedMap[key] = allowed
                }
                if (record.pid == requestPid) {
                    record.dispatchRequestPermissionResult(
                        requestCode,
                        allowed,
                        onetime,
                        permission
                    )
                }
            }
        }

        configManager.update(requestUid, packages)
        configManager.updatePermission(
            requestUid,
            key,
            when {
                onetime -> ConfigManager.FLAG_ASK
                allowed -> ConfigManager.FLAG_GRANTED
                else -> ConfigManager.FLAG_DENIED
            }
        )
    }
}
