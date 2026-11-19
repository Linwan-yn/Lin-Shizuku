package moe.shizuku.privileged.api

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.toMutableStateList
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import moe.shizuku.privileged.api.core.AppThemeState
import moe.shizuku.privileged.api.core.Prefs
import moe.shizuku.privileged.api.ui.apps.AppsScreen
import moe.shizuku.privileged.api.ui.auth.AuthAppsScreen
import moe.shizuku.privileged.api.ui.battery.BatteryScreen
import moe.shizuku.privileged.api.ui.browser.BrowserActivity
import moe.shizuku.privileged.api.ui.home.HomeScreen
import moe.shizuku.privileged.api.ui.modules.ModulesScreen
import moe.shizuku.privileged.api.ui.modules.ScriptEditActivity
import moe.shizuku.privileged.api.ui.nav.NavAnimations
import moe.shizuku.privileged.api.ui.pairing.PairingScreen
import moe.shizuku.privileged.api.ui.settings.SettingsScreen
import moe.shizuku.privileged.api.ui.terminal.TerminalScreen
import moe.shizuku.privileged.api.ui.theme.LinShizukuTheme
import moe.shizuku.privileged.api.ui.widget.GlassNavItem
import moe.shizuku.privileged.api.ui.widget.LiquidGlassBottomBar
import moe.shizuku.privileged.api.util.ActivityTransitions
import moe.shizuku.privileged.api.util.SystemSettings

class MainActivity : ComponentActivity() {

    companion object {
        /** 通知点击 / 「输入配对码」跳转：为 true 时启动后直接进入配对页 */
        const val EXTRA_GO_PAIRING = "moe.shizuku.privileged.api.extra.GO_PAIRING"

        /** 首次启动运行时权限引导：本地标记（仅请求一次） */
        private const val PREFS_FIRST_PERMISSION = "lin_first_permission"
        private const val KEY_PERM_REQUESTED = "requested"
    }

    /**
     * 90at：当前是否直达配对页（可观察状态）。
     * onCreate 从 intent 初始化；onNewIntent 更新——通知跳首页（不带 GO_PAIRING）
     * 复用栈顶 Activity 时，MainScreen 据此自动弹出配对页回首页。
     */
    private val goPairingState = mutableStateOf(false)

    /**
     * 90aw：intent 到达版本号。onNewIntent 时自增，传给 MainScreen 的 LaunchedEffect——
     * 解决「配对页由首页按钮进入（goPairing 恒 false）时，无 GO_PAIRING 的通知/自动
     * 跳转 intent 到达，goPairing 值不变、LaunchedEffect 不触发，配对页子栈残留」
     * （现象：配对成功后点通知回到应用仍停在配对页且显示旧状态「需要您操作」）。
     */
    private val intentVersionState = mutableStateOf(0)

