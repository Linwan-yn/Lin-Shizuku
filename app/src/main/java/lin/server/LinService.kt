package lin.server

import android.content.Context
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.Parcelable
import com.lin.server.IRemoteProcess
import com.lin.server.ILinApplication
import com.lin.server.ILinService
import com.lin.server.IUserServiceCallback
import rikka.hidden.compat.PackageManagerApis
import lin.LinApiConstants
import lin.server.BinderSender.register
import lin.server.ServerConstants.MANAGER_APPLICATION_ID
import lin.server.binder.BinderDistributor
import lin.server.communication.CallerContext
import lin.server.communication.PermissionEnforcer
import lin.server.communication.LinCommunicationBridge
import lin.server.bootstrap.ServerBootstrap
import lin.server.ext.FollowLinStartupExt
import lin.server.grant.ManagerGrantHelper
import lin.server.ktx.mainHandler
import lin.server.monitor.PackageMonitor
import lin.server.query.ApplicationQueryHelper
import lin.server.service.LinServiceCore
import lin.server.shizuku.ShizukuApiConstants
import lin.server.shizuku.ShizukuCallbackFactory
import lin.server.shizuku.ShizukuServiceIntercept
import lin.server.userservice.UserServiceManager
import lin.server.util.Logger
import lin.server.daemon.DaemonManager
import kotlin.system.exitProcess

class LinService : ILinService.Stub() {

    private val clientManager: ClientManager
    private val configManager: ConfigManager
    private val userServiceManager: UserServiceManager
    private val managerAppId: Int

    private val serviceCore: LinServiceCore
    internal val permissionEnforcer: PermissionEnforcer
    private val bridge: LinCommunicationBridge

    internal lateinit var shizukuServiceIntercept: ShizukuServiceIntercept

    @Volatile
    private var daemonPid: Int = -1
    @Volatile
    private var daemonRestartCount: Int = 0
    @Volatile
    private var lastDaemonRestartTime: Long = 0L

