package lin.server.bootstrap

import android.content.pm.ApplicationInfo
import android.ddm.DdmHandleAppName
import android.os.Looper
import android.os.Process
import android.os.ServiceManager
import lin.server.ServerConstants.MANAGER_APPLICATION_ID
import lin.server.LinService
import lin.server.util.Logger
import lin.server.util.PackageManagerCompat

object ServerBootstrap {
    private val LOGGER = Logger("ServerBootstrap")

    @JvmStatic
    fun main(args: Array<String>) {
        // 写启动日志到 launch.log
        Logger.appendToLaunchLog("=== LinServer 启动 ===")
        Logger.appendToLaunchLog("PID=${android.os.Process.myPid()}, UID=${android.os.Process.myUid()}")
        Logger.appendToLaunchLog("Java: ${System.getProperty("java.version")}")
        DdmHandleAppName.setAppName("Lin_server", 0)
        // v4（MT 授权超时修复）：服务端启动时清理历史残留的服务端进程。
        // 覆盖升级后旧服务端进程（旧协议/旧版本，如 1.0.8 的 Stellar_server）不随
        // APK 覆盖而退出的场景——旧进程会继续拉起授权弹窗但收不到管理器回传的
        // 授权确认（intercept 105 空实现 / 协议错配），导致第三方应用授权超时。
        // 只保留当前进程（由 start.sh / 无线调试新拉起的新实例）。
        cleanupStaleServers()
        Looper.prepareMainLooper()
        LinService()
        Looper.loop()
    }

    /** 清理除自身外的所有 Lin_server / Stellar_server 进程（shell/root 同 uid 可 kill）。 */
    private fun cleanupStaleServers() {
        try {
            val myPid = Process.myPid()
            val cmd = "for p in \$(pgrep -f 'Lin_server|Stellar_server' 2>/dev/null); do " +
                    "if [ \"\$p\" != \"$myPid\" ]; then kill -9 \$p 2>/dev/null; fi; done"
            Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd)).waitFor()
            LOGGER.i("已清理历史残留服务端进程（保留 PID=%d）", myPid)
        } catch (_: Throwable) {
        }
    }

    fun waitSystemService(name: String) {
        while (ServiceManager.getService(name) == null) {
            try {
                LOGGER.i("服务 $name 尚未启动，等待 1 秒。")
                Thread.sleep(1000)
            } catch (_: InterruptedException) {
            }
        }
    }

    val managerApplicationInfo: ApplicationInfo?
        get() = PackageManagerCompat.getApplicationInfo(MANAGER_APPLICATION_ID, 0, 0)
}
