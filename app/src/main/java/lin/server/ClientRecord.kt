package lin.server

import android.os.Bundle
import com.lin.server.ILinApplication
import moe.shizuku.server.IShizukuApplication
import lin.LinApiConstants
import lin.server.shizuku.ShizukuApiConstants
import lin.server.util.Logger
import java.util.concurrent.ConcurrentHashMap

open class ClientRecord(
    val uid: Int,
    val pid: Int,
    val client: ILinApplication?,
    val packageName: String,
    val apiVersion: Int
) {
    val attachTime: Long = System.currentTimeMillis()
    var shizukuApplication: IShizukuApplication? = null

    // 90bp：三个权限/状态 map 由普通 HashMap 改为 ConcurrentHashMap——
    // Binder 线程池会并发读写（attach 初始化、权限弹窗回调、各应用 checkPermission 读），
    // 原实现存在并发写竞态（ConcurrentModificationException/脏读）导致偶发崩溃或权限误判。
    val lastDenyTimeMap: MutableMap<String, Long> = ConcurrentHashMap()

    val allowedMap: MutableMap<String, Boolean> = ConcurrentHashMap()

    val onetimeMap: MutableMap<String, Boolean> = ConcurrentHashMap()

    fun dispatchRequestPermissionResult(
        requestCode: Int,
        allowed: Boolean,
        onetime: Boolean,
        permission: String = LinApiConstants.PERMISSION_LIN
    ) {
        if (!allowed) lastDenyTimeMap[permission] = System.currentTimeMillis()

        val reply = Bundle().apply {
            putBoolean(LinApiConstants.REQUEST_PERMISSION_REPLY_ALLOWED, allowed)
            putBoolean(LinApiConstants.REQUEST_PERMISSION_REPLY_IS_ONETIME, onetime)
            putString(LinApiConstants.REQUEST_PERMISSION_REPLY_PERMISSION, permission)
        }

        try {
            client?.dispatchRequestPermissionResult(requestCode, reply)
        } catch (e: Throwable) {
            LOGGER.w(e, "dispatchRequestPermissionResult failed for client (uid=%d, pid=%d, package=%s)", uid, pid, packageName)
        }
    }

    fun dispatchShizukuPermissionResult(requestCode: Int, allowed: Boolean, serviceUid: Int, serviceVersion: Int, serviceSeContext: String?) {
        val app = shizukuApplication ?: return
        if (!allowed) lastDenyTimeMap[ShizukuApiConstants.PERMISSION_NAME] = System.currentTimeMillis()

        try {
            app.dispatchRequestPermissionResult(requestCode, Bundle().apply {
                putBoolean(ShizukuApiConstants.REQUEST_PERMISSION_REPLY_ALLOWED, allowed)
            })
            LOGGER.i("已通知 Shizuku 客户端权限结果: uid=$uid, pid=$pid, allowed=$allowed")

            if (allowed) {
                val replyServerVersion = if (apiVersion == -1) 12 else ShizukuApiConstants.SERVER_VERSION
                app.bindApplication(Bundle().apply {
                    putInt(ShizukuApiConstants.BindApplication.SERVER_UID, serviceUid)
                    putInt(ShizukuApiConstants.BindApplication.SERVER_VERSION, replyServerVersion)
                    putInt(ShizukuApiConstants.BindApplication.SERVER_PATCH_VERSION, ShizukuApiConstants.SERVER_PATCH_VERSION)
                    putString(ShizukuApiConstants.BindApplication.SERVER_SECONTEXT, serviceSeContext)
                    putBoolean(ShizukuApiConstants.BindApplication.PERMISSION_GRANTED, true)
                    putBoolean(ShizukuApiConstants.BindApplication.SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE, false)
                })
                LOGGER.i("已重新绑定 Shizuku 客户端: uid=$uid, pid=$pid, granted=true, version=$replyServerVersion")
            }
        } catch (e: Throwable) {
            LOGGER.w(e, "dispatchShizukuPermissionResult failed for client (uid=%d, pid=%d)", uid, pid)
        }
    }

    companion object {
        protected val LOGGER: Logger = Logger("ClientRecord")
    }
}
