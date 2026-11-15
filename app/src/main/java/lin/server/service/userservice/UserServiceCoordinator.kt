package lin.server.service.userservice

import android.os.Bundle
import android.os.IBinder
import com.lin.server.IUserServiceCallback
import lin.server.userservice.UserServiceManager

class UserServiceCoordinator(
    private val userServiceManager: UserServiceManager
) {
    fun startUserService(
        uid: Int,
        pid: Int,
        args: Bundle?,
        callback: IUserServiceCallback?
    ): String? = args?.let { userServiceManager.startUserService(uid, pid, it, callback) }

    fun stopUserService(token: String?) {
        token?.let { userServiceManager.stopUserService(it) }
    }

    fun attachUserService(binder: IBinder?, options: Bundle?) {
        if (binder != null && options != null) {
            userServiceManager.attachUserService(binder, options)
        }
    }

    fun getUserServiceCount(uid: Int): Int = userServiceManager.getUserServiceCount(uid)
}
