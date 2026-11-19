package lin.server.userservice

import android.app.ActivityThread
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.ContextHidden
import android.content.IContentProvider
import android.ddm.DdmHandleAppName
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.UserHandle
import android.os.UserHandleHidden
import android.util.Log
import com.lin.api.BinderContainer
import dev.rikka.tools.refine.Refine
import rikka.hidden.compat.ActivityManagerApis
import lin.server.ServerConstants
import lin.server.api.IContentProviderUtils
import lin.server.util.UserHandleCompat.PER_USER_RANGE
import kotlin.system.exitProcess

object UserServiceStarter {
    private const val TAG = "LinUserServiceStarter"
    private const val EXTRA_BINDER = "moe.shizuku.privileged.api.intent.extra.BINDER"
    private const val EXTRA_CLIENT_BINDER = "moe.shizuku.privileged.api.intent.extra.CLIENT_BINDER"

    // v6（MT 授权超时修复）：UserServiceStarter 是独立 app_process 进程，其 android.util.Log
    // 只进 logcat（普通用户抓不到）。把关键步骤/失败原因同步落盘到
    // /data/local/tmp/Lin-Shizuku/Lin/log/Lin_user_service.log，
    // 用于定位 MT 管理器 Dhizuku server（用户服务）启动失败的确切环节。
    private fun logFile(msg: String) {
        try {
            val f = java.io.File("/data/local/tmp/Lin-Shizuku/Lin/log/Lin_user_service.log")
            f.parentFile?.mkdirs()
            if (f.exists() && f.length() > 100 * 1024) f.delete()
            f.appendText(java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US)
                .format(java.util.Date()) + " " + msg + "\n")
        } catch (_: Throwable) {
        }
    }

    private fun logFile(tag: String, msg: String, tr: Throwable?) {
        logFile(msg + (if (tr != null) "\n" + Log.getStackTraceString(tr) else ""))
    }

    @Suppress("FieldCanBeLocal")
    private var stellarBinder: IBinder? = null
    private var clientBinder: IBinder? = null

    @JvmStatic
    fun main(args: Array<String>) {
        // 写启动标记到 stderr.log
        lin.server.util.Logger.appendToUserServiceStderr("=== UserServiceStarter 启动 ===")
        lin.server.util.Logger.appendToUserServiceStderr("PID=${android.os.Process.myPid()}, UID=${android.os.Process.myUid()}")

        if (Looper.getMainLooper() == null) {
            Looper.prepareMainLooper()
        }

        var token: String? = null
        var packageName: String? = null
        var className: String? = null
        var uid: Int = -1
        var serviceMode: Int = UserServiceConstants.MODE_ONE_TIME
        var verificationToken: String? = null
        var debugName: String? = null

        for (arg in args) {
            when {
                arg.startsWith("--token=") ->
                    token = arg.substringAfter("--token=").trim('\'')
                arg.startsWith("--package=") ->
                    packageName = arg.substringAfter("--package=").trim('\'')
                arg.startsWith("--class=") ->
                    className = arg.substringAfter("--class=").trim('\'')
                arg.startsWith("--uid=") ->
                    uid = arg.substringAfter("--uid=").toIntOrNull() ?: -1
                arg.startsWith("--mode=") ->
                    serviceMode = arg.substringAfter("--mode=").toIntOrNull()
                        ?: UserServiceConstants.MODE_ONE_TIME
                arg.startsWith("--verification-token=") ->
                    verificationToken = arg.substringAfter("--verification-token=").trim('\'')
                arg.startsWith("--debug-name=") ->
                    debugName = arg.substringAfter("--debug-name=").trim('\'')
            }
        }

        if (token == null || packageName == null || className == null || uid == -1) {
            Log.e(TAG, "缺少必需参数")
            logFile("缺少必需参数: token=$token package=$packageName class=$className uid=$uid")
            exitProcess(1)
        }

        val userId = uid / PER_USER_RANGE

        Log.i(TAG, "启动 UserService: package=$packageName, class=$className, uid=$uid, userId=$userId")
        logFile("UserServiceStarter 启动: package=$packageName, class=$className, uid=$uid, userId=$userId, mode=$serviceMode")

        val userBinder = createUserService(packageName, className, userId, debugName)
        if (userBinder == null) {
            Log.e(TAG, "创建 UserService 实例失败")
            logFile("创建 UserService 实例失败（见上方异常）")
            exitProcess(1)
        }

        val serviceBinder = UserServiceBinder(userBinder)
        Log.i(TAG, "已创建 UserServiceBinder 包装")
        logFile("已创建 UserServiceBinder 包装: class=$className")

        if (!sendBinderToServer(serviceBinder, token, packageName, className, uid, serviceMode, verificationToken ?: "")) {
            Log.e(TAG, "发送 Binder 到服务器失败")
            logFile("发送 Binder 到服务器失败")
            exitProcess(1)
        }

        Log.i(TAG, "UserService 启动成功")
        logFile("UserService 启动成功: class=$className")

        Looper.loop()

        Log.i(TAG, "UserService 退出")
        exitProcess(0)
    }

    private fun createUserService(packageName: String, className: String, userId: Int, debugName: String?): IBinder? {
        Log.i(TAG, "创建 UserService: $packageName/$className")
        logFile("开始创建 UserService 实例: $packageName/$className")

        return try {
            val activityThread = ActivityThread.systemMain()
            val systemContext = activityThread.systemContext
            DdmHandleAppName.setAppName(debugName ?: "$packageName:user_service", userId)
            val userHandle: UserHandle = Refine.unsafeCast(
                UserHandleHidden.of(userId)
            )
            val context = Refine.unsafeCast<ContextHidden>(systemContext).createPackageContextAsUser(
                packageName,
                Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY,
                userHandle
            )
            logFile("createPackageContextAsUser 成功: $packageName")
            var application: Application?
            try {
                val mPackageInfo = context::class.java.getDeclaredField("mPackageInfo")
                mPackageInfo.isAccessible = true
                val loadedApk = mPackageInfo.get(context)

                val makeApplication = loadedApk.javaClass.getDeclaredMethod(
                    "makeApplication",
                    Boolean::class.javaPrimitiveType,
                    Instrumentation::class.java
                )
                application = makeApplication.invoke(loadedApk, true, null) as Application

                val mInitialApplication = activityThread.javaClass.getDeclaredField("mInitialApplication")
                mInitialApplication.isAccessible = true
                mInitialApplication.set(activityThread, application)
                Log.i(TAG, "Application 创建成功: ${application.javaClass.name}")
            } catch (e: Exception) {
                // Application 初始化失败时回退到旧的 Context 方案
                // 主要用于解决部分联发科设备上的兼容性问题
                // 参见 RikkaApps/Shizuku Issue #1171 和 RikkaApps/Shizuku-API PR #299
                // Android 14+ 传 null Instrumentation 会命中平台 makeApplicationInner 的已知 NPE，
                // 回退 Context 是预期路径。每个独立子进程都会复现同一段反射栈，不再落盘整段
                // 堆栈刷屏，只记一行类型/消息；完整栈仍可在 logcat 的 Log.w 中查看。
                val cause = e.cause ?: e
                val reason = "${cause.javaClass.simpleName}: ${cause.message}"
                Log.w(TAG, "无法初始化 Application，已回退到 Context: $reason")
                logFile("无法初始化 Application，已回退到 Context（$reason）")
                application = null
            }

            val classLoader = (if (application != null) application.classLoader else context.classLoader)
            val serviceClass = classLoader.loadClass(className)
            logFile("类加载成功: $className，classLoader=${classLoader.javaClass.name}")

            val instance = try {
                val constructorWithContext = serviceClass.getConstructor(Context::class.java)
                constructorWithContext.newInstance(application ?: context)
            } catch (_: NoSuchMethodException) {
                val constructor = serviceClass.getDeclaredConstructor()
                constructor.isAccessible = true
                constructor.newInstance()
            }

            if (instance is IBinder) {
                Log.i(TAG, "UserService 实例创建成功: $className")
                logFile("UserService 实例创建成功: $className")
                instance
            } else {
                Log.e(TAG, "服务类不是 Binder 的子类: ${instance.javaClass.name}")
                logFile("服务类不是 Binder 的子类: ${instance.javaClass.name}")
                null
            }
        } catch (e: Throwable) {
            Log.e(TAG, "创建 UserService 失败", e)
            logFile(TAG, "创建 UserService 失败", e)
            null
        }
    }

    private fun sendBinderToServer(
        serviceBinder: IBinder,
        token: String,
        packageName: String,
        className: String,
        uid: Int,
        serviceMode: Int,
        verificationToken: String
    ): Boolean {
        val providerName = "${ServerConstants.MANAGER_APPLICATION_ID}.lin"
        Log.i(TAG, "连接到 Provider: $providerName (客户端包名: $packageName)")
        logFile("连接到 Provider: $providerName")

        val userId = 0
        var provider: IContentProvider? = null
        
        // 创建一个专属的 Binder Token 作为连接标识
        val connectionToken = android.os.Binder()

        return try {
            // 传入 connectionToken，让系统精确记录这次连接
            provider = ActivityManagerApis.getContentProviderExternal(
                providerName, userId, connectionToken, providerName
            )

            if (provider == null) {
                Log.e(TAG, "Provider 为 null")
                logFile("Provider 为 null: $providerName")
                return false
            }

            if (!provider.asBinder().pingBinder()) {
                Log.e(TAG, "Provider 已死亡")
                logFile("Provider 已死亡: $providerName")
                return false
            }

            val extra = Bundle().apply {
                putParcelable(EXTRA_BINDER, BinderContainer(serviceBinder))
                putString(UserServiceConstants.OPT_TOKEN, token)
                putString(UserServiceConstants.OPT_PACKAGE_NAME, packageName)
                putString(UserServiceConstants.OPT_CLASS_NAME, className)
                putInt(UserServiceConstants.OPT_UID, uid)
                putInt(UserServiceConstants.OPT_PID, android.os.Process.myPid())
                putString(UserServiceConstants.OPT_VERIFICATION_TOKEN, verificationToken)
            }

            val reply = IContentProviderUtils.callCompat(
                provider, null, providerName, "sendUserService", null, extra
            )

            if (reply != null) {
                reply.classLoader = BinderContainer::class.java.classLoader
                val container = reply.getParcelable<BinderContainer>(EXTRA_BINDER)

                if (container?.binder != null && container.binder!!.pingBinder()) {
                    stellarBinder = container.binder
                    stellarBinder!!.linkToDeath({
                        Log.i(TAG, "Lin 服务器已死亡，用户服务退出...")
                        logFile("Lin 服务器已死亡，用户服务退出")
                        // V3-1（1.1.2 保活根治）：服务端死亡事件立即上报 Manager——
                        // Provider call 会拉起主进程（被清后系统随 call 重建），Manager 收到后
                        // 写诊断日志 + Root 自动恢复，不依赖 :guard 进程存活。失败不影响退出。
                        runCatching {
                            val pn = "${ServerConstants.MANAGER_APPLICATION_ID}.lin"
                            val p = ActivityManagerApis.getContentProviderExternal(
                                pn, 0, android.os.Binder(), pn
                            )
                            if (p != null && p.asBinder().pingBinder()) {
                                IContentProviderUtils.callCompat(
                                    p, null, pn, "notifyServerDied", "userService:${packageName}", Bundle()
                                )
                                logFile("已上报服务端死亡事件给 Manager")
                            }
                        }
                        exitProcess(0)
                    }, 0)

                    val clientContainer = reply.getParcelable<BinderContainer>(EXTRA_CLIENT_BINDER)
                    if (clientContainer?.binder != null && clientContainer.binder!!.pingBinder()) {
                        clientBinder = clientContainer.binder
                        if (serviceMode == UserServiceConstants.MODE_ONE_TIME) {
                            clientBinder!!.linkToDeath({
                                Log.i(TAG, "客户端 App 已退出，一次性模式，服务退出...")
                                exitProcess(0)
                            }, 0)
                            Log.i(TAG, "已设置客户端死亡监听（一次性模式）")
                        }
                    }

                    logFile("发送 Binder 到服务器成功: provider=$providerName")
                    return true
                }

                logFile("reply 中无有效 Binder（container=${container?.binder != null}）")
            } else {
                logFile("callCompat sendUserService 返回 null")
            }

            false
        } catch (e: Exception) {
            Log.e(TAG, "发送 Binder 到服务器失败", e)
            logFile(TAG, "发送 Binder 到服务器失败", e)
            false
        } finally {
            provider?.let {
                try {
                    // 使用与获取时完全相同的 connectionToken 注销，彻底切断系统底层的关联牵连
                    ActivityManagerApis.removeContentProviderExternal(providerName, connectionToken)
                } catch (e: Exception) {
                    Log.w(TAG, "removeContentProviderExternal 失败", e)
                }
            }
        }
    }
}