    init {
        try {
            LOGGER.i("正在启动 Lin 服务...")

            LOGGER.i("等待系统服务...")
            ServerBootstrap.waitSystemService("package")
            ServerBootstrap.waitSystemService(Context.ACTIVITY_SERVICE)
            ServerBootstrap.waitSystemService(Context.USER_SERVICE)
            ServerBootstrap.waitSystemService(Context.APP_OPS_SERVICE)

            LOGGER.i("获取管理器应用信息...")
            val ai = ServerBootstrap.managerApplicationInfo
            if (ai == null) {
                LOGGER.e("无法获取管理器应用信息")
                exitProcess(ServerConstants.MANAGER_APP_NOT_FOUND)
            }

            managerAppId = ai.uid
            LOGGER.i("管理器应用 UID: $managerAppId")

            Logger.initProvider()

            LOGGER.i("初始化配置管理器...")
            configManager = ConfigManager()
            clientManager = ClientManager(configManager)
            userServiceManager = UserServiceManager()

            LOGGER.i("初始化服务核心...")
            serviceCore = LinServiceCore(clientManager, configManager, userServiceManager)
            permissionEnforcer = PermissionEnforcer(clientManager, configManager, managerAppId)
            bridge = LinCommunicationBridge(serviceCore, permissionEnforcer)

            LOGGER.i("初始化 Shizuku 兼容层...")
            shizukuServiceIntercept = ShizukuServiceIntercept(
                ShizukuCallbackFactory.create(
                    clientManager, configManager, userServiceManager,
                    managerAppId, serviceCore,
                    shizukuNotifier = { uid, pid, requestCode, allowed ->
                        shizukuServiceIntercept.notifyPermissionResult(uid, pid, requestCode, allowed)
                    }
                )
            )

            LOGGER.i("启动文件监听...")
            ApkChangedObservers.start(ai.sourceDir) {
                LOGGER.w("检测到管理器应用文件变化，检查应用状态...")
                if (ServerBootstrap.managerApplicationInfo == null) {
                    LOGGER.w("用户 0 中的管理器应用已卸载，正在退出...")
                    exitProcess(ServerConstants.MANAGER_APP_NOT_FOUND)
                } else {
                    LOGGER.i("管理器应用仍然存在，继续运行")
                }
            }

            LOGGER.i("注册包移除监听...")
            PackageMonitor.registerPackageRemovedReceiver(ai)

            LOGGER.i("注册 Binder...")
            register(this)

            LOGGER.i("发送 Binder 到客户端...")
            mainHandler.post {
                try {
                    BinderDistributor.sendBinderToManager(this)
                    BinderSender.sendBinderToAuthorizedRunningClients(configManager)
                    // 全量补发：覆盖"进程早于服务启动且长期稳定运行"的应用（如 InstallerX），
                    // 它们不会有 uid 状态变化触发单发，必须在每次服务启动时主动补发
                    BinderSender.sendBinderToAllAuthorizedClients(configManager)
                    // 1.1.2：激活服务后自动授予本应用 WRITE_SECURE_SETTINGS
                    // （「关闭 USB 调试 / 无线调试 / 开发者选项」这类系统开关依赖该权限，
                    //  官方实现也是启动一次后自行取得）。授权命令含重试与 Root 兜底，
                    //  放后台线程执行，避免阻塞服务启动收尾。
                    Thread {
                        try {
                            ManagerGrantHelper.grantWriteSecureSettings(managerAppId)
                        } catch (_: Throwable) {
                        }
                        try {
                            if (configManager.isAccessibilityAutoStartEnabled()) {
                                ManagerGrantHelper.grantAccessibilityService()
                            }
                        } catch (_: Throwable) {
                        }
                    }.start()
                    FollowLinStartupExt.schedule(this, configManager)
                    if (configManager.isDaemonEnabled()) {
                        startDaemon()
                        LOGGER.i("已启动进程守护")
                    }
                    notifyClientsServiceStarted()
                    startBinderResendLoop()
                    LOGGER.i("Lin 服务启动完成")
                } catch (e: Throwable) {
                    LOGGER.e(e, "发送 Binder 失败")
                }
            }
        } catch (e: Throwable) {
            LOGGER.e(e, "Lin 服务初始化失败")
            exitProcess(1)
        }
    }

    override fun getVersion(): Int {
        val caller = CallerContext.fromBinder()
        return bridge.handleGetVersion(caller)
    }

    override fun getUid(): Int {
        val caller = CallerContext.fromBinder()
        return bridge.handleGetUid(caller)
    }

    override fun getSELinuxContext(): String? {
        val caller = CallerContext.fromBinder()
        return bridge.handleGetSELinuxContext(caller)
    }

    override fun getVersionName(): String? {
        val caller = CallerContext.fromBinder()
        return bridge.handleGetVersionName(caller)
    }

    override fun getVersionCode(): Int {
        val caller = CallerContext.fromBinder()
        return bridge.handleGetVersionCode(caller)
    }

    override fun checkSelfPermission(permission: String?): Boolean {
        if (permission == null) return false
        val caller = CallerContext.fromBinder()
        return bridge.handleCheckSelfPermission(caller, permission)
    }

    override fun requestPermission(permission: String, requestCode: Int) {
        val caller = CallerContext.fromBinder()
        bridge.handleRequestPermission(caller, permission, requestCode)
    }

    override fun shouldShowRequestPermissionRationale(): Boolean {
        val caller = CallerContext.fromBinder()
        return bridge.handleShouldShowRequestPermissionRationale(caller)
    }

    override fun getSupportedPermissions(): Array<String> {
        return bridge.handleGetSupportedPermissions()
    }

