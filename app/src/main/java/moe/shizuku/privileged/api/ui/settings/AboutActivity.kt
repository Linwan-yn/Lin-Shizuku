package moe.shizuku.privileged.api.ui.settings

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.privileged.api.BuildConfig
import moe.shizuku.privileged.api.ui.theme.LinShizukuTheme
import moe.shizuku.privileged.api.util.ActivityTransitions
import moe.shizuku.privileged.api.util.UpdateChecker
import java.util.Locale

/**
 * 90am：关于/软件更新二级界面（仿 ColorOS「软件更新」页：渐变主视觉 + 版本状态 +
 * 更新说明 + 下载安装）。原设置页「检查更新」按钮已移入此处。
 * 90an：新增「版本记录」卡片列表（GitHub Releases 历史）。
 * 90ao：新增「设备信息」区块；版本记录卡片可点击进入 VersionDetailActivity 二级详情页。
 */
class AboutActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LinShizukuTheme {
                AboutScreen(onBack = {
                    ActivityTransitions.finishWithAnimation(this)
                })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AboutScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var currentVersion by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(true) }
    var release by remember { mutableStateOf<UpdateChecker.ReleaseInfo?>(null) }
    var hasNewer by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf("") }
    var releases by remember { mutableStateOf<List<UpdateChecker.ReleaseInfo>>(emptyList()) }
    // 1.1.2：展开态——「发现新版本」入口可点开，点击后才展示版本信息/更新说明/设备信息/下载按钮
    var expanded by remember { mutableStateOf(false) }

    // 下载状态
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0) }
    var failed by remember { mutableStateOf(false) }
    var completed by remember { mutableStateOf(false) }

    fun checkUpdate() {
        checking = true
        errorMsg = ""
        scope.launch(Dispatchers.IO) {
            try {
                val r = UpdateChecker.fetchLatest()
                val server = r.tag.removePrefix("v").removePrefix("V")
                val ignored = ctx.getSharedPreferences("update_check", android.content.Context.MODE_PRIVATE)
                    .getString("ignored_tag", "") == r.tag
                val newer = UpdateChecker.compareVersion(server, currentVersion) > 0
                withContext(Dispatchers.Main) {
                    release = r
                    hasNewer = newer && !ignored
                    checking = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    checking = false
                    errorMsg = e.message?.take(80) ?: e.javaClass.simpleName
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        currentVersion = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName?.substringBefore("-") ?: "1.0.0"
        } catch (_: Exception) { "1.0.0" }
        checkUpdate()
        // 90an：拉取全部历史 Release（失败静默，不影响主流程）
        scope.launch(Dispatchers.IO) {
            try {
                val all = UpdateChecker.fetchReleases()
                withContext(Dispatchers.Main) { releases = all }
            } catch (_: Exception) {
            }
        }
    }

    // 下载进度轮询
    LaunchedEffect(downloading) {
        while (downloading) {
            delay(300)
            val p = UpdateChecker.progress
            if (p >= 0) progress = p
            if (!UpdateChecker.isDownloading && p < 0) {
                downloading = false
                failed = UpdateChecker.downloadFailed
                completed = UpdateChecker.downloadCompleted
            }
        }
    }

    fun startDownload() {
        val r = release ?: return
        downloading = true
        progress = 0
        failed = false
        completed = false
        if (ctx is android.app.Activity) {
            scope.launch(Dispatchers.IO) {
                UpdateChecker.downloadAndInstall(ctx, r.apkUrl, r.tag) {}
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("软件更新", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            // 1.1.2：渐变主视觉改造为「发现新版本」可点入口——
            // 折叠态只显示版本状态 + 展开提示；点击展开完整更新信息（版本信息/更新说明/
            // 下载按钮全部收纳其中），再点收起。设备信息保持常显不折叠。
            val canExpand = hasNewer && !checking && release != null
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Brush.verticalGradient(listOf(Color(0xFF5B4BD8), Color(0xFF9B7BFF))))
                    .clickable(enabled = canExpand || (errorMsg.isNotEmpty() && !checking)) {
                        if (errorMsg.isNotEmpty() && !checking) {
                            expanded = false
                            checkUpdate()
                        } else {
                            expanded = !expanded
                        }
                    }
                    .padding(vertical = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // 卡片主视觉（图二详情页同款：大版本号 + 应用名 + 状态徽章）
                    Text(
                        if (currentVersion.isBlank()) "1.0.8" else currentVersion,
                        fontSize = 56.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text("Lin-Shizuku", fontSize = 18.sp, color = Color.White.copy(alpha = 0.92f))
                    Spacer(modifier = Modifier.height(14.dp))
                    Surface(shape = RoundedCornerShape(50), color = Color.White.copy(alpha = 0.18f)) {
                        Text(
                            when {
                                checking -> "正在检查更新..."
                                errorMsg.isNotEmpty() -> "检查失败，点击重试"
                                hasNewer -> "发现新版本 ${release?.tag ?: ""}"
                                else -> "已是最新版本"
                            },
                            color = Color.White,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                        )
                    }
                    // 有更新时显示展开提示箭头（可点开）
                    if (hasNewer) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Icon(
                            if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                            null,
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }

            // 当前版本（入口卡下方，恢复原版顺序）
            InfoRow("当前版本", "Lin-Shizuku ${BuildConfig.VERSION_NAME}")

            // 1.1.2：点击「发现新版本」后展开的完整更新信息（更新说明/下载按钮收纳其中）
            AnimatedVisibility(visible = expanded && canExpand) {
                Column {
                    if (release != null) {
                        InfoRow("软件版本", release!!.tag)
                        InfoRow("更新时间", release!!.publishedAt.substringBefore("T"))
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("更新说明", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(6.dp))
                        val body = release!!.body.trim()
                        if (body.isNotEmpty()) {
                            Text(
                                body,
                                fontSize = 13.sp,
                                lineHeight = 20.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        } else {
                            Text("（该版本暂无详细说明）", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (errorMsg.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "检查失败：$errorMsg",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // 底部操作按钮（下载并安装 / 立即安装 / 检查更新）
                    Button(
                        onClick = {
                            when {
                                completed -> {
                                    if (ctx is android.app.Activity) UpdateChecker.installApk(ctx)
                                }
                                hasNewer -> startDownload()
                                else -> checkUpdate()
                            }
                        },
                        enabled = !checking,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (checking) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.size(8.dp))
                            Text("检查中...")
                        } else {
                            Text(
                                when {
                                    downloading -> if (progress > 0) "正在下载 $progress%" else "正在连接服务器..."
                                    failed -> "下载失败，点击重试"
                                    completed -> "立即安装"
                                    hasNewer -> "下载并安装"
                                    errorMsg.isNotEmpty() -> "重新检查"
                                    else -> "检查更新"
                                }
                            )
                        }
                    }
                }
            }

            // 设备信息（常显，不随「发现新版本」折叠——恢复原版平铺）
            Spacer(modifier = Modifier.height(24.dp))
            Text("设备信息", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            deviceInfoRows(ctx).forEach { (k, v) -> InfoRow(k, v) }

            // 90an：版本记录（卡片列表，点击进入 90ao 二级详情页）
            if (releases.isNotEmpty()) {
                Spacer(modifier = Modifier.height(28.dp))
                Text("版本记录", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                releases.forEachIndexed { idx, r ->
                    VersionCard(
                        title = "Lin-Shizuku ${r.tag.removePrefix("v").removePrefix("V")}",
                        isLatest = idx == 0,
                        publishedAt = r.publishedAt.substringBefore("T"),
                        body = r.body.trim(),
                        onClick = {
                            val i = Intent(ctx, VersionDetailActivity::class.java).apply {
                                putExtra("tag", r.tag)
                                putExtra("published", r.publishedAt.substringBefore("T"))
                                putExtra("body", r.body.trim())
                                putExtra("latest", idx == 0)
                            }
                            ctx.startActivity(i)
                            if (ctx is android.app.Activity) ActivityTransitions.overrideForward(ctx)
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/** 信息行：标签 + 值（仿 ColorOS 软件版本列表） */
@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(0.5.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    )
}

/** 90ao：版本记录卡片——点击进入版本详情二级页 */
@Composable
private fun VersionCard(
    title: String,
    isLatest: Boolean,
    publishedAt: String,
    body: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                if (isLatest) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    ) {
                        Text(
                            "最新",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(publishedAt, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (body.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                // 1.0.11f：完整显示更新说明，不截断、不用省略号
                Text(
                    body,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

/** 90ao：设备信息取值（仿 ColorOS「关于本机」） */
@Composable
private fun deviceInfoRows(ctx: Context): List<Pair<String, String>> {
    val model = Build.MODEL
    val product = if (Build.VERSION.SDK_INT >= 31) Build.PRODUCT else Build.DEVICE
    val soc = if (Build.VERSION.SDK_INT >= 31) {
        val m = Build.SOC_MANUFACTURER ?: ""
        val n = Build.SOC_MODEL ?: ""
        if (m.isBlank() && n.isBlank()) Build.HARDWARE else listOf(m, n).filter { it.isNotBlank() }.joinToString(" ")
    } else {
        Build.HARDWARE
    }
    val mem = try {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        String.format(Locale.US, "%.1f GB", mi.totalMem / 1073741824.0)
    } catch (_: Exception) {
        "未知"
    }
    return listOf(
        "设备名称" to model,
        "设备型号" to product,
        "处理器" to soc,
        "运行内存" to mem
    )
}
