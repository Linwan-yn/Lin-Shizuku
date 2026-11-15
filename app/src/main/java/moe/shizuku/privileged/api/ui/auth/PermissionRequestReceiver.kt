package moe.shizuku.privileged.api.ui.auth

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import moe.shizuku.privileged.api.R

/**
 * 权限请求通知接收器：
 * 当服务端（Shell 身份）收到第三方应用的权限请求时，
 * 会发送一个广播，本接收器收到后弹通知提醒用户。
 * 用户点击通知后打开授权对话框。
 */
class PermissionRequestReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_NOTIFY_PERMISSION = "lin.shizuku.NOTIFY_PERMISSION_REQUEST"
        const val CHANNEL_ID = "lin_permission_request"
        const val NOTIFICATION_ID = 1001
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_NOTIFY_PERMISSION) return

        val packageName = intent.getStringExtra("packageName") ?: return
        val uid = intent.getIntExtra("uid", -1)
        val pid = intent.getIntExtra("pid", -1)
        val requestCode = intent.getIntExtra("requestCode", -1)

        // 获取应用名
        val appLabel = try {
            val pm = context.packageManager
            val ai = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(ai).toString()
        } catch (_: Throwable) {
            packageName
        }

        // 创建通知渠道
        createNotificationChannel(context)

        // 点击通知后打开授权 Activity
        val notificationIntent = Intent(context, RequestPermissionActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("uid", uid)
            putExtra("pid", pid)
            putExtra("requestCode", requestCode)
            putExtra("permission", "shizuku")
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            requestCode,
            notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("授权请求")
            .setContentText("要允许 $appLabel 使用 Lin-Shizuku 吗？点击授权")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "权限请求",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "第三方应用请求 Lin-Shizuku 权限时的通知"
                enableVibration(true)
                enableLights(true)
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }
}