    override fun dispatchPermissionConfirmationResult(
        requestUid: Int,
        requestPid: Int,
        requestCode: Int,
        data: Bundle?
    ) {
        if (data == null) return

        val permission = data.getString(
            LinApiConstants.REQUEST_PERMISSION_REPLY_PERMISSION,
            LinApiConstants.PERMISSION_LIN
        )
        if (permission == ShizukuApiConstants.PERMISSION_NAME) {
            val allowed = data.getBoolean(LinApiConstants.REQUEST_PERMISSION_REPLY_ALLOWED, false)
            val onetime = data.getBoolean(LinApiConstants.REQUEST_PERMISSION_REPLY_IS_ONETIME, false)

            LOGGER.i("Shizuku 权限结果: uid=$requestUid, pid=$requestPid, code=$requestCode, allowed=$allowed, onetime=$onetime")

            val record = clientManager.findClient(requestUid, requestPid)
            if (onetime) {
                if (record != null) {
                    record.onetimeMap[ShizukuApiConstants.PERMISSION_NAME] = allowed
                } else {
                    clientManager.findClients(requestUid).forEach {
                        it.onetimeMap[ShizukuApiConstants.PERMISSION_NAME] = allowed
                    }
                }
            } else {
                val newFlag = if (allowed) ConfigManager.FLAG_GRANTED else ConfigManager.FLAG_DENIED
                configManager.updatePermission(requestUid, ShizukuApiConstants.PERMISSION_NAME, newFlag)
                // 74 轮修复：双写 stellar 键——兼容应用经 rikka(shizuku) 弹窗授权后，
                // 自家 stellar 生态应用（按 stellar 键查询）也立即生效，避免"授予无效/无特权"。
                configManager.updatePermission(requestUid, LinApiConstants.PERMISSION_LIN, newFlag)
                clientManager.findClients(requestUid).forEach { it.onetimeMap.remove(ShizukuApiConstants.PERMISSION_NAME) }
                // 授权变更后立即补发 Binder：已 attach 的兼容/生态应用无需重启进程即可重新绑定并生效
                if (newFlag == ConfigManager.FLAG_GRANTED) {
                    BinderSender.resendBinderToUid(requestUid)
                }
            }

            if (!allowed) {
                val now = System.currentTimeMillis()
                if (record != null) {
                    record.lastDenyTimeMap[ShizukuApiConstants.PERMISSION_NAME] = now
                } else {
                    clientManager.findClients(requestUid).forEach {
                        it.lastDenyTimeMap[ShizukuApiConstants.PERMISSION_NAME] = now
                    }
                }
            }

            shizukuServiceIntercept.notifyPermissionResult(requestUid, requestPid, requestCode, allowed)
            // 1.1.2：rish 通道没有 IShizukuApplication，结果靠这里唤醒阻塞中的 rish 事务
            shizukuServiceIntercept.completeRishPermission(requestCode, allowed)
            return
        }

        val caller = CallerContext.fromBinder()
        bridge.handleDispatchPermissionConfirmationResult(caller, requestUid, requestPid, requestCode, data)
    }

    override fun getFlagForUid(uid: Int, permission: String): Int {
        if (permission == ShizukuApiConstants.PERMISSION_NAME) {
            val linFlag = configManager.getPermissionFlag(uid, ShizukuApiConstants.PERMISSION_NAME)
            return ShizukuApiConstants.linToShizukuFlag(linFlag)
        }

        val caller = CallerContext.fromBinder()
        return bridge.handleGetFlagForUid(caller, uid, permission)
    }

    /** 已授权应用数：统计服务端授权表（configManager 权威）中任一权限为 GRANTED 的 uid 数。 */
    override fun getAuthorizedCount(): Int {
        val n = configManager.getAuthorizedUidCount()
        LOGGER.d("getAuthorizedCount：已授权应用数=%d（授权表 uid 数=%d）", n, configManager.packages.size)
        return n
    }

