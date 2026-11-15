package moe.shizuku.privileged.api.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 公平运行内存机制（金标联盟 ITGSA：vivo / 小米 / OPPO / 荣耀）广播接收器。
 *
 * 系统在后台内存紧张时主动下发通知，代替"一刀切"杀后台：
 * - itgsa.intent.action.TRIM：内存预警，收到后主动释放冗余资源，降低被管控/被杀概率
 * - itgsa.intent.action.KILL：应用查杀通知（即将被系统回收），收到后保存关键状态并释放资源
 */
class FairMemoryReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "FairMemory"

        /** 内存预警 */
        const val ACTION_TRIM = "itgsa.intent.action.TRIM"

        /** 应用查杀 */
        const val ACTION_KILL = "itgsa.intent.action.KILL"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_TRIM -> {
                Log.d(TAG, "ITGSA 内存预警：主动释放冗余资源")
                val pending = goAsync()
                try {
                    FairMemory.releaseCaches(context, aggressive = true)
                } catch (e: Throwable) {
                    Log.e(TAG, "TRIM 处理失败", e)
                } finally {
                    pending.finish()
                }
            }

            ACTION_KILL -> {
                Log.d(TAG, "ITGSA 应用查杀：保存状态并释放资源")
                val pending = goAsync()
                try {
                    // 关键状态已由 SharedPreferences / 模块文件系统实时持久化，
                    // 此处仅做兜底释放，避免被杀前残留缓存占用。
                    FairMemory.releaseCaches(context, aggressive = true)
                } catch (e: Throwable) {
                    Log.e(TAG, "KILL 处理失败", e)
                } finally {
                    pending.finish()
                }
            }
        }
    }
}
