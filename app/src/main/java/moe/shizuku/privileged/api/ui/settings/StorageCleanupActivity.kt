package moe.shizuku.privileged.api.ui.settings

import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Web
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import moe.shizuku.privileged.api.core.StorageStats
import moe.shizuku.privileged.api.ui.theme.LinShizukuTheme
import moe.shizuku.privileged.api.util.ActivityTransitions
import java.io.File

/** 存储空间清理页：缓存文件 / WebView 缓存 两项可清理（本应用无下载内容与插件，不展示）。 */
class StorageCleanupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LinShizukuTheme {
                CleanupScreen(onBack = { ActivityTransitions.finishWithAnimation(this) })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CleanupScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var refreshKey by remember { mutableStateOf(0) }
    val stats = remember(refreshKey) { StorageStats.measure(ctx) }
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("存储空间清理", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            CleanupCard(
                icon = { Icon(Icons.Filled.DeleteSweep, null, tint = MaterialTheme.colorScheme.primary) },
                title = "缓存文件",
                desc = "缓存是使用 App 过程中产生的临时数据，清理缓存不会影响 App 的正常使用。",
                sizeBytes = stats.cacheBytes,
                onClean = {
                    val freed = StorageStats.clearCache(ctx)
                    Toast.makeText(ctx, "已清理缓存 ${StorageStats.formatMB(freed)} MB", Toast.LENGTH_SHORT).show()
                    refreshKey++
                }
            )
            CleanupCard(
                icon = { Icon(Icons.Filled.Web, null, tint = MaterialTheme.colorScheme.primary) },
                title = "WebView 缓存",
                desc = "浏览模块 WebUI 时 WebView 产生的网页缓存，清理后下次打开会自动重新加载。",
                sizeBytes = stats.webViewBytes,
                onClean = {
                    val freed = StorageStats.clearWebView(ctx)
                    Toast.makeText(ctx, "已清理 WebView 缓存 ${StorageStats.formatMB(freed)} MB", Toast.LENGTH_SHORT).show()
                    refreshKey++
                }
            )
            CleanupCard(
                icon = { Icon(Icons.Filled.SystemUpdate, null, tint = MaterialTheme.colorScheme.primary) },
                title = "软件更新包",
                desc = "检查更新时下载的 APK 安装包，清理后不影响当前已安装的版本。",
                sizeBytes = remember(refreshKey) { measureUpdatePackages(ctx) },
                onClean = {
                    val freed = cleanUpdatePackages(ctx)
                    Toast.makeText(ctx, "已清理更新包 ${StorageStats.formatMB(freed)} MB", Toast.LENGTH_SHORT).show()
                    refreshKey++
                }
            )
            CleanupCard(
                icon = { Icon(Icons.Filled.FileOpen, null, tint = MaterialTheme.colorScheme.primary) },
                title = "日志文件",
                desc = "脚本执行日志、App 崩溃日志与服务端启动日志，清理后不影响正常使用。",
                sizeBytes = remember(refreshKey) { measureLogs(ctx) },
                onClean = {
                    val freed = cleanLogs(ctx)
                    Toast.makeText(ctx, "已清理日志 ${StorageStats.formatMB(freed)} MB", Toast.LENGTH_SHORT).show()
                    refreshKey++
                }
            )
        }
    }
}

@Composable
private fun CleanupCard(
    icon: @Composable () -> Unit,
    title: String,
    desc: String,
    sizeBytes: Long,
    onClean: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                icon()
                Spacer(Modifier.size(10.dp))
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                desc,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp
            )
            Spacer(Modifier.height(14.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onClick = onClean) {
                    Text("清理 ${StorageStats.formatMB(sizeBytes)} MB", fontSize = 13.sp)
                }
            }
        }
    }
}

/** 计算更新包总大小（应用专属目录 + 公共 Download 目录） */
private fun measureUpdatePackages(context: android.content.Context): Long {
    var total = 0L
    // 应用专属下载目录
    val appDir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "apks")
    if (appDir.exists() && appDir.isDirectory) {
        appDir.listFiles()?.forEach { f -> if (f.name.endsWith(".apk")) total += f.length() }
    }
    // 公共 Download 目录（旧版下载路径）
    val publicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Lin-Shizuku/apks")
    if (publicDir.exists() && publicDir.isDirectory) {
        publicDir.listFiles()?.forEach { f -> if (f.name.endsWith(".apk")) total += f.length() }
    }
    return total
}

/** 清理所有更新包，返回释放的字节数 */
private fun cleanUpdatePackages(context: android.content.Context): Long {
    var freed = 0L
    val dirs = listOf(
        File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "apks"),
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Lin-Shizuku/apks")
    )
    for (dir in dirs) {
        if (dir.exists() && dir.isDirectory) {
            dir.listFiles()?.forEach { f ->
                if (f.name.endsWith(".apk") || f.name.endsWith(".apk.tmp")) {
                    freed += f.length()
                    f.delete()
                }
            }
        }
    }
    return freed
}

/** 计算日志总大小（统一 externalCacheDir/Logs 目录） */
private fun measureLogs(context: android.content.Context): Long {
    val dir = File(context.externalCacheDir ?: context.cacheDir, "Logs")
    if (!dir.exists() || !dir.isDirectory) return 0L
    var total = 0L
    dir.walkTopDown().forEach { f -> if (f.isFile) total += f.length() }
    return total
}

/** 清理所有日志（统一 Logs 目录 + 服务端启动日志），返回释放的字节数 */
private fun cleanLogs(context: android.content.Context): Long {
    var freed = 0L
    // 1. 清理统一 Logs 目录下所有文件（脚本日志 + 崩溃日志）
    val dir = File(context.externalCacheDir ?: context.cacheDir, "Logs")
    if (dir.exists() && dir.isDirectory) {
        dir.listFiles()?.forEach { f ->
            freed += f.length()
            f.delete()
        }
    }
    // 2. 清理服务端启动日志（/data/local/tmp/Lin-Shizuku/Lin/log/）
    try {
        lin.Lin.newProcess(
            arrayOf("sh", "-c", "rm -f /data/local/tmp/Lin-Shizuku/Lin/log/*.log 2>/dev/null"),
            null, null
        ).waitFor()
    } catch (_: Throwable) {}
    return freed
}