    override fun updateFlagForUid(uid: Int, permission: String, flag: Int) {
        if (permission == ShizukuApiConstants.PERMISSION_NAME) {
            val linFlag = ShizukuApiConstants.shizukuToLinFlag(flag)
            configManager.updatePermission(uid, ShizukuApiConstants.PERMISSION_NAME, linFlag)
            clientManager.findClients(uid).forEach { it.onetimeMap.remove(ShizukuApiConstants.PERMISSION_NAME) }
            // 授权变更后立即补发 Binder：授权为「允许/询问」的应用若进程已在运行
            // （早于服务启动或早于授权），无需重启进程即可收到 Shizuku Binder 并生效
            if (linFlag == ConfigManager.FLAG_GRANTED || linFlag == ConfigManager.FLAG_ASK) {
                BinderSender.resendBinderToUid(uid)
            }
            return
        }

        val caller = CallerContext.fromBinder()
        bridge.handleUpdateFlagForUid(caller, uid, permission, flag)
    }

    override fun grantRuntimePermission(packageName: String, permissionName: String, userId: Int) {
        val caller = CallerContext.fromBinder()
        bridge.handleGrantRuntimePermission(caller, packageName, permissionName, userId)
    }

    override fun revokeRuntimePermission(packageName: String, permissionName: String, userId: Int) {
        val caller = CallerContext.fromBinder()
        bridge.handleRevokeRuntimePermission(caller, packageName, permissionName, userId)
    }

    override fun newProcess(cmd: Array<String?>?, env: Array<String?>?, dir: String?): IRemoteProcess {
        val caller = CallerContext.fromBinder()
        return bridge.handleNewProcess(caller, cmd ?: emptyArray(), env, dir)
    }

    override fun newPtyProcess(cmd: Array<String?>?, env: Array<String?>?, dir: String?): com.lin.server.IRemotePtyProcess {
        val caller = CallerContext.fromBinder()
        return bridge.handleNewPtyProcess(caller, cmd ?: emptyArray(), env, dir)
    }

    override fun getSystemProperty(name: String?, defaultValue: String?): String {
        val caller = CallerContext.fromBinder()
        return bridge.handleGetSystemProperty(caller, name ?: "", defaultValue ?: "")
    }

    override fun setSystemProperty(name: String?, value: String?) {
        val caller = CallerContext.fromBinder()
        bridge.handleSetSystemProperty(caller, name ?: "", value ?: "")
    }

    override fun startUserService(args: Bundle?, callback: IUserServiceCallback?): String? {
        val caller = CallerContext.fromBinder()
        return bridge.handleStartUserService(caller, args, callback)
    }

    override fun stopUserService(token: String?) {
        val caller = CallerContext.fromBinder()
        bridge.handleStopUserService(caller, token)
    }

    override fun attachUserService(binder: IBinder?, options: Bundle?) {
        bridge.handleAttachUserService(binder, options)
    }

    override fun getUserServiceCount(): Int {
        val caller = CallerContext.fromBinder()
        return bridge.handleGetUserServiceCount(caller)
    }

    override fun getLogs(): List<String> {
        val caller = CallerContext.fromBinder()
        return bridge.handleGetLogs(caller)
    }

    override fun getLogsForUid(uid: Int): List<String>? {
        val caller = CallerContext.fromBinder()
        return bridge.handleGetLogsForUid(caller, uid)
    }

    override fun clearLogs() {
        val caller = CallerContext.fromBinder()
        bridge.handleClearLogs(caller)
    }

    override fun isShizukuCompatEnabled(): Boolean {
        val caller = CallerContext.fromBinder()
        permissionEnforcer.enforceManager(caller, "isShizukuCompatEnabled")
        return configManager.isShizukuCompatEnabled()
    }

    override fun setShizukuCompatEnabled(enabled: Boolean) {
        val caller = CallerContext.fromBinder()
        permissionEnforcer.enforceManager(caller, "setShizukuCompatEnabled")
        configManager.setShizukuCompatEnabled(enabled)
        LOGGER.i("Shizuku 兼容层已%s", if (enabled) "启用" else "禁用")
    }

