package moe.shizuku.privileged.api.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import rikka.shizuku.Shizuku

/**
 * Shizuku 兼容 Receiver：监听 rikka.shizuku.intent.action.REQUEST_BINDER 广播，
 * 通过回调 Binder 把 Shizuku 服务端 Binder 传递给 shell 进程（rish 模式）。
 *
 * Scene 等使用 rish 模式的应用通过发送此广播检测 Shizuku 是否运行，
 * 并获取服务端 Binder 进行后续通信。
 */
class ShizukuReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ShizukuReceiver"
        private const val ACTION_REQUEST_BINDER = "rikka.shizuku.intent.action.REQUEST_BINDER"

        /** 服务端 binder 尚未就绪时最多等待的毫秒数（rish 客户端 5 秒后就报超时）。 */
        private const val WAIT_BINDER_MS = 3000L
        private const val WAIT_STEP_MS = 100L
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REQUEST_BINDER) {
            return
        }

        Log.d(TAG, "Received REQUEST_BINDER from ${intent.`package`}")

        val callbackBinder = intent.getBundleExtra("data")?.getBinder("binder")
        if (callbackBinder == null) {
            Log.w(TAG, "No callback binder in intent")
            return
        }

        // 1.1.2（rish 授权修复）：广播回调必须尽快、且必须回一次，否则 rish 的
        // ShizukuShellLoader 会一直等，直到 5 秒后打印 "Request timeout"。
        // 原实现只取一次 Shizuku.getBinder()，binder 尚未就绪时直接 return，
        // 客户端既拿不到 binder 也拿不到失败信号，表现就是「执行 rish 不弹窗」。
        // 这里改用 goAsync 短暂等 binder 就绪，仍未就绪就回传 null，
        // 让客户端立刻走 "Server is not running"，而不是干等 5 秒。
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        val sourceDir = appContext.applicationInfo.sourceDir

        Thread {
            var sendBinder: IBinder? = null
            try {
                var waited = 0L
                var binder = Shizuku.getBinder()
                while (waited < WAIT_BINDER_MS) {
                    if (binder != null && runCatching { binder!!.pingBinder() }.getOrDefault(false)) {
                        sendBinder = binder
                        break
                    }
                    Thread.sleep(WAIT_STEP_MS)
                    waited += WAIT_STEP_MS
                    binder = Shizuku.getBinder()
                }

                val data = Parcel.obtain()
                try {
                    data.writeStrongBinder(sendBinder)
                    data.writeString(sourceDir)
                    callbackBinder.transact(1, data, null, IBinder.FLAG_ONEWAY)
                    Log.d(
                        TAG,
                        "Binder sent to caller: alive=${sendBinder != null}, waited=${waited}ms"
                    )
                } finally {
                    data.recycle()
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to send binder: ${e.message}", e)
            } finally {
                pendingResult.finish()
            }
        }.start()
    }
}
