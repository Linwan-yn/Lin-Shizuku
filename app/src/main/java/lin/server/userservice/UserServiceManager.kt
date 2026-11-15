package lin.server.userservice

import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.lin.server.IUserServiceCallback
import lin.server.ApkChangedObservers
import lin.server.ServerConstants
import lin.server.util.Logger
import lin.server.util.PackageManagerCompat
import lin.server.util.UserHandleCompat
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class UserServiceManager {
    companion object {
        private val LOGGER = Logger("UserServiceManager")
        private const val SAFE_APK_DIR = "/data/local/tmp/Lin-Shizuku/Lin/Apk"
        private const val SAFE_APK_PATH = "$SAFE_APK_DIR/Lin_safe.apk"
        // 旧版路径残留：升级后自动清理（/data/local/tmp/stellar 与 /data/local/tmp/stellar_manager_safe.apk）
        private const val LEGACY_SAFE_APK_DIR = "/data/local/tmp/stellar"
        private const val LEGACY_SAFE_APK_PATH = "/data/local/tmp/stellar_manager_safe.apk"
        // 上一版路径残留（本次改名前）：/data/local/tmp/Lin-Shizuku/stellar/manager_safe.apk
        private const val LEGACY_V1_SAFE_APK_DIR = "/data/local/tmp/Lin-Shizuku/stellar"
        private const val LEGACY_V1_SAFE_APK_PATH = "/data/local/tmp/Lin-Shizuku/stellar/manager_safe.apk"
        private const val DIRECTORY_MODE = 0x1C0 // 0700
        private const val FILE_MODE = 0x100 // 0400
        private const val PERMISSION_MASK = 0x1FF // 0777
        private val safeApkLock = Any()
    }

    private val servicesByToken = ConcurrentHashMap<String, UserServiceRecord>()

    private val servicesByPackage = ConcurrentHashMap<String, MutableList<UserServiceRecord>>()

    /** 1.1.2：startUserService 并发去重锁——查重与注册必须在同一临界区内完成，
     *  否则两个并发请求会各建一条记录、各拉一个 app_process（实测日志同一毫秒
     *  出现两份 UserServiceStarter 启动记录）。 */
    private val startLock = Any()

    private val apkListeners = ConcurrentHashMap<UserServiceRecord, () -> Unit>()

    fun startUserService(
        callingUid: Int,
        callingPid: Int,
        args: Bundle,
        callback: IUserServiceCallback?
    ): String? {
        val packageName = args.getString(UserServiceConstants.ARG_PACKAGE_NAME)
        val className = args.getString(UserServiceConstants.ARG_CLASS_NAME)
        val processNameSuffix = args.getString(UserServiceConstants.ARG_PROCESS_NAME_SUFFIX)
            ?: "userservice"
        val debug = args.getBoolean(UserServiceConstants.ARG_DEBUG, false)
        val use32Bit = args.getBoolean(UserServiceConstants.ARG_USE_32_BIT, false)
        val versionCode = args.getLong(UserServiceConstants.ARG_VERSION_CODE, 0)
        val serviceMode = args.getInt(UserServiceConstants.ARG_SERVICE_MODE, UserServiceConstants.MODE_ONE_TIME)
        val verificationToken = args.getString(UserServiceConstants.ARG_VERIFICATION_TOKEN) ?: ""
        val userId = UserHandleCompat.getUserId(callingUid)

        if (packageName.isNullOrEmpty() || className.isNullOrEmpty()) {
            LOGGER.w("参数无效: packageName=%s, className=%s", packageName, className)
            notifyStartFailed(callback, UserServiceConstants.ERROR_INVALID_ARGS,
                "Invalid arguments: packageName or className is empty")
            return null
        }

        val packageInfo = PackageManagerCompat.getPackageInfo(packageName, 0, userId)
        if (packageInfo == null) {
            LOGGER.w("包未找到: %s", packageName)
            notifyStartFailed(callback, UserServiceConstants.ERROR_PACKAGE_NOT_FOUND,
                "Package not found: $packageName")
            return null
        }

        val applicationInfo = packageInfo.applicationInfo
        if (applicationInfo == null) {
            LOGGER.w("应用信息为空: %s", packageName)
            notifyStartFailed(callback, UserServiceConstants.ERROR_PACKAGE_NOT_FOUND,
                "Application info is null: $packageName")
            return null
        }

        val apkPath = applicationInfo.sourceDir

        val token = UUID.randomUUID().toString()

        val key = "$packageName:$className:$processNameSuffix"
        // 1.1.2：并发去重——查重、启动中判断与注册放在同一临界区，杜绝双启。
        var reuseRecord: UserServiceRecord? = null
        var newRecord: UserServiceRecord? = null
        synchronized(startLock) {
            val existingRecord = findRecordByKey(key, callingUid)
            if (existingRecord != null) {
                when {
                    existingRecord.versionCode == versionCode && existingRecord.isConnected -> {
                        reuseRecord = existingRecord
                    }
                    // 正在启动（进程已拉起但尚未 attach）：绝不能走「停止旧服务以升级」，
                    // 否则会把刚拉起的进程杀掉再重启，客户端在窗口内看不到任何服务。
                    existingRecord.started && !existingRecord.isConnected -> {
                        reuseRecord = existingRecord
                    }
                    else -> {
                        LOGGER.i("停止旧服务以升级: %s", key)
                        existingRecord.removeSelf(silent = true)
                    }
                }
            }

            if (reuseRecord == null) {
                val record = UserServiceRecord(
                    token = token,
                    callingUid = callingUid,
                    callingPid = callingPid,
                    packageName = packageName,
                    className = className,
                    processNameSuffix = processNameSuffix,
                    versionCode = versionCode,
                    serviceMode = serviceMode,
                    verificationToken = verificationToken,
                    callback = callback,
                    onRemove = { removeRecord(it) }
                )
                servicesByToken[token] = record
                servicesByPackage.getOrPut(packageName) {
                    Collections.synchronizedList(mutableListOf())
                }.add(record)
                // 1.1.2（并发双启二次修复）：started 必须在这里、在锁内立即置位。
                // exec 在锁外执行，若等到 exec 成功才置 started，就存在一个窗口：
                // 第二个并发请求进锁后看到 started=false && isConnected=false，
                // 会把这个「刚注册、正要启动」的记录当成旧服务 removeSelf 掉再起一个新进程，
                // 用户服务日志里就会看到同一毫秒成对的两条“UserServiceStarter 启动”。
                record.started = true
                newRecord = record
            }
        }

        reuseRecord?.let { existing ->
            LOGGER.i("复用已存在的服务: %s", key)
            // 启动中的记录 binder 仍为 null，此时只返回同一 token，由首次请求的
            // callback 在 attachUserService 时收到连接通知。
            val binder = existing.serviceBinder
            if (binder != null) {
                try {
                    callback?.onServiceConnected(binder, existing.verificationToken)
                } catch (e: Exception) {
                    LOGGER.w(e, "通知复用服务失败")
                }
            }
            return existing.token
        }

        val record = newRecord!!

        setupApkObserver(record, apkPath)

        val cmd = generateStartCommand(record, apkPath, debug, use32Bit)
        LOGGER.i("启动 UserService: %s", record.className)

        // 1.1.2（Tasker/Dhizuku 用户服务超时修复）：用户服务进程启动后要经 Manager 的
        // Lin Provider 把 service binder 回传给服务端。Manager 常是刚被 Provider call
        // 拉起的冷进程，此时 Lin.binder 还没注入，回传会失败、进程直接退出，
        // 服务端只能等 8 秒后报「未附加」。
        // 在 exec 之前主动给 Manager 补发一次 Binder，让回传链路一启动就可用。
        runCatching {
            val managerUid = PackageManagerCompat.getApplicationInfo(
                ServerConstants.MANAGER_APPLICATION_ID, 0, 0
            )?.uid
            if (managerUid != null) {
                lin.server.BinderSender.resendBinderToUid(managerUid)
            }
        }.onFailure {
            LOGGER.w(it, "启动前补发 Manager Binder 失败")
        }

        try {
            Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            record.started = true
        } catch (e: Exception) {
            LOGGER.e(e, "启动 UserService 进程失败")
            removeRecord(record)
            notifyStartFailed(callback, UserServiceConstants.ERROR_PROCESS_START_FAILED,
                "Failed to start process: ${e.message}")
            return null
        }

        // v6（MT 授权超时修复）：启动 UserService 进程后开启看门狗——
        // 8 秒内用户服务（MT 管理器的 Dhizuku server）未回连 attach 即记录错误，
        // 并在运行日志中提示查看 UserServiceStarter 落盘日志定位失败环节。
        val watchdogRecord = record
        val watchdogKey = key
        Thread {
            try {
                Thread.sleep(8000)
            } catch (_: InterruptedException) {
            }
            val rec = watchdogRecord
            if (!rec.isConnected) {
                LOGGER.e(
                    "用户服务启动后 8 秒内未附加（attachUserService 未到达）：key=%s，进程已启动=%s，请查看 /data/local/tmp/Lin-Shizuku/Lin/log/Lin_user_service.log 定位 UserServiceStarter 失败环节",
                    watchdogKey, rec.started
                )
            }
        }.start()

        return token
    }

    fun stopUserService(token: String): Boolean {
        val record = servicesByToken[token] ?: return false

        LOGGER.i("停止 UserService: %s", record.className)
        record.removeSelf()
        return true
    }

    fun attachUserService(binder: IBinder, options: Bundle): Boolean {
        val token = options.getString(UserServiceConstants.OPT_TOKEN)
        if (token == null) {
            LOGGER.w("attachUserService: 缺少 token")
            return false
        }

        val record = servicesByToken[token]
        if (record == null) {
            LOGGER.w("未找到 token 对应的记录: %s (未授权的启动)", token)
            return false
        }

        val pid = options.getInt(UserServiceConstants.OPT_PID, -1)
        LOGGER.i("UserService 已附加: token=%s, package=%s, pid=%d",
            token, record.packageName, pid)

        record.onServiceAttached(binder, pid)
        return true
    }

    fun getUserServiceCount(callingUid: Int): Int =
        servicesByToken.values.count { it.callingUid == callingUid }

    fun removeUserServicesForPackage(packageName: String) {
        val records = servicesByPackage[packageName]?.toList() ?: return
        for (record in records) {
            record.removeSelf()
        }
    }

    fun onClientDisconnected(callingUid: Int, callingPid: Int) {
        LOGGER.i("客户端断开连接: uid=%d, pid=%d", callingUid, callingPid)

        val recordsToRemove = servicesByToken.values.filter { record ->
            record.callingUid == callingUid &&
            record.serviceMode == UserServiceConstants.MODE_ONE_TIME
        }

        for (record in recordsToRemove) {
            LOGGER.i("停止一次性服务: package=%s, class=%s",
                record.packageName, record.className)
            record.removeSelf()
        }
    }

    fun getDaemonServices(packageName: String, callingUid: Int): List<UserServiceRecord> {
        return servicesByToken.values.filter { record ->
            record.packageName == packageName &&
            record.callingUid == callingUid &&
            record.serviceMode == UserServiceConstants.MODE_DAEMON &&
            record.isConnected
        }
    }

    private fun findRecordByKey(key: String, callingUid: Int): UserServiceRecord? =
        servicesByToken.values.find { it.getKey() == key && it.callingUid == callingUid }

    private fun removeRecord(record: UserServiceRecord) {
        servicesByToken.remove(record.token)
        servicesByPackage[record.packageName]?.remove(record)
        apkListeners.remove(record)
    }

    private fun setupApkObserver(record: UserServiceRecord, apkPath: String) {
        val listener: () -> Unit = {
            val userId = UserHandleCompat.getUserId(record.callingUid)
            val newPi = PackageManagerCompat.getPackageInfo(record.packageName, 0, userId)

            if (newPi == null) {
                LOGGER.i("包已移除，停止服务: %s", record.packageName)
                record.removeSelf()
            } else {
                LOGGER.i("包已更新，服务将在下次请求时重启")
            }
        }

        apkListeners[record] = listener
        ApkChangedObservers.start(apkPath, listener)
    }

    private fun generateStartCommand(
        record: UserServiceRecord,
        apkPath: String,
        debug: Boolean,
        use32Bit: Boolean
    ): String {
        val appProcess = if (use32Bit && File("/system/bin/app_process32").exists()) {
            "/system/bin/app_process32"
        } else {
            "/system/bin/app_process"
        }

        val managerApkPath = prepareSafeManagerApk(PackageManagerCompat.getApplicationInfo(
            ServerConstants.MANAGER_APPLICATION_ID, 0, 0
        )?.sourceDir ?: "")

        val processName = "${record.packageName}:${record.processNameSuffix}"
        val debugArgs = if (debug) getDebugArgs() else ""
        val debugName = if (debug) " --debug-name=$processName" else ""

        return String.format(
            Locale.ENGLISH,
            UserServiceConstants.USER_SERVICE_CMD_FORMAT,
            managerApkPath,
            appProcess,
            debugArgs,
            processName,
            record.token,
            record.packageName,
            record.className,
            record.callingUid,
            record.serviceMode,
            record.verificationToken,
            debugName
        )
    }

    private fun prepareSafeManagerApk(sourcePath: String): String = synchronized(safeApkLock) {
        if (sourcePath.isBlank()) return@synchronized sourcePath

        try {
            val sourceFile = File(sourcePath)
            checkSafePath(sourceFile, directory = false, expectedMode = null, checkOwner = false)

            val safeDir = File(SAFE_APK_DIR)
            if (lstatOrNull(safeDir) == null) {
                check(safeDir.mkdirs()) { "无法创建安全 APK 目录" }
            } else {
                checkSafePath(safeDir, directory = true, expectedMode = null)
            }
            Os.chmod(safeDir.absolutePath, DIRECTORY_MODE)
            checkSafePath(safeDir, directory = true, expectedMode = DIRECTORY_MODE)

            deleteOwnedFile(File(LEGACY_SAFE_APK_PATH))
            deleteOwnedDir(File(LEGACY_SAFE_APK_DIR))
            deleteOwnedFile(File(LEGACY_V1_SAFE_APK_PATH))
            deleteOwnedDir(File(LEGACY_V1_SAFE_APK_DIR))

            val destFile = File(SAFE_APK_PATH)
            if (lstatOrNull(destFile) != null) {
                checkSafePath(destFile, directory = false, expectedMode = null)
            }

            val needsUpdate = lstatOrNull(destFile) == null ||
                destFile.length() != sourceFile.length() ||
                destFile.lastModified() != sourceFile.lastModified()
            if (needsUpdate) {
                val sourceDigest = sha256(sourceFile)
                val tempFile = File(safeDir, ".Lin_safe.apk.tmp-${Process.myPid()}")
                try {
                    if (lstatOrNull(tempFile) != null) {
                        checkSafePath(tempFile, directory = false, expectedMode = null)
                        check(tempFile.delete()) { "无法删除旧临时 APK" }
                    }
                    FileOutputStream(tempFile).use { output ->
                        sourceFile.inputStream().use { it.copyTo(output) }
                        output.fd.sync()
                    }
                    check(tempFile.setLastModified(sourceFile.lastModified())) {
                        "无法同步临时 APK 修改时间"
                    }
                    Os.chmod(tempFile.absolutePath, FILE_MODE)
                    checkSafePath(tempFile, directory = false, expectedMode = FILE_MODE)
                    check(MessageDigest.isEqual(sourceDigest, sha256(tempFile))) {
                        "临时 APK SHA-256 校验失败"
                    }
                    Os.rename(tempFile.absolutePath, destFile.absolutePath)
                } finally {
                    deleteOwnedFile(tempFile)
                }
            }

            Os.chmod(destFile.absolutePath, FILE_MODE)
            checkSafePath(destFile, directory = false, expectedMode = FILE_MODE)
            destFile.absolutePath
        } catch (e: Throwable) {
            LOGGER.e(e, "准备安全 APK 失败，回退到原路径")
            sourcePath
        }
    }

    fun cleanupManagerApkCache() = synchronized(safeApkLock) {
        deleteOwnedFile(File(LEGACY_SAFE_APK_PATH))
        deleteOwnedDir(File(LEGACY_SAFE_APK_DIR))
        deleteOwnedFile(File(LEGACY_V1_SAFE_APK_PATH))
        deleteOwnedDir(File(LEGACY_V1_SAFE_APK_DIR))
        val safeDir = File(SAFE_APK_DIR)
        val stat = lstatOrNull(safeDir)
        if (stat != null && !OsConstants.S_ISLNK(stat.st_mode) &&
            OsConstants.S_ISDIR(stat.st_mode) && stat.st_uid == Process.myUid()) {
            deleteOwnedFile(File(SAFE_APK_PATH))
            safeDir.listFiles()
                ?.filter { it.name.startsWith(".Lin_safe.apk.tmp-") }
                ?.forEach(::deleteOwnedFile)
            safeDir.delete()
        }
    }

    private fun checkSafePath(
        file: File,
        directory: Boolean,
        expectedMode: Int?,
        checkOwner: Boolean = true
    ) {
        val stat = lstatOrNull(file) ?: error("路径不存在: ${file.absolutePath}")
        check(!OsConstants.S_ISLNK(stat.st_mode)) { "拒绝符号链接: ${file.absolutePath}" }
        check(if (directory) OsConstants.S_ISDIR(stat.st_mode) else OsConstants.S_ISREG(stat.st_mode)) {
            "路径类型错误: ${file.absolutePath}"
        }
        if (checkOwner) check(stat.st_uid == Process.myUid()) { "路径所有者不匹配: ${file.absolutePath}" }
        if (expectedMode != null) {
            check(stat.st_mode and PERMISSION_MASK == expectedMode) { "路径权限不安全: ${file.absolutePath}" }
        }
    }

    private fun deleteOwnedFile(file: File) {
        val stat = lstatOrNull(file) ?: return
        if (stat.st_uid == Process.myUid() &&
            (OsConstants.S_ISREG(stat.st_mode) || OsConstants.S_ISLNK(stat.st_mode))) {
            file.delete()
        }
    }

    /** 删除自建的目录（仅限 owner 匹配且非符号链接，防止跟随链接误删其它位置）。 */
    private fun deleteOwnedDir(file: File) {
        val stat = lstatOrNull(file) ?: return
        if (stat.st_uid == Process.myUid() &&
            OsConstants.S_ISDIR(stat.st_mode) && !OsConstants.S_ISLNK(stat.st_mode)) {
            file.deleteRecursively()
        }
    }

    private fun sha256(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest()
    }

    private fun lstatOrNull(file: File) = try {
        Os.lstat(file.absolutePath)
    } catch (e: ErrnoException) {
        if (e.errno == OsConstants.ENOENT) null else throw e
    }

    private fun getDebugArgs(): String =
        " -Xcompiler-option --debuggable" +
        " -XjdwpProvider:adbconnection" +
        " -XjdwpOptions:suspend=n,server=y"

    private fun notifyStartFailed(callback: IUserServiceCallback?, errorCode: Int, message: String) {
        try {
            callback?.onServiceStartFailed(errorCode, message)
        } catch (e: Exception) {
            LOGGER.w(e, "通知启动失败失败")
        }
    }
}
