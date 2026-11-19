package lin.server.shizuku

import android.os.Parcel
import android.util.Log
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuApplication
import moe.shizuku.server.IShizukuService
import moe.shizuku.server.IShizukuServiceConnection
import lin.server.ConfigManager
import lin.server.BinderSender
import lin.server.util.Logger
import lin.server.util.PackageManagerCompat
import rikka.hidden.compat.PackageManagerApis

class ShizukuServiceIntercept(
    private val callback: ShizukuServiceCallback
) : IShizukuService.Stub() {

    companion object {
        private const val TAG = "ShizukuServiceIntercept"

        private const val PER_USER_RANGE = 100000

        // 偏移客户端兼容：部分自研 Shizuku 封装（ST 管理器等）把 IShizukuService.aidl 里
        // 手写的显式序号 N 误当成裸事务码，而 AIDL 生成器实际常量是 FIRST_CALL_TRANSACTION(1)+N，
        // 于是这类客户端发出的主接口事务码整体少 1（checkSelfPermission 误发到 requestPermission 码位，
        // 授权态永远读不回）。按包名识别后把主接口码 +1 重映射回标准分派；官方 SDK 走生成 Stub、
        // 码值正确，不在名单内，不受影响。
        private val OFFSET_CLIENT_PACKAGES = setOf("com.example.filemanager")
        private const val OFFSET_CODE = 1

        // 仅重映射主接口号段；自定义转发码(1)、rish 通道(30000+)不参与偏移。
        private fun isMainServiceCode(code: Int): Boolean =
            (code in 2..17) || (code in 100..106)

        private fun getAppId(uid: Int): Int = uid % PER_USER_RANGE
        private fun getUserId(uid: Int): Int = uid / PER_USER_RANGE
    }

    // 偏移客户端识别结果按 uid 缓存（进程生命周期内有效；重装/换包后随进程重启清空）。
    private val offsetClientCache = java.util.concurrent.ConcurrentHashMap<Int, Boolean>()

    private fun isOffsetClient(uid: Int): Boolean {
        offsetClientCache[uid]?.let { if (it) return true }
        val hit = try {
            PackageManagerApis.getPackagesForUidNoThrow(uid)
                .any { it in OFFSET_CLIENT_PACKAGES }
        } catch (_: Throwable) {
            false
        }
        // 只缓存命中，未命中每次重查，避免卸载重装、同 uid 换包后误判。
        if (hit) offsetClientCache[uid] = true
        return hit
    }

    // v5：授权链路事务码日志走服务端 LOGGER（落盘到 Lin_server_runtime.log），
    // 与 ShizukuCallbackFactory/LinService 的权限日志同文件，adb cat 即可完整复盘授权链路。
    private val LOGGER = Logger("ShizukuServiceIntercept")

    private val clientManager get() = callback.clientManager
    private val configManager get() = callback.configManager

    private fun isEnabled(): Boolean = configManager.isShizukuCompatEnabled()

    private fun enforceEnabled() {
        if (!isEnabled()) {
            throw SecurityException("Shizuku compat layer is disabled")
        }
    }

    private val userServiceAdapter by lazy {
        ShizukuUserServiceAdapter(callback.userServiceManager)
    }

    private val rishService by lazy { RishServiceImpl() }

    /**
     * 1.1.2（rish 通道授权）：rish 客户端运行在 app_process 里，没有 IShizukuApplication，
     * 无法接收异步的授权结果。这里在 rish 事务入口同步弹窗，并阻塞等待管理器回传结果：
     * 允许 → 放行本次事务；拒绝 → 抛 SecurityException（rish 侧打印失败原因）。
     */
    private val rishPending = java.util.concurrent.ConcurrentHashMap<Int, java.util.concurrent.CountDownLatch>()
    private val rishAllowed = java.util.concurrent.ConcurrentHashMap<Int, Boolean>()
    private val rishCodeSeq = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * 1.1.2（询问态直连调用）：应用不经 requestPermission、直接调 API 时，
     * 原来 enforceCallingPermission 只会抛 "Permission denied for xxx"，
     * 界面上就是「无权限」且永远看不到弹窗（雹的悬浮按钮、Scene 快捷方式的
     * newProcess 都是这条路径）。这里对「询问」态补一次弹窗投递，
     * 让用户至少能把权限授出来；本次调用仍按原行为拒绝，授权后再次调用即通过。
     * 同一 uid 5 秒内只投递一次，避免按钮连点刷屏。
     */
    private val askPromptedAt = java.util.concurrent.ConcurrentHashMap<Int, Long>()

    private fun promptPermissionForAsk(uid: Int, pid: Int, method: String) {
        val now = System.currentTimeMillis()
        val last = askPromptedAt[uid] ?: 0L
        if (now - last < 5000L) return
        askPromptedAt[uid] = now
        try {
            val requestCode = 0x7300 or (rishCodeSeq.incrementAndGet() and 0xFF)
            LOGGER.i(
                "权限处于询问，主动投递授权弹窗: uid=%d, pid=%d, method=%s, code=%d",
                uid, pid, method, requestCode
            )
            callback.requestPermission(uid, pid, requestCode)
        } catch (e: Throwable) {
            LOGGER.w(e, "主动投递授权弹窗失败: uid=$uid, method=$method")
        }
    }

    private fun ensureRishPermission(uid: Int, pid: Int): Boolean {
        if (checkPermission(uid) == ConfigManager.FLAG_GRANTED) return true
        if (checkOnetimePermission(uid, pid)) return true
        if (checkPermission(uid) == ConfigManager.FLAG_DENIED) {
            LOGGER.i("rish 权限已被拒绝，不再弹窗: uid=%d", uid)
            return false
        }

        val requestCode = 0x7200 or (rishCodeSeq.incrementAndGet() and 0xFF)
        val latch = java.util.concurrent.CountDownLatch(1)
        rishPending[requestCode] = latch
        return try {
            LOGGER.i("rish 权限请求: uid=%d, pid=%d, code=%d", uid, pid, requestCode)
            callback.requestPermission(uid, pid, requestCode)
            // rish 客户端自身 5 秒就报 Request timeout，这里等 10 秒足够，
            // 不宜无限占住 Binder 线程。
            val finished = latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
            val allowed = rishAllowed[requestCode] ?: false
            LOGGER.i("rish 权限结果: uid=%d, code=%d, allowed=%b, timeout=%b",
                uid, requestCode, allowed, !finished)
            finished && allowed
        } catch (e: Throwable) {
            LOGGER.w(e, "rish 权限请求异常: uid=%d", uid)
            false
        } finally {
            rishPending.remove(requestCode)
            rishAllowed.remove(requestCode)
        }
    }

    /** 管理器弹窗点选后回传结果时调用，唤醒阻塞中的 rish 事务。 */
    fun completeRishPermission(requestCode: Int, allowed: Boolean) {
        val latch = rishPending[requestCode] ?: return
        rishAllowed[requestCode] = allowed
        latch.countDown()
        LOGGER.i("rish 权限回传: code=%d, allowed=%b", requestCode, allowed)
    }

    private inline fun <T> withClearedIdentity(block: () -> T): T {
        val id = clearCallingIdentity()
        try {
            return block()
        } finally {
            restoreCallingIdentity(id)
        }
    }

    private fun checkCallerManagerPermission(callingUid: Int): Boolean =
        getAppId(callingUid) == callback.managerAppId

    private fun checkPermission(uid: Int): Int {
        return configManager.getPermissionFlag(uid, ShizukuApiConstants.PERMISSION_NAME)
    }

    private fun checkOnetimePermission(uid: Int, pid: Int): Boolean {
        return clientManager.findClient(uid, pid)?.onetimeMap?.get(ShizukuApiConstants.PERMISSION_NAME) == true
    }

    private fun getLastDenyTime(uid: Int, pid: Int): Long =
        clientManager.findClient(uid, pid)?.lastDenyTimeMap?.get(ShizukuApiConstants.PERMISSION_NAME) ?: 0

    private fun enforceCallingPermission(method: String) {
        enforceEnabled()

        val callingUid = getCallingUid()
        val callingPid = getCallingPid()

        if (callingPid == callback.servicePid) return
        if (checkCallerManagerPermission(callingUid)) return

        val permFlag = checkPermission(callingUid)
        if (permFlag == ConfigManager.FLAG_GRANTED) return

        if (checkOnetimePermission(callingUid, callingPid)) return

        // 「询问」：先把授权弹窗投递出去，再拒绝本次调用。
        // 应用直连 API（不先发 requestPermission）时，这是它唯一能看到弹窗的机会。
        if (permFlag != ConfigManager.FLAG_DENIED) {
            promptPermissionForAsk(callingUid, callingPid, method)
        }

        throw SecurityException("Permission denied for $method")
    }

    private fun transactRemote(data: Parcel, reply: Parcel?, flags: Int) {
        enforceCallingPermission("transactRemote")

        val targetBinder = data.readStrongBinder()
        val targetCode = data.readInt()

        val callingUid = getCallingUid()
        val callingPid = getCallingPid()
        val clientRecord = clientManager.findClient(callingUid, callingPid)

        val targetFlags = if (clientRecord != null && clientRecord.apiVersion >= 13) {
            data.readInt()
        } else {
            flags
        }

        Log.d(TAG, "transactRemote: uid=$callingUid, code=$targetCode")

        val newData = Parcel.obtain()
        try {
            newData.appendFrom(data, data.dataPosition(), data.dataAvail())
            withClearedIdentity {
                targetBinder.transact(targetCode, newData, reply, targetFlags)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "transactRemote failed", e)
        } finally {
            newData.recycle()
        }
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        val callingUid = getCallingUid()
        // 偏移客户端：主接口事务码整体 +1 重映射到标准分派；其余客户端码值原样透传。
        val offset = isOffsetClient(callingUid)
        val effCode = if (offset && isMainServiceCode(code)) code + OFFSET_CODE else code

        // v5（MT 授权超时修复）：关键事务码（授权链路相关）记录到服务端运行日志，
        // 用于确认第三方客户端（MT 管理器等）实际发送的事务码与参数走向。
        if (effCode == 14 || effCode == 15 || effCode == 16 || effCode == 17 || effCode == 18 ||
            effCode == 104 || effCode == 105 || effCode == 106
        ) {
            try {
                LOGGER.i(
                    "Shizuku transact: code=%d, effCode=%d, offset=%b, callerUid=%d, callerPid=%d",
                    code, effCode, offset, callingUid, getCallingPid()
                )
            } catch (_: Throwable) {}
        }

        if (code == ShizukuApiConstants.BINDER_TRANSACTION_transact) {
            data.enforceInterface(ShizukuApiConstants.BINDER_DESCRIPTOR)
            transactRemote(data, reply, flags)
            return true
        }

        // 官方 Shizuku API 客户端（12.2.0+ / 13.x，MT 管理器等）的 attachApplication V13 使用
        // 原始 transact 事务码 18（客户端硬编码，未随 IShizukuService.aidl 的 17 同步）。
        // 不处理 18 会让 V13 attach 失败并回退 V11（14），而 14 又是 requestPermission——
        // 客户端永远 attach 不上，授权请求也就永远等不到结果。
        if (effCode == 18) {
            data.enforceInterface(ShizukuApiConstants.BINDER_DESCRIPTOR)
            val application = IShizukuApplication.Stub.asInterface(data.readStrongBinder())
            // 1.1.2（授权回调修复）：官方 V13 封包是 [token][application][Bundle]，
            // rish 自定义封包是 [token][application][int][Bundle]。
            // 之前无条件 readInt() 把官方 Bundle 的 length 字段读走，args 解析错位。
            // 现在先记录 binder 后的位置，先尝试直接读 Bundle；失败就回退、跳 int 再读。
            val posAfterBinder = data.dataPosition()
            LOGGER.i("code=18 attach: application=%s, posAfterBinder=%d, avail=%d",
                if (application != null) "ok" else "null", posAfterBinder, data.dataAvail())
            var args: android.os.Bundle? = null
            try {
                if (data.dataAvail() > 0) {
                    args = data.readTypedObject(android.os.Bundle.CREATOR)
                }
            } catch (_: Throwable) {
                args = null
            }
            if (args == null && data.dataAvail() >= 4) {
                try {
                    data.setDataPosition(posAfterBinder)
                    data.readInt()
                    args = data.readTypedObject(android.os.Bundle.CREATOR)
                    LOGGER.i("code=18: 回退到 rish 格式（跳 int）成功")
                } catch (_: Throwable) {
                    args = null
                }
            }
            LOGGER.i("code=18 attach: uid=%d, pid=%d, args=%s, apiVersion=%d",
                getCallingUid(), getCallingPid(),
                if (args != null) "ok" else "null",
                args?.getInt(ShizukuApiConstants.AttachApplication.API_VERSION, -1) ?: -1)
            attachApplication(application, args)
            reply?.writeNoException()
            return true
        }

        // 事务码 14 = requestPermission(int)（现代 Shizuku API）；旧版客户端的
        // attachApplicationV11(IShizukuApplication, String) 回退通道也走 14。
        // 用 parcel 剩余数据区分：requestPermission 只有单个 int；V11 attach 在
        // binder 之后还有 packageName 字符串。两者互不干扰，与官方 v13 服务端行为一致。
        if (effCode == 14) {
            data.enforceInterface(ShizukuApiConstants.BINDER_DESCRIPTOR)
            val pos = data.dataPosition()
            val first = data.readInt() // binder handle（V11）或 requestCode（requestPermission）
            if (data.dataAvail() > 0) {
                data.setDataPosition(pos)
                val application = IShizukuApplication.Stub.asInterface(data.readStrongBinder())
                val packageName = data.readString()
                val args = android.os.Bundle().apply {
                    putString(ShizukuApiConstants.AttachApplication.PACKAGE_NAME, packageName)
                    putInt(ShizukuApiConstants.AttachApplication.API_VERSION, -1)
                }
                attachApplication(application, args)
            } else {
                requestPermission(first)
            }
            reply?.writeNoException()
            return true
        }

        if (effCode in 30000..30002) {
            // 1.1.2：rish 通道也要走授权弹窗。原来这里直接 enforceCallingPermission("rish")，
            // 权限停在「询问」时只会抛 SecurityException，用户永远看不到弹窗；
            // 现在未决定就弹窗并阻塞等结果，允许后才执行，拒绝则抛给 rish。
            val rishUid = getCallingUid()
            val rishPid = getCallingPid()
            if (!ensureRishPermission(rishUid, rishPid)) {
                throw SecurityException("Permission denied for rish")
            }
            return withClearedIdentity { rishService.onTransact(code, data, reply, flags) }
        }

        // 事务码 105 = getFlagsForUid(uid, mask)（自家 AIDL 编号）；官方 v13 客户端
        // （MT 管理器等）用 105 回传授权确认 dispatchPermissionConfirmationResult
        // (uid, pid, requestCode, Bundle)。用 parcel 剩余数据判别：
        // getFlagsForUid 只有 2 个 int；dispatch 在 3 个 int 之后还有 Bundle。
        // v5：此前 105 走 super（getFlagsForUid），管理器兜底回传的授权确认被吞，
        // MT 管理器永远等不到回调 → "请求 Shell 权限超时"。
        if (effCode == 105) {
            data.enforceInterface(ShizukuApiConstants.BINDER_DESCRIPTOR)
            val pos = data.dataPosition()
            data.readInt()
            data.readInt()
            if (data.dataAvail() > 0) {
                data.setDataPosition(pos)
                val uid = data.readInt()
                val pid = data.readInt()
                val requestCode = data.readInt()
                val bundle = data.readTypedObject(android.os.Bundle.CREATOR)
                dispatchPermissionConfirmationResult(uid, pid, requestCode, bundle)
            } else {
                // 自家生态的 getFlagsForUid 调用：直接分发给本类实现
                data.setDataPosition(pos)
                val uid = data.readInt()
                val mask = data.readInt()
                val result = getFlagsForUid(uid, mask)
                reply?.writeNoException()
                reply?.writeInt(result)
            }
            return true
        }

        return super.onTransact(effCode, data, reply, flags)
    }

    override fun getVersion(): Int {
        // 检测方法，不需要权限——否则 rish 模式（shell uid 2000）检测不到服务端
        return callback.serviceVersion
    }

    override fun getUid(): Int {
        return callback.serviceUid
    }

    override fun checkPermission(permission: String?): Int {
        if (!isEnabled()) return -1

        val callingUid = getCallingUid()
        val callingPid = getCallingPid()

        val hasPermission = checkPermission(callingUid) == ConfigManager.FLAG_GRANTED ||
                checkOnetimePermission(callingUid, callingPid)

        return if (hasPermission) 0 else -1
    }

    override fun getSELinuxContext(): String {
        return callback.serviceSeLinuxContext ?: throw IllegalStateException("无法获取 SELinux 上下文")
    }

    override fun getSystemProperty(name: String?, defaultValue: String?): String {
        enforceCallingPermission("getSystemProperty")
        return callback.getSystemProperty(name, defaultValue)
    }

    override fun setSystemProperty(name: String?, value: String?) {
        enforceCallingPermission("setSystemProperty")
        callback.setSystemProperty(name, value)
    }

    override fun newProcess(cmd: Array<String?>?, env: Array<String?>?, dir: String?): IRemoteProcess {
        enforceCallingPermission("newProcess")
        val callingUid = getCallingUid()
        val callingPid = getCallingPid()
        Log.d(TAG, "newProcess: uid=$callingUid, cmd=${cmd?.contentToString()}")
        val linProcess = withClearedIdentity { callback.newProcess(callingUid, callingPid, cmd, env, dir) }
        return LinRemoteProcessAdapter(linProcess)
    }

    override fun addUserService(conn: IShizukuServiceConnection?, args: android.os.Bundle?): Int {
        if (conn == null || args == null) {
            throw IllegalArgumentException("conn or args is null")
        }

        enforceCallingPermission("addUserService")

        val callingUid = getCallingUid()
        val callingPid = getCallingPid()

        Log.d(TAG, "addUserService: uid=$callingUid, pid=$callingPid")

        return withClearedIdentity {
            userServiceAdapter.addUserService(conn, args, callingUid, callingPid)
        }
    }

    override fun removeUserService(conn: IShizukuServiceConnection?, args: android.os.Bundle?): Int {
        if (conn == null || args == null) {
            throw IllegalArgumentException("conn or args is null")
        }

        enforceCallingPermission("removeUserService")

        Log.d(TAG, "removeUserService: uid=${getCallingUid()}")

        return withClearedIdentity {
            userServiceAdapter.removeUserService(conn, args)
        }
    }

    override fun requestPermission(requestCode: Int) {
        if (!isEnabled()) return

        val callingUid = getCallingUid()
        val callingPid = getCallingPid()
        val pkg = PackageManagerApis.getPackagesForUidNoThrow(callingUid).firstOrNull()
        LOGGER.i("requestPermission 到达: uid=%d, pid=%d, requestCode=%d, pkg=%s", callingUid, callingPid, requestCode, pkg)
        callback.requestPermission(callingUid, callingPid, requestCode)
    }

    override fun checkSelfPermission(): Boolean {
        if (!isEnabled()) return false

        val callingUid = getCallingUid()
        val callingPid = getCallingPid()

        val granted = checkPermission(callingUid) == ConfigManager.FLAG_GRANTED ||
                checkOnetimePermission(callingUid, callingPid)
        LOGGER.i("checkSelfPermission: uid=%d, pid=%d, granted=%b", callingUid, callingPid, granted)
        return granted
    }

    override fun shouldShowRequestPermissionRationale(): Boolean {
        if (!isEnabled()) return false

        val callingUid = getCallingUid()
        val callingPid = getCallingPid()

        if (checkPermission(callingUid) == ConfigManager.FLAG_DENIED) return true

        val lastDenyTime = getLastDenyTime(callingUid, callingPid)
        return lastDenyTime > 0 && (System.currentTimeMillis() - lastDenyTime) <= 10000
    }

    override fun attachApplication(application: IShizukuApplication?, args: android.os.Bundle?) {
        if (application == null) {
            LOGGER.w("attachApplication: application 为 null，跳过")
            return
        }
        if (!isEnabled()) {
            LOGGER.w("attachApplication: Shizuku compat layer is disabled")
            return
        }

        val callingUid = getCallingUid()
        val callingPid = getCallingPid()
        val apiVersion = args?.getInt(ShizukuApiConstants.AttachApplication.API_VERSION, -1) ?: -1

        LOGGER.i("attachApplication: uid=$callingUid, pid=$callingPid, apiVersion=$apiVersion")

        val packages = callback.getPackagesForUid(callingUid)
        val packageName = args?.getString(ShizukuApiConstants.AttachApplication.PACKAGE_NAME)
            ?: packages.firstOrNull()
            ?: "unknown"

        clientManager.attachShizukuApplication(callingUid, callingPid, application, packageName, apiVersion)

        val hasPermission = checkPermission(callingUid) == ConfigManager.FLAG_GRANTED ||
                checkOnetimePermission(callingUid, callingPid)

        // 1.1.2（询问态弹窗，attach 入口）：应用连上服务时权限还是「询问」，
        // 说明它既没被允许、也没被拒绝过。
        // 大量应用（雹的悬浮按钮、Tasker 的用户服务）在 checkSelfPermission() 拿到 -1
        // 之后就自己显示「无权限」直接放弃，根本不往下调用 API，服务端连事务都收不到，
        // 永远没有弹窗的机会。在 attach 这个必经入口主动投一次弹窗，
        // 把授权提前到「应用一连接」这一步；用户点「始终允许」后，后续调用全部通过。
        // 已被明确拒绝（FLAG_DENIED）的不再打扰。
        if (!hasPermission && !checkCallerManagerPermission(callingUid) &&
            checkPermission(callingUid) != ConfigManager.FLAG_DENIED
        ) {
            promptPermissionForAsk(callingUid, callingPid, "attach")
        }

        val replyServerVersion = if (apiVersion == -1) 12 else ShizukuApiConstants.SERVER_VERSION

        val reply = android.os.Bundle().apply {
            putInt(ShizukuApiConstants.BindApplication.SERVER_UID, callback.serviceUid)
            putInt(ShizukuApiConstants.BindApplication.SERVER_VERSION, replyServerVersion)
            putInt(ShizukuApiConstants.BindApplication.SERVER_PATCH_VERSION, ShizukuApiConstants.SERVER_PATCH_VERSION)
            putString(ShizukuApiConstants.BindApplication.SERVER_SECONTEXT, callback.serviceSeLinuxContext)
            putBoolean(ShizukuApiConstants.BindApplication.PERMISSION_GRANTED, hasPermission)
            putBoolean(ShizukuApiConstants.BindApplication.SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE, false)
        }

        try {
            application.bindApplication(reply)
            LOGGER.i("bindApplication 成功: uid=$callingUid, pid=$callingPid, granted=$hasPermission")
        } catch (e: Throwable) {
            LOGGER.w(e, "bindApplication 失败")
        }
    }

    override fun exit() {
        throw SecurityException("exit() not allowed")
    }

    override fun attachUserService(binder: android.os.IBinder?, options: android.os.Bundle?) {
        if (binder == null || options == null) {
            Log.w(TAG, "attachUserService: binder or options is null")
            return
        }

        val token = options.getString(ShizukuApiConstants.UserServiceArgs.TOKEN)
        if (token != null) {
            Log.d(TAG, "attachUserService: token=$token")
            withClearedIdentity {
                userServiceAdapter.onLinServiceAttached(token, binder)
            }
        }
    }

    override fun dispatchPackageChanged(intent: android.content.Intent?) {
    }

    override fun isHidden(uid: Int): Boolean = false

    override fun dispatchPermissionConfirmationResult(
        requestUid: Int,
        requestPid: Int,
        requestCode: Int,
        data: android.os.Bundle?
    ) {
        // v4（MT 授权超时修复）：1.0.8 服务端该接口有完整实现（授权确认→更新权限→通知客户端），
        // RC5 曾误留空实现，导致管理器经 Shizuku 兼容通道（intercept 105）回传的授权确认被吞，
        // MT 管理器永远等不到 dispatchRequestPermissionResult 回调 → "请求 Shell 权限超时"。
        // 此处补全为与 LinService（lin 通道）等价的处理，保证两条回传通道都闭环。
        if (data == null) return
        if (!isEnabled()) return

        // 官方 v13 回传 Bundle 无 permission key（仅 ALLOWED/IS_ONETIME），此处默认 shizuku。
        // 若兼容客户端额外携带 "shizuku:request-permission-reply-permission"，以之为准。
        val permission = data.getString(
            "shizuku:request-permission-reply-permission",
            ShizukuApiConstants.PERMISSION_NAME
        )
        if (permission != ShizukuApiConstants.PERMISSION_NAME) return

        val allowed = data.getBoolean(
            "shizuku:request-permission-reply-allowed",
            false
        )
        val onetime = data.getBoolean(
            "shizuku:request-permission-reply-is-onetime",
            false
        )

        val record = clientManager.findClient(requestUid, requestPid)
        val pkg = PackageManagerApis.getPackagesForUidNoThrow(requestUid).firstOrNull()
        LOGGER.i("dispatchPermissionConfirmationResult: uid=%d, pid=%d, requestCode=%d, allowed=%b, onetime=%b, pkg=%s, recordFound=%b",
            requestUid, requestPid, requestCode, allowed, onetime, pkg, record != null)

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
            // 双写 lin 键：自家生态应用（按 lin 键查询）也立即生效
            configManager.updatePermission(requestUid, lin.LinApiConstants.PERMISSION_LIN, newFlag)
            LOGGER.i("授权已写库: uid=%d, pkg=%s, shizukuFlag=%d, linFlag=%d", requestUid, pkg, newFlag, newFlag)
            clientManager.findClients(requestUid).forEach {
                it.onetimeMap.remove(ShizukuApiConstants.PERMISSION_NAME)
            }
            // 授权变更后补发 Binder：已 attach 的兼容应用无需重启进程即可重新绑定并生效
            if (newFlag == ConfigManager.FLAG_GRANTED) {
                lin.server.BinderSender.resendBinderToUid(requestUid)
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

        notifyPermissionResult(requestUid, requestPid, requestCode, allowed)
    }

    override fun getFlagsForUid(uid: Int, mask: Int): Int {
        if (!isEnabled()) return 0
        return ShizukuApiConstants.linToShizukuFlag(checkPermission(uid))
    }

    override fun updateFlagsForUid(uid: Int, mask: Int, value: Int) {
        if (!isEnabled()) return
        val linFlag = ShizukuApiConstants.shizukuToLinFlag(value)
        configManager.updatePermission(uid, ShizukuApiConstants.PERMISSION_NAME, linFlag)
        val granted = linFlag == ConfigManager.FLAG_GRANTED
        clientManager.findClients(uid).forEach {
            it.onetimeMap.remove(ShizukuApiConstants.PERMISSION_NAME)
            it.allowedMap[ShizukuApiConstants.PERMISSION_NAME] = granted
        }
    }

    fun notifyPermissionResult(uid: Int, pid: Int, requestCode: Int, allowed: Boolean) {
        val exactRecord = clientManager.findClient(uid, pid)
        if (exactRecord?.shizukuApplication != null) {
            exactRecord.dispatchShizukuPermissionResult(
                requestCode,
                allowed,
                callback.serviceUid,
                callback.serviceVersion,
                callback.serviceSeLinuxContext
            )
            return
        }

        val uidRecords = clientManager.findClients(uid).filter { it.shizukuApplication != null }
        if (uidRecords.isEmpty()) {
            LOGGER.w("notifyPermissionResult: 未找到 uid=$uid, pid=$pid 的可用客户端（exactRecord=%b, allUidClients=%d）",
                exactRecord != null, clientManager.findClients(uid).size)
            // 兜底：即使找不到客户端，也补发 Binder 让 App 重新 attach，
            // 下次 attach 时 bindApplication 会返回 PERMISSION_GRANTED=true
            try {
                BinderSender.resendBinderToUid(uid)
                LOGGER.i("notifyPermissionResult: 已补发 Binder 给 uid=$uid，等待客户端重新 attach")
            } catch (_: Throwable) {}
            return
        }

        LOGGER.w("notifyPermissionResult: 未命中 pid=$pid，回退为 uid=$uid 广播 ${uidRecords.size} 个客户端")
        uidRecords.forEach { record ->
            record.dispatchShizukuPermissionResult(
                requestCode,
                allowed,
                callback.serviceUid,
                callback.serviceVersion,
                callback.serviceSeLinuxContext
            )
        }
    }
}
