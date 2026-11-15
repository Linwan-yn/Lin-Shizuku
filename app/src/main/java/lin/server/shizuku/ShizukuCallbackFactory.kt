package lin.server.shizuku

import android.content.Intent
import android.os.Process
import android.system.Os
import rikka.hidden.compat.ActivityManagerApis
import rikka.hidden.compat.PackageManagerApis
import lin.LinApiConstants
import lin.server.ClientManager
import lin.server.ConfigManager
import lin.server.ServerConstants
import lin.server.service.LinServiceCore
import lin.server.userservice.UserServiceManager
import lin.server.util.Logger
import lin.server.util.PackageManagerCompat

object ShizukuCallbackFactory {
    private val LOGGER = Logger("ShizukuCallbackFactory")

    fun create(
        clientManager: ClientManager,
        configManager: ConfigManager,
        userServiceManager: UserServiceManager,
        managerAppId: Int,
        serviceCore: LinServiceCore,
        shizukuNotifier: ShizukuPermissionNotifier
    ): ShizukuServiceCallback {
        val cachedUid = Os.getuid()
        val cachedPid = Process.myPid()
        val cachedSeContext = try { android.os.SELinux.getContext() } catch (_: Throwable) { null }

        return object : ShizukuServiceCallback {
            override val serviceUid: Int = cachedUid
            override val serviceVersion: Int = LinApiConstants.SERVER_VERSION
            override val serviceSeLinuxContext: String? = cachedSeContext

            override val clientManager: ClientManager get() = clientManager
            override val configManager: ConfigManager get() = configManager
            override val userServiceManager: UserServiceManager get() = userServiceManager
            override val managerAppId: Int get() = managerAppId
            override val servicePid: Int = cachedPid

            override fun getPackagesForUid(uid: Int): List<String> {
                return PackageManagerApis.getPackagesForUidNoThrow(uid).toList()
            }

            override fun getSystemProperty(name: String?, defaultValue: String?): String {
                return android.os.SystemProperties.get(name, defaultValue)
            }

            override fun setSystemProperty(name: String?, value: String?) {
                android.os.SystemProperties.set(name, value)
            }

            override fun newProcess(uid: Int, pid: Int, cmd: Array<String?>?, env: Array<String?>?, dir: String?): com.lin.server.IRemoteProcess {
                return serviceCore.processManager.newProcess(uid, pid, cmd ?: emptyArray(), env, dir)
            }

            override fun requestPermission(uid: Int, pid: Int, requestCode: Int) {
                val userId = uid / 100000
                // 1.1.2（rish 授权修复）：rish 走的是 app_process，uid 属于发起它的应用
                // （Termux / Scene），但某些环境下 getPackagesForUid 会返回空。
                // 原来这里直接 return，既不弹窗也不回调，客户端只能等到 5 秒超时报
                // "Request timeout"，看起来就是「执行 rish 没反应」。
                // 现在依次兜底：uid 包名 → 已 attach 的 client 记录 → shell 固定包名。
                val packages = PackageManagerApis.getPackagesForUidNoThrow(uid)
                val clientPackage = try {
                    clientManager.findClient(uid, pid)?.packageName
                } catch (_: Throwable) {
                    null
                }
                val packageName = packages.firstOrNull()
                    ?: clientPackage
                    ?: if (uid == 2000) "com.android.shell" else "unknown"

                LOGGER.i(
                    "Shizuku 权限请求: uid=%d, pid=%d, pkg=%s, code=%d, uidPkgs=%s, clientPkg=%s",
                    uid, pid, packageName, requestCode, packages?.toString() ?: "[]",
                    clientPackage ?: "-"
                )

                val ai = PackageManagerCompat.getApplicationInfo(packageName, 0, userId) ?: run {
                    // 包名解析不到时也继续弹窗，只用一个最小 ApplicationInfo，
                    // 界面上退化为显示包名，而不是把整个请求吞掉。
                    LOGGER.w("无法获取应用信息，使用占位信息继续弹窗: $packageName")
                    android.content.pm.ApplicationInfo().apply {
                        this.packageName = packageName
                    }
                }

                val currentFlag = configManager.getPermissionFlag(uid, ShizukuApiConstants.PERMISSION_NAME)

                if (currentFlag == ConfigManager.FLAG_DENIED) {
                    LOGGER.i("Shizuku 权限已被永久拒绝: uid=$uid")
                    shizukuNotifier.notifyPermissionResult(uid, pid, requestCode, false)
                    return
                }

                if (currentFlag == ConfigManager.FLAG_GRANTED) {
                    LOGGER.i("Shizuku 权限已被永久授权: uid=$uid")
                    shizukuNotifier.notifyPermissionResult(uid, pid, requestCode, true)
                    return
                }

                // 1.1.2（授权弹窗缺失修复）：首次请求时这里会创建默认配置。
                // 一旦 createConfigWithAllPermissions 抛出（包信息缺失、meta-data 解析异常等），
                // 整个 requestPermission 事务就会失败，应用侧把异常吞掉 → 界面上永远不弹窗；
                // 第二次请求时 find(uid) 已有记录、跳过创建，于是「切一次状态再点就正常」。
                // 配置创建失败不应影响弹窗本身，包一层 catch 继续走投递流程。
                val needCreateConfig = try {
                    configManager.find(uid) == null
                } catch (e: Throwable) {
                    LOGGER.w(e, "查询权限配置失败: uid=$uid")
                    false
                }
                if (needCreateConfig) {
                    try {
                        configManager.createConfigWithAllPermissions(uid, packageName)
                    } catch (e: Throwable) {
                        LOGGER.w(e, "创建默认权限配置失败（不影响弹窗）: uid=$uid, pkg=$packageName")
                    }
                }

                val lastDenyTime = clientManager.findClient(uid, pid)?.lastDenyTimeMap?.get(ShizukuApiConstants.PERMISSION_NAME) ?: 0
                val denyOnce = (System.currentTimeMillis() - lastDenyTime) > 10000

                val intent = Intent(ServerConstants.REQUEST_PERMISSION_ACTION)
                    .setPackage(ServerConstants.MANAGER_APPLICATION_ID)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
                    .putExtra("uid", uid)
                    .putExtra("pid", pid)
                    .putExtra("requestCode", requestCode)
                    .putExtra("applicationInfo", ai)
                    .putExtra("denyOnce", denyOnce)
                    .putExtra("permission", ShizukuApiConstants.PERMISSION_NAME)

                LOGGER.i(
                    "Shizuku 权限请求投递: uid=%d, pid=%d, pkg=%s, flag=%d, denyOnce=%b",
                    uid, pid, packageName, currentFlag, denyOnce
                )
                ActivityManagerApis.startActivityNoThrow(intent, null, userId)
            }
        }
    }
}
