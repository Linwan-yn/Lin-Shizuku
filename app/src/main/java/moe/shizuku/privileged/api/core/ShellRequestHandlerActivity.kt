package moe.shizuku.privileged.api.core

import android.app.Activity
import android.os.Bundle
import android.util.Log
import rikka.shizuku.Shizuku

/**
 * Shell 请求 Binder 的 Activity 入口：兼容原版 Shizuku 的 ShellRequestHandlerActivity。
 * 当广播在某些系统上被拦截时，Scene 等 rish 模式应用会降级为启动 Activity 来请求 Binder。
 */
class ShellRequestHandlerActivity : Activity() {

    companion object {
        private const val TAG = "ShellRequestHandlerActivity"
        private const val ACTION_REQUEST_BINDER = "rikka.shizuku.intent.action.REQUEST_BINDER"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d(TAG, "Received REQUEST_BINDER via Activity from ${intent.`package`}")

        val callbackBinder = intent.getBundleExtra("data")?.getBinder("binder")
        if (callbackBinder == null) {
            Log.w(TAG, "No callback binder in intent")
            finish()
            return
        }

        val shizukuBinder = Shizuku.getBinder()
        if (shizukuBinder == null || !shizukuBinder.pingBinder()) {
            Log.w(TAG, "Shizuku service not running, binder=$shizukuBinder")
            finish()
            return
        }

        try {
            val data = android.os.Parcel.obtain()
            data.writeStrongBinder(shizukuBinder)
            data.writeString(applicationInfo.sourceDir)
            callbackBinder.transact(1, data, null, android.os.IBinder.FLAG_ONEWAY)
            data.recycle()
            Log.d(TAG, "Binder sent to caller successfully via Activity")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to send binder via Activity: ${e.message}", e)
        }

        finish()
    }
}
