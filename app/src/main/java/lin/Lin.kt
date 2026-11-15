package lin

import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.IBinder.DeathRecipient
import android.os.Looper
import android.os.Parcel
import android.os.RemoteException
import android.util.Log
import androidx.annotation.RestrictTo
import com.lin.server.ILinApplication
import com.lin.server.ILinService
import lin.Lin.addBinderDeadListener
import lin.Lin.addBinderReceivedListener
import lin.Lin.addBinderReceivedListenerSticky
import lin.Lin.addRequestPermissionResultListener
import lin.Lin.removeRequestPermissionResultListener
import lin.Lin.requestPermission
import java.util.Objects

object Lin {
    @get:RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
    var binder: IBinder? = null

    private var service: ILinService? = null

    private var serverUid = -1

    private var serverApiVersion = -1

    private var serverContext: String? = null

    private var permissionGranted = false

    private var shouldShowRequestPermissionRationale = false

    private var binderReady = false

    private var serviceStarted = false

    private var packageName: String? = null


    private val Lin_APPLICATION: ILinApplication = object : ILinApplication.Stub() {
        override fun bindApplication(data: Bundle) {
            serverUid = data.getInt(LinApiConstants.BIND_APPLICATION_SERVER_UID, -1)
            serverApiVersion = data.getInt(LinApiConstants.BIND_APPLICATION_SERVER_VERSION, -1)
            serverContext = data.getString(LinApiConstants.BIND_APPLICATION_SERVER_SECONTEXT)
            permissionGranted =
                data.getBoolean(LinApiConstants.BIND_APPLICATION_PERMISSION_GRANTED, false)
            shouldShowRequestPermissionRationale = data.getBoolean(
                LinApiConstants.BIND_APPLICATION_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE,
                false
            )

            scheduleBinderReceivedListeners()
        }

        override fun dispatchRequestPermissionResult(requestCode: Int, data: Bundle) {
            val allowed =
                data.getBoolean(LinApiConstants.REQUEST_PERMISSION_REPLY_ALLOWED, false)
            val onetime =
                data.getBoolean(LinApiConstants.REQUEST_PERMISSION_REPLY_IS_ONETIME, false)
            val permission = data.getString(
                LinApiConstants.REQUEST_PERMISSION_REPLY_PERMISSION,
                LinApiConstants.PERMISSION_LIN
            )
            if (permission == LinApiConstants.PERMISSION_LIN) {
                permissionGranted = allowed
                if (allowed) {
                    shouldShowRequestPermissionRationale = false
                }
            }
            scheduleRequestPermissionResultListener(
                requestCode,
                allowed,
                onetime
            )
        }

        override fun onServiceStarted() {
            serviceStarted = true
            scheduleServiceStartedListeners()
        }
    }

    private val DEATH_RECIPIENT = DeathRecipient {
        onBinderReceived(null, null)
    }

    @Throws(RemoteException::class)
    private fun attachApplication(binder: IBinder, packageName: String?): Boolean {
        var result: Boolean

        val args = Bundle()
        args.putInt(
            LinApiConstants.ATTACH_APPLICATION_API_VERSION,
            LinApiConstants.SERVER_VERSION
        )
        args.putString(LinApiConstants.ATTACH_APPLICATION_PACKAGE_NAME, packageName)

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken("com.lin.server.ILinService")
            data.writeStrongBinder(Lin_APPLICATION.asBinder())
            data.writeInt(1)
            args.writeToParcel(data, 0)
            result = binder.transact(
                18,
                data,
                reply,
                0
            )
            reply.readException()
        } finally {
            reply.recycle()
            data.recycle()
        }

