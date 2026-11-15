package moe.shizuku.privileged.api.util

import android.content.Context
import java.io.File
import moe.shizuku.privileged.api.R

object Starter {

    /**
     * 返回启动命令（短脚本形式）：
     * sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh
     * ——moe.shizuku.privileged.api 就是本应用自己的包名，路径固定。
     * App 每次启动都会重写该脚本（覆盖安装后 base.apk 路径变化），并放开
     * 目录/脚本权限（chmod 755），使 root / adb（uid 2000）/ 普通终端均可执行。
     */
    fun getSdcardCommand(context: Context): String {
        return try {
            writeSdcardFiles(context)
            val filesDir = context.getExternalFilesDir(null)
            if (filesDir != null && filesDir.parentFile != null) {
                val sh = File(filesDir.parentFile, "start.sh")
                if (sh.exists()) "sh $sh" else fallbackCommand(context)
            } else {
                fallbackCommand(context)
            }
        } catch (_: Throwable) {
            fallbackCommand(context)
        }
    }

    /** 生成/刷新 Android/data/moe.shizuku.privileged.api/start.sh，并放开权限。 */
    fun writeSdcardFiles(context: Context): Boolean {
        return try {
            val content = buildScriptContent(context) ?: return false
            val filesDir = context.getExternalFilesDir(null) ?: return false
            val dir = filesDir.parentFile ?: return false
            if (!dir.exists() && !dir.mkdirs()) return false
            val sh = File(dir, "start.sh")
            sh.writeText(content)
            // 放开目录与脚本权限：Android 11+ 对 Android/data/<包名> 有访问保护，
            // 本应用是目录 owner，可 chmod 755，使 shell（uid 2000）能读脚本
            try {
                Runtime.getRuntime().exec(arrayOf("sh", "-c", "chmod 755 \"$dir\" \"$sh\""))
            } catch (_: Throwable) {
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** 生成脚本内容：模板 + 本应用 base.apk 路径。 */
    private fun buildScriptContent(context: Context): String? {
        return try {
            val baseApk = context.applicationInfo.sourceDir
            if (baseApk.isEmpty()) return null
            // P2-10：静态引用 R.raw.start（原 getIdentifier 动态引用在 shrinkResources 下会被误删，
            // 且省去一次运行时资源查找）
            val input = context.resources.openRawResource(R.raw.start)
            val template = input.bufferedReader().readText()
            input.close()
            template.replace("%%%APK_PATH%%%", baseApk)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * v5.0：无线调试（adb shell）启动命令 = 官方同款 native 启动器。
     *
     * 直接搬官方 Shizuku master / Stellar 的激活方式：
     * `shell:<nativeLibraryDir>/liblin.so --apk=<base.apk>`
     * ——liblin.so 是官方 starter.cpp 改编编译的 Lin 版 native 启动器
     * （SERVER_CLASS_PATH=lin.server.LinService，SERVER_NAME=Lin_server），
     * 内部 fork + setsid + exec app_process（-Djava.class.path=<apk>
     * 双保险 CLASSPATH），与官方 libshizuku.so 机制完全一致。
     * 不再使用自拼长 shell 脚本（app_process 直启在实测中因连接/命令
     * 执行链路问题从未成功）；native 启动器从 /data/app 的 lib 目录
     * 被 shell 直接执行，是官方验证可行的路径（SELinux/权限同官方）。
     */
    fun getAdbShellCommand(context: Context): String {
        val cmd = try {
            val starterFile = File(context.applicationInfo.nativeLibraryDir, "liblin.so")
            val apk = context.applicationInfo.sourceDir
            if (starterFile.exists() && apk.isNotEmpty()) {
                "$starterFile --apk=$apk"
            } else {
                fallbackCommand(context)
            }
        } catch (_: Throwable) {
            fallbackCommand(context)
        }
        // 1.1.2：激活服务时顺手授予本应用 WRITE_SECURE_SETTINGS。
        // 「关闭 USB 调试 / 关闭无线调试 / 关闭开发者选项」这类系统开关依赖该权限，
        // 官方做法就是启动一次后自行取得。这里由 shell（uid 2000）身份执行最可靠——
        // 服务端进程内还会再执行一次作兜底（ManagerGrantHelper），两处都做。
        // 输出全部丢弃，不影响 AdbWirelessHelper 对 RUNNING-OK / START-FAILED 的判定。
        return "$cmd; $ADB_GRANT_SUFFIX"
    }

    /** 授权命令片段（绝对路径 pm，避免 app_process/shell 环境 PATH 差异）。 */
    private const val ADB_GRANT_SUFFIX =
        "/system/bin/pm grant moe.shizuku.privileged.api " +
            "android.permission.WRITE_SECURE_SETTINGS >/dev/null 2>&1"

    /** 兜底：外部存储不可用时直接返回内嵌长命令。 */
    private fun fallbackCommand(context: Context): String {
        return try {
            val baseApk = context.applicationInfo.sourceDir
            if (baseApk.isNotEmpty()) {
                // v3（MT 授权超时修复）：pgrep 同时匹配历史版本进程名 Lin_server/Stellar_server
                // （覆盖升级后旧服务端进程不随应用退出而残留，导致协议错配、第三方授权确认丢失），
                // 先启后停：启动当前 LinService 并清理旧协议进程，保证管理器与服务端协议一致。
                // 1.1.1：兜底命令补齐 setsid（与 res/raw/start.sh 模板 90bb 轮对齐）——
                // 无线调试启动时经 adb shell 执行本命令，若无 setsid 则服务进程仍在 adb 会话/
                // 进程组内，系统关闭开发者选项「无线调试」时按 shell 会话/进程组清理会连坐杀掉
                // 服务进程（用户反馈"关无线调试服务也跟着关、进程消失"）。setsid 使其脱离会话。
                // v4.16：注入 APK 路径（旧版引用未赋值的 \$baseApk → CLASSPATH 空 → 启动必败）。
                // v4.17：app_process 参数对齐官方 starter.cpp（-Djava.class.path 替代 CLASSPATH env）。
                // P3-1 修复：进程差集改用逐 pid case 匹配（原 grep -v \"\$OLD\" 有两个缺陷：
                // ① OLD 为空时 grep -v \"\" 吞掉所有行 → NEW 恒空 → 首启误报 START-FAILED；
                // ② OLD 为空格分隔多 pid 时是单模式带空格串，几乎排不掉任何行 → 把旧进程
                // 也算进 NEW。同时排除执行本命令的 sh 包装进程自身（其命令行含匹配模式，
                // 原实现 OLD 会误含 \$\$，kill -9 会杀掉正在执行的 shell 导致结果丢失）。
                "APK=\"$baseApk\"; OLD=\$(pgrep -f 'Lin_server|Stellar_server' 2>/dev/null); " +
                    "OLD=\$(for p in \$OLD; do [ \"\$p\" != \"\$\$\" ] && echo \"\$p\"; done); " +
                    "mkdir -p /data/local/tmp/Lin-Shizuku/Lin/log && chmod 0777 /data/local/tmp/Lin-Shizuku /data/local/tmp/Lin-Shizuku/Lin && " +
                    "setsid nohup app_process -Djava.class.path=\"\$APK\" /system/bin --nice-name=Lin_server lin.server.LinService >/data/local/tmp/Lin-Shizuku/Lin/log/Lin_server_launch.log 2>&1 & " +
                    "sleep 2; NEW=\$(pgrep -f 'Lin_server|Stellar_server' 2>/dev/null); " +
                    "NEW=\$(for p in \$NEW; do [ \"\$p\" = \"\$\$\" ] && continue; case \" \$OLD \" in *\" \$p \"*) ;; *) echo \"\$p\";; esac; done); " +
                    "if [ -n \"\$NEW\" ]; then kill -9 \$OLD 2>/dev/null; echo RUNNING-OK; else echo START-FAILED; fi"
            } else {
                "sh /sdcard/start.sh"
            }
        } catch (_: Throwable) {
            "sh /sdcard/start.sh"
        }
    }
}
