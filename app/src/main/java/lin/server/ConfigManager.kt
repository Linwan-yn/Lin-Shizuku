package lin.server

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import rikka.hidden.compat.ActivityManagerApis
import rikka.hidden.compat.PackageManagerApis
import rikka.hidden.compat.UserManagerApis
import lin.LinApiConstants.PERMISSIONS
import lin.LinApiConstants.PERMISSION_KEY
import lin.LinApiConstants.PERMISSION_LIN
import lin.server.LinConfig.PackageEntry
import lin.server.api.IContentProviderUtils
import lin.server.ktx.workerHandler
import lin.server.shizuku.ShizukuApiConstants
import lin.server.util.Logger
import lin.server.util.PackageManagerCompat
import lin.server.util.ProviderDiscovery
import lin.server.util.UserHandleCompat

class ConfigManager {

    private val mWriteRunner: Runnable = Runnable { saveToManager(config) }

    private val config: LinConfig
    private val configLoadedFromManager: Boolean
    val packages: MutableMap<Int, PackageEntry>
        get() = LinkedHashMap(config.packages)

    /** 77 轮：权限查询无锁只读缓存（uid → permission → flag）。
     *  服务端高频路径（Shizuku 生态应用 checkPermission/checkSelfPermission/getFlagsForUid）
     *  每次事务都查询权限标志，原实现全部走 synchronized 全局锁；加缓存后读路径无锁直返，
     *  响应更快、不阻塞 Binder 线程池（解决 MT 等生态应用偶发超时）。所有写路径末尾失效对应 uid。 */
    private val permissionCache = java.util.concurrent.ConcurrentHashMap<Int, java.util.concurrent.ConcurrentHashMap<String, Int>>()

    init {
        val loadResult = loadFromManagerWithStatus()
        this.config = loadResult.first
        this.configLoadedFromManager = loadResult.second

        var changed = false

        for (entry in LinkedHashMap(config.packages)) {

            val packages = PackageManagerApis.getPackagesForUidNoThrow(entry.key)
            if (packages.isEmpty()) {
                LOGGER.i("remove config for uid %d since it has gone", entry.key)
                config.packages.remove(entry.key)
                changed = true
                continue
            }

            var needRemoving = true

            for (packageName in entry.value.packages) {
                if (packages.contains(packageName)) {
                    needRemoving = false
                    break
                }
            }

            val rawSize = entry.value.packages.size
            val s = LinkedHashSet(entry.value.packages)
            entry.value.packages.clear()
            entry.value.packages.addAll(s)
            val shrunkSize = entry.value.packages.size
            if (shrunkSize < rawSize) {
                LOGGER.w("entry.packages has duplicate! Shrunk. (%d -> %d)", rawSize, shrunkSize)
            }

            if (needRemoving) {
                LOGGER.i("remove config for uid %d since the packages for it changed", entry.key)
                config.packages.remove(entry.key)
                changed = true
            }
        }

        for (userId in UserManagerApis.getUserIdsNoThrow()) {
            for (pi in PackageManagerCompat.getInstalledPackagesNoThrow(
                (PackageManager.MATCH_ALL or PackageManager.GET_META_DATA or PackageManager.GET_PROVIDERS).toLong(),
                userId
            )) {
                if (
                    pi == null ||
                    pi.applicationInfo == null ||
                    (
                        pi.applicationInfo!!.metaData
                            ?.getString(PERMISSION_KEY, "")
                            ?.split(",")
                            ?.map { it.trim() }
                            ?.any { PERMISSIONS.contains(it) } != true &&
                            !ProviderDiscovery.hasLinProvider(pi)
                    ) ||
                    pi.packageName == ServerConstants.MANAGER_APPLICATION_ID
                ) {
                    continue
                }

                val uid = pi.applicationInfo!!.uid

                val packages = ArrayList<String>()
                packages.add(pi.packageName)

                updateLocked(uid, packages)
                changed = true
            }
        }

        for (entry in LinkedHashMap(config.packages)) {
            val permissions = LinkedHashSet<String>()
            val packages = PackageManagerApis.getPackagesForUidNoThrow(entry.key)
            for (packageName in packages) {
                val applicationInfo = PackageManagerCompat.getApplicationInfo(
                    packageName, PackageManager.GET_META_DATA.toLong(),
                    UserHandleCompat.getUserId(entry.key)
                ) ?: continue
                for (permission in (applicationInfo.metaData?.getString(PERMISSION_KEY, "")
                    ?: "").split(",").map { it.trim() }) {
                    if (PERMISSIONS.contains(permission)) {
                        permissions.add(permission)
                    }
                }
                val packageInfo = PackageManagerCompat.getPackageInfo(
                    packageName,
                    (PackageManager.GET_META_DATA or PackageManager.GET_PROVIDERS).toLong(),
                    UserHandleCompat.getUserId(entry.key)
                )
                if (
                    applicationInfo.metaData?.getBoolean(ShizukuApiConstants.META_DATA_KEY, false) == true ||
                    packageInfo?.let { ProviderDiscovery.hasShizukuProvider(it) } == true
                ) {
                    permissions.add("shizuku")
                }
            }
            val packageEntry = findLocked(entry.key)!!
            val permissionsToRemove = mutableListOf<String>()
            for (permission in entry.value.permissions) {
                if (!permissions.contains(permission.key)) {
                    permissionsToRemove.add(permission.key)
                }
            }
            for (permissionKey in permissionsToRemove) {
                packageEntry.permissions.remove(permissionKey)
                changed = true
            }
            for (permission in permissions) {
                if (packageEntry.permissions[permission] == null) {
                    packageEntry.permissions[permission] = FLAG_ASK
                    changed = true
                }
            }
        }

        if (changed && configLoadedFromManager) {
            scheduleWriteLocked()
        }
    }

