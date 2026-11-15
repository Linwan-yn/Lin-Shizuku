package lin.server

import android.app.ActivityManagerHidden
import android.app.ActivityManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.RemoteException
import android.text.TextUtils
import rikka.hidden.compat.ActivityManagerApis
import rikka.hidden.compat.PackageManagerApis
import rikka.hidden.compat.adapter.ProcessObserverAdapter
import rikka.hidden.compat.adapter.UidObserverAdapter
import lin.LinApiConstants.PERMISSION_KEY
import lin.LinApiConstants.PERMISSION_LIN
import lin.server.ServerConstants.MANAGER_APPLICATION_ID
import lin.server.binder.BinderDistributor
import lin.server.ktx.mainHandler
import lin.server.shizuku.ShizukuApiConstants
import lin.server.util.Logger
import lin.server.util.PackageManagerCompat
import lin.server.util.ProviderDiscovery
import lin.server.util.UserHandleCompat.PER_USER_RANGE
import kotlin.system.exitProcess

object BinderSender {
    private val LOGGER = Logger("BinderSender")
    private var linService: LinService? = null
    private var initialManagerUid: Int = -1

    /**
     * 授权变更后补发 Binder：目标 uid 的进程若已在运行（可能是在授权前启动的，
     * 收不到服务启动时的补发），无需用户重启应用即可立即拿到 Shizuku Binder 生效。
     */
    fun resendBinderToUid(uid: Int) {
        try {
            val packages = PackageManagerApis.getPackagesForUidNoThrow(uid)
            if (packages.isEmpty()) return
            LOGGER.i("授权变更，补发 Binder：uid=%d，packages=%s", uid, TextUtils.join(", ", packages))
            sendBinder(uid, -1)
        } catch (t: Throwable) {
            LOGGER.w(t, "授权变更补发 Binder 失败：uid=%d", uid)
        }
    }

