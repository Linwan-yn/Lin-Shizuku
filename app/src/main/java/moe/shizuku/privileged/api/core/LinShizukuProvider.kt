package moe.shizuku.privileged.api.core

import android.os.Bundle
import android.util.Log
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider

/**
 * Shizuku provider with logging and Sui disabled.
 */
class LinShizukuProvider : ShizukuProvider() {

    override fun onCreate(): Boolean {
        disableAutomaticSuiInitialization()
        Log.i(TAG, "LinShizukuProvider onCreate")
        return super.onCreate()
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        // A1（90n 实现）：「Shizuku 兼容层」开关关闭时拒绝通过 provider 下发 binder，
        // 使 rikka 客户端（第三方应用）无法再使用兼容通道，开关真实生效。
        val compat = try { Prefs.shizukuCompat } catch (_: Throwable) { true }
        if (!compat) {
            Log.i(TAG, "call blocked: 兼容层已关闭 (method=$method)")
            return null
        }
        Log.i(TAG, "call method=$method, extras=${extras != null}")
        return try {
            val result = super.call(method, arg, extras)
            Log.i(TAG, "call result=$result, binder alive=${Shizuku.getBinder()?.pingBinder() == true}")
            result
        } catch (e: Throwable) {
            Log.e(TAG, "call failed: ${e.message}", e)
            null
        }
    }

    companion object {
        private const val TAG = "LinShizukuProvider"
    }
}
