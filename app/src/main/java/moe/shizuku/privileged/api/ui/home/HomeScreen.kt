package moe.shizuku.privileged.api.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import moe.shizuku.privileged.api.ui.widget.CodeBlock
import moe.shizuku.privileged.api.ui.widget.GlassCard
import moe.shizuku.privileged.api.ui.widget.GlassPanel
import moe.shizuku.privileged.api.ui.widget.IconContainer
import moe.shizuku.privileged.api.ui.widget.OutputDialog
import moe.shizuku.privileged.api.ui.widget.StatusDot
import rikka.shizuku.Shizuku

@Composable
fun HomeScreen(
    onManageApps: () -> Unit,
    onPair: () -> Unit,
    onGuide: () -> Unit = {},
    onStartWireless: () -> Unit = {}
) {
    val ctx = LocalContext.current.applicationContext
    val vm: HomeViewModel = viewModel()
    val state by vm.state.collectAsState()

    // 本地 shell 启动命令：短脚本 sh .../start.sh（App 自动生成，app_process 直连
    // 本应用 base.apk，进程名 Lin_server，中文输出；不经过 liblin.so——
    // 其内部固定执行 pm path lin.manager，会定向加载 Lin 服务端）
    val localStartCommand = remember {
        try {
            moe.shizuku.privileged.api.util.Starter.getSdcardCommand(ctx)
        } catch (_: Throwable) { "sh /sdcard/start.sh" }
    }

    var showStopConfirm by remember { mutableStateOf(false) }
    var showGuide by remember { mutableStateOf(false) }
    var showRootConfirm by remember { mutableStateOf(false) }
    var showNotificationDenied by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()

    // Refresh on binder events (service started / died), same as official HomeActivity.
    DisposableEffect(Unit) {
        val received = Shizuku.OnBinderReceivedListener {
            vm.refresh(ctx, includeCount = true)
        }
        val dead = Shizuku.OnBinderDeadListener {
            vm.refresh(ctx, includeCount = true)
        }
        Shizuku.addBinderReceivedListenerSticky(received)
        Shizuku.addBinderDeadListener(dead)
        onDispose {
            Shizuku.removeBinderReceivedListener(received)
            Shizuku.removeBinderDeadListener(dead)
        }
    }

    // Initial load + light polling fallback while the screen is visible.
    // 90i 轮：3s→5s——服务状态变化已由上方 binder 监听器实时驱动刷新，
    // 轮询仅兜底 rootAvailable/计数等非 binder 变化，放宽间隔降低前台 CPU 占用
    LaunchedEffect(Unit) {
        vm.refresh(ctx, includeCount = true)
        while (true) {
            delay(5000)
            vm.refresh(ctx, includeCount = false)
        }
    }

    val running = state.isRunning

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp)
            .padding(top = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 89c 收敛：首页标题条为五档降级色面板（GlassPanel 已去掉 drawBackdrop 真玻璃）
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
                    text = "Lin-Shizuku",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.weight(1f))
                if (running) {
                    Text(
                        "运行中",
                        fontSize = 12.sp,
                        color = Color(0xFF4CAF50),
                        fontWeight = FontWeight.Medium
                    )
                } else if (state.starting) {
                    Text(
                        "启动中",
                        fontSize = 12.sp,
                        color = Color(0xFFFFA726),
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(14.dp))

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(bottom = 72.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

        // Status card (real binder state)
        GlassCard {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(
                        active = running,
                        size = 14.dp
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Crossfade(
                        targetState = running,
                        animationSpec = tween(durationMillis = 300),
                        label = "statusText"
                    ) { isRunning ->
                        Text(
                            text = if (isRunning) "正在运行" else "未运行",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    AnimatedVisibility(
                        visible = running,
                        enter = fadeIn(tween(220)) + scaleIn(initialScale = 0.9f, animationSpec = tween(220)),
                        exit = fadeOut(tween(160)) + scaleOut(targetScale = 0.9f, animationSpec = tween(160))
                    ) {
                        OutlinedButton(
                            onClick = { showStopConfirm = true },
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Color(0xFFE53935)
                            )
                        ) {
                            Icon(Icons.Filled.Stop, null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("停止", fontSize = 12.sp)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("服务版本", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                    Text(
                        if (running && state.serverVersion > 0) "API ${lin.LinApiConstants.API_VERSION_NAME}" else "—",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("运行身份", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                    Text(
                        state.identityText,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp
                    )
                }
                val seContext = state.seContext
                if (running && seContext != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("SELinux", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                        Text(
                            seContext,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Authorized apps entry (real count)
        GlassCard(
            onClick = onManageApps
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconContainer(
                    icon = Icons.Filled.Security,
                    size = 48.dp,
                    iconSize = 26.dp
                )
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("应用授权管理", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        "点击以管理 Shizuku 生态应用",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "已授权",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${state.authorizedCount} 个应用",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Wireless debugging startup
        StartupCard(
            icon = Icons.Filled.Sensors,
            title = "通过无线调试启动",
            subtitle = "免 Root · Android 11+",
            description = if (state.isRunning) {
                "服务正在运行。需要重启服务端时点「重启服务」，配对信息会保留；只有换 WiFi 或重启手机后才需要「重新配对」。"
            } else {
                "在 Android 11 或更高版本上，您可以直接从设备启用无线调试并启动 Lin-Shizuku，无需连接电脑。先点「配对」完成配对，之后点「启动」即可直接启动服务。TCP 模式开启时启动后自动切换到固定端口，之后无需 WiFi 也可重启服务。"
            }
        ) {
            // 1.1.2 交互打磨：按钮由状态驱动，不再是"两个永远高亮的紫按钮"。
            //  未运行 → 配对 + 启动；运行中 → 重新配对（弱化）+ 重启服务。
            // 原来运行中点「启动」只会弹一句"服务已在运行中，无需重复启动"，
            // 用户不点一下永远不知道当前状态该点哪个。
            val svcRunning = state.isRunning
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                OutlinedButton(
                    onClick = {
                        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                        if (!nm.areNotificationsEnabled()) {
                            showNotificationDenied = true
                        } else {
                            onPair()
                        }
                    },
                    modifier = Modifier.width(110.dp),
                    enabled = !state.starting
                ) {
                    Text(if (svcRunning) "重新配对" else "配对", fontSize = 12.sp)
                }
                Button(
                    onClick = {
                        vm.startWireless(ctx, onNeedPair = { onPair() }, forceRestart = svcRunning)
                    },
                    modifier = Modifier.width(110.dp),
                    enabled = !state.starting,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(
                        when {
                            state.starting && state.startMode == StartMode.WADB -> "启动中..."
                            svcRunning -> "重启服务"
                            else -> "启动"
                        },
                        fontSize = 12.sp
                    )
                }
            }
            if (svcRunning) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "服务已在运行：服务掉线直接点「重启服务」，不需要重新配对；只有换 WiFi 或重启手机后才需要「重新配对」。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 17.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Root startup (real su detection + real start)
        StartupCard(
            icon = Icons.Filled.VpnKey,
            title = "通过 root 权限启动",
            subtitle = if (state.rootAvailable) "已检测到 su" else "未检测到 su",
            description = buildString {
                append("另外，Lin-Shizuku 支持开机自启。若未自动启动，请检查系统或第三方工具是否限制了 Lin-Shizuku。")
                if (running && state.serverUid == 0) {
                    append(" 您正在以 root 身份运行；如果使用 Magisk，可以尝试 Sui。")
                }
            }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Button(
                    onClick = { showRootConfirm = true },
                    modifier = Modifier.width(140.dp),
                    enabled = !state.starting,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(
                        when {
                            state.starting && state.startMode == StartMode.ROOT -> "启动中..."
                            running && state.serverUid == 0 -> "重启"
                            else -> "启动"
                        },
                        fontSize = 14.sp
                    )
                }
            }
        }

        // ADB 权限排查帮助：仅服务以 Shell(adb, uid 2000) 身份运行时显示；
        // Root(uid 0) 与未运行时不展示，避免常驻告警式提示与实际状态脱节。
        if (state.isRunning && state.serverUid == 2000) {
            Spacer(modifier = Modifier.height(14.dp))
            AdbPermissionTipCard()
        }

        // ADB via PC
        StartupCard(
            icon = Icons.Filled.SettingsEthernet,
            title = "通过连接电脑启动（使用 adb）",
            subtitle = "无 Root 设备",
            description = "没有 root 的设备可借助 adb 启动 Lin-Shizuku（需连接电脑）。设备每次重启后需重新执行，查看「启动方式使用手册」卡片获取完整流程。",
            command = "adb shell $localStartCommand"
        )

        Spacer(modifier = Modifier.height(14.dp))

        // Local shell
        StartupCard(
            icon = Icons.Filled.Terminal,
            title = "通过本地 shell 启动",
            subtitle = "终端 App",
            description = "在终端 App 中执行下方命令即可启动 Lin-Shizuku。root、adb 或普通终端均可运行，启动失败可查看日志排查。",
            command = localStartCommand
        )

        Spacer(modifier = Modifier.height(14.dp))

        // 90bl：统一的「启动方式使用手册」入口卡片（90bk 起作为唯一手册入口，置于启动卡片区最下方——
        // 无线调试/连接电脑/本地 shell/root 共用；按钮「使用手册」缩短置右，与其它启动卡按钮风格一致）
        StartupCard(
            icon = Icons.Filled.MenuBook,
            title = "启动方式使用手册",
            subtitle = "无线调试 · 连接电脑 · 本地 shell · root",
            description = "几种启动方式的使用方法、配对流程与常见问题汇总：无线调试配对（小窗/通知输入配对码）、adb 连接电脑、本地 shell、root 启动与开机自启。"
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                OutlinedButton(
                    onClick = { showGuide = true },
                    modifier = Modifier.width(130.dp)
                ) { Text("使用手册", fontSize = 12.sp) }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

    }

    // Startup output dialog (real output streamed from the startup flow)
    if (state.dialogVisible) {
        OutputDialog(
            title = when (state.startMode) {
                StartMode.ROOT -> "Root 启动"
                StartMode.WADB -> "无线调试启动"
                else -> "启动服务"
            },
            output = state.output,
            loading = state.starting,
            loadingText = when (state.startMode) {
                StartMode.ROOT -> "正在通过 su 启动 Lin-Shizuku..."
                StartMode.WADB -> "正在通过无线调试启动..."
                else -> "正在启动..."
            },
            onDismiss = { vm.dismissDialog() },
            // 1.1.2：未配对/端口不可用时，把按钮文案明确成「去配对」，
            // 用户不会再点了"开发者选项"却不知道这跟配对是同一件事。
            developerOptionsLabel = if (state.needPair) "去配对" else "开发者选项",
            onDeveloperOptions = if (state.startMode == StartMode.WADB) {
                {
                    // 跳转开发者选项 + 推送配对通知
                    // 1.1.2：先关掉启动弹窗，否则从配对页返回时这层弹窗还盖在首页上
                    vm.dismissDialog()
                    try {
                        ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: Throwable) {}
                    onPair()
                }
            } else null
        )
    }

    // 启动使用手册（内置，无需联网；90g 轮按官方 Shizuku 手册重写：
    // 无线调试/配对完整流程 + 连接电脑 + root + 常见问题）
    if (showGuide) {
        AlertDialog(
            onDismissRequest = { showGuide = false },
            title = { Text("启动使用手册") },
            text = {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(end = 4.dp)
                ) {
                    GuideSection("方式一：无线调试启动（免 Root · Android 11+）")
                    GuideStep(1, "开启开发者选项", "进入「设置 → 关于手机」，连续点击「版本号」7 次，直到出现「您已处于开发者模式」提示。")
                    GuideStep(2, "启用无线调试", "进入「设置 → 开发者选项 → 无线调试」，打开开关。系统弹出「允许在此网络开启无线调试」时选择「允许」（可勾选「始终允许此网络」）。部分系统（小米/OPPO）需在开发者选项中同时开启「USB 调试」并连接数据线一次。")
                    GuideStep(3, "回到本 App 点「配对」", "点击首页「通过无线调试启动」卡片中的「配对」按钮，App 开始搜索配对服务并弹出配对输入。配对成功后点「启动」即可直接启动服务。")
                    GuideStep(4, "输入配对码", "在系统「无线调试」页面点「使用配对码配对设备」，记下 6 位配对码；输入到本 App 的配对输入（小窗输入；已授权通知权限时也可在通知栏输入）并发送。配对成功系统会显示「已连接」。配对仅需一次。")
                    GuideStep(5, "等待自动启动", "配对成功后 App 自动继续启动，Lin-Shizuku 服务开始运行，页面显示「正在运行」、运行身份为 shell (adb)。")
                    GuideStep(6, "重启后重新启动", "设备每次重启后服务都会停止：重复第 3 步即可（配对信息保留，无需重新配对）；若配对失效，重复第 3、4 步重新配对。")
                    GuideSection("方式二：通过连接电脑启动（无 Root · adb）")
                    GuideStep(1, "安装 adb", "下载 Google「SDK 平台工具」并解压（Windows / Linux / Mac 均可）。在解压目录打开终端，输入 adb 有版本信息即成功（PowerShell 与 Linux/Mac 用 ./adb）。")
                    GuideStep(2, "开启 USB 调试并授权", "进入「开发者选项」打开「USB 调试」；连接电脑后在终端输入 adb devices，手机弹窗勾选「始终允许」并确认；再次 adb devices 能看到设备即成功。")
                    GuideStep(3, "执行启动命令", "复制下方命令粘贴到终端执行（终端不要关闭）。每次重启后需重新执行。")
                    GuideSection("方式三：通过 root 权限启动")
                    GuideStep(1, "直接启动", "设备已 root 时点击「启动」即可，无需配对与 adb。支持开机自启；若未自动启动，请检查系统或第三方工具是否限制了 Lin-Shizuku 后台运行。")
                    GuideSection("常见问题")
                    GuideTip("一直显示「正在搜索配对服务」", "请允许 Lin-Shizuku 在后台运行：搜索配对需要访问本地网络，部分厂商在应用退到后台后立即禁止联网。可在系统设置中允许本应用后台运行。")
                    GuideTip("输入配对码后立刻提示失败（MIUI 小米/POCO）", "在系统「设置 → 通知管理 → 通知显示设置」中，将通知样式切换为「原生样式」。")
                    GuideTip("启动后提示 adb 权限受限", "MIUI（小米/POCO）：开发者选项开启「USB 调试（安全设置）」（与「USB 调试」是两个独立选项）；ColorOS（OPPO/一加）：关闭「权限监控」；Flyme（魅族）：关闭「Flyme 支付保护」。")
                    GuideTip("Shizuku 随机停止", "保证本 App 可在后台运行；不要关闭「USB 调试」与「开发者选项」；USB 使用模式设为「仅充电」（Android 9+ 为「默认 USB 配置」→「不进行数据传输」）；Android 11+ 可启用「停用 adb 授权超时功能」。")
                    GuideTip("EMUI（华为）随机停止", "在「开发者选项」中开启「「仅充电」模式下允许 ADB 调试」选项。")
                    GuideTip("root 启动后无法开机自启", "请允许 Lin-Shizuku 在后台运行，关闭系统或第三方工具对本应用的后台限制。MIUI 用户不要使用「手机管家」的扫描功能，它会自动禁用开发者选项。")
                    GuideTip("Sony 设备注意事项", "连接 USB 后不要点击弹出的对话框，否则会导致 USB 使用模式发生变化，导致连接中断。")
                    GuideTip("GrapheneOS 用户", "可能需要关闭「系统设置 → 安全 → Secure app spawning」。")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "提示：无线调试与手机连接同一 Wi-Fi 网络时最稳定；配对码有时效，超时后在系统页重新生成即可。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showGuide = false }) { Text("知道了") }
            }
        )
    }

    // Stop confirmation, same as official dialog_stop_message
    if (showStopConfirm) {
        AlertDialog(
            onDismissRequest = { showStopConfirm = false },
            title = { Text("停止服务") },
            text = { Text("确定要停止 Lin-Shizuku 服务吗？此操作会断开所有已授权应用的 Lin-Shizuku 连接。") },
            confirmButton = {
                TextButton(onClick = {
                    showStopConfirm = false
                    vm.stop(ctx)
                }) { Text("停止") }
            },
            dismissButton = {
                TextButton(onClick = { showStopConfirm = false }) { Text("取消") }
            }
        )
    }

    // 通知权限预检：没开通知时提示去开启，不进入配对流程
    if (showNotificationDenied) {
        AlertDialog(
            onDismissRequest = { showNotificationDenied = false },
            title = { Text("需要开启通知权限") },
            text = {
                Text(
                    "配对启动需要通知权限：配对码会显示在通知栏，服务运行状态也通过通知展示。\n\n请在接下来的系统设置中允许 Lin-Shizuku 发送通知。",
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showNotificationDenied = false
                    // 跳转到通知设置页
                    // 修复：ctx 是 applicationContext，从非 Activity context 启动 Activity
                    // 必须加 FLAG_ACTIVITY_NEW_TASK，否则 AndroidRuntimeException 闪退
                    val intent = android.content.Intent().apply {
                        action = android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS
                        putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    ctx.startActivity(intent)
                }) {
                    Text("去开启")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNotificationDenied = false }) { Text("取消") }
            }
        )
    }

    // 90ah：root 启动需用户确认（防误触自动授权）
    if (showRootConfirm) {
        AlertDialog(
            onDismissRequest = { showRootConfirm = false },
            title = { Text("以 Root 启动服务？") },
            text = { Text("将执行 su 启动 Lin-Shizuku 服务端。请确认已在 root 管理器（Magisk/KernelSU 等）允许本应用授权，否则启动会失败。") },
            confirmButton = {
                TextButton(onClick = {
                    showRootConfirm = false
                    vm.startRoot(ctx)
                }) { Text("确认启动") }
            },
            dismissButton = {
                TextButton(onClick = { showRootConfirm = false }) { Text("取消") }
            }
        )
    }
    } // 89b：外层固定容器（顶栏玻璃条）闭合
}

@Composable
private fun GuideSection(title: String) {
    Spacer(modifier = Modifier.height(10.dp))
    Text(
        title,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary
    )
    Spacer(modifier = Modifier.height(4.dp))
}

@Composable
private fun GuideTip(title: String, description: String) {
    Row(modifier = Modifier.padding(vertical = 6.dp)) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "!",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                description,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun GuideStep(number: Int, title: String, description: String) {
    Row(modifier = Modifier.padding(vertical = 6.dp)) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                number.toString(),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                description,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StartupCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    description: String,
    command: String? = null,
    actions: @Composable (() -> Unit)? = null
) {
    GlassCard {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconContainer(
                    icon = icon,
                    size = 40.dp,
                    iconSize = 22.dp
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text(
                        subtitle,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                description,
                fontSize = 13.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 19.sp
            )
            if (command != null) {
                Spacer(modifier = Modifier.height(12.dp))
                CodeBlock(code = command)
            }
            if (actions != null) {
                Spacer(modifier = Modifier.height(12.dp))
                actions()
            }
        }
    }
}

/** 首页：adb 权限排查帮助卡（仅 Shell/adb 模式显示；小米安全设置 / ColorOS 权限监控 / 禁用系统优化）。 */
@Composable
private fun AdbPermissionTipCard() {
    val tipColor = MaterialTheme.colorScheme.onSurfaceVariant
    GlassCard {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconContainer(
                    icon = Icons.Filled.Info,
                    size = 40.dp,
                    iconSize = 22.dp
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("adb 权限排查", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text(
                        "adb 模式下，若应用报权限不足，可逐项检查以下开关。",
                        fontSize = 12.sp,
                        color = tipColor,
                        lineHeight = 17.sp
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            AdbCheckItem(
                title = "小米 / POCO",
                desc = "开发者选项开启「USB 调试（安全设置）」（USB debugging (Security settings)）；它与「USB 调试」是两个独立开关。"
            )
            AdbCheckItem(
                title = "OPPO / 一加 / realme（ColorOS）",
                desc = "开发者选项关闭「权限监控」（Permission monitoring）。"
            )
            AdbCheckItem(
                title = "系统优化",
                desc = "ColorOS 默认隐藏「禁用系统优化」（Disable system optimization），执行下方命令显示后再打开（会略增后台耗电）。"
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text("电脑端 adb 执行：", fontSize = 11.sp, color = tipColor, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(4.dp))
            CodeBlock(code = "adb shell settings put secure system_opt_enable 1")
            Spacer(modifier = Modifier.height(8.dp))
            Text("设备本地 shell 执行（免 adb）：", fontSize = 11.sp, color = tipColor, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(4.dp))
            CodeBlock(code = "settings put secure system_opt_enable 1")
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                "以上开关均无改善时，可改用 Root 模式启动。",
                fontSize = 12.5.sp,
                color = tipColor,
                lineHeight = 18.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "改完开关后需重启 Lin-Shizuku（或重新启动服务）才会生效",
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                lineHeight = 18.sp
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                "隐藏该开关：把命令末尾的 1 改成 0",
                fontSize = 11.sp,
                color = tipColor
            )
        }
    }
}

@Composable
private fun AdbCheckItem(title: String, desc: String) {
    val tipColor = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(
            title,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            lineHeight = 17.sp
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            desc,
            fontSize = 12.5.sp,
            color = tipColor,
            lineHeight = 18.sp
        )
    }
}