    private fun scheduleWriteLocked() {
        // 1.1.2（授权被清空修复）：从 manager 加载配置失败时，本进程里的 config 只是
        // 「按已安装应用重建出来的默认表」（每个应用 lin=0=询问），绝不能写回 manager，
        // 否则一次「服务端先起、manager 后起」的启动竞态就会把 manager 里保存的历史授权
        // 整体覆盖成全部「询问」。加载失败时改为只读运行，等下次成功加载后再写。
        if (!configLoadedFromManager) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (workerHandler.hasCallbacks(mWriteRunner)) {
                return
            }
        } else {
            workerHandler.removeCallbacks(mWriteRunner)
        }
        workerHandler.postDelayed(mWriteRunner, WRITE_DELAY)
    }

    private fun findLocked(uid: Int): PackageEntry? {
        return config.packages[uid]
    }

    fun find(uid: Int): PackageEntry? {
        synchronized(this) {
            return findLocked(uid)
        }
    }

    fun getPermissionFlag(uid: Int, permission: String): Int {
        // 77 轮：无锁读缓存快速路径（高频权限查询）
        permissionCache[uid]?.get(permission)?.let { return it }
        synchronized(this) {
            val flag = findLocked(uid)?.permissions?.get(permission) ?: FLAG_ASK
            val m = permissionCache.getOrPut(uid) { java.util.concurrent.ConcurrentHashMap() }
            m[permission] = flag
            return flag
        }
    }

    /** 已授权应用数：任一权限为 GRANTED 的 uid 计数（避免 stellar+shizuku 双权限重复计数）。 */
    fun getAuthorizedUidCount(): Int {
        synchronized(this) {
            return config.packages.values.count { entry ->
                entry.permissions.values.any { it == FLAG_GRANTED }
            }
        }
    }

    fun findOldConfigByPackageName(currentUid: Int, packageName: String): Pair<Int, PackageEntry>? {
        synchronized(this) {
            for ((uid, entry) in config.packages) {
                if (uid == currentUid) {
                    continue
                }
                if (entry.packages.contains(packageName)) {
                    return Pair(uid, entry)
                }
            }
            return null
        }
    }

    fun createConfigWithAllPermissions(uid: Int, packageName: String) {
        synchronized(this) {
            val userId = UserHandleCompat.getUserId(uid)
            val applicationInfo = PackageManagerCompat.getApplicationInfo(
                packageName,
                PackageManager.GET_META_DATA.toLong(),
                userId
            )

            if (applicationInfo == null) {
                LOGGER.w("无法获取应用信息: %s, 使用默认权限", packageName)
                val packages = mutableListOf(packageName)
                updateLocked(uid, packages)
                return
            }

            val declaredPermissions = LinkedHashSet<String>()
            val metaDataPermissions = applicationInfo.metaData?.getString(PERMISSION_KEY, "") ?: ""
            for (permission in metaDataPermissions.split(",").map { it.trim() }) {
                if (PERMISSIONS.contains(permission)) {
                    declaredPermissions.add(permission)
                }
            }

            LOGGER.i("应用 %s 声明的权限: %s", packageName, declaredPermissions.toString())

            val entry = PackageEntry()
            entry.packages.add(packageName)

            for (permission in declaredPermissions) {
                entry.permissions[permission] = FLAG_ASK
            }

            if (entry.permissions.isEmpty()) {
                entry.permissions[PERMISSION_LIN] = FLAG_ASK
            }

            config.packages[uid] = entry
            permissionCache.remove(uid)
            scheduleWriteLocked()

            LOGGER.i("已创建配置: uid=%d, package=%s, permissions=%s",
                uid, packageName, entry.permissions.toString())
        }
    }

    private fun updateLocked(
        uid: Int,
        packages: MutableList<String>?
    ) {
        var entry = findLocked(uid)
        if (entry == null) {
            entry = PackageEntry()
            entry.permissions[PERMISSION_LIN] = FLAG_ASK
            config.packages[uid] = entry
        }
        if (packages != null) {
            for (packageName in packages) {
                if (entry.packages.contains(packageName)) {
                    continue
                }
                entry.packages.add(packageName)
            }
        }
        permissionCache.remove(uid)
        scheduleWriteLocked()
    }

    fun update(
        uid: Int,
        packages: MutableList<String>?
    ) {
        synchronized(this) {
            updateLocked(uid, packages)
        }
    }

    private fun updatePermissionLocked(uid: Int, permission: String, newFlag: Int) {
        var entry = findLocked(uid)
        if (entry == null) {
            entry = PackageEntry()
            val packages = PackageManagerApis.getPackagesForUidNoThrow(uid)
            entry.packages.addAll(packages)
            config.packages[uid] = entry
            LOGGER.i("为 uid=%d 创建新配置以保存权限 %s", uid, permission)
        } else if (entry.packages.isEmpty()) {
            entry.packages.addAll(PackageManagerApis.getPackagesForUidNoThrow(uid))
            LOGGER.i("为 uid=%d 的权限配置补全包名: %s", uid, entry.packages.toString())
        }
        entry.permissions[permission] = newFlag
        permissionCache.remove(uid)
        scheduleWriteLocked()
    }

    fun updatePermission(uid: Int, permission: String, newFlag: Int) {
        synchronized(this) {
            updatePermissionLocked(uid, permission, newFlag)
        }
    }

    private fun removeLocked(uid: Int) {
        config.packages.remove(uid)
        permissionCache.remove(uid)
        scheduleWriteLocked()
    }

    fun remove(uid: Int) {
        synchronized(this) {
            removeLocked(uid)
        }
    }

    fun isShizukuCompatEnabled(): Boolean {
        synchronized(this) {
            return config.shizukuCompatEnabled
        }
    }

    fun setShizukuCompatEnabled(enabled: Boolean) {
        synchronized(this) {
            if (config.shizukuCompatEnabled != enabled) {
                config.shizukuCompatEnabled = enabled
                LOGGER.i("Shizuku 兼容层状态已更改: %s", if (enabled) "启用" else "禁用")
                scheduleWriteLocked()
            }
        }
    }

    fun isAccessibilityAutoStartEnabled(): Boolean {
        return try {
            val freshConfig = loadFromManager()
            freshConfig.accessibilityAutoStart
        } catch (_: Throwable) {
            false
        }
    }

    fun isDaemonEnabled(): Boolean {
        synchronized(this) {
            return config.daemonEnabled
        }
    }

    fun setDaemonEnabled(enabled: Boolean) {
        synchronized(this) {
            if (config.daemonEnabled != enabled) {
                config.daemonEnabled = enabled
                LOGGER.i("进程守护状态已更改: %s", if (enabled) "启用" else "禁用")
                scheduleWriteLocked()
            }
        }
    }

    companion object {
        private val LOGGER: Logger = Logger("ConfigManager")

        const val FLAG_ASK: Int = 0
        const val FLAG_GRANTED: Int = 1
        const val FLAG_DENIED: Int = 2

        private val GSON_IN: Gson = GsonBuilder().create()
        private val GSON_OUT: Gson = GsonBuilder()
            .setVersion(LinConfig.LATEST_VERSION.toDouble())
            .create()

        private const val WRITE_DELAY = 200L
        private const val MANAGER_PROVIDER = "${ServerConstants.MANAGER_APPLICATION_ID}.lin"

        // 1.1.2：缓存 IContentProvider，避免每次 saveConfig 都 get+remove（两次 Binder 往返 ~10-30ms）。
        // manager 进程长期存活，provider 稳定；binder 死亡或调用失败时清空缓存重新获取。
        @Volatile
        private var cachedProvider: android.content.IContentProvider? = null

        private fun obtainProvider(): android.content.IContentProvider? {
            cachedProvider?.let { p ->
                if (p.asBinder().isBinderAlive) return p
                cachedProvider = null
            }
            val p = ActivityManagerApis.getContentProviderExternal(MANAGER_PROVIDER, 0, null, MANAGER_PROVIDER)
            cachedProvider = p
            return p
        }

        private fun callProvider(method: String, extras: Bundle?): Bundle? {
            var provider = obtainProvider() ?: return null
            return try {
                IContentProviderUtils.callCompat(provider, null, MANAGER_PROVIDER, method, null, extras ?: Bundle())
            } catch (tr: Throwable) {
                LOGGER.w(tr, "ContentProvider 调用失败，重试一次: %s", method)
                cachedProvider = null
                provider = obtainProvider() ?: return null
                try {
                    IContentProviderUtils.callCompat(provider, null, MANAGER_PROVIDER, method, null, extras ?: Bundle())
                } catch (tr2: Throwable) {
                    LOGGER.e(tr2, "ContentProvider 重试仍失败: %s", method)
                    null
                }
            }
        }

        fun loadFromManagerWithStatus(): Pair<LinConfig, Boolean> {
            // 1.1.2：服务端常比 manager 先起（尤其覆盖安装后的首次重启），Provider 尚未就绪时
            // loadConfig 会返回 null。原来一次失败就落到「默认空配置」，再被 init 阶段的
            // updateLocked 写回 manager，历史授权就整份丢了。这里改成短重试，拿到再返回。
            repeat(3) { attempt ->
                try {
                    val reply = callProvider("loadConfig", null)
                    if (reply != null) {
                        val json = reply.getString("configJson")
                        if (json != null) {
                            val config = GSON_IN.fromJson(json, LinConfig::class.java) ?: LinConfig()
                            return Pair(config, true)
                        }
                        LOGGER.i("manager 无配置，使用默认值")
                        return Pair(LinConfig(), true)
                    }
                    LOGGER.w("从 manager 加载配置返回 null（manager 可能未就绪），第 %d 次", attempt + 1)
                } catch (tr: Throwable) {
                    LOGGER.w(tr, "从 manager 加载配置失败（第 %d 次）", attempt + 1)
                }
                if (attempt < 2) {
                    try {
                        Thread.sleep(300)
                    } catch (_: InterruptedException) {
                    }
                }
            }
            // 三次都失败：本轮只读运行（scheduleWriteLocked 会直接跳过），等下次启动成功加载。
            LOGGER.w("从 manager 加载配置失败，使用默认值并禁止本次启动写回")
            return Pair(LinConfig(), false)
        }

        fun loadFromManager(): LinConfig {
            return loadFromManagerWithStatus().first
        }

        fun saveToManager(config: LinConfig) {
            try {
                val json = GSON_OUT.toJson(config)
                val extras = Bundle().apply { putString("configJson", json) }
                val result = callProvider("saveConfig", extras)
                if (result != null) {
                    LOGGER.v("配置已保存到 manager")
                } else {
                    LOGGER.w("保存配置到 manager 失败: 返回 null")
                }
            } catch (tr: Throwable) {
                LOGGER.e(tr, "保存配置到 manager 失败")
            }
        }
    }
}