    @Throws(RemoteException::class)
    private fun sendBinder(uid: Int, pid: Int) {
        val packages = PackageManagerApis.getPackagesForUidNoThrow(uid)
        if (packages.isEmpty()) {
            LOGGER.w("sendBinder：uid=%d 没有关联包名", uid)
            return
        }

        LOGGER.v("sendBinder：准备向 uid=%d, pid=%d 发送 Binder，packages=%s", uid, pid, TextUtils.join(", ", packages))

        val userId = uid / PER_USER_RANGE
        for (packageName in packages) {
            try {
                val pi = PackageManagerCompat.getPackageInfo(
                    packageName,
                    // 仅需要 meta-data 与 providers 判断 Lin/Shizuku 支持；
                    // 不取 GET_PERMISSIONS（该 flag 会使 PackageInfo 携带大量权限数据，加大每次查询的内存与耗时）
                    (PackageManager.GET_META_DATA or PackageManager.GET_PROVIDERS).toLong(),
                    userId
                )

                if (pi == null) {
                    LOGGER.w("sendBinder：无法获取包信息：%s", packageName)
                    continue
                }

                if (pi.applicationInfo == null) {
                    LOGGER.w("sendBinder：applicationInfo 为 null：%s", packageName)
                    continue
                }

                if (pi.packageName == MANAGER_APPLICATION_ID) {
                    LOGGER.i("sendBinder：向管理器发送 Binder：%s", packageName)
                    BinderDistributor.sendBinderToManager(linService, userId)
                    return
                }

                val hasLinProvider = ProviderDiscovery.hasLinProvider(pi)
                val hasShizukuProvider = ProviderDiscovery.hasShizukuProvider(pi)
                val metaData = pi.applicationInfo!!.metaData
                if (metaData != null) {
                    val permissions = metaData.getString(PERMISSION_KEY, "")
                    val declaredPermissions = permissions.split(",").map { it.trim() }
                    if (declaredPermissions.contains(PERMISSION_LIN) || hasLinProvider) {
                        LOGGER.i("sendBinder：向用户应用发送 Lin Binder：%s", packageName)
                        val sent = BinderDistributor.sendBinderToUserApp(linService, packageName, userId)
                        if (sent) return
                        LOGGER.w("sendBinder：向 %s 发送 Lin Binder 失败，继续尝试同 UID 的其他包", packageName)
                        continue
                    }

                    val shizukuSupport =
                        metaData.getBoolean(ShizukuApiConstants.META_DATA_KEY, false) ||
                            metaData.getString(ShizukuApiConstants.META_DATA_KEY)
                                ?.equals("true", ignoreCase = true) == true ||
                            hasShizukuProvider

                    if (shizukuSupport) {
                        // 修复：不再以"是否声明 moe.shizuku.manager.permission.MANAGER"判定为
                        // Shizuku 管理器并跳过——大量 Shizuku 生态应用为兼容都会声明该权限，
                        // 但 signature 级权限实际拿不到，按声明跳过会导致它们永远收不到 Binder。
                        // 真正的管理器（MANAGER_APPLICATION_ID）已在上方 sendBinderToManager 处理。
                        LOGGER.i("sendBinder：向应用发送 Shizuku Binder：%s", packageName)
                        val sent = BinderDistributor.sendShizukuBinderToUserApp(
                            linService?.shizukuServiceIntercept,
                            packageName,
                            userId
                        )
                        if (sent) return
                        LOGGER.w("sendBinder：向 %s 发送 Shizuku Binder 失败，继续尝试同 UID 的其他包", packageName)
                        continue
                    }

                    LOGGER.d("sendBinder：包 %s 的 meta-data 未声明 Lin/Shizuku 支持", packageName)
                } else {
                    if (hasLinProvider) {
                        LOGGER.i("sendBinder：通过 Provider 向用户应用发送 Lin Binder：%s", packageName)
                        val sent = BinderDistributor.sendBinderToUserApp(linService, packageName, userId)
                        if (sent) return
                        LOGGER.w("sendBinder：向 %s 发送 Lin Binder 失败，继续尝试同 UID 的其他包", packageName)
                        continue
                    }

                    if (hasShizukuProvider) {
                        // 同上：不按声明 MANAGER 权限跳过，真正的管理器已在上方处理
                        LOGGER.i("sendBinder：通过 Provider 向应用发送 Shizuku Binder：%s", packageName)
                        val sent = BinderDistributor.sendShizukuBinderToUserApp(
                            linService?.shizukuServiceIntercept,
                            packageName,
                            userId
                        )
                        if (sent) return
                        LOGGER.w("sendBinder：向 %s 发送 Shizuku Binder 失败，继续尝试同 UID 的其他包", packageName)
                        continue
                    }

                    LOGGER.d("sendBinder：包 %s 没有 meta-data，也未发现兼容 Provider，跳过", packageName)
                }
            } catch (t: Throwable) {
                LOGGER.e(t, "sendBinder：处理包 %s 时异常，继续下一个包", packageName)
            }
        }

        LOGGER.d("sendBinder：uid=%d 的所有包都不满足条件，未发送 Binder", uid)
    }