    /**
     * 首次启动运行时权限请求：通知（POST_NOTIFICATIONS）+ 附近设备（NEARBY_WIFI_DEVICES）。
     * 回调后写入「已请求一次」标记；被拒绝的项不再反复弹框，可在设置页手动开启。
     */
    private val firstLaunchPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        getSharedPreferences(PREFS_FIRST_PERMISSION, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_PERM_REQUESTED, true).apply()
    }

    /**
     * 首次安装启动时，主动请求尚未授予的运行时权限（仅一次）。
     * - POST_NOTIFICATIONS：激活通知 / 保活通知需要（Android 13+）；
     * - NEARBY_WIFI_DEVICES：本地网络 / 附近设备发现需要（Android 13+，Manifest 已声明）。
     * 已授予的权限不重复弹出；清单为空时直接写标记，不再发起系统请求。
     */
    private fun requestFirstLaunchPermissionsIfNeeded() {
        val prefs = getSharedPreferences(PREFS_FIRST_PERMISSION, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_PERM_REQUESTED, false)) return
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES)
                != PackageManager.PERMISSION_GRANTED
        ) {
            perms += Manifest.permission.NEARBY_WIFI_DEVICES
        }
        if (perms.isEmpty()) {
            prefs.edit().putBoolean(KEY_PERM_REQUESTED, true).apply()
        } else {
            firstLaunchPermissionLauncher.launch(perms.toTypedArray())
        }
    }

    /**
     * 90at：配对完成/自动启动后跳首页（不带 GO_PAIRING 的 intent 复用本 Activity）。
     * SINGLE_TOP 复用时不会重走 onCreate，这里更新 goPairing 状态，
     * MainScreen 据此把配对页子栈弹出回到首页。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        goPairingState.value = intent?.getBooleanExtra(EXTRA_GO_PAIRING, false) ?: false
        intentVersionState.value++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 通知栏点击 / 「输入配对码」按钮跳转：直达配对页
        goPairingState.value = intent?.getBooleanExtra(EXTRA_GO_PAIRING, false) ?: false
        Prefs.init(this)
        // 首次安装启动：主动请求通知 + 附近设备运行时权限（仅一次，拒绝后不反复弹）
        requestFirstLaunchPermissionsIfNeeded()
        // 高刷由 SystemSettings.applySystem 按设置页开关统一处理，此处不再无条件锁定高刷模式。
        SystemSettings.applySystem(this)
        // 76 轮：强力保活开启时，应用进程启动即拉起守护服务（进程被清后重新进入也能恢复）
        runCatching {
            if (Prefs.strongKeepAlive) moe.shizuku.privileged.api.core.StrongKeepAliveService.start(this)
        }
        // 统一诊断日志：固定写入应用专属目录 files/日志.log（Application 已初始化，此处重复设置兜底）
        moe.shizuku.privileged.api.core.DiagnosticLog.setFallbackDir(getExternalFilesDir(null) ?: filesDir)
        // 生成/刷新启动脚本 /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh
        // （app_process 直连 + 中文输出；放开目录/脚本权限供 root/adb/普通终端执行；
        //  覆盖安装后 base.apk 路径变化需重写）
        runCatching { moe.shizuku.privileged.api.util.Starter.writeSdcardFiles(this) }
        // Lin-Shizuku 生态：App 前台进程启动时主动从 .lin provider 拉取 Binder（服务可能已先于 App 运行），
        // 拉取后桥接官方 Shizuku 单例，首页立即显示运行状态
        runCatching {
            lin.LinProvider.requestBinderForNonProviderProcess(applicationContext)
        }
        // 可信 WLAN 自动启动监听（进程存活期间有效）
        moe.shizuku.privileged.api.core.WlanAutoStartManager.register(applicationContext)
        // 无线调试关闭守护已在 ShizukuApp.onCreate 进程级注册（覆盖 :guard 进程，不依赖界面）
        // 系统暗色模式监听：跟随系统分支即时响应系统切换
        AppThemeState.systemNight.value = isSystemNight(resources.configuration)
        registerComponentCallbacks(object : android.content.ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
                AppThemeState.systemNight.value = isSystemNight(newConfig)
            }
            // ComponentCallbacks 接口必需成员（本应用无内存告警处理需求）
            override fun onLowMemory() {}
        })
        // 公平运行内存机制：检测上次运行是否因系统内存限制（MemoryLimiter）被杀，若是则提示一次
        // （弹窗本体在 setContent 中以 Compose AlertDialog 呈现，跟随深色/浅色主题）
        setContent {
            val scope = rememberCoroutineScope()
            // 启动页动画已移除（用户反馈效果不好）
            // 77 轮：移除 nearby 权限运行时申请——配对发现走 NSD 无需该权限，
            // 避免 Android 15+/17 触发系统「选择要连接的设备」对话框转圈卡死。
            // 自动检查更新（放在最前面，确保最先执行）
            var updateInfo by remember { mutableStateOf<UpdateData?>(null) }
            var downloading by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(3000)
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        // 90ai（P1-2）：降低后台检查频率——距上次成功检查 < 24 小时跳过，减少流量与耗电
                        val prefs = getSharedPreferences("update_check", android.content.Context.MODE_PRIVATE)
                        val now = System.currentTimeMillis()
                        if (now - prefs.getLong("last_check_time", 0L) < 24 * 60 * 60 * 1000L) {
                            return@withContext
                        }
                        val release = moe.shizuku.privileged.api.util.UpdateChecker.fetchLatest()
                        val currentVersion = try {
                            val pkg = packageManager.getPackageInfo(packageName, 0)
                            pkg.versionName?.substringBefore("-") ?: "1.0.0"
                        } catch (_: Exception) { "1.0.0" }
                        val serverVersion = release.tag.removePrefix("v").removePrefix("V")
                        val ignoredTag = prefs.getString("ignored_tag", "")
                        // 70 轮：数值化比较——云端 > 本地才有更新；本地 ≥ 云端视为最新（本地 1.0.3 > 云端 1.0.2 不再误报）
                        // 90ai：无论是否有更新都记录检查时间（24h 内不再重复检查）
                        prefs.edit().putLong("last_check_time", now).apply()
                        if (release.apkUrl.isNotBlank() &&
                            moe.shizuku.privileged.api.util.UpdateChecker.compareVersion(serverVersion, currentVersion) > 0 &&
                            release.tag != ignoredTag) {
                            updateInfo = UpdateData(release.tag, release.publishedAt, release.apkUrl, release.body)
                        }
                    } catch (_: Throwable) {}
                }
            }
            // 外观设置变更
            val darkOption by AppThemeState.darkModeOption
            val amoled by AppThemeState.amoled
            val dynamicColor by AppThemeState.dynamicColor
            val glassMode by AppThemeState.glassMode
            val systemNight by AppThemeState.systemNight
            val dark = when (darkOption) {
                "总是开启" -> true
                "总是关闭" -> false
                else -> systemNight
            }
            // 公平运存提示：Compose 弹窗跟随 LinShizukuTheme 深色/浅色配色
            val showFairMemoryDialog = remember {
                mutableStateOf(moe.shizuku.privileged.api.core.FairMemory.lastExitWasMemoryKill(this))
            }
            LinShizukuTheme(
                darkTheme = dark,
                dynamicColor = dynamicColor,
                amoled = amoled
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        MainScreen(
                            goPairing = goPairingState.value,
                            intentVersion = intentVersionState.value,
                            glassMode = glassMode,
                            onEditScript = { id ->
                                val intent = Intent(this@MainActivity, ScriptEditActivity::class.java)
                                if (id != null) intent.putExtra("script_id", id)
                                startActivity(intent)
                                ActivityTransitions.overrideForward(this@MainActivity)
                            },
                            onSaveAsScript = { cmd ->
                                val intent = Intent(this@MainActivity, ScriptEditActivity::class.java)
                                intent.putExtra("prefill_content", cmd)
                                startActivity(intent)
                                ActivityTransitions.overrideForward(this@MainActivity)
                            }
                        )
                    }
                }
                if (showFairMemoryDialog.value) {
                    AlertDialog(
                        onDismissRequest = { showFairMemoryDialog.value = false },
                        title = { Text("公平运存提示") },
                        text = { Text("检测到上次运行因系统内存限制被终止，应用已自动优化内存策略（释放模块缓存与 WebView 缓存）。若仍频繁出现，可在「设置 → 关于」中查看内存占用。") },
                        confirmButton = {
                            TextButton(onClick = { showFairMemoryDialog.value = false }) { Text("知道了") }
                        }
                    )
                }
                updateInfo?.let { info ->
                    UpdateBottomSheet(
                        tag = info.tag,
                        publishedAt = info.publishedAt,
                        body = info.body,
                        apkUrl = info.apkUrl,
                        onDismiss = { updateInfo = null },
                        onIgnore = {
                            updateInfo = null
                            getSharedPreferences("update_check", android.content.Context.MODE_PRIVATE)
                                .edit().putString("ignored_tag", info.tag).apply()
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (Prefs.hideRecents) {
            try {
                val am = getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                am.appTasks.forEach { it.setExcludeFromRecents(true) }
            } catch (_: Throwable) {}
        }
    }

    override fun finish() {
        super.finish()
        ActivityTransitions.overrideBackward(this)
    }
}

/** 90ai（P1-2）：更新提示数据（版本号 / 发布时间 / 下载地址 / 发布说明）。 */
private data class UpdateData(
    val tag: String,
    val publishedAt: String,
    val apkUrl: String,
    val body: String
)