    override fun isDaemonEnabled(): Boolean {
        val caller = CallerContext.fromBinder()
        permissionEnforcer.enforceManager(caller, "isDaemonEnabled")
        return configManager.isDaemonEnabled()
    }

    override fun setDaemonEnabled(enabled: Boolean) {
        val caller = CallerContext.fromBinder()
        permissionEnforcer.enforceManager(caller, "setDaemonEnabled")
        configManager.setDaemonEnabled(enabled)

        if (enabled) {
            startDaemon()
        } else {
            stopDaemon()
            daemonPid = -1
        }

        LOGGER.i("进程守护已%s", if (enabled) "启用" else "禁用")
    }

    override fun getSystemService(name: String?): IBinder? {
        val caller = CallerContext.fromBinder()
        permissionEnforcer.enforcePermission(caller, "getSystemService")

        if (name == null) return null

        // 90bp：缓存 ServiceManager Class（原实现每次调用 Class.forName 反射加载）
        return serviceManagerClass
            .getMethod("getService", String::class.java)
            .invoke(null, name) as? IBinder
    }

    private fun stopDaemon() {
        DaemonManager.stopDaemon()
    }

    override fun exit() {
        val caller = CallerContext.fromBinder()
        permissionEnforcer.enforceManager(caller, "exit")
        LOGGER.i("exit")
        userServiceManager.cleanupManagerApkCache()
        daemonPid = -1
        stopDaemon()
        exitProcess(0)
    }