    fun register(linService: LinService?) {
        BinderSender.linService = linService

        val ai = PackageManagerCompat.getApplicationInfo(MANAGER_APPLICATION_ID, 0, 0)
        if (ai != null) {
            initialManagerUid = ai.uid
            LOGGER.i("初始管理器 UID：%d", initialManagerUid)
        }

        try {
            ActivityManagerApis.registerProcessObserver(ProcessObserver())
        } catch (tr: Throwable) {
            LOGGER.e(tr, "注册进程观察者失败")
        }

        if (Build.VERSION.SDK_INT >= 26) {
            var flags =
                ActivityManagerHidden.UID_OBSERVER_GONE or ActivityManagerHidden.UID_OBSERVER_IDLE or ActivityManagerHidden.UID_OBSERVER_ACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                flags = flags or ActivityManagerHidden.UID_OBSERVER_CACHED
            }
            try {
                ActivityManagerApis.registerUidObserver(
                    UidObserver(),
                    flags,
                    ActivityManagerHidden.PROCESS_STATE_UNKNOWN,
                    null
                )
            } catch (tr: Throwable) {
                LOGGER.e(tr, "注册 UID 观察者失败")
            }
        }
    }

    fun sendBinderToAuthorizedRunningClients(configManager: ConfigManager) {
        val runningProcesses = getRunningAppProcesses()
        if (runningProcesses.isEmpty()) {
            LOGGER.w("启动补发 Binder：未获取到正在运行的进程")
            return
        }

        val handledUids = HashSet<Int>()
        for (process in runningProcesses) {
            val uid = process.uid
            if (!handledUids.add(uid)) continue

            val entry = configManager.find(uid) ?: continue
            if (entry.permissions.none { it.value == ConfigManager.FLAG_GRANTED }) {
                LOGGER.v("启动补发 Binder：跳过未授权 uid=%d，process=%s", uid, process.processName)
                continue
            }

            try {
                LOGGER.i(
                    "启动补发 Binder：向已运行且已授权的 uid=%d 发送 Binder，process=%s",
                    uid,
                    process.processName
                )
                sendBinder(uid, process.pid)
            } catch (tr: Throwable) {
                LOGGER.w(tr, "启动补发 Binder：uid=%d 发送失败", uid)
            }
        }
    }

    /**
     * 启动全量补发：遍历授权表中所有已授权 uid（不论进程是否在运行）。
     *
     * 仅补发"正在运行"的进程存在盲区：部分第三方应用进程早于服务启动且长期稳定运行
     * （无 uid active/cached/state 变化），UidObserver/ProcessObserver 永远不会触发它，
     * 授权补发（resendBinderToUid）也只在授权变更瞬间触发一次——这些应用将永远收不到
     * Shizuku Binder（表现为"请激活 Sui 或 Shizuku"、可用特权 0）。
     * 改为对授权表全量补发：未运行的进程 provider 获取失败返回 false，无副作用。
     */
    fun sendBinderToAllAuthorizedClients(configManager: ConfigManager) {
        val handledUids = HashSet<Int>()
        for ((uid, entry) in configManager.packages) {
            if (!handledUids.add(uid)) continue
            if (entry.permissions.none { it.value == ConfigManager.FLAG_GRANTED }) {
                LOGGER.v("启动全量补发 Binder：跳过未授权 uid=%d", uid)
                continue
            }
            try {
                LOGGER.i("启动全量补发 Binder：向已授权 uid=%d 发送 Binder（进程可能未运行，失败无害）", uid)
                sendBinder(uid, -1)
            } catch (tr: Throwable) {
                LOGGER.w(tr, "启动全量补发 Binder：uid=%d 发送失败", uid)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun getRunningAppProcesses(): List<ActivityManager.RunningAppProcessInfo> {
        return try {
            val service = ActivityManager::class.java
                .getDeclaredMethod("getService")
                .invoke(null)
            val method = service.javaClass.methods.firstOrNull {
                it.name == "getRunningAppProcesses" && it.parameterTypes.isEmpty()
            } ?: return emptyList()
            method.invoke(service) as? List<ActivityManager.RunningAppProcessInfo> ?: emptyList()
        } catch (tr: Throwable) {
            LOGGER.w(tr, "启动补发 Binder：获取正在运行的进程失败")
            emptyList()
        }
    }

    private class ProcessObserver : ProcessObserverAdapter() {
        // hidden compat 4.4.0 stub 将 onProcessStarted 声明为抽象；运行时 jar 版为具体方法，
        // 空 override 在两种版本下均合法
        override fun onProcessStarted(p0: Int, p1: Int, p2: Int, p3: String?, p4: String?) {
        }

        @Throws(RemoteException::class)
        override fun onForegroundActivitiesChanged(
            pid: Int,
            uid: Int,
            foregroundActivities: Boolean
        ) {
            LOGGER.d(
                "前台 Activity 状态变化：pid=%d，uid=%d，foregroundActivities=%s",
                pid,
                uid,
                foregroundActivities.toString()
            )

            synchronized(PID_LIST) {
                if (PID_LIST.contains(pid) || !foregroundActivities) {
                    return
                }
                PID_LIST.add(pid)
            }

            sendBinder(uid, pid)
        }

        override fun onProcessDied(pid: Int, uid: Int) {
            LOGGER.d("进程死亡：pid=%d，uid=%d", pid, uid)

            synchronized(PID_LIST) {
                PID_LIST.remove(pid)
            }
        }

        @Throws(RemoteException::class)
        override fun onProcessStateChanged(pid: Int, uid: Int, procState: Int) {
            LOGGER.d("进程状态变化：pid=%d，uid=%d，procState=%d", pid, uid, procState)

            synchronized(PID_LIST) {
                if (PID_LIST.contains(pid)) {
                    return
                }
                PID_LIST.add(pid)
            }

            sendBinder(uid, pid)
        }

        companion object {
            private val PID_LIST: MutableList<Int> = ArrayList()
        }
    }

    private class UidObserver : UidObserverAdapter() {
        @Throws(RemoteException::class)
        override fun onUidActive(uid: Int) {
            LOGGER.v("UID 变为 active：uid=%d", uid)

            if (linService?.permissionEnforcer?.isManager(
                    lin.server.communication.CallerContext(uid, 0)
                ) == true
            ) {
                LOGGER.i("检测到管理器 UID active，延迟 500ms 后发送 Binder")
                mainHandler.postDelayed({
                    uidStarts(uid)
                }, 500)
            } else {
                uidStarts(uid)
            }
        }

        @Throws(RemoteException::class)
        override fun onUidCachedChanged(uid: Int, cached: Boolean) {
            // 风暴治理：HyperOS 上前台/缓存切换会高频触发 cached 事件（实测 600ms 内上百次）。
            // 1) cached=true（进入缓存）无需任何处理；2) cached=false 时若 uid 已在 UID_LIST
            //（此前已补发过 Binder），同样直接返回——两种情况均不打日志，消除日志/字符串分配风暴。
            if (cached) return
            synchronized(UID_LIST) {
                if (UID_LIST.contains(uid)) return
            }
            uidStarts(uid)
        }

        @Throws(RemoteException::class)
        override fun onUidIdle(uid: Int, disabled: Boolean) {
            // 1.1.2：idle 不代表 Binder 失效（uid 仍在 UID_LIST 中，gone 时才移除并补发），
            // 原先每次 idle 打一条 I 日志 + 调 uidStarts（内部必然命中 UID_LIST 直接返回）——
            // 纯无效扫描且刷屏（实测每分钟十余条 UID idle/active）。此处只保留调试日志。
            LOGGER.v("UID idle：uid=%d，disabled=%s", uid, disabled.toString())
        }

        @Throws(RemoteException::class)
        override fun onUidGone(uid: Int, disabled: Boolean) {
            // 1.1.2：切换应用时系统会成批回调 UID gone（一次十几条），降到 DEBUG，
            // 避免把运行日志刷满；排障时再临时开 verbose。
            LOGGER.d("UID gone：uid=%d，disabled=%s，应用可能已停止或卸载", uid, disabled.toString())

            uidGone(uid)

            if (linService?.permissionEnforcer?.isManager(
                    lin.server.communication.CallerContext(uid, 0)
                ) == true
            ) {
                LOGGER.w("检测到管理器 UID gone，检查管理器是否仍然安装")
                mainHandler.postDelayed({
                    val ai = PackageManagerCompat.getApplicationInfo(MANAGER_APPLICATION_ID, 0, 0)
                    if (ai == null) {
                        LOGGER.w("管理器应用已卸载，退出服务")
                        exitProcess(ServerConstants.MANAGER_APP_NOT_FOUND)
                    } else {
                        LOGGER.i("管理器应用仍然存在，可能只是进程终止")
                    }
                }, 2000)
            }
        }

        @Throws(RemoteException::class)
        fun uidStarts(uid: Int) {
            synchronized(UID_LIST) {
                if (UID_LIST.contains(uid)) {
                    // 已启动过的 uid 直接返回，不打日志（cached 风暴下该分支会被高频命中）
                    return
                }
                UID_LIST.add(uid)
                LOGGER.v("UID %d 启动", uid)
            }

            if (initialManagerUid != -1) {
                // 管理器 UID 变化检测降频：UID 启动事件高频，PMS 查询每次都有开销，
                // 2 秒内只查一次，避免高频事件放大 CPU 占用
                val now = System.currentTimeMillis()
                var needCheck = false
                synchronized(UID_LIST) {
                    if (now - lastManagerCheck > 2000) {
                        lastManagerCheck = now
                        needCheck = true
                    }
                }
                if (needCheck) {
                    val ai = PackageManagerCompat.getApplicationInfo(MANAGER_APPLICATION_ID, 0, 0)
                    if (ai != null && ai.uid != initialManagerUid) {
                        LOGGER.w("检测到管理器 UID 变化：%d -> %d，退出服务", initialManagerUid, ai.uid)
                        exitProcess(ServerConstants.MANAGER_APP_NOT_FOUND)
                    }
                }
            }

            sendBinder(uid, -1)
        }

        fun uidGone(uid: Int) {
            synchronized(UID_LIST) {
                if (UID_LIST.remove(uid)) {
                    LOGGER.v("UID 已移除启动记录：uid=%d", uid)
                }
            }
        }

        companion object {
            private val UID_LIST: MutableList<Int> = ArrayList()
            /** 管理器 UID 变化检测的最近查询时间戳（降频用） */
            private var lastManagerCheck: Long = 0L
        }
    }
}