/**
 * 90ai（P1-2）：更新提示改为底部滑出面板（对齐 Shizuku NEXT 1.1.0 交互）——
 * 展示版本号、发布时间与完整更新内容（可滚动），下方为下载/安装操作区。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UpdateBottomSheet(
    tag: String,
    publishedAt: String,
    body: String,
    apkUrl: String,
    onDismiss: () -> Unit,
    onIgnore: () -> Unit
) {
    val activity = LocalContext.current as? android.app.Activity ?: return
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf(0) }
    var failed by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var completed by remember {
        mutableStateOf(
            moe.shizuku.privileged.api.util.UpdateChecker.checkExistingApk(activity, apkUrl)?.let {
                moe.shizuku.privileged.api.util.UpdateChecker.installedFile = it
                moe.shizuku.privileged.api.util.UpdateChecker.downloadFailed = false
                true
            } ?: false
        )
    }
    LaunchedEffect(downloading) {
        while (downloading) {
            kotlinx.coroutines.delay(300)
            val p = moe.shizuku.privileged.api.util.UpdateChecker.progress
            if (p >= 0) progress = p
            if (!moe.shizuku.privileged.api.util.UpdateChecker.isDownloading && p < 0) {
                downloading = false
                failed = moe.shizuku.privileged.api.util.UpdateChecker.downloadFailed
                completed = moe.shizuku.privileged.api.util.UpdateChecker.downloadCompleted
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (!downloading) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .verticalScroll(androidx.compose.foundation.rememberScrollState())
                .navigationBarsPadding()
        ) {
            // 标题行：版本号 + 关闭
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Text(
                    when { downloading -> "正在下载..."; failed -> "下载失败"; completed -> "下载完成"; else -> "发现新版本" },
                    style = MaterialTheme.typography.titleLarge
                )
                TextButton(onClick = { if (!downloading) onDismiss() }) {
                    Text("✕", fontSize = 18.sp)
                }
            }
            Text("Lin-Shizuku $tag", fontSize = 14.sp)
            Text("发布时间：$publishedAt", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // 更新内容（可滚动查看完整内容）
            if (body.isNotBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                androidx.compose.material3.HorizontalDivider()
                Spacer(modifier = Modifier.height(10.dp))
                Text("更新内容", fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Text(
                    body,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 6.dp, bottom = 8.dp)
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            // 状态区
            when {
                failed -> Text("下载失败，请检查网络后重试。", fontSize = 13.sp)
                completed -> Text("Lin-Shizuku $tag 下载完成。\n点击立即安装进行安装。", fontSize = 13.sp)
                downloading -> {
                    androidx.compose.foundation.layout.Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        androidx.compose.foundation.layout.Column(
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Lin-Shizuku $tag", fontSize = 14.sp)
                            Text(if (progress <= 0) "正在连接服务器..." else "正在下载安装包...", fontSize = 12.sp)
                        }
                        androidx.compose.foundation.layout.Box(
                            contentAlignment = androidx.compose.ui.Alignment.Center,
                            modifier = Modifier.size(36.dp)
                        ) {
                            if (progress > 0) {
                                androidx.compose.material3.CircularProgressIndicator(
                                    progress = { progress / 100f },
                                    modifier = Modifier.size(36.dp),
                                    strokeWidth = 3.dp
                                )
                                Text("$progress%", fontSize = 9.sp)
                            } else {
                                androidx.compose.material3.CircularProgressIndicator(
                                    modifier = Modifier.size(36.dp),
                                    strokeWidth = 3.dp
                                )
                            }
                        }
                    }
                }
                else -> Text("Lin-Shizuku $tag\n点击立即更新下载并安装。", fontSize = 13.sp)
            }
            Spacer(modifier = Modifier.height(12.dp))
            // 操作区
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                if (!downloading && !completed && !failed) {
                    TextButton(onClick = onIgnore) { Text("忽略此版") }
                }
                TextButton(enabled = !downloading, onClick = {
                    when {
                        completed -> {
                            moe.shizuku.privileged.api.util.UpdateChecker.installApk(activity)
                        }
                        failed -> {
                            failed = false; completed = false
                            downloading = true; progress = 0
                            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                moe.shizuku.privileged.api.util.UpdateChecker.downloadAndInstall(
                                    activity, apkUrl, tag
                                ) {}
                            }
                        }
                        else -> {
                            downloading = true; progress = 0
                            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                moe.shizuku.privileged.api.util.UpdateChecker.downloadAndInstall(
                                    activity, apkUrl, tag
                                ) {}
                            }
                        }
                    }
                }) { Text(when { downloading -> "下载中..."; failed -> "重试"; completed -> "立即安装"; else -> "立即更新" }) }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}


/**
 * Navigation target identifiers.
 * Tab screens use 10+tab index so they never collide with home sub-screens (0..9).
 */