    override fun attachApplication(application: ILinApplication?, args: Bundle?) {
        if (application == null || args == null) {
            return
        }

        val requestPackageName =
            args.getString(LinApiConstants.ATTACH_APPLICATION_PACKAGE_NAME) ?: return
        val apiVersion = args.getInt(LinApiConstants.ATTACH_APPLICATION_API_VERSION, -1)

        val callingPid = getCallingPid()
        val callingUid = getCallingUid()
        val isManager: Boolean = MANAGER_APPLICATION_ID == requestPackageName
        var clientRecord: ClientRecord? = null

        val packages = PackageManagerApis.getPackagesForUidNoThrow(callingUid)
        if (!packages.contains(requestPackageName)) {
            LOGGER.w("请求包 $requestPackageName 不属于 uid $callingUid")
            throw SecurityException("请求包 $requestPackageName 不属于 uid $callingUid")
        }

        val existingClient = clientManager.findClient(callingUid, callingPid)
        if (existingClient == null) {
            LOGGER.i("创建新客户端记录: uid=%d, pid=%d, package=%s", callingUid, callingPid, requestPackageName)
            synchronized(this) {
                clientRecord = clientManager.addClient(
                    callingUid,
                    callingPid,
                    application,
                    requestPackageName,
                    apiVersion
                )
            }
            if (clientRecord == null) {
                LOGGER.w("添加客户端失败")
                return
            }
        } else {
            LOGGER.i("使用已存在的客户端记录: uid=%d, pid=%d, package=%s", callingUid, callingPid, requestPackageName)
            clientRecord = existingClient
        }

        if (isManager) {
            if (configManager.isAccessibilityAutoStartEnabled()) {
                LOGGER.i("管理器已连接，授予无障碍服务权限...")
                ManagerGrantHelper.grantAccessibilityService()
            }
            try {
                application.asBinder().linkToDeath({
                    LOGGER.i("管理器进程已死亡")
                    if (configManager.isAccessibilityAutoStartEnabled()) {
                        LOGGER.i("正在授予无障碍服务权限...")
                        ManagerGrantHelper.grantAccessibilityService()
                    }
                }, 0)
            } catch (e: Throwable) {
                LOGGER.w(e, "监控管理器进程死亡失败")
            }
        }

        LOGGER.i("attachApplication 完成: %s %d %d", requestPackageName, callingUid, callingPid)

        val reply = Bundle()
        reply.putInt(LinApiConstants.BIND_APPLICATION_SERVER_UID, serviceCore.serviceInfo.getUid())
        reply.putInt(
            LinApiConstants.BIND_APPLICATION_SERVER_VERSION,
            LinApiConstants.SERVER_VERSION
        )
        reply.putString(
            LinApiConstants.BIND_APPLICATION_SERVER_SECONTEXT,
            serviceCore.serviceInfo.getSELinuxContext()
        )
        if (!isManager) {
            val cr = clientRecord ?: return
            val permissionGranted =
                cr.allowedMap[LinApiConstants.PERMISSION_LIN] ?: false
            LOGGER.i("权限状态检查: uid=%d, pid=%d, package=%s, granted=%s, allowedMap=%s",
                callingUid, callingPid, requestPackageName, permissionGranted,
                cr.allowedMap.toString()
            )
            reply.putBoolean(
                LinApiConstants.BIND_APPLICATION_PERMISSION_GRANTED,
                permissionGranted
            )
            reply.putBoolean(
                LinApiConstants.BIND_APPLICATION_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE,
                false
            )
        }
        try {
            application.bindApplication(reply)
        } catch (e: Throwable) {
            LOGGER.w(e, "attachApplication")
        }
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        // Scene/rish 双协议分发（Scene 适配 v2）：
        // rish（Scene 等未声明 Shizuku meta-data 的应用）经 ShizukuReceiver 广播拿到的
        // 是管理器进程持有的裸 LinService Binder（BinderDistributor.sendBinderToManager
        // 发送的就是 LinService 自身），但 rish 按官方 IShizukuService 协议调用
        // （attachApplicationV13=18、requestPermission=14、checkSelfPermission 等）。
        // 裸 LinService 的 AIDL super.onTransact 只 enforceInterface(com.lin.server.ILinService)，
        // 对 shizuku descriptor 必然抛 SecurityException → rish attach 失败 → Scene 回退弹 up.sh。
        // 修复：嗅探 descriptor 后把整个事务转给已完整实现的 ShizukuServiceIntercept，
        // 让 rish 从任意出口拿到的 Binder 都能完成 Shizuku 握手与授权。
        val savedPos = data.dataPosition()
        data.setDataPosition(0)
        val descriptor = try {
            data.readString()
        } catch (_: Throwable) {
            null
        }
        data.setDataPosition(savedPos)
        if (descriptor == ShizukuApiConstants.BINDER_DESCRIPTOR) {
            return shizukuServiceIntercept.onTransact(code, data, reply, flags)
        }

        if (code == ServerConstants.BINDER_TRANSACTION_getApplications) {
            data.enforceInterface(LinApiConstants.BINDER_DESCRIPTOR)
            val userId = data.readInt()
            val result = ApplicationQueryHelper.getApplications(userId, configManager)
            reply?.let {
                it.writeNoException()
                result.writeToParcel(it, Parcelable.PARCELABLE_WRITE_RETURN_VALUE)
            }
            return true
        } else if (code == LinApiConstants.BINDER_TRANSACTION_transact) {
            data.enforceInterface(LinApiConstants.BINDER_DESCRIPTOR)
            transactRemote(data, reply, flags)
            return true
        }
        return super.onTransact(code, data, reply, flags)
    }

    private fun transactRemote(data: Parcel, reply: Parcel?, flags: Int) {
        val caller = CallerContext.fromBinder()
        permissionEnforcer.enforcePermission(caller, "transactRemote")

        val targetBinder = data.readStrongBinder()
        val targetCode = data.readInt()
        val targetFlags: Int

        val clientRecord = clientManager.findClient(caller.uid, caller.pid)

        targetFlags = if (clientRecord != null && clientRecord.apiVersion >= 13) {
            data.readInt()
        } else {
            flags
        }

        LOGGER.d(
            "transact: uid=%d, descriptor=%s, code=%d",
            caller.uid,
            targetBinder.interfaceDescriptor,
            targetCode
        )

        val newData = Parcel.obtain()
        try {
            newData.appendFrom(data, data.dataPosition(), data.dataAvail())
        } catch (tr: Throwable) {
            LOGGER.w(tr, "appendFrom")
            return
        }
        try {
            val id = clearCallingIdentity()
            targetBinder.transact(targetCode, newData, reply, targetFlags)
            restoreCallingIdentity(id)
        } finally {
            newData.recycle()
        }
    }

