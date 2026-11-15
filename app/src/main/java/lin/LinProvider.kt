package lin

import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.core.net.toUri
import com.lin.api.BinderContainer
import lin.Lin.onBinderReceived
import lin.Lin.pingBinder

@Suppress("deprecation")
open class LinProvider : ContentProvider() {
    override fun attachInfo(context: Context?, info: ProviderInfo) {
        super.attachInfo(context, info)

        check(!info.multiprocess) { "android:multiprocess must be false" }

        check(info.exported) { "android:exported must be true" }

        isProviderProcess = true
    }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (extras == null) {
            return null
        }

        extras.classLoader = BinderContainer::class.java.getClassLoader()

        val reply = Bundle()
        when (method) {
            METHOD_SEND_BINDER -> {
                handleSendBinder(extras)
            }

            METHOD_GET_BINDER -> {
                if (!handleGetBinder(reply)) {
                    return null
                }
            }

            METHOD_SEND_USER_SERVICE -> {
                return handleSendUserService(extras)
            }
        }
        return reply
    }

    private fun handleSendBinder(extras: Bundle) {
        val container = extras.getParcelable<BinderContainer?>(EXTRA_BINDER)
        if (container == null || container.binder == null) {
            Log.e(TAG, "发送 Binder 的 extras 中没有有效 Binder")
            return
        }
        val newBinder = container.binder
        val current = Lin.binder
        // 90av：仅当「同一 Binder 对象」重复发送时才忽略。
        // 旧实现按 pingBinder() 存活即忽略——旧服务进程尚未被清理时，重启后的新
        // Lin_server 进程发送的 Binder 会被拒收；旧进程随后被杀，provider 永久持有
        // 死 Binder，配对页 30s 内拉取不到 → 首次启动必报「等待服务启动超时」
        // （点重试、再次启动新进程后才桥接成功）。
        if (current != null && current.pingBinder() && current === newBinder) {
            Log.w(TAG, "同一 Lin Binder 重复发送，忽略")
            return
        }

        Log.i(TAG, "接收并绑定 Lin Binder（替换旧 Binder）")

        onBinderReceived(newBinder, context!!.packageName)

        // 桥接：同一 binder 同时喂给官方 Shizuku 单例，使本 App 既有 UI（Shizuku.getBinder/pingBinder）立即生效
        runCatching {
            rikka.shizuku.Shizuku.onBinderReceived(newBinder, context!!.packageName)
        }

        if (enableMultiProcess) {
            Log.d(TAG, "多进程支持已启用，广播 Binder 给同包其他进程")

            val intent = Intent(ACTION_BINDER_RECEIVED)
                .putExtra(EXTRA_BINDER, container)
                .setPackage(context!!.packageName)
            context!!.sendBroadcast(intent)
        }
    }

    private fun handleGetBinder(reply: Bundle): Boolean {
        val binder: IBinder? = Lin.binder
        if (binder == null || !binder.pingBinder()) return false

        reply.putParcelable(EXTRA_BINDER, BinderContainer(binder))
        return true
    }

    private fun handleSendUserService(extras: Bundle): Bundle? {
        Log.i(TAG, "收到用户服务 Binder")

        // 1.1.2（Tasker/Dhizuku 用户服务超时修复）：这个 call 可能把 Manager 冷启动，
        // 而 Lin.binder 要等服务端补发才注入。原来这里拿到 null 就直接返回 null，
        // 用户服务进程判定「发送 Binder 到服务器失败」后 exit，服务端 8 秒后报
        // 「用户服务启动后 8 秒内未附加」。这里最多等 6 秒，等 binder 注入后再转发。
        var service = Lin.getService()
        if (service == null) {
            Log.i(TAG, "Lin 服务尚未就绪，等待 binder 注入（最多 6 秒）")
            val deadline = System.currentTimeMillis() + 6000L
            while (service == null && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(200)
                } catch (_: InterruptedException) {
                    break
                }
                service = Lin.getService()
            }
        }
        if (service == null) {
            Log.e(TAG, "Lin 服务未连接，无法附加用户服务（已等待 6 秒）")
            return null
        }

        val container = extras.getParcelable<BinderContainer?>(EXTRA_BINDER)
        if (container?.binder == null) {
            Log.e(TAG, "用户服务 extras 中没有有效 Binder")
            return null
        }

        try {
            service.attachUserService(container.binder, extras)
        } catch (e: Exception) {
            Log.e(TAG, "附加用户服务失败", e)
            return null
        }

        val reply = Bundle()
        val linBinder = Lin.binder
        if (linBinder != null) {
            reply.putParcelable(EXTRA_BINDER, BinderContainer(linBinder))
        }
        val clientBinder = Lin.getClientBinder()
        if (clientBinder != null) {
            reply.putParcelable(EXTRA_CLIENT_BINDER, BinderContainer(clientBinder))
        }
        return reply
    }

    override fun query(
        uri: Uri,
        projection: Array<String?>?,
        selection: String?,
        selectionArgs: Array<String?>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String?>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String?>?
    ): Int = 0

    companion object {
        private const val TAG = "LinProvider"

        const val METHOD_SEND_BINDER: String = "sendBinder"

        const val METHOD_GET_BINDER: String = "getBinder"

        const val METHOD_SEND_USER_SERVICE: String = "sendUserService"

        const val ACTION_BINDER_RECEIVED: String = "com.lin.api.action.BINDER_RECEIVED"

        private const val EXTRA_BINDER = "moe.shizuku.privileged.api.intent.extra.BINDER"
        private const val EXTRA_CLIENT_BINDER = "moe.shizuku.privileged.api.intent.extra.CLIENT_BINDER"

        const val MANAGER_APPLICATION_ID: String = "moe.shizuku.privileged.api"

        private var enableMultiProcess = false

        private var isProviderProcess = false

        fun setIsProviderProcess(isProviderProcess: Boolean) {
            Companion.isProviderProcess = isProviderProcess
        }

        fun enableMultiProcessSupport(isProviderProcess: Boolean) {
            Log.d(
                TAG,
                if (isProviderProcess) "当前进程是 Provider 进程，启用多进程 Binder 转发"
                else "当前进程不是 Provider 进程，启用多进程 Binder 接收"
            )

            Companion.isProviderProcess = isProviderProcess
            enableMultiProcess = true
        }

        fun requestBinderForNonProviderProcess(context: Context) {
            if (isProviderProcess) {
                return
            }

            Log.d(TAG, "非 Provider 进程请求当前进程的 Lin Binder")

            val receiver: BroadcastReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val container = intent.getParcelableExtra<BinderContainer?>(EXTRA_BINDER)
                    if (container != null && container.binder != null) {
                        Log.i(TAG, "非 Provider 进程收到 Lin Binder 广播")
                        onBinderReceived(container.binder, context.packageName)
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(
                    receiver,
                    IntentFilter(ACTION_BINDER_RECEIVED),
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                context.registerReceiver(receiver, IntentFilter(ACTION_BINDER_RECEIVED))
            }
            val reply = try {
                context.contentResolver.call(
                    ("content://" + context.packageName + ".lin").toUri(),
                    METHOD_GET_BINDER, null, Bundle()
                )
            } catch (tr: Throwable) {
                null
            }

            if (reply != null) {
                reply.classLoader = BinderContainer::class.java.getClassLoader()

                val container = reply.getParcelable<BinderContainer?>(EXTRA_BINDER)
                if (container != null && container.binder != null) {
                    Log.i(TAG, "非 Provider 进程通过 Provider 取得 Lin Binder")
                    onBinderReceived(container.binder, context.packageName)
                }
            }
        }
    }
}