        return result
    }


    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
    fun onBinderReceived(newBinder: IBinder?, packageName: String?) {
        if (binder === newBinder) return

        if (newBinder == null) {
            binder = null
            service = null
            serverUid = -1
            serverApiVersion = -1
            serverContext = null
            permissionGranted = false
            shouldShowRequestPermissionRationale = false
            binderReady = false
            serviceStarted = false
            this.packageName = null

            scheduleBinderDeadListeners()
        } else {
            val oldBinder = binder
            if (oldBinder != null) {
                oldBinder.unlinkToDeath(DEATH_RECIPIENT, 0)
            }
            binder = newBinder
            service = ILinService.Stub.asInterface(newBinder)
            this.packageName = packageName

            try {
                newBinder.linkToDeath(DEATH_RECIPIENT, 0)
            } catch (_: Throwable) {
            }

            try {
                if (!attachApplication(newBinder, packageName)) {
                    onBinderReceived(null, null)
                }
            } catch (e: Throwable) {
                Log.w("LinApplication", Log.getStackTraceString(e))
                try {
                    newBinder.unlinkToDeath(DEATH_RECIPIENT, 0)
                } catch (_: Throwable) {
                }
                onBinderReceived(null, null)
            }
        }
    }

    private val RECEIVED_LISTENERS: MutableList<ListenerHolder<OnBinderReceivedListener>> =
        ArrayList<ListenerHolder<OnBinderReceivedListener>>()
    private val DEAD_LISTENERS: MutableList<ListenerHolder<OnBinderDeadListener>> =
        ArrayList<ListenerHolder<OnBinderDeadListener>>()
    private val PERMISSION_LISTENERS: MutableList<ListenerHolder<OnRequestPermissionResultListener>> =
        ArrayList<ListenerHolder<OnRequestPermissionResultListener>>()
    private val SERVICE_STARTED_LISTENERS: MutableList<ListenerHolder<OnServiceStartedListener>> =
        ArrayList<ListenerHolder<OnServiceStartedListener>>()
    private val MAIN_HANDLER = Handler(Looper.getMainLooper())


    fun addBinderReceivedListener(
        listener: OnBinderReceivedListener,
        handler: Handler? = null
    ) {
        addBinderReceivedListener(
            listener,
            false,
            handler
        )
    }

    fun addBinderReceivedListenerSticky(
        listener: OnBinderReceivedListener,
        handler: Handler? = null
    ) {
        addBinderReceivedListener(
            listener = listener,
            sticky = true,
            handler = handler
        )
    }

    private fun addBinderReceivedListener(
        listener: OnBinderReceivedListener,
        sticky: Boolean,
        handler: Handler?
    ) {
        if (sticky && binderReady) {
            if (handler != null) {
                handler.post { listener.onBinderReceived() }
            } else if (Looper.myLooper() == Looper.getMainLooper()) {
                listener.onBinderReceived()
            } else {
                MAIN_HANDLER.post { listener.onBinderReceived() }
            }
        }
        synchronized(RECEIVED_LISTENERS) {
            RECEIVED_LISTENERS.add(ListenerHolder(listener, handler))
        }
    }

    fun removeBinderReceivedListener(listener: OnBinderReceivedListener): Boolean {
        synchronized(RECEIVED_LISTENERS) {
            return RECEIVED_LISTENERS.removeIf { it.listener === listener }
        }
    }

    private fun scheduleBinderReceivedListeners() {
        synchronized(RECEIVED_LISTENERS) {
            for (holder in RECEIVED_LISTENERS) {
                if (holder.handler != null) {
                    holder.handler.post { holder.listener.onBinderReceived() }
                } else {
                    if (Looper.myLooper() == Looper.getMainLooper()) {
                        holder.listener.onBinderReceived()
                    } else {
                        MAIN_HANDLER.post { holder.listener.onBinderReceived() }
                    }
                }
            }
        }
        binderReady = true
    }

    fun addBinderDeadListener(listener: OnBinderDeadListener, handler: Handler? = null) {
        synchronized(RECEIVED_LISTENERS) {
            DEAD_LISTENERS.add(ListenerHolder(listener, handler))
        }
    }

    fun removeBinderDeadListener(listener: OnBinderDeadListener): Boolean {
        synchronized(RECEIVED_LISTENERS) {
            return DEAD_LISTENERS.removeIf { it.listener === listener }
        }
    }

    private fun scheduleBinderDeadListeners() {
        synchronized(RECEIVED_LISTENERS) {
            for (holder in DEAD_LISTENERS) {
                if (holder.handler != null) {
                    holder.handler.post { holder.listener.onBinderDead() }
                } else {
                    if (Looper.myLooper() == Looper.getMainLooper()) {
                        holder.listener.onBinderDead()
                    } else {
                        MAIN_HANDLER.post { holder.listener.onBinderDead() }
                    }
                }
            }
        }
    }

    @JvmOverloads
    fun addRequestPermissionResultListener(
        listener: OnRequestPermissionResultListener,
        handler: Handler? = null
    ) {
        synchronized(RECEIVED_LISTENERS) {
            PERMISSION_LISTENERS.add(
                ListenerHolder(
                    listener,
                    handler
                )
            )
        }
    }

    fun removeRequestPermissionResultListener(listener: OnRequestPermissionResultListener): Boolean {
        synchronized(RECEIVED_LISTENERS) {
            return PERMISSION_LISTENERS.removeIf { it.listener === listener }
        }
    }

    private fun scheduleRequestPermissionResultListener(requestCode: Int, allowed: Boolean, onetime: Boolean) {
        synchronized(RECEIVED_LISTENERS) {
            for (holder in PERMISSION_LISTENERS) {
                if (holder.handler != null) {
                    holder.handler.post {
                        holder.listener.onRequestPermissionResult(
                            requestCode,
                            allowed,
                            onetime
                        )
                    }
                } else {
                    if (Looper.myLooper() == Looper.getMainLooper()) {
                        holder.listener.onRequestPermissionResult(requestCode, allowed, onetime)
                    } else {
                        MAIN_HANDLER.post {
                            holder.listener.onRequestPermissionResult(
                                requestCode,
                                allowed,
                                onetime
                            )
                        }
                    }
                }
            }
        }
    }

    internal fun requireService(): ILinService = service ?: error("Service not connected")

    fun getService(): ILinService? = service

    fun getPackageName(): String? = packageName

    fun addServiceStartedListener(listener: OnServiceStartedListener, handler: Handler? = null) {
        val holder = ListenerHolder(listener, handler)
        synchronized(RECEIVED_LISTENERS) {
            SERVICE_STARTED_LISTENERS.add(holder)
        }
        if (serviceStarted) {
            dispatchServiceStartedListener(holder)
        }
    }

    fun removeServiceStartedListener(listener: OnServiceStartedListener): Boolean {
        synchronized(RECEIVED_LISTENERS) {
            return SERVICE_STARTED_LISTENERS.removeIf { it.listener === listener }
        }
    }

    private fun scheduleServiceStartedListeners() {
        synchronized(RECEIVED_LISTENERS) {
            for (holder in SERVICE_STARTED_LISTENERS) {
                dispatchServiceStartedListener(holder)
            }
        }
    }

    private fun dispatchServiceStartedListener(holder: ListenerHolder<OnServiceStartedListener>) {
        if (holder.handler != null) {
            holder.handler.post { holder.listener.onServiceStarted() }
        } else {
            MAIN_HANDLER.post { holder.listener.onServiceStarted() }
        }
    }

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
    fun getClientBinder(): IBinder? = Lin_APPLICATION.asBinder()

    fun pingBinder(): Boolean = binder?.pingBinder() == true

    private fun rethrowAsRuntimeException(e: RemoteException?): RuntimeException = RuntimeException(e)

    fun transactRemote(data: Parcel, reply: Parcel?, flags: Int) {
        try {
            requireService().asBinder()
                .transact(LinApiConstants.BINDER_TRANSACTION_transact, data, reply, flags)
        } catch (e: RemoteException) {
            throw rethrowAsRuntimeException(e)
        }
    }

    fun newProcess(cmd: Array<String?>, env: Array<String?>?, dir: String?): LinRemoteProcess {
        try {
            return LinRemoteProcess(requireService().newProcess(cmd, env, dir))
        } catch (e: RemoteException) {
            throw rethrowAsRuntimeException(e)
        }
    }

    fun newPtyProcess(cmd: Array<String?>, env: Array<String?>?, dir: String?): LinPtyProcess {
        try {
            return LinPtyProcess(requireService().newPtyProcess(cmd, env, dir))
        } catch (e: RemoteException) {
            throw rethrowAsRuntimeException(e)
        }
    }

    val uid: Int
        get() {
            if (serverUid != -1) return serverUid
            try {
                serverUid = requireService().getUid()
            } catch (e: RemoteException) {
                throw rethrowAsRuntimeException(e)
            } catch (_: SecurityException) {
                return -1
            }
            return serverUid
        }

    val version: Int
        get() {
            if (serverApiVersion != -1) return serverApiVersion
            try {
                serverApiVersion = requireService().getVersion()
            } catch (e: RemoteException) {
                throw rethrowAsRuntimeException(e)
            } catch (_: SecurityException) {
                return -1
            }
            return serverApiVersion
        }


    val latestServiceVersion: Int
        get() = LinApiConstants.SERVER_VERSION

    val sELinuxContext: String?
        get() {
            if (serverContext != null) return serverContext
            try {
                serverContext = requireService().getSELinuxContext()
            } catch (e: RemoteException) {
                throw rethrowAsRuntimeException(e)
            } catch (_: SecurityException) {
                return null
            }
            return serverContext
        }

    val versionName: String?
        get() {
            try {
                return requireService().getVersionName()
            } catch (e: RemoteException) {
                throw rethrowAsRuntimeException(e)
            }
        }

    val versionCode: Int
        get() {
            try {
                return requireService().getVersionCode()
            } catch (e: RemoteException) {
                throw rethrowAsRuntimeException(e)
            }
        }


    fun checkRemotePermission(permission: String?): Int {
        if (serverUid == 0) return PackageManager.PERMISSION_GRANTED
        try {
            return requireService().checkPermission(permission)
        } catch (e: RemoteException) {
            throw rethrowAsRuntimeException(e)
        }
    }

    val supportedPermissions: Array<String>
        get() =
            try {
                requireService().supportedPermissions
            } catch (e: RemoteException) {
                throw rethrowAsRuntimeException(e)
            }

    fun requestPermission(
        permission: String = LinApiConstants.PERMISSION_LIN,
        requestCode: Int
    ) {
        try {
            requireService().requestPermission(permission, requestCode)
        } catch (e: RemoteException) {
            throw rethrowAsRuntimeException(e)
        }
    }

    fun checkSelfPermission(permission: String = LinApiConstants.PERMISSION_LIN): Boolean {
        return try {
            requireService().checkSelfPermission(permission)
        } catch (_: RemoteException) {
            false
        }
    }

    fun shouldShowRequestPermissionRationale(): Boolean {
        if (permissionGranted) return false
        if (shouldShowRequestPermissionRationale) return true
        try {
            shouldShowRequestPermissionRationale =
                requireService().shouldShowRequestPermissionRationale()
        } catch (e: RemoteException) {
            throw rethrowAsRuntimeException(e)
        }
        return shouldShowRequestPermissionRationale
    }

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
    fun exit() {
        try {
            requireService().exit()
        } catch (e: RemoteException) {
            throw rethrowAsRuntimeException(e)
        }
    }


    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
    fun dispatchPermissionConfirmationResult(
        requestUid: Int,
        requestPid: Int,
        requestCode: Int,
        data: Bundle,
    ) {
        try {
            requireService().dispatchPermissionConfirmationResult(
                requestUid,
                requestPid,
                requestCode,
                data
            )
        } catch (e: RemoteException) {
            throw rethrowAsRuntimeException(e)
        }
    }

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
    fun getFlagForUid(uid: Int, permission: String): Int {
        try {
            return requireService().getFlagForUid(uid, permission)
        } catch (e: RemoteException) {
            throw rethrowAsRuntimeException(e)
        }
    }

    /** 已授权应用数（服务端 configManager 权威统计）。 */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
    fun getAuthorizedCount(): Int {
        try {
            return requireService().getAuthorizedCount()
        } catch (e: RemoteException) {
            throw rethrowAsRuntimeException(e)
        }
    }

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
    fun updateFlagForUid(uid: Int, permission: String, flag: Int) {
        try {
            requireService().updateFlagForUid(uid, permission, flag)
        } catch (e: RemoteException) {
            throw rethrowAsRuntimeException(e)
        }
    }

    /** 管理器回复授权请求弹窗（oneway，与 server 的 dispatchPermissionConfirmationResult 对应） */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
    fun dispatchPermissionConfirmationResult(
        requestUid: Int,
        requestPid: Int,
        requestCode: Int,
        allowed: Boolean,
        onetime: Boolean,
        permission: String
    ) {
        try {
            val data = android.os.Bundle().apply {
                putBoolean(LinApiConstants.REQUEST_PERMISSION_REPLY_ALLOWED, allowed)
                putBoolean(LinApiConstants.REQUEST_PERMISSION_REPLY_IS_ONETIME, onetime)
                putString(LinApiConstants.REQUEST_PERMISSION_REPLY_PERMISSION, permission)
            }
            requireService().dispatchPermissionConfirmationResult(requestUid, requestPid, requestCode, data)
        } catch (e: RemoteException) {
            throw rethrowAsRuntimeException(e)
        }
    }

    fun grantRuntimePermission(packageName: String, permissionName: String, userId: Int) {
        try {
            requireService().grantRuntimePermission(packageName, permissionName, userId)
        } catch (e: RemoteException) {
            throw rethrowAsRuntimeException(e)
        }
    }

    fun revokeRuntimePermission(packageName: String, permissionName: String, userId: Int) {
        try {
            requireService().revokeRuntimePermission(packageName, permissionName, userId)
        } catch (e: RemoteException) {
            throw rethrowAsRuntimeException(e)
        }
    }

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
    fun getLogs(): List<String> {
        try {
            return requireService().logs
        } catch (e: RemoteException) {
            throw rethrowAsRuntimeException(e)
        }
    }

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
    fun getLogsForUid(uid: Int): List<String>? {
        try {
            return requireService().getLogsForUid(uid)
        } catch (e: RemoteException) {
            throw rethrowAsRuntimeException(e)
        }
    }

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
    fun clearLogs() {
        try {
            requireService().clearLogs()
        } catch (e: RemoteException) {
            throw rethrowAsRuntimeException(e)
        }
    }

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
    fun isShizukuCompatEnabled(): Boolean {
        try {
            return requireService().isShizukuCompatEnabled
        } catch (e: RemoteException) {
            throw rethrowAsRuntimeException(e)
        }
    }

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
    fun setShizukuCompatEnabled(enabled: Boolean) {
        try {
            requireService().setShizukuCompatEnabled(enabled)
        } catch (e: RemoteException) {
            throw rethrowAsRuntimeException(e)
        }
    }

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
    fun isDaemonEnabled(): Boolean {
        return try {
            requireService().isDaemonEnabled()
        } catch (e: RemoteException) {
            throw rethrowAsRuntimeException(e)
        }
    }

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
    fun setDaemonEnabled(enabled: Boolean) {
        try {
            requireService().setDaemonEnabled(enabled)
        } catch (e: RemoteException) {
            throw rethrowAsRuntimeException(e)
        }
    }

    fun getSystemService(name: String): IBinder? {
        return try {
            requireService().getSystemService(name)
        } catch (e: RemoteException) {
            throw rethrowAsRuntimeException(e)
        }
    }

    fun interface OnBinderReceivedListener {
        fun onBinderReceived()
    }

    fun interface OnBinderDeadListener {
        fun onBinderDead()
    }

    fun interface OnRequestPermissionResultListener {
        fun onRequestPermissionResult(requestCode: Int, allowed: Boolean, onetime: Boolean)
    }

    fun interface OnServiceStartedListener {
        fun onServiceStarted()
    }

    private class ListenerHolder<T>(val listener: T, val handler: Handler?) {

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || javaClass != other.javaClass) return false
            val that = other as ListenerHolder<*>
            return listener == that.listener && handler == that.handler
        }

        override fun hashCode(): Int {
            return Objects.hash(listener, handler)
        }
    }
}
