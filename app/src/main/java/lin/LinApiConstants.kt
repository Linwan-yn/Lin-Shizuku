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

    val PERMISSIONS = arrayOf(
        PERMISSION_LIN,
        PERMISSION_FOLLOW_STARTUP
    )

    fun isRuntimePermission(permission: String): Boolean =
        permission == PERMISSION_LIN || permission == "shizuku" || permission.endsWith(":runtime")

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
