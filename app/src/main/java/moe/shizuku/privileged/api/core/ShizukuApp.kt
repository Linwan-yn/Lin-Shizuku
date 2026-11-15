package moe.shizuku.privileged.api.core

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Environment
import android.os.LocaleList
import java.util.Locale
import moe.shizuku.privileged.api.util.CrashHandler
import org.conscrypt.Conscrypt
import java.security.Security

class ShizukuApp : Application() {

    override fun attachBaseContext(base: Context) {
        // 强制简体中文：不跟随系统、不支持切换
        val wrapped = try {
            val loc = Locale.SIMPLIFIED_CHINESE
            Locale.setDefault(loc)
            val cfg = Configuration(base.resources.configuration)
            if (Build.VERSION.SDK_INT >= 24) {
                cfg.setLocales(LocaleList(loc))
            } else {
                @Suppress("DEPRECATION")
                cfg.locale = loc
            }
            base.createConfigurationContext(cfg)
        } catch (_: Throwable) {
            base
        }
        super.attachBaseContext(wrapped)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // P0-1 冷启动提速：Conscrypt 类加载 + Provider 注册从启动路径移到后台线程。
        // 使用点仅 ADB 配对（AdbKey/AdbPairingClient），远晚于启动，注入安全性不受影响。
        Thread {
            runCatching { Security.insertProviderAt(Conscrypt.newProvider(), 1) }
        }.start()
        // Install crash handler BEFORE any other init
        CrashHandler.install(this)
        try {
            Prefs.init(this)
        } catch (_: Throwable) {}
        // 诊断日志目录在进程创建早期就固定下来（主进程 / :guard / 开机自启等所有上下文一致），
        // 避免早于 MainActivity 的进程里 fallbackDir 为 null 导致日志被直接丢弃。
        runCatching { DiagnosticLog.setFallbackDir(getExternalFilesDir(null) ?: filesDir) }
        // 1.1.1：无线调试关闭守护——进程级注册（Application.onCreate），不依赖 MainActivity。
        // 任一进程创建即注册（含 :guard 守护进程），主进程被系统回收后监听仍存活，
        // 「无线调试关闭服务依旧运行」守护不因界面未打开/进程回收而失效。
        runCatching { WirelessDebugGuard.register(this) }
        // 1.1.2（保活自愈闭环·进程级兜底）：任一进程被创建（含被系统重建/开机/打开应用/
        // 通知拉起）时，检查开关开启但服务未真实运行的守护并立即补拉起——
        // 根治「:guard 修一次就消失后再无人守护」「进程守护只生效一次」：
        // 把「打开一次」升级为「每次进程创建都自检自愈」。
        runCatching {
            if (Prefs.processGuard && !isProcessAlive("$packageName:guard")) {
                moe.shizuku.privileged.api.core.GuardService.start(this)
                moe.shizuku.privileged.api.core.DiagnosticLog.append("[保活] 进程级兜底：:guard 不在，已自动拉起（进程 ${processName()}）")
            }
        }
        runCatching {
            if (Prefs.strongKeepAlive &&
                !moe.shizuku.privileged.api.core.ShizukuManager.isServiceRunning(this, moe.shizuku.privileged.api.core.StrongKeepAliveService::class.java)
            ) {
                moe.shizuku.privileged.api.core.StrongKeepAliveService.start(this)
                moe.shizuku.privileged.api.core.DiagnosticLog.append("[保活] 进程级兜底：强力保活不在，已自动拉起（进程 ${processName()}）")
            }
        }
        runCatching {
            if (Prefs.autoActivate &&
                !moe.shizuku.privileged.api.core.ShizukuManager.isServiceRunning(this, moe.shizuku.privileged.api.core.AppTriggerService::class.java)
            ) {
                moe.shizuku.privileged.api.core.AppTriggerService.start(this)
                moe.shizuku.privileged.api.core.DiagnosticLog.append("[保活] 进程级兜底：Scene/黑域自动激活不在，已自动拉起（进程 ${processName()}）")
            }
        }
    }

    /** 1.1.2：指定进程名是否存活（runningAppProcesses 对本应用可见）。 */
    private fun isProcessAlive(processName: String): Boolean = try {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        am.runningAppProcesses?.any { it.processName == processName } == true
    } catch (_: Throwable) {
        false
    }

    /** 当前进程名（诊断日志用）。 */
    private fun processName(): String = try {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        am.runningAppProcesses?.find { it.pid == android.os.Process.myPid() }?.processName ?: "?"
    } catch (_: Throwable) {
        "?"
    }

    // 公平运行内存机制：响应系统内存通知，按级别主动释放缓存
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        runCatching { FairMemory.onTrimMemory(this, level) }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        runCatching { FairMemory.onLowMemory(this) }
    }

    companion object {
        lateinit var instance: ShizukuApp
            private set
    }
}
