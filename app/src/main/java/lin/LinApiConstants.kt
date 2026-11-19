package lin



object LinApiConstants {
    /** 1.0.3：服务端 Binder 握手版本（attach-reply-version）。
     *  1.0.3 新增：notifyServerDied 死亡上报 + 保活底座自动申请；向前兼容旧客户端（apiVersion=-1 回退 12）。 */
    val SERVER_VERSION = 104

    /** 1.0.3：服务端 API 版本名（首页「服务版本」显示）。 */
    const val API_VERSION_NAME = "1.0.3"

    const val PERMISSION_KEY = "lin.permissions"
    const val ACTION_FOLLOW_STARTUP = "lin.intent.action.FOLLOW_STELLAR_STARTUP"
    const val PERMISSION_LIN = "lin"
    const val PERMISSION_FOLLOW_STARTUP = "follow_lin_startup"
    const val PERMISSION_SHIZUKU = "shizuku"

    val PERMISSIONS = arrayOf(
        PERMISSION_LIN,
        PERMISSION_FOLLOW_STARTUP
    )

    /** 标准 Shizuku SDK 注册时声明的 API 权限名（新版 V23 / 旧版 V22 / 经典 API）。 */
    val SHIZUKU_API_PERMISSIONS = setOf(
        "moe.shizuku.manager.permission.API_V23",
        "moe.shizuku.manager.permission.API_V22",
        "moe.shizuku.manager.permission.API"
    )

    /** 服务端认可的全部权限名：Lin 自有 + Shizuku 兼容 + 标准 API 名 + 运行时权限。 */
    fun isRecognizedPermission(permission: String?): Boolean {
        if (permission == null) return false
        if (PERMISSIONS.contains(permission)) return true
        if (permission == PERMISSION_SHIZUKU) return true
        if (SHIZUKU_API_PERMISSIONS.contains(permission)) return true
        return isRuntimePermission(permission)
    }

    /**
     * 归一化存储键：标准 API_V23/API_V22/API 与 "shizuku" 统一落到 "shizuku" 键，
     * 其余权限名原样返回。Shizuku 兼容应用无论用哪种权限名请求/回读，都读写同一权限位。
     */
    fun normalizePermissionKey(permission: String?): String {
        if (permission == null) return PERMISSION_LIN
        if (permission == PERMISSION_SHIZUKU || SHIZUKU_API_PERMISSIONS.contains(permission)) {
            return PERMISSION_SHIZUKU
        }
        return permission
    }

    fun isRuntimePermission(permission: String): Boolean =
        permission == PERMISSION_LIN || permission == PERMISSION_SHIZUKU || permission.endsWith(":runtime")

    const val BINDER_DESCRIPTOR = "com.lin.server.ILinService"
    const val BINDER_TRANSACTION_transact = 1

    const val BIND_APPLICATION_SERVER_VERSION = "lin:attach-reply-version"
    const val BIND_APPLICATION_SERVER_UID = "lin:attach-reply-uid"
    const val BIND_APPLICATION_SERVER_SECONTEXT = "lin:attach-reply-secontext"
    const val BIND_APPLICATION_PERMISSION_GRANTED = "lin:attach-reply-permission-granted"
    const val BIND_APPLICATION_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE = "lin:attach-reply-should-show-request-permission-rationale"

    const val REQUEST_PERMISSION_REPLY_ALLOWED = "lin:request-permission-reply-allowed"
    const val REQUEST_PERMISSION_REPLY_IS_ONETIME = "lin:request-permission-reply-is-onetime"
    const val REQUEST_PERMISSION_REPLY_PERMISSION = "lin:request-permission-reply-permission"

    const val ATTACH_APPLICATION_PACKAGE_NAME = "lin:attach-package-name"
    const val ATTACH_APPLICATION_API_VERSION = "lin:attach-api-version"
}
