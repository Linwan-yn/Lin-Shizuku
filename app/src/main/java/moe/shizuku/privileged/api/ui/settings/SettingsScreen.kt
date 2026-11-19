package moe.shizuku.privileged.api.ui.settings

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Brightness2
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Feedback
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.shizuku.privileged.api.core.AppThemeState
import moe.shizuku.privileged.api.BuildConfig
import moe.shizuku.privileged.api.core.DiagnosticLog
import moe.shizuku.privileged.api.core.Prefs
import moe.shizuku.privileged.api.core.ShizukuManager
import moe.shizuku.privileged.api.ui.browser.BrowserActivity
import moe.shizuku.privileged.api.ui.widget.GlassFallbackColor
import moe.shizuku.privileged.api.ui.widget.GlassPanel
import moe.shizuku.privileged.api.ui.widget.pressClickable
import moe.shizuku.privileged.api.ui.widget.pressClickableNoRipple
import moe.shizuku.privileged.api.util.ActivityTransitions
import moe.shizuku.privileged.api.util.SystemSettings
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onOpenBattery: () -> Unit = {}, onOpenApps: () -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }

    // 所有开关/下拉框直接以 Prefs 真实值初始化：重建（recreate）后立即显示
    // 真实状态，避免先显示默认值再异步同步造成的"关→开"动画。
    var bootStart by remember { mutableStateOf(Prefs.startOnBoot) }
    var wlanAutoStart by remember { mutableStateOf(Prefs.wlanAutoStart) }
    var darkModeOption by remember { mutableStateOf(Prefs.darkModeOption) }
    var amoled by remember { mutableStateOf(Prefs.amoled) }
    var dynamicColor by remember { mutableStateOf(Prefs.dynamicColor) }
    var glassMode by remember { mutableStateOf(Prefs.glassMode) }
    var hideRecents by remember { mutableStateOf(Prefs.hideRecents) }
    var highRefresh by remember { mutableStateOf(Prefs.highRefresh) }
    var highRefreshLevel by remember { mutableStateOf(Prefs.highRefreshLevel) }
    var hapticEnabled by remember { mutableStateOf(Prefs.hapticEnabled) }
    var tcpMode by remember { mutableStateOf(Prefs.tcpMode) }
    var tcpPort by remember { mutableStateOf(Prefs.tcpPort) }
    // 90bt：TCP 模式真实执行中（防重复点击/连击）
    var tcpBusy by remember { mutableStateOf(false) }
    var autoPair by remember { mutableStateOf(Prefs.autoPairEnabled) }
    var autoWirelessBoot by remember { mutableStateOf(Prefs.autoWirelessBoot) }
    var watchdog by remember { mutableStateOf(Prefs.watchdog) }
    // 新增功能开关状态
    var bootMode by remember { mutableStateOf(Prefs.bootMode) }
    var accessibilityBoot by remember { mutableStateOf(Prefs.accessibilityBoot) }
    var shizukuCompat by remember { mutableStateOf(Prefs.shizukuCompat) }
    var wirelessSu by remember { mutableStateOf(Prefs.wirelessSu) }
    var processGuard by remember { mutableStateOf(Prefs.processGuard) }
    var bootCardExpanded by remember { mutableStateOf(false) }
    // 76 轮：强力保活 / Scene黑域自动激活 / 自动开启 USB 调试
    var strongKeepAlive by remember { mutableStateOf(Prefs.strongKeepAlive) }
    var autoActivate by remember { mutableStateOf(Prefs.autoActivate) }
    var autoUsbDebug by remember { mutableStateOf(Prefs.autoUsbDebug) }
    // 1.1.2：WRITE_SECURE_SETTINGS 状态。进入设置页时若服务已在运行但权限还没拿到，
    // 自动补一次授权（服务端启动时已授予一次，这里覆盖"服务在装新版前就已启动"等情况）。
    var writeSecureSettingsGranted by remember {
        mutableStateOf(moe.shizuku.privileged.api.util.WirelessDebugController.hasWriteSecureSettings(ctx))
    }
    LaunchedEffect(Unit) {
        if (!writeSecureSettingsGranted) {
            // 最多等 12s（服务可能正在启动中），拿到权限立刻刷新状态
            repeat(6) {
                val ok = withContext(Dispatchers.IO) {
                    moe.shizuku.privileged.api.core.ShizukuManager.isRunningCompat() &&
                        moe.shizuku.privileged.api.core.ShizukuManager.ensureWriteSecureSettings(ctx)
                }
                if (ok) {
                    writeSecureSettingsGranted = true
                    android.widget.Toast.makeText(
                        ctx, "已自动授予 WRITE_SECURE_SETTINGS", android.widget.Toast.LENGTH_SHORT
                    ).show()
                    return@LaunchedEffect
                }
                delay(2000)
            }
        }
    }
    // 76 轮：自启动与电池设置分入口
    var showBatteryAutostartSheet by remember { mutableStateOf(false) }
    val batterySheetState = rememberModalBottomSheetState()
    // 68 轮：命令执行身份（跟随服务 / Shell(com.android.shell) / Root）
    var execIdentity by remember { mutableStateOf(Prefs.execIdentity) }
    val execServerIdentity = moe.shizuku.privileged.api.core.ShizukuManager.serverIdentity().label


    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp)
            .padding(top = 16.dp)
    ) {
        // 89c 收敛：设置页标题条为五档降级色面板（GlassPanel 已去掉 drawBackdrop 真玻璃）
        GlassPanel(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "设置",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    // 动态读 BuildConfig.VERSION_NAME，跟随 app/build.gradle.kts 版本号自动同步
                    "Lin-Shizuku · " + moe.shizuku.privileged.api.BuildConfig.VERSION_NAME,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.height(14.dp))

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(bottom = 72.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())
        ) {
            // === 权限与身份 ===
            SectionHeader("权限与身份")
            // 68 轮：命令执行身份（适配 com.android.shell）
            ExecutionIdentityCard(
                identity = execIdentity,
                serverIdentity = execServerIdentity,
                onIdentityChange = {
                    execIdentity = it
                    Prefs.execIdentity = it
                }
            )
            Spacer(modifier = Modifier.height(12.dp))
            // 1.0.11：修改系统设置权限（WRITE_SETTINGS）——点击跳转系统授权页；
            // 系统授权页返回后 ON_RESUME 自动重读 canWrite，开关状态实时刷新
            var writeSettingsGranted by remember { mutableStateOf(android.provider.Settings.System.canWrite(ctx)) }
            val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                    if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                        writeSettingsGranted = android.provider.Settings.System.canWrite(ctx)
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }
            SettingToggle(
                title = "修改系统设置权限",
                subtitle = if (writeSettingsGranted) "已授权" else "未授权：前往授予权限",
                checked = writeSettingsGranted,
                icon = Icons.Filled.Tune,
                onCheckedChange = {
                    runCatching {
                        ctx.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS)
                                .setData(android.net.Uri.parse("package:" + ctx.packageName))
                        )
                    }
                }
            )
            Spacer(modifier = Modifier.height(12.dp))
            // 附近设备权限（NEARBY_WIFI_DEVICES）——mDNS 配对扫描需要
            var nearbyWifiGranted by remember { mutableStateOf(
                androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.NEARBY_WIFI_DEVICES) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            ) }
            DisposableEffect(lifecycleOwner) {
                val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                    if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                        nearbyWifiGranted = androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.NEARBY_WIFI_DEVICES) ==
                            android.content.pm.PackageManager.PERMISSION_GRANTED
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }
            SettingToggle(
                title = "附近设备权限",
                subtitle = if (nearbyWifiGranted) "已授权" else "未授权：Android 13+ 配对需要，点击授权",
                checked = nearbyWifiGranted,
                icon = Icons.Filled.Wifi,
                onCheckedChange = {
                    runCatching {
                        ctx.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                .setData(android.net.Uri.parse("package:" + ctx.packageName))
                        )
                    }
                }
            )
            Spacer(modifier = Modifier.height(12.dp))
            // 获取应用列表权限（QUERY_ALL_PACKAGES）——安装时声明，默认已授权
            SettingToggle(
                title = "获取应用列表",
                subtitle = "已授权：模块管理与授权列表可用",
                checked = true,
                icon = Icons.Filled.Android,
                onCheckedChange = {
                    runCatching {
                        ctx.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                .setData(android.net.Uri.parse("package:" + ctx.packageName))
                        )
                    }
                }
            )
            Spacer(modifier = Modifier.height(12.dp))
            // WRITE_SECURE_SETTINGS——开关控制，用户自行决定是否授予
            SettingToggle(
                title = "WRITE_SECURE_SETTINGS 权限",
                subtitle = if (writeSecureSettingsGranted) "已授予：可控制系统开关"
                           else "未授予：关闭后无法控制部分系统开关",
                checked = writeSecureSettingsGranted,
                icon = Icons.Filled.Lock,
                onCheckedChange = { wantGrant ->
                    scope.launch(Dispatchers.IO) {
                        val ok = if (wantGrant) {
                            moe.shizuku.privileged.api.core.ShizukuManager
                                .ensureWriteSecureSettings(ctx)
                        } else {
                            moe.shizuku.privileged.api.core.ShizukuManager
                                .revokeWriteSecureSettings(ctx)
                        }
                        withContext(Dispatchers.Main) {
                            writeSecureSettingsGranted = if (wantGrant) ok else !ok
                            if (!ok) {
                                android.widget.Toast.makeText(
                                    ctx,
                                    if (wantGrant) "授权失败：请确认服务正在运行"
                                    else "撤销失败，请确认服务正在运行",
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                }
            )
            Spacer(modifier = Modifier.height(12.dp))
            // 禁用系统优化开关显示/隐藏（secure system_opt_enable）
            var sysOptVisible by remember { mutableStateOf<Boolean?>(null) }
            LaunchedEffect(Unit) {
                sysOptVisible = withContext(Dispatchers.IO) {
                    runCatching {
                        val out = moe.shizuku.privileged.api.core.ShizukuManager
                            .runCommand("settings get secure system_opt_enable 2>&1", Prefs.execIdentity)
                        out.trim() == "1"
                    }.getOrDefault(false)
                }
            }
            SettingToggle(
                title = "显示「禁用系统优化」开关",
                subtitle = if (sysOptVisible == null) "正在读取..."
                           else if (sysOptVisible == true) "已显示：在开发者选项中可见"
                           else "已隐藏：开发者选项中不可见",
                checked = sysOptVisible == true,
                icon = Icons.Filled.Tune,
                onCheckedChange = { wantShow ->
                    scope.launch(Dispatchers.IO) {
                        val out = runCatching {
                            moe.shizuku.privileged.api.core.ShizukuManager
                                .runCommand("settings put secure system_opt_enable ${if (wantShow) 1 else 0} 2>&1", Prefs.execIdentity)
                        }.getOrDefault("")
                        withContext(Dispatchers.Main) {
                            if (out.contains("Exception") || out.contains("Error")) {
                                android.widget.Toast.makeText(ctx, "设置失败：请确认服务正在运行", android.widget.Toast.LENGTH_LONG).show()
                            } else {
                                sysOptVisible = wantShow
                                android.widget.Toast.makeText(ctx, if (wantShow) "已显示「禁用系统优化」开关" else "已隐藏「禁用系统优化」开关", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            )

        Spacer(modifier = Modifier.height(20.dp))

            // === 启动与连接 ===
            SectionHeader("启动与连接")

        // 开机启动卡片：可展开，内含启动模式四选一 + 无障碍自启
        BootModeCard(
            expanded = bootCardExpanded,
            onExpandToggle = { bootCardExpanded = !bootCardExpanded },
            bootMode = bootMode,
            onModeChange = {
                bootMode = it
                Prefs.bootMode = it
            },
            accessibilityBoot = accessibilityBoot,
            onAccessibilityChange = {
                accessibilityBoot = it
                Prefs.accessibilityBoot = it
                if (it) {
                    // 打开系统无障碍设置页
                    val intent = android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                }
            },
            rootAvailable = moe.shizuku.privileged.api.core.ShizukuManager.isRooted(),
            lastBootResult = Prefs.lastBootResult
        )
        Spacer(modifier = Modifier.height(12.dp))

        FeatureToggleCard(
            icon = Icons.Filled.Share,
            title = "Shizuku 兼容层",
            desc = "允许应用通过 Lin-Shizuku 服务运行",
            checked = shizukuCompat,
            onCheckedChange = {
                shizukuCompat = it
                Prefs.shizukuCompat = it
            }
        )
        Spacer(modifier = Modifier.height(12.dp))
        FeatureToggleCard(
            icon = Icons.Filled.Tag,
            title = "无线调试提权 su",
            desc = "尝试通过无线调试使用 su 提权启动服务",
            checked = wirelessSu,
            onCheckedChange = {
                wirelessSu = it
                Prefs.wirelessSu = it
            }
        )
        Spacer(modifier = Modifier.height(12.dp))
        SettingToggle(
            title = "可信 WLAN 自动启动",
            subtitle = "连接可信 WLAN 时自动启动（Android 13+）",
            checked = wlanAutoStart,
            icon = Icons.Filled.Wifi,
            onCheckedChange = {
                wlanAutoStart = it
                Prefs.wlanAutoStart = it
            }
        )

        Spacer(modifier = Modifier.height(20.dp))

        // === 保活与守护 ===
        SectionHeader("保活与守护")
        // 1.1.2（自愈闭环）：返回设置页时，Prefs 开启但服务未真实运行 → 自动补拉起，
        // 根治「关了再开才生效」（假开：Prefs=true 但服务被后台启动限制拦截没起来）
        DisposableEffect(lifecycleOwner) {
            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                    runCatching {
                        if (Prefs.processGuard &&
                            !moe.shizuku.privileged.api.core.ShizukuManager.isServiceRunning(ctx, moe.shizuku.privileged.api.core.GuardService::class.java)
                        ) {
                            moe.shizuku.privileged.api.core.GuardService.start(ctx)
                            moe.shizuku.privileged.api.core.DiagnosticLog.append("[设置] onResume 兜底：进程守护未运行，已自动拉起")
                        }
                    }
                    runCatching {
                        if (Prefs.strongKeepAlive &&
                            !moe.shizuku.privileged.api.core.ShizukuManager.isServiceRunning(ctx, moe.shizuku.privileged.api.core.StrongKeepAliveService::class.java)
                        ) {
                            moe.shizuku.privileged.api.core.StrongKeepAliveService.start(ctx)
                            moe.shizuku.privileged.api.core.DiagnosticLog.append("[设置] onResume 兜底：强力保活未运行，已自动拉起")
                        }
                    }
                    runCatching {
                        if (Prefs.autoActivate &&
                            !moe.shizuku.privileged.api.core.ShizukuManager.isServiceRunning(ctx, moe.shizuku.privileged.api.core.AppTriggerService::class.java)
                        ) {
                            moe.shizuku.privileged.api.core.AppTriggerService.start(ctx)
                            moe.shizuku.privileged.api.core.DiagnosticLog.append("[设置] onResume 兜底：Scene/黑域自动激活未运行，已自动拉起")
                        }
                    }
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
        // 1.0.11d：删除"降权激活"独立开关——已合并进"执行身份"卡片（选 Shell 自动降权）
        FeatureToggleCard(
            icon = Icons.Filled.Autorenew,
            title = "进程守护",
            desc = "独立进程互守：服务端崩溃自动重启（Root/TCP），主进程被杀自动拉起；耗电略微增加",
            checked = processGuard,
            onCheckedChange = {
                processGuard = it
                Prefs.processGuard = it
                // 进程守护开启即启用崩溃自动恢复
                watchdog = it
                Prefs.watchdog = it
                // A4（90n 实现）：开启启动双进程守护（独立 :guard 进程），关闭停止
                if (it) {
                    moe.shizuku.privileged.api.core.GuardService.start(ctx)
                    // 1.1.2（自愈闭环）：启动后延时验证服务真实运行，失败重试一次并记录
                    scope.launch {
                        delay(800)
                        if (!moe.shizuku.privileged.api.core.ShizukuManager.isServiceRunning(ctx, moe.shizuku.privileged.api.core.GuardService::class.java)) {
                            moe.shizuku.privileged.api.core.GuardService.start(ctx)
                            delay(800)
                            if (!moe.shizuku.privileged.api.core.ShizukuManager.isServiceRunning(ctx, moe.shizuku.privileged.api.core.GuardService::class.java)) {
                                android.widget.Toast.makeText(ctx, "进程守护启动被系统拦截，请重试", android.widget.Toast.LENGTH_SHORT).show()
                                moe.shizuku.privileged.api.core.DiagnosticLog.append("[设置] 进程守护启动失败（两次尝试后仍未运行）")
                            } else {
                                moe.shizuku.privileged.api.core.DiagnosticLog.append("[设置] 进程守护首次启动被拦截，重试成功")
                            }
                        }
                    }
                } else {
                    moe.shizuku.privileged.api.core.GuardService.stop(ctx)
                }
            }
        )
        Spacer(modifier = Modifier.height(12.dp))
        // 76 轮：强力保活——闹钟心跳兜底 + 息屏持续检测 + 划掉任务自动复活
        FeatureToggleCard(
            icon = Icons.Filled.Alarm,
            title = "强力保活",
            desc = "闹钟心跳兜底、息屏持续检测、划掉任务后自动复活；耗电量会略微增加",
            checked = strongKeepAlive,
            onCheckedChange = {
                strongKeepAlive = it
                Prefs.strongKeepAlive = it
                if (it) {
                    moe.shizuku.privileged.api.core.StrongKeepAliveService.start(ctx)
                    // 1.1.2（自愈闭环）：启动后延时验证，失败重试一次
                    scope.launch {
                        delay(800)
                        if (!moe.shizuku.privileged.api.core.ShizukuManager.isServiceRunning(ctx, moe.shizuku.privileged.api.core.StrongKeepAliveService::class.java)) {
                            moe.shizuku.privileged.api.core.StrongKeepAliveService.start(ctx)
                            delay(800)
                            if (!moe.shizuku.privileged.api.core.ShizukuManager.isServiceRunning(ctx, moe.shizuku.privileged.api.core.StrongKeepAliveService::class.java)) {
                                android.widget.Toast.makeText(ctx, "强力保活启动被系统拦截，请重试", android.widget.Toast.LENGTH_SHORT).show()
                                moe.shizuku.privileged.api.core.DiagnosticLog.append("[设置] 强力保活启动失败（两次尝试后仍未运行）")
                            } else {
                                moe.shizuku.privileged.api.core.DiagnosticLog.append("[设置] 强力保活首次启动被拦截，重试成功")
                            }
                        }
                    }
                } else {
                    moe.shizuku.privileged.api.core.StrongKeepAliveService.stop(ctx)
                }
            }
        )
        Spacer(modifier = Modifier.height(12.dp))
        // 76 轮：Scene/黑域自动激活——进入 Scene、黑域时弹通知，点击即可激活
        FeatureToggleCard(
            icon = Icons.Filled.NotificationsActive,
            title = "Scene/黑域自动激活",
            desc = "进入 Scene、黑域时弹出激活通知，点击直接启动服务（需授予「使用情况访问权限」）",
            checked = autoActivate,
            onCheckedChange = {
                autoActivate = it
                Prefs.autoActivate = it
                if (it) {
                    moe.shizuku.privileged.api.core.AppTriggerService.start(ctx)
                    // 1.1.2（自愈闭环）：启动后延时验证，失败重试一次
                    scope.launch {
                        delay(800)
                        if (!moe.shizuku.privileged.api.core.ShizukuManager.isServiceRunning(ctx, moe.shizuku.privileged.api.core.AppTriggerService::class.java)) {
                            moe.shizuku.privileged.api.core.AppTriggerService.start(ctx)
                            delay(800)
                            if (!moe.shizuku.privileged.api.core.ShizukuManager.isServiceRunning(ctx, moe.shizuku.privileged.api.core.AppTriggerService::class.java)) {
                                android.widget.Toast.makeText(ctx, "Scene/黑域自动激活启动被系统拦截，请重试", android.widget.Toast.LENGTH_SHORT).show()
                                moe.shizuku.privileged.api.core.DiagnosticLog.append("[设置] Scene/黑域自动激活启动失败（两次尝试后仍未运行）")
                            } else {
                                moe.shizuku.privileged.api.core.DiagnosticLog.append("[设置] Scene/黑域自动激活首次启动被拦截，重试成功")
                            }
                        }
                    }
                } else {
                    // 90h 轮：关闭时同步停止监听服务（旧版只改 pref 不停服务，服务空转且无法重置状态）
                    moe.shizuku.privileged.api.core.AppTriggerService.stop(ctx)
                }
            }
        )
        Spacer(modifier = Modifier.height(12.dp))
        // 76 轮：自动开启 USB 调试——Root/Shell 可写时保持 adb_enabled 开启
        SettingToggle(
            title = "自动开启 USB 调试",
            subtitle = "自动保持 USB 调试开启（需 Root）",
            checked = autoUsbDebug,
            icon = Icons.Filled.Usb,
            onCheckedChange = {
                autoUsbDebug = it
                Prefs.autoUsbDebug = it
                if (it) moe.shizuku.privileged.api.core.ShizukuManager.applyAutoUsbDebug(ctx)
            }
        )
        Spacer(modifier = Modifier.height(12.dp))
        SettingNavCard(
            icon = Icons.Filled.PowerSettingsNew,
            title = "自启动与电池设置",
            desc = "按机型跳转系统自启动与电池优化页面",
            onClick = { showBatteryAutostartSheet = true }
        )
        Spacer(modifier = Modifier.height(12.dp))
        SettingNavCard(
            icon = Icons.Filled.BatterySaver,
            title = "电池优化管理",
            desc = "加入白名单，防止后台被系统清理",
            onClick = { onOpenBattery() }
        )

        Spacer(modifier = Modifier.height(20.dp))

        // === 界面外观 ===
        SectionHeader("界面外观")
        SettingSelector(
            title = "夜间模式",
            subtitle = "选择深色主题触发方式",
            icon = Icons.Filled.DarkMode,
            options = listOf("跟随系统", "总是关闭", "总是开启"),
            // 90bw：图标卡片网格（跟随系统=Contrast / 浅色=WbSunny / 深色=DarkMode）
            icons = listOf(Icons.Filled.Contrast, Icons.Filled.WbSunny, Icons.Filled.DarkMode),
            selected = darkModeOption,
            onSelect = {
                darkModeOption = it
                Prefs.darkModeOption = it
                AppThemeState.darkModeOption.value = it
            }
        )
        Spacer(modifier = Modifier.height(12.dp))
        val darkThemeDisabled = darkModeOption == "总是关闭"
        SettingToggle(
            title = "黑色夜间主题",
            subtitle = if (darkThemeDisabled) "需先开启夜间模式" else "夜间模式时纯黑背景（AMOLED）",
            checked = amoled,
            enabled = !darkThemeDisabled,
            icon = Icons.Filled.Brightness2,
            onCheckedChange = {
                amoled = it
                Prefs.amoled = it
                AppThemeState.amoled.value = it
            }
        )
        Spacer(modifier = Modifier.height(12.dp))
        SettingToggle(
            title = "使用系统主题颜色",
            subtitle = "Android 12+ 跟随壁纸动态取色",
            checked = dynamicColor,
            icon = Icons.Filled.Palette,
            onCheckedChange = {
                dynamicColor = it
                Prefs.dynamicColor = it
                AppThemeState.dynamicColor.value = it
            }
        )
        Spacer(modifier = Modifier.height(12.dp))
        // 80 轮：底部导航材质改为「夜间模式同款」——行内显示当前值，点击弹出底部选项单（原为卡片内平铺五选项）
        val glassOptions = listOf("普通", "穿透玻璃", "高斯模糊", "毛玻璃", "液态玻璃", "原生液态玻璃")
        SettingSelector(
            title = "底部导航材质",
            subtitle = "近实体磨砂 · 低模糊高透 · 纯模糊磨砂 · 磨砂+折射 · 透亮折射 · 官方原生",
            options = glassOptions,
            selected = glassOptions[glassMode.coerceIn(0, glassOptions.lastIndex)],
            icon = Icons.Filled.BlurOn,
            onSelect = { label ->
                val index = glassOptions.indexOf(label)
                if (index >= 0) {
                    glassMode = index
                    Prefs.glassMode = index
                    AppThemeState.glassMode.value = index
                }
            }
        )

        Spacer(modifier = Modifier.height(20.dp))


        // === 增强功能 ===
        SectionHeader("实验功能")
        SettingToggle(
            title = "隐藏后台",
            subtitle = "最近任务中隐藏，进程后台运行",
            checked = hideRecents,
            icon = Icons.Filled.VisibilityOff,
            onCheckedChange = {
                hideRecents = it
                Prefs.hideRecents = it
                SystemSettings.applySystem(ctx)
            }
        )
        Spacer(modifier = Modifier.height(12.dp))
        SettingToggle(
            title = "高刷新率",
            subtitle = "自动选择设备支持的最高刷新率",
            checked = highRefresh,
            icon = Icons.Filled.Speed,
            onCheckedChange = {
                highRefresh = it
                Prefs.highRefresh = it
                SystemSettings.applySystem(ctx)
            }
        )
        Spacer(modifier = Modifier.height(12.dp))
        // 高刷档位：仅在高刷开关开启时可选择，关闭时置灰
        val hrOptions = listOf("设备最高", "120 Hz", "90 Hz", "60 Hz")
        Box(modifier = Modifier.alpha(if (highRefresh) 1f else 0.5f)) {
            SettingSelector(
                title = "高刷档位",
                subtitle = "开启后按所选档位强制刷新率",
                icon = Icons.Filled.Speed,
                options = hrOptions,
                selected = hrOptions[highRefreshLevel.coerceIn(0, hrOptions.lastIndex)],
                onSelect = { label ->
                    if (highRefresh) {
                        val index = hrOptions.indexOf(label)
                        if (index >= 0) {
                            highRefreshLevel = index
                            Prefs.highRefreshLevel = index
                            SystemSettings.applySystem(ctx)
                        }
                    }
                }
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        SettingToggle(
            title = "触感反馈",
            subtitle = "按下、切换、长按拖拽时震动",
            checked = hapticEnabled,
            icon = Icons.Filled.Vibration,
            onCheckedChange = {
                hapticEnabled = it
                Prefs.hapticEnabled = it
            }
        )
        Spacer(modifier = Modifier.height(12.dp))
        // 90ah：自动化配对开关（默认开）——无障碍服务读取系统配对弹窗 6 位码自动完成配对
        SettingToggle(
            title = "自动化配对",
            subtitle = "自动读取配对码完成配对（需开启无障碍服务）",
            checked = autoPair,
            icon = Icons.Filled.Autorenew,
            onCheckedChange = {
                autoPair = it
                Prefs.autoPairEnabled = it
            }
        )
        Spacer(modifier = Modifier.height(12.dp))
        // 1.0.11：全自动无线配对（默认开）——常规启动失败后用 root 命令开 Wi-Fi→开发者选项→无线调试→拉起配对界面，
        // 后续抓码/配对由无障碍+自动化配对链路完成，全程无需触屏
        SettingToggle(
            title = "全自动无线配对",
            subtitle = "开机自启失败时自动 root 拉起配对界面（配合无障碍）",
            checked = autoWirelessBoot,
            icon = Icons.Filled.Autorenew,
            onCheckedChange = {
                autoWirelessBoot = it
                Prefs.autoWirelessBoot = it
            }
        )
        Spacer(modifier = Modifier.height(12.dp))
        // 90bt：TCP 模式真实实现——通过服务端 shell 执行 setprop + 重启 adbd，
        // 打开 = adb tcpip:<端口> 固定 TCP 监听，关闭 = usb: 恢复；失败自动回滚开关。
        // 90bu：TCP 模式正确定位 =「服务端启动/重启通道」，不是 App 与服务端之间的通信方式。
        // App 使用 Shizuku 始终走 Binder；TCP 只在「激活/重启服务端」时作为固定端口通道：
        //  - 服务端已激活：开启开关立即真实切换（su / transport / 服务端 三通道）；
        //  - 服务端未激活：仅记录偏好，激活流程（startShizukuViaAdb 启动成功后）自动
        //    通过当前连接发 tcpip:<端口> 建立固定端口重启通道——「激活就能用 TCP 模式」。
        SettingToggle(
            title = "TCP 模式",
            subtitle = "激活/重启服务端的固定端口通道；App 用服务走 Binder，与 TCP 无关",
            checked = tcpMode,
            enabled = !tcpBusy,
            icon = Icons.Filled.Router,
            onCheckedChange = { target ->
                // 90bt：开关 = 纯偏好（TCP 通道启用状态），不做硬切、不报失败。
                // 通道由激活/重启服务端流程自动建立（startShizukuViaAdb tcpMode 切换 +
                // detectAvailablePort 优先固定端口）；关闭后下次重启服务端自动恢复动态端口。
                tcpMode = target
                Prefs.tcpMode = target
                DiagnosticLog.append("[TCP] 模式${if (target) "开启（端口 $tcpPort，激活/重启时自动建立通道）" else "关闭（下次重启恢复动态端口）"}")
                Toast.makeText(
                    ctx,
                    if (target) "TCP 通道已启用（激活/重启服务端时自动经 $tcpPort 拉起）"
                    else "TCP 通道已停用（下次重启服务端时恢复无线调试动态端口）",
                    Toast.LENGTH_SHORT
                ).show()
            }
        )
        if (tcpMode) {
            Spacer(modifier = Modifier.height(12.dp))
            // 90aq：TCP 端口支持自定义输入（预设 5555/6666/7777/8888 + 自定义）；
            // 已开启时切换端口立即重启 adbd 生效
            var showCustomPort by remember { mutableStateOf(false) }
            var customPortInput by remember { mutableStateOf("") }
            var customPortError by remember { mutableStateOf(false) }
            SettingSelector(
                title = "TCP 端口",
                subtitle = "TCP 模式固定监听端口（建议 5555-8888）",
                icon = Icons.Filled.Tag,
                options = listOf("5555", "6666", "7777", "8888", "自定义"),
                selected = tcpPort.toString(),
                onSelect = {
                    if (it == "自定义") {
                        customPortInput = tcpPort.toString()
                        customPortError = false
                        showCustomPort = true
                    } else {
                        changeTcpPort(ctx, scope, it.toInt()) { newPort ->
                            tcpPort = newPort
                            Prefs.tcpPort = newPort
                        }
                    }
                }
            )
            if (showCustomPort) {
                AlertDialog(
                    onDismissRequest = { showCustomPort = false },
                    title = { Text("自定义 TCP 端口") },
                    text = {
                        Column {
                            OutlinedTextField(
                                value = customPortInput,
                                onValueChange = { input ->
                                    customPortInput = input.filter { it.isDigit() }.take(5)
                                    customPortError = false
                                },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                label = { Text("端口（1-65535）") },
                                isError = customPortError,
                                modifier = Modifier.fillMaxWidth()
                            )
                            if (customPortError) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text("请输入 1-65535 之间的有效端口", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val v = customPortInput.toIntOrNull()
                            if (v != null && v in 1..65535) {
                                changeTcpPort(ctx, scope, v) { newPort ->
                                    tcpPort = newPort
                                    Prefs.tcpPort = newPort
                                }
                                showCustomPort = false
                            } else {
                                customPortError = true
                            }
                        }) { Text("确定") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showCustomPort = false }) { Text("取消") }
                    }
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))

        Spacer(modifier = Modifier.height(20.dp))

        // === 内存与储存 ===
        SectionHeader("内存与储存")
        // 公平运行内存机制：展示当前应用内存占用（PSS），便于对照内存区间自查
        val pssMb = remember { moe.shizuku.privileged.api.core.FairMemory.currentPssMB() }
        SettingValueCard(
            icon = Icons.Filled.Memory,
            title = "内存占用（公平运存）",
            desc = "内存紧张时系统会通知本应用主动释放模块与 WebView 缓存；若因系统内存上限被终止，重启后会自动提示。",
            value = "$pssMb MB"
        )
        Spacer(modifier = Modifier.height(12.dp))
        SettingNavCard(
            icon = Icons.Filled.Storage,
            title = "存储空间管理",
            desc = "查看缓存、WebView 与应用数据占用，一键清理或重置 App",
            onClick = {
                ctx.startActivity(android.content.Intent(ctx, StorageActivity::class.java))
                (ctx as? android.app.Activity)?.let { ActivityTransitions.overrideForward(it) }
            }
        )

        Spacer(modifier = Modifier.height(20.dp))

        // === 关于 ===
        SectionHeader("关于")
        SettingInfoCard(
            icon = Icons.Filled.Android,
            title = "Lin-Shizuku ${BuildConfig.VERSION_NAME.substringBefore("-")}",
            desc = "",
            // 90am：点击进入关于/软件更新二级界面（检查更新已移入）
            onClick = {
                ctx.startActivity(android.content.Intent(ctx, AboutActivity::class.java))
                (ctx as? android.app.Activity)?.let { ActivityTransitions.overrideForward(it) }
            }
        )
        Spacer(modifier = Modifier.height(12.dp))
        SettingNavCard(
            icon = Icons.Filled.Article,
            title = "服务端日志",
            desc = "查看服务端 Binder 分发与授权执行日志，便于排查第三方应用权限问题",
            onClick = {
                ctx.startActivity(android.content.Intent(ctx, ServerLogActivity::class.java))
                (ctx as? android.app.Activity)?.let { ActivityTransitions.overrideForward(it) }
            }
        )
        Spacer(modifier = Modifier.height(12.dp))
        // 反馈入口：展示联系邮箱，支持一键复制
        var showFeedback by remember { mutableStateOf(false) }
        SettingNavCard(
            icon = Icons.Filled.Feedback,
            title = "反馈",
            desc = "遇到问题或建议，欢迎邮件联系",
            onClick = { showFeedback = true }
        )
        if (showFeedback) {
            FeedbackDialog(
                ctx = ctx,
                onDismiss = { showFeedback = false }
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        // 90al：赞赏入口——悬浮卡片展示赞赏码，支持保存到相册 / 分享到微信
        var showDonation by remember { mutableStateOf(false) }
        SettingNavCard(
            icon = Icons.Filled.Favorite,
            title = "赞赏",
            desc = "支持作者",
            onClick = { showDonation = true }
        )
        if (showDonation) {
            DonationDialog(
                ctx = ctx,
                onDismiss = { showDonation = false }
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        // 开源组件入口：列出本应用集成的开源项目与许可证详情
        SettingNavCard(
            icon = Icons.Filled.Book,
            title = "开源组件",
            desc = "查看本应用使用的开源组件及许可证",
            onClick = {
                ctx.startActivity(android.content.Intent(ctx, OpenSourceActivity::class.java))
                (ctx as? android.app.Activity)?.let { ActivityTransitions.overrideForward(it) }
            }
        )
        Spacer(modifier = Modifier.height(12.dp))
    }

    // 76 轮：自启动与电池设置分入口弹窗（按机型适配，回退应用详情页）
    if (showBatteryAutostartSheet) {
        ModalBottomSheet(
            onDismissRequest = { showBatteryAutostartSheet = false },
            sheetState = batterySheetState,
            // 89 轮：弹窗玻璃质感底色
            containerColor = GlassFallbackColor(glassMode)
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
                Text(
                    "自启动与电池设置",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "按机型跳转到系统对应页面；未找到入口时回退到应用详情页",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                SheetItem(
                    icon = Icons.Filled.PlayArrow,
                    title = if (SystemSettings.isMiuiHyperOs()) "自启动管理（MIUI/HyperOS）" else "自启动设置",
                    desc = "允许开机自动启动、后台运行"
                ) {
                    showBatteryAutostartSheet = false
                    SystemSettings.openAutostartSettings(ctx)
                }
                Spacer(modifier = Modifier.height(10.dp))
                // 90 轮：原「电池优化豁免」子项已去除 —— 与增强功能区块「电池优化管理」
                // （均跳 SystemSettings.openBatteryOptimization）功能重复，保留增强功能区唯一入口
                SheetItem(
                    icon = Icons.Filled.Info,
                    title = "应用详情页",
                    desc = "手动管理通知、权限等（通用回退入口）"
                ) {
                    showBatteryAutostartSheet = false
                    SystemSettings.openAppDetails(ctx)
                }
            }
        }
        } // 89b：内层滚动内容闭合（固定顶栏之外）
    }
}

/** 76 轮：底部弹窗条目。 */
@Composable
private fun SheetItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    desc: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().pressClickableNoRipple { onClick() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(desc, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(
                Icons.Filled.ChevronRight,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

private fun openBrowser(ctx: android.content.Context, url: String) {
    val intent = Intent(ctx, BrowserActivity::class.java)
    intent.putExtra("url", url)
    ctx.startActivity(intent)
    (ctx as? android.app.Activity)?.let { ActivityTransitions.overrideForward(it) }
}

/** 反馈弹窗：显示联系邮箱，支持一键复制到剪贴板。 */
private const val FEEDBACK_EMAIL = "2902579979@qq.com"

@Composable
private fun FeedbackDialog(ctx: android.content.Context, onDismiss: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(0.92f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("反馈与联系", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    TextButton(onClick = onDismiss) { Text("✕", fontSize = 16.sp) }
                }
                Text(
                    "遇到问题、Bug 或功能建议，欢迎发送邮件到下方邮箱。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(14.dp))
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            FEEDBACK_EMAIL,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { copyToClipboard(ctx, "反馈邮箱", FEEDBACK_EMAIL) }) {
                            Text("复制", fontSize = 13.sp)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = { copyToClipboard(ctx, "反馈邮箱", FEEDBACK_EMAIL) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("复制邮箱", fontSize = 13.sp) }
            }
        }
    }
}

/** 写入系统剪贴板并给出 Toast 提示。 */
private fun copyToClipboard(ctx: android.content.Context, label: String, text: String) {
    try {
        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
        android.widget.Toast.makeText(ctx, "邮箱已复制：$text", android.widget.Toast.LENGTH_SHORT).show()
    } catch (e: Throwable) {
        android.widget.Toast.makeText(ctx, "复制失败：${e.message ?: e.javaClass.simpleName}", android.widget.Toast.LENGTH_SHORT).show()
    }
}

/**
 * 90al：赞赏悬浮卡片——展示完整赞赏码，支持「保存到相册」与「打开微信」。
 * 微信无法从外部直接拉起扫一扫识别指定图片，因此「打开微信」采用分享图片给微信
 * （用户在微信中长按图片 → 识别图中二维码即可赞赏），是最接近一键直达的路径。
 */
@Composable
private fun DonationDialog(ctx: android.content.Context, onDismiss: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(0.92f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("赞赏支持", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    TextButton(onClick = onDismiss) { Text("✕", fontSize = 16.sp) }
                }
                Text(
                    "感谢支持！您的赞赏是持续维护 Lin-Shizuku 的动力。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(moe.shizuku.privileged.api.R.drawable.donation_qr),
                    contentDescription = "赞赏码",
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { saveDonationQr(ctx) },
                        modifier = Modifier.weight(1f)
                    ) { Text("保存到相册", fontSize = 13.sp) }
                    Button(
                        onClick = { shareDonationQrToWechat(ctx) },
                        modifier = Modifier.weight(1f)
                    ) { Text("打开微信", fontSize = 13.sp) }
                }
            }
        }
    }
}

/** 90al：保存赞赏码到相册（API 29+ 用 MediaStore 免权限；旧版写 Pictures + 媒体扫描）。 */
private fun saveDonationQr(ctx: android.content.Context) {
    try {
        val bmp = android.graphics.BitmapFactory.decodeResource(ctx.resources, moe.shizuku.privileged.api.R.drawable.donation_qr)
            ?: return
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "Lin-Shizuku-赞赏码.png")
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES + "/Lin-Shizuku")
                put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                ctx.contentResolver.openOutputStream(uri)?.use {
                    bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                values.clear()
                values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
                ctx.contentResolver.update(uri, values, null, null)
                android.widget.Toast.makeText(ctx, "已保存到相册（Pictures/Lin-Shizuku）", android.widget.Toast.LENGTH_SHORT).show()
                return
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES), "Lin-Shizuku")
            if (dir.mkdirs() || dir.exists()) {
                val file = java.io.File(dir, "Lin-Shizuku-赞赏码.png")
                file.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                android.media.MediaScannerConnection.scanFile(ctx, arrayOf(file.absolutePath), arrayOf("image/png"), null)
                android.widget.Toast.makeText(ctx, "已保存到相册（Pictures/Lin-Shizuku）", android.widget.Toast.LENGTH_SHORT).show()
                return
            }
        }
        android.widget.Toast.makeText(ctx, "保存失败，请重试", android.widget.Toast.LENGTH_SHORT).show()
    } catch (e: Throwable) {
        android.widget.Toast.makeText(ctx, "保存失败：${e.message ?: e.javaClass.simpleName}", android.widget.Toast.LENGTH_SHORT).show()
    }
}

/** 90al：把赞赏码分享给微信（用户在微信长按图片 → 识别图中二维码即可赞赏）。 */
private fun shareDonationQrToWechat(ctx: android.content.Context) {
    try {
        ctx.packageManager.getPackageInfo("com.tencent.mm", 0)
        val bmp = android.graphics.BitmapFactory.decodeResource(ctx.resources, moe.shizuku.privileged.api.R.drawable.donation_qr)
            ?: return
        val cache = java.io.File(ctx.cacheDir, "donation_qr.png")
        cache.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", cache)
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            putExtra(android.content.Intent.EXTRA_TEXT, "Lin-Shizuku 赞赏码，感谢支持！")
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setPackage("com.tencent.mm")
        }
        ctx.startActivity(intent)
    } catch (_: Exception) {
        android.widget.Toast.makeText(ctx, "未安装微信，可先保存到相册", android.widget.Toast.LENGTH_SHORT).show()
    }
}

/** 功能开关卡片：圆角方形图标 + 标题 + 描述 + 右侧 Switch（参照参考图样式）。 */
@Composable
private fun FeatureToggleCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    desc: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(22.dp))
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                if (!desc.isNullOrBlank()) {
                    Text(desc, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 17.sp)
                }
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.primary,
                    checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    }
}

/** 跳转卡片：圆角方形图标 + 标题 + 描述 + 右侧箭头，整体与 TCP 端口卡片同款。 */
@Composable
private fun SettingNavCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    desc: String,
    onClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pressClickableNoRipple(onClick = onClick)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(22.dp))
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                if (!desc.isNullOrBlank()) {
                    Text(desc, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 17.sp)
                }
            }
            Spacer(modifier = Modifier.width(4.dp))
            Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 只读数值卡片：图标 + 标题 + 描述 + 右侧高亮数值（如内存占用），不可点击。 */
@Composable
private fun SettingValueCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    desc: String,
    value: String
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(22.dp))
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                if (!desc.isNullOrBlank()) {
                    Text(desc, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 17.sp)
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(value, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** 静态信息卡片：图标 + 标题 + 描述，无箭头、不可点击（如版本信息）。 */
@Composable
private fun SettingInfoCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    desc: String? = null,
    onClick: (() -> Unit)? = null
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(22.dp))
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                if (!desc.isNullOrBlank()) {
                    Text(desc, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 17.sp)
                }
            }
            if (onClick != null) {
                Icon(
                    Icons.Filled.ChevronRight,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/**
 * 68 轮：命令执行身份卡片（适配 com.android.shell）。
 * 三选一：跟随服务 / Shell(com.android.shell, uid 2000) / Root。
 * 影响模块、脚本、终端、输出页等所有「执行类」命令的权限身份。
 * 80 轮：改为与开机启动同款——收起只显示标题行 + 当前身份，点击展开/收起三选项。
 */
@Composable
private fun ExecutionIdentityCard(
    identity: moe.shizuku.privileged.api.core.ShizukuManager.ExecIdentity,
    serverIdentity: String,
    onIdentityChange: (moe.shizuku.privileged.api.core.ShizukuManager.ExecIdentity) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf(
        Triple(
            moe.shizuku.privileged.api.core.ShizukuManager.ExecIdentity.AUTO,
            "跟随服务",
            "跟随服务端权限：root 服务→Root，shell 服务→Shell"
        ),
        Triple(
            moe.shizuku.privileged.api.core.ShizukuManager.ExecIdentity.SHELL,
            "Shell (com.android.shell)",
            "强制以 Shell 用户 uid 2000 执行，root 服务端自动降级"
        ),
        Triple(
            moe.shizuku.privileged.api.core.ShizukuManager.ExecIdentity.ROOT,
            "Root",
            "强制以 Root uid 0 执行，仅服务端为 root 时可用"
        )
    )
    val currentLabel = when (identity) {
        moe.shizuku.privileged.api.core.ShizukuManager.ExecIdentity.AUTO -> "跟随服务"
        moe.shizuku.privileged.api.core.ShizukuManager.ExecIdentity.SHELL -> "Shell"
        moe.shizuku.privileged.api.core.ShizukuManager.ExecIdentity.ROOT -> "Root"
    }
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pressClickableNoRipple { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Terminal,
                        null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("执行身份", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text("模块/脚本/终端命令的执行权限身份", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    currentLabel,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
            }
            if (expanded) {
                Spacer(modifier = Modifier.height(12.dp))
                options.forEach { (opt, title, desc) ->
                    val selected = identity == opt
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .pressClickable { onIdentityChange(opt) }
                            .background(
                                if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                                else androidx.compose.ui.graphics.Color.Transparent
                            )
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (selected) "●" else "○",
                            fontSize = 13.sp,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    title,
                                    fontSize = 14.sp,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                // 跟随服务：标题右侧标注当前服务端身份，一眼看清实际生效权限
                                if (opt == moe.shizuku.privileged.api.core.ShizukuManager.ExecIdentity.AUTO) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        "当前 $serverIdentity",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(desc, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 15.sp)
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
        }
    }
}

/** 开机启动卡片：可展开头部 + 启动模式四选一 + 无障碍自启。 */
@Composable
private fun BootModeCard(
    expanded: Boolean,
    onExpandToggle: () -> Unit,
    bootMode: String,
    onModeChange: (String) -> Unit,
    accessibilityBoot: Boolean,
    onAccessibilityChange: (Boolean) -> Unit,
    rootAvailable: Boolean = true,
    lastBootResult: String = ""
) {
    val modes = listOf("关闭", "开机广播", "预热", "Root脚本")
    val modeDesc = when (bootMode) {
        "开机广播" -> "通过系统广播在开机时自动启动"
        "预热" -> "开机后等待 WiFi，初始化 ADB TCP/IP 端口后断开，不启动服务端，以便无 WiFi 时启动服务端"
        "Root脚本" -> "通过 Root 脚本在开机后直接启动服务"
        else -> "已关闭开机自启动"
    }
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pressClickableNoRipple { onExpandToggle() },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Bolt, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(22.dp))
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("开机启动", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text("配置开机自启动模式", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(
                    Icons.Filled.ExpandMore,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
            }
            if (expanded) {
                Spacer(modifier = Modifier.height(18.dp))
                // 69 轮：区段小标题统一 13sp SemiBold，与材质卡说明层级一致
                Text("启动模式", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(10.dp))
                // 四档分段选择器（胶囊容器，选中项高亮）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    modes.forEach { mode ->
                        val selected = bootMode == mode
                        val disabled = mode == "Root脚本" && !rootAvailable
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    when {
                                        selected -> MaterialTheme.colorScheme.primary
                                        disabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                                        else -> androidx.compose.ui.graphics.Color.Transparent
                                    }
                                )
                                .pressClickable { if (!disabled) onModeChange(mode) }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                mode,
                                fontSize = 13.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = when {
                                    selected -> MaterialTheme.colorScheme.onPrimary
                                    disabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                // 69 轮：说明文字 12sp/17 与全站说明统一
                Text(modeDesc, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 17.sp)
                // 90l 轮：最近一次开机自启结果（含失败原因引导），避免"静默空壳"
                if (lastBootResult.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    val ok = lastBootResult.contains("成功")
                    Text(
                        text = lastBootResult,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = if (ok) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error
                    )
                }
                // 无障碍自启仅在"开机广播"模式下显示
                if (bootMode == "开机广播") {
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("无障碍自启", fontWeight = FontWeight.Medium, fontSize = 15.sp)
                            Text("通过无障碍唤醒开机广播", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = accessibilityBoot,
                            onCheckedChange = onAccessibilityChange,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.primary,
                                checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun SettingToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
        modifier = if (enabled) Modifier else Modifier.alpha(0.5f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(22.dp))
                }
                Spacer(modifier = Modifier.width(14.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    color = if (enabled) Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
                Text(
                    subtitle,
                    fontSize = 12.sp,
                    color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    lineHeight = 17.sp
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Switch(
                checked = checked && enabled,
                enabled = enabled,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.primary,
                    checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingSelector(
    title: String,
    subtitle: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    // 90bw：传图标列表时弹窗改为「图标卡片网格」（迅雷/豆包风格），
    // 不传时保持原单列文字列表（TCP 端口等通用选择器）
    icons: List<androidx.compose.ui.graphics.vector.ImageVector>? = null
) {
    var showSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pressClickableNoRipple { showSheet = true }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(22.dp))
                }
                Spacer(modifier = Modifier.width(14.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text(
                    subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 17.sp
                )
            }
            Text(
                selected,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(4.dp))
            Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = sheetState,
            // 89 轮：弹窗玻璃质感底色（Dialog 窗口无录制层，用五档玻璃色半透明面板）
            containerColor = GlassFallbackColor(Prefs.glassMode)
        ) {
            Column(modifier = Modifier.padding(bottom = 24.dp)) {
                Text(
                    title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )
            if (icons != null && icons.size == options.size) {
                // 90bw：图标卡片网格（跟随系统/浅色/深色）——圆形容器图标 + 选中描边 + 底部对勾
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    options.forEachIndexed { index, option ->
                        val optIcon = icons[index]
                        val isSelected = option == selected
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(16.dp))
                                .pressClickable {
                                    onSelect(option)
                                    showSheet = false
                                }
                                .padding(vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(28.dp))
                                    .background(
                                        if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                                    )
                                    .border(
                                        width = if (isSelected) 1.5.dp else 0.dp,
                                        color = MaterialTheme.colorScheme.primary,
                                        shape = RoundedCornerShape(28.dp)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    optIcon, null,
                                    modifier = Modifier.size(26.dp),
                                    tint = if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                option,
                                fontSize = 13.sp,
                                color = if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                                if (isSelected) {
                                    Icon(Icons.Filled.Done, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            } else {
                options.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .pressClickable {
                                onSelect(option)
                                showSheet = false
                            }
                            .padding(horizontal = 24.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            option,
                            fontSize = 16.sp,
                            modifier = Modifier.weight(1f),
                            color = if (option == selected)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurface
                        )
                        if (option == selected) {
                            Icon(
                                Icons.Filled.Done,
                                null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
            }
        }
    }
}

// ==================== 90bt：TCP 模式真实执行（双通道） ====================
// 之前依赖 Shizuku 服务端执行：服务端未运行（或 shell 身份无 setprop 权限）
// 时开关直接失败（实测 Toast「服务端未运行或权限不足」）。
// 现在改为双通道，按用户本地 Root Shell 思路：
//   通道 1：`su -c` 本地 root shell 直接执行（不依赖服务端，root 管理器授权即用）；
//   通道 2：Shizuku 服务端（跟随服务端身份）。
// 任一通道成功并 getprop 校验通过即视为成功；开启时同步设置 persist.adb.tcp.port
// （重启后 adbd 仍按固定端口启动，用户教程技巧），关闭时清零。

/** 直接以 su -c 在本地 root shell 执行命令（不依赖 Shizuku 服务端）。失败返回 null。 */
private fun runRootShellCommand(cmd: String): String? = try {
    val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().readText()
    if (!p.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) {
        p.destroyForcibly()
        null
    } else out
} catch (e: Throwable) {
    null
}

private fun checkTcpResult(last: String, enable: Boolean, port: Int): Boolean =
    if (enable) last == port.toString()
    else last == "0" || last.isEmpty()

/** 真实切换 TCP 模式：开启 → adb tcpip:<port>（含 persist 持久化）；关闭 → usb:（tcp.port=0）。返回是否成功。 */
private suspend fun applyTcpModeSwitch(ctx: android.content.Context, enable: Boolean, port: Int): Boolean {
    val target = if (enable) port.toString() else "0"
    val cmd = "setprop persist.adb.tcp.port $target; setprop service.adb.tcp.port $target; stop adbd; start adbd; sleep 1; getprop service.adb.tcp.port"
    // 通道 1：本地 su（root 管理器授权后直接执行，服务端未运行也能用）
    val suOut = withContext(Dispatchers.IO) { runRootShellCommand(cmd) }
    val suLast = suOut?.trim()?.lineSequence()?.lastOrNull { it.isNotBlank() }?.trim().orEmpty()
    if (suOut != null && checkTcpResult(suLast, enable, port)) {
        DiagnosticLog.append("[TCP] ${if (enable) "开启（端口 $port）" else "关闭"} 成功（su 通道）")
        return true
    }
    // 通道 2：ADB transport（无线调试激活场景，无需 root；adbd 自己切端口）
    val adbOk = withContext(Dispatchers.IO) {
        try { moe.shizuku.privileged.api.util.AdbWirelessHelper().switchTcpModeViaAdb(ctx, enable, port) }
        catch (_: Throwable) { false }
    }
    if (adbOk) {
        DiagnosticLog.append("[TCP] ${if (enable) "开启（端口 $port）" else "关闭"} 成功（transport 通道）")
        return true
    }
    // 通道 3：Shizuku 服务端
    return try {
        val out = ShizukuManager.runCommand(cmd, ShizukuManager.ExecIdentity.AUTO)
        DiagnosticLog.append("[TCP] ${if (enable) "开启($port)" else "关闭"} 服务端通道输出: ${out.trim().take(200)}")
        checkTcpResult(out.trim().lineSequence().lastOrNull { it.isNotBlank() }?.trim().orEmpty(), enable, port)
    } catch (e: Throwable) {
        DiagnosticLog.append("[TCP] ${if (enable) "开启" else "关闭"} 服务端通道异常: ${e.message}")
        false
    }
}

/** 已开启 TCP 模式时切换端口：立即重启 adbd 使新端口生效（三通道）。返回是否成功。 */
private suspend fun applyTcpPortSwitch(ctx: android.content.Context, port: Int): Boolean {
    val cmd = "setprop persist.adb.tcp.port $port; setprop service.adb.tcp.port $port; stop adbd; start adbd; sleep 1; getprop service.adb.tcp.port"
    // 通道 1：本地 su
    val suOut = withContext(Dispatchers.IO) { runRootShellCommand(cmd) }
    val suLast = suOut?.trim()?.lineSequence()?.lastOrNull { it.isNotBlank() }?.trim().orEmpty()
    if (suOut != null && suLast == port.toString()) {
        DiagnosticLog.append("[TCP] 端口 $port 切换成功（su 通道）")
        return true
    }
    // 通道 2：ADB transport（无线调试激活场景）
    val adbOk = withContext(Dispatchers.IO) {
        try { moe.shizuku.privileged.api.util.AdbWirelessHelper().switchTcpModeViaAdb(ctx, true, port) }
        catch (_: Throwable) { false }
    }
    if (adbOk) {
        DiagnosticLog.append("[TCP] 端口 $port 切换成功（transport 通道）")
        return true
    }
    // 通道 3：Shizuku 服务端
    return try {
        val out = ShizukuManager.runCommand(cmd, ShizukuManager.ExecIdentity.AUTO)
        DiagnosticLog.append("[TCP] 切换端口 $port 服务端输出: ${out.trim().take(200)}")
        out.trim().lineSequence().lastOrNull { it.isNotBlank() }?.trim() == port.toString()
    } catch (e: Throwable) {
        DiagnosticLog.append("[TCP] 端口切换服务端异常: ${e.message}")
        false
    }
}

/**
 * 保存端口并（若 TCP 模式已开启）立即重切 adbd 端口。
 * [onSaved] 同步回调用于更新 UI 状态与 Prefs。
 */
private fun changeTcpPort(
    ctx: android.content.Context,
    scope: CoroutineScope,
    newPort: Int,
    onSaved: (Int) -> Unit
) {
    onSaved(newPort)
    // 仅服务端已激活时立即重切 adbd 端口；未激活时只保存端口，激活流程会用新端口建立通道
    if (Prefs.tcpMode && ShizukuManager.isLinServerRunning()) {
        scope.launch {
            val ok = applyTcpPortSwitch(ctx, newPort)
            DiagnosticLog.append("[TCP] ${if (ok) "端口 $newPort 切换成功" else "端口 $newPort 切换失败"}")
            Toast.makeText(
                ctx,
                if (ok) "TCP 端口已切换为 $newPort" else "端口切换失败（需要 Root 授权、无线调试或运行中的服务端）",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}