    override fun checkPermission(permission: String?): Int {
        val caller = CallerContext.fromBinder()
        permissionEnforcer.enforcePermission(caller, "checkPermission")
        return rikka.hidden.compat.PermissionManagerApis.checkPermission(permission, serviceCore.serviceInfo.getUid())
    }

    private fun notifyClientsServiceStarted() {
        clientManager.allClients().forEach { record ->
            try {
                record.client?.onServiceStarted()
            } catch (e: Throwable) {
                LOGGER.w(e, "通知客户端服务启动失败: uid=%d, pid=%d, package=%s", record.uid, record.pid, record.packageName)
            }
        }
    }

    private fun startDaemon() {
        try {
            val myPid = android.os.Process.myPid()
            val classpath = System.getProperty("java.class.path") ?: return
            val startCmd = "CLASSPATH=$classpath app_process /system/bin --nice-name=Lin_server lin.server.LinService &"
            daemonPid = DaemonManager.startDaemon(myPid, startCmd)
            if (daemonPid > 0) {
                startDaemonMonitor()
            }
        } catch (e: Throwable) {
            LOGGER.e(e, "启动守护进程失败")
        }
    }

    /**
     * 90ba：Binder 心跳重发——provider 进程（:stellar）被系统（ColorOS 省电策略 /
     * LMKD）杀掉重建后，进程内 Lin.binder 丢失，应用主进程拉不到服务端 Binder，
     * 表现为「服务进程(PID)存在但等待 Binder 响应超时」。
     * 这里每 15 秒向管理器 provider 重发服务端 Binder（幂等：provider 同对象忽略、
     * 异对象替换），保证 provider 重建后 15 秒内自动恢复桥接，无需手动点启动。
     */
    private fun startBinderResendLoop() {
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                try {
                    BinderDistributor.sendBinderToManager(this@LinService)
                } catch (e: Throwable) {
                    LOGGER.e(e, "Binder 心跳重发失败")
                }
                mainHandler.postDelayed(this, 15_000L)
            }
        }, 15_000L)
    }

    private fun startDaemonMonitor() {
        Thread {
            while (configManager.isDaemonEnabled()) {
                try {
                    Thread.sleep(5000)
                    if (daemonPid > 0 && !isProcessAlive(daemonPid)) {
                        val now = System.currentTimeMillis()
                        
                        if (now - lastDaemonRestartTime < 60_000) {
                            daemonRestartCount++
                        } else {
                            daemonRestartCount = 1
                        }
                        lastDaemonRestartTime = now

                        if (daemonRestartCount > 5) {
                            LOGGER.e("守护进程频繁死亡，已停止自动拉起守护进程")
                            daemonPid = -1
                            break
                        }

                        LOGGER.w("检测到守护进程死亡，第 $daemonRestartCount 次尝试重新启动...")
                        startDaemon()
                        break
                    }
                } catch (e: Exception) {
                    LOGGER.e(e, "守护进程监控错误")
                }
            }
        }.start()
    }

    private fun isProcessAlive(pid: Int): Boolean {
        return try {
            java.io.File("/proc/$pid").exists()
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        private val LOGGER = Logger("LinService")

        /** 90bp：ServiceManager Class 缓存（getSystemService 高频反射路径） */
        private val serviceManagerClass: Class<*> by lazy {
            Class.forName("android.os.ServiceManager")
        }

        @JvmStatic
        fun main(args: Array<String>) {
            ServerBootstrap.main(args)
        }
    }
}
