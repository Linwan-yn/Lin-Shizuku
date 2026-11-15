package moe.shizuku.privileged.api.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log

/**
 * 可信 WLAN 自动启动：进程存活期间监听 WLAN 连接，
 * 设置中「可信 WLAN 自动启动」开启且服务未运行时，自动尝试以 Root 方式启动服务。
 * （非 Root 设备无自启能力，与「开机启动」一致，需用户通过无线调试手动启动。）
 */
object WlanAutoStartManager {
    private const val TAG = "WlanAutoStart"
    private var registered = false

    fun register(ctx: Context) {
        if (registered) return
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    try {
                        val caps = cm.getNetworkCapabilities(network) ?: return
                        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return
                        if (!Prefs.wlanAutoStart) return
                        if (ShizukuManager.isRunningCompat()) return
                        Log.d(TAG, "可信 WLAN 已连接，自动启动 Shizuku...")
                        ShizukuManager.startService(ctx) { result ->
                            Log.d(TAG, "自动启动结果: ${result.take(120)}")
                        }
                    } catch (e: Throwable) {
                        Log.e(TAG, "自动启动失败", e)
                    }
                }
            })
            registered = true
            Log.d(TAG, "WLAN 自动启动监听已注册")
        } catch (e: Throwable) {
            Log.e(TAG, "注册 WLAN 监听失败", e)
        }
    }
}
