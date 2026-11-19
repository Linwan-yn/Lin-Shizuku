package moe.shizuku.privileged.api.ui.battery

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.shizuku.privileged.api.ui.widget.GlassCard
import moe.shizuku.privileged.api.ui.widget.GlassPanel

@Composable
fun BatteryScreen() {
    val ctx = LocalContext.current
    val pm = ctx.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager
    val isIgnoring = pm.isIgnoringBatteryOptimizations(ctx.packageName)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
            .padding(top = 16.dp)
            .padding(bottom = 72.dp)
    ) {
        // 89 轮：电池页标题玻璃条（跟随设置页五档材质）
        GlassPanel(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 12.dp)
            ) {
                Text("电池优化", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Shizuku 需要在后台持续运行，加入电池优化白名单可避免被系统杀死",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.height(14.dp))

        // Status card
        GlassCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (isIgnoring) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                    contentDescription = null,
                    tint = if (isIgnoring) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (isIgnoring) "已加入白名单" else "未加入白名单",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp
                    )
                    Text(
                        if (isIgnoring) "Shizuku 不会被系统电池优化限制"
                        else "Shizuku 可能被系统后台杀死",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Action button: 未加入白名单→请求授权；已加入→打开系统详情
        Button(
            onClick = {
                if (!isIgnoring) {
                    try {
                        val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        intent.data = Uri.parse("package:${ctx.packageName}")
                        ctx.startActivity(intent)
                    } catch (_: Throwable) {
                        try {
                            val intent = Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            ctx.startActivity(intent)
                        } catch (_: Throwable) {}
                    }
                } else {
                    openSystemBatteryDetails(ctx)
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.BatteryChargingFull, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (isIgnoring) "查看系统电量详情" else "加入电池优化白名单")
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 直达系统应用详情/电量详情（图二界面）
        OutlinedButton(
            onClick = { openSystemBatteryDetails(ctx) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("打开系统电量详情")
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Tips
        GlassCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("为什么需要白名单？", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(
                    "• Shizuku 服务需要持续运行以提供 ADB/ROOT 权限\n" +
                    "• 部分厂商系统（小米/华为/OPPO/vivo）会 aggressively 杀后台\n" +
                    "• 加入白名单后 Shizuku 可在后台稳定运行\n" +
                    "• 同时建议在系统多任务界面锁定 Shizuku",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp
                )
            }
        }
    }
}

private fun openSystemBatteryDetails(ctx: android.content.Context) {
    try {
        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        intent.data = Uri.parse("package:${ctx.packageName}")
        ctx.startActivity(intent)
    } catch (_: Throwable) {
        try {
            val intent = Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            ctx.startActivity(intent)
        } catch (_: Throwable) {}
    }
}