private object NavTarget {
    const val HOME = 0
    const val AUTH_APPS = 1
    const val PAIRING = 2
    const val BATTERY = 3
    const val MODULES = 11
    const val TERMINAL = 12
    const val APPS = 13
    const val SETTINGS = 14
}

    private fun isSystemNight(config: android.content.res.Configuration): Boolean {
        return (config.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

@Composable
private fun MainScreen(
    goPairing: Boolean = false,
    intentVersion: Int = 0,
    onEditScript: (String?) -> Unit,
    onSaveAsScript: (String) -> Unit,
    glassMode: Int = Prefs.glassMode
) {
    val ctx = LocalContext.current
    val view = LocalView.current

    var tab by rememberSaveable { mutableIntStateOf(0) }
    // Back stack of sub-screen targets within the home tab. Empty = home.
    // Saved across Activity recreation (settings applied via recreate()).
    val subStack = rememberSaveable(
        saver = listSaver(
            save = { it.toList() },
            restore = { it.toMutableStateList() }
        )
    ) {
        mutableStateListOf<Int>().apply {
            // 通知点击 / 「输入配对码」跳转：直达配对页
            if (goPairing) add(NavTarget.PAIRING)
        }
    }
    // Track previous target for direction-aware animation.
    var prevTarget by remember { mutableIntStateOf(NavTarget.HOME) }

    val currentSub = subStack.lastOrNull() ?: 0
    // 子页（首页或其它 tab 推入）优先显示；无子页时：首页 tab→HOME，其它 tab→tab+10
    val currentTarget = if (subStack.isNotEmpty()) currentSub else if (tab == 0) 0 else tab + 10

    // 90at：通知/自动启动以无 GO_PAIRING 的 intent 复用本 Activity（onNewIntent 更新 goPairing=false）
    // 时，自动弹出配对页回首页——配对完成场景（通知跳首页、autoStart 完成跳首页）统一生效。
    // 90aw：额外以 intentVersion 驱动——配对页由首页按钮进入（goPairing 恒 false）时，
    // 无 GO_PAIRING 的 intent 到达（配对成功通知点击等），值不变 LaunchedEffect 本不触发，
    // 配对页会残留旧状态；现在任何新 intent 到达都会重评估：带 GO_PAIRING→进入配对页（去重），
    // 不带→弹出配对页回首页。首次组合（intentVersion=0, goPairing=false）对空栈无副作用。
    LaunchedEffect(intentVersion, goPairing) {
        if (goPairing) {
            if (!subStack.contains(NavTarget.PAIRING)) {
                prevTarget = currentTarget
                subStack.add(NavTarget.PAIRING)
            }
        } else if (subStack.contains(NavTarget.PAIRING)) {
            prevTarget = currentTarget
            subStack.removeAll { it == NavTarget.PAIRING }
        }
    }

    val items = listOf(
        GlassNavItem(Icons.Filled.Home, "首页"),
        GlassNavItem(Icons.Filled.ViewModule, "模块"),
        GlassNavItem(Icons.Filled.Terminal, "终端"),
        GlassNavItem(Icons.Filled.BatteryFull, "电池"),
        GlassNavItem(Icons.Filled.Settings, "设置")
    )

    /** Push a sub-screen onto the home back stack. */
    fun pushSub(target: Int) {
        prevTarget = currentTarget
        subStack.add(target)
    }

    /** Pop the top sub-screen. Returns true if something was popped. */
    fun popSub(): Boolean {
        if (subStack.isEmpty()) return false
        prevTarget = currentTarget
        subStack.removeLast()
        return true
    }

    /** Switch to a bottom tab, clearing any sub-stack. */
    fun switchTab(newTab: Int) {
        // 95 轮（长按误切定位）：埋点——记录 switchTab 的每次调用、参数与调用栈，
        // 用于定位"长按底栏后 tab 被置 0 切首页"的真实调用源
        try {
            val sb = StringBuilder()
            Throwable().stackTrace?.take(8)?.forEach {
                sb.append("\n  at ").append(it.className).append('.').append(it.methodName)
                    .append('(').append(it.fileName).append(':').append(it.lineNumber).append(')')
            }
            android.util.Log.e(
                "LinBarSwitch", "switchTab($newTab) tab=$tab subStack=$subStack caller=$sb"
            )
        } catch (_: Exception) {
        }
        if (newTab == tab && subStack.isEmpty()) return
        prevTarget = currentTarget
        subStack.clear()
        tab = newTab
        // Haptic feedback on tab switch
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        }
    }

    // System back handler: pop sub-stack first, then return to home tab.
    BackHandler(enabled = subStack.isNotEmpty() || tab != 0) {
        when {
            subStack.isNotEmpty() -> { popSub() }
            tab != 0 -> { switchTab(0) }
        }
    }

    // Determine animation direction:
    // - Sub-screen push/pop: horizontal slide
    // - Tab switch: cross-fade + scale
    val isSubNavigation = (tab == 0 && prevTarget < 10) || (currentTarget < 10)
    val goingBack = when {
        // Popping a sub-screen (home or other tab)
        subStack.isNotEmpty().not() && prevTarget in 1..3 && currentTarget < 10 -> true
        // Tab switch back to home
        prevTarget >= 10 && currentTarget == 0 -> true
        // Sub-screen popped back to a non-home tab (e.g. settings)
        prevTarget < 10 && currentTarget >= 10 -> true
        else -> false
    }

    // AndroidLiquidGlass：录制层——渐变背景 + 页面内容被录制为 backdrop，供底部玻璃胶囊取样
    val backdrop = rememberLayerBackdrop()

    Box(modifier = Modifier.fillMaxSize()) {
        // 液态玻璃采样内容层
        Box(
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop)
        ) {
            // 页面背景：与整屏 Surface 一致的纯背景，避让区颜色跟随主背景，不额外刷偏色面板
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            )

            // 89c 收敛版：不再向子树提供录制层——顶栏/卡片一律用降级色面板，无 drawBackdrop
            AnimatedContent(
                targetState = currentTarget,
                transitionSpec = NavAnimations.transitionSpec(
                isSubPush = isSubNavigation,
                goingBack = goingBack
            ),
            label = "mainNav",
            modifier = Modifier
                .fillMaxSize()
        ) { target ->
            when (target) {
                NavTarget.HOME -> HomeScreen(
                    onManageApps = { pushSub(NavTarget.AUTH_APPS) },
                    onPair = { pushSub(NavTarget.PAIRING) },
                    onStartWireless = { pushSub(NavTarget.PAIRING) },
                    onGuide = {
                        val intent = Intent(ctx, BrowserActivity::class.java)
                        intent.putExtra("url", "https://shizuku.rikka.app/zh-hans/guide/")
                        intent.putExtra("title", "分步骤指南")
                        ctx.startActivity(intent)
                        (ctx as? android.app.Activity)?.let { ActivityTransitions.overrideForward(it) }
                    }
                )
                NavTarget.AUTH_APPS -> AuthAppsScreen()
                NavTarget.PAIRING -> PairingScreen(onBack = { popSub() })
                NavTarget.BATTERY -> BatteryScreen()
                NavTarget.MODULES -> ModulesScreen(onEditScript = onEditScript)
                NavTarget.TERMINAL -> TerminalScreen(onSaveAsScript = onSaveAsScript)
                NavTarget.APPS -> AppsScreen()
                NavTarget.SETTINGS -> SettingsScreen(
                    onOpenBattery = {
                        // 78 轮：统一走 SystemSettings.openBatteryOptimization（与配对页/弹窗行为一致）
                        moe.shizuku.privileged.api.util.SystemSettings.openBatteryOptimization(ctx)
                    },
                    onOpenApps = { pushSub(NavTarget.APPS) }
                )
                else -> HomeScreen(
                    onManageApps = { pushSub(NavTarget.AUTH_APPS) },
                    onPair = { pushSub(NavTarget.PAIRING) },
                    onStartWireless = { pushSub(NavTarget.PAIRING) },
                    onGuide = {
                        val intent = Intent(ctx, BrowserActivity::class.java)
                        intent.putExtra("url", "https://shizuku.rikka.app/zh-hans/guide/")
                        intent.putExtra("title", "分步骤指南")
                        ctx.startActivity(intent)
                        (ctx as? android.app.Activity)?.let { ActivityTransitions.overrideForward(it) }
                    }
                )
            }
        }
        } // 关闭液态玻璃采样内容层

        // 顶层：悬浮胶囊，叠加在系统导航栏之上，不占用内容空间
        if (glassMode == 5) {
            // 原生液态玻璃（官方 LiquidBottomTabs 风格）
            moe.shizuku.privileged.api.ui.widget.OfficialLiquidBottomBar(
                backdrop = backdrop,
                items = items,
                selectedIndex = tab,
                onSelect = { switchTab(it) },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        } else {
            LiquidGlassBottomBar(
                backdrop = backdrop,
                items = items,
                selectedIndex = tab,
                onSelect = { switchTab(it) },
                glassMode = glassMode,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}
