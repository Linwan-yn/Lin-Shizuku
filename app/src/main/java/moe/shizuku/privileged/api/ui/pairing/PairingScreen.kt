package moe.shizuku.privileged.api.ui.pairing

import moe.shizuku.privileged.api.core.ShizukuManager
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingScreen(onBack: () -> Unit = {}) {
    val ctx = LocalContext.current
    val vm: PairingViewModel = viewModel(factory = viewModelFactory {
        initializer { PairingViewModel(ctx.applicationContext) }
    })
    val steps by vm.steps.collectAsState()
    val currentStepIndex by vm.currentStepIndex.collectAsState()
    val isCompleted by vm.isCompleted.collectAsState()
    val errorMessage by vm.errorMessage.collectAsState()
    val outputLines by vm.outputLines.collectAsState()
    val pairCode by vm.pairCode.collectAsState()
    val pairingNow by vm.pairingNow.collectAsState()
    val pairError by vm.pairError.collectAsState()
    // 修复：读取 ADB 连接端口（配对卡片展示用，非配对端口）
    val connectPort by vm.connectPort.collectAsState()
    var showLog by remember { mutableStateOf(false) }
    // 1.1.2 交互打磨：重新配对 / 重新启动都会打断当前正在运行的服务，
    // 必须二次确认，避免误触（原来这两个按钮一直高亮可点，点一下就断连）。
    var showRepairConfirm by remember { mutableStateOf(false) }
    var showRestartConfirm by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()

    // Auto-scroll to the current step.
    // v5.1：始终跟随当前步骤（含 index=0），按内容比例居中滚动——
    // 旧实现 currentStepIndex>0 才滚 + 固定 140dp/步，步骤描述换行时
    // 估算漂移，且流程停在第一步（检测端口）时页面不滚动，用户只能看到
    // 列表后段（启动服务进程/等待Binder/启动完成）全"等待"，误以为卡死。
    LaunchedEffect(currentStepIndex, steps.size) {
        val total = steps.size
        if (total > 0 && currentStepIndex >= 0) {
            val target = (
                scrollState.maxValue.toFloat() *
                    (currentStepIndex + 0.5f).coerceAtMost(total.toFloat()) / total
                ).toInt().coerceAtLeast(0)
            scrollState.animateScrollTo(target)
        }
    }

    // 90az：配对页（重新）进入时重新评估配对状态——若配对已在通知中心完成
    //（广播错过 / ViewModel 保留 / 后台回收重建等场景），自动继续启动流程，
    // 不再停留在「需要我的操作」。
    LaunchedEffect(Unit) {
        vm.reEvaluatePairedState()
    }

    // 启动成功 = Lin_server 服务就绪 → 触发模块引擎（拉起所有已启用模块，幂等）。
    // 1.1.2 交互打磨：不再成功后 600ms 自动返回首页。
    // 自动返回会让「启动完成」这一屏一闪而过，用户感知成「配对成功就直接变成运行中」，
    // 也就是 1→3 跳段；失败时也看不到究竟是哪一步出的问题。
    // 改为停留在本页，由底部「完成」按钮主动返回。
    LaunchedEffect(isCompleted) {
        if (isCompleted) {
            runCatching { ShizukuManager.triggerModuleEngine(ctx) }
        }
    }

    // Notification permission (needed for the pairing-code notification input).
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> vm.setNotificationPermission(granted) }

    val currentStep = steps.getOrNull(currentStepIndex)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("无线调试启动", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
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
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // Status summary card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isCompleted) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(20.dp))
                            }
                        } else if (errorMessage != null) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFE53935)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("!", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            }
                        } else {
                            CircularProgressIndicator(
                                modifier = Modifier.size(36.dp),
                                strokeWidth = 3.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                when {
                                    isCompleted -> "启动完成"
                                    errorMessage != null -> "启动失败"
                                    else -> "正在启动..."
                                },
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "步骤 ${(currentStepIndex + 1).coerceAtMost(steps.size)} / ${steps.size}",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    // Progress bar
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(
                                    if (steps.isEmpty()) 0f
                                    else (currentStepIndex + 1).coerceAtMost(steps.size).toFloat() / steps.size
                                )
                                .height(6.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
                                    )
                                )
                        )
                    }
                    val errText = errorMessage
                    if (errText != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            errText,
                            fontSize = 13.sp,
                            color = Color(0xFFE53935),
                            lineHeight = 18.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 76 轮：配对前置提示——网络权限 + MIUI/HyperOS 电池优化（参考 Shizuku NEXT 引导）
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Wifi,
                            null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "配对需要访问本地网络",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Lin-Shizuku 通过无线调试与本机 ADB 通信并启动服务，请保持 WiFi 连接、允许本地网络访问。",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp
                    )
                }
            }
            if (moe.shizuku.privileged.api.util.SystemSettings.isMiuiHyperOs()) {
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.BatterySaver,
                                null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "MIUI/HyperOS 提示",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "部分系统不允许应用不可见时访问网络（即使已用前台服务）。请为本应用关闭电池优化，避免配对/启动静默失败。",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 18.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { moe.shizuku.privileged.api.util.SystemSettings.openBatteryOptimization(ctx) },
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(vertical = 4.dp, horizontal = 12.dp)
                        ) { Text("关闭电池优化", fontSize = 13.sp) }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 1.1.2 交互打磨：已配对时步骤里不会出现「无线调试配对」，
            // 用户从「配对」按钮进来却只看到启动流程，会以为配对这一步被跳过了。
            // 这里显式说明一次配对状态，并指出重新配对的入口在哪。
            if (errorMessage == null && steps.none { it.title == "无线调试配对" }) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "配对状态：已配对",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "本次直接启动服务，不需要重新输入配对码。只有换了 WiFi、重启手机或配对失效时，才需要点底部「重新配对」。",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 18.sp
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Timeline steps
            Column {
                steps.forEachIndexed { index, step ->
                    TimelineStepRow(
                        step = step,
                        isLast = index == steps.size - 1,
                        onGrantNotification = if (step.title == "授权通知权限" && step.status != StepStatus.COMPLETED) {
                            {
                                val granted = if (Build.VERSION.SDK_INT >= 33) {
                                    ctx.getSystemService(android.app.NotificationManager::class.java)
                                        .areNotificationsEnabled()
                                } else {
                                    true
                                }
                                if (granted) {
                                    vm.setNotificationPermission(true)
                                } else {
                                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            }
                        } else {
                            null
                        },
                        onOpenNotificationSettings = if (step.title == "授权通知权限") {
                            { openNotificationSettings(ctx) }
                        } else {
                            null
                        },
                        // 操作按钮与配对码输入框全部内联进对应步骤卡片，不再悬浮在底部
                        inlineAction = when {
                            // 第二步「开启无线调试」：两个操作按钮内联进卡片
                            step.title == "开启无线调试" && step.needsUserAction -> {
                                {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(
                                            onClick = { vm.continueAfterSetup() },
                                            modifier = Modifier.weight(1f),
                                            contentPadding = PaddingValues(vertical = 6.dp, horizontal = 12.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                        ) { Text("已开启，继续", fontSize = 13.sp) }
                                        OutlinedButton(
                                            onClick = { openDeveloperSettings(ctx) },
                                            modifier = Modifier.weight(1f),
                                            contentPadding = PaddingValues(vertical = 6.dp, horizontal = 12.dp)
                                        ) { Text("打开开发者选项", fontSize = 13.sp) }
                                    }
                                }
                            }
                            // 「无线调试配对」：配对码输入框内联进卡片（替代原底部独立浮层）
                            step.title == "无线调试配对" && step.needsUserAction -> {
                                {
                                    // 1.1.1 v5.7：配对端口不再需要用户输入/查看——配对端口由
                                    // mDNS（NsdManager 主通道 + MulticastSocket 兜底）/盲扫自动识别；
                                    // ADB 连接端口（Connection Port）由下方「检测 ADB 端口」步骤
                                    // 自动获取显示（无线调试打开即有 _adb-tls-connect 广播）。
                                    // v5.8：六位配对码改 6 个独立数字框（对齐短信验证码 UX）——
                                    // 输入自动前进、退格自动后退、支持整段粘贴、自动聚焦空框
                                    val focusRequesters = remember { List(6) { FocusRequester() } }
                                    // 修复：跟踪当前聚焦框，用于高亮边框色彩
                                    var focusedIndex by remember { mutableStateOf(-1) }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        // 框之间 8dp 间距；weight(1f)+aspectRatio(1f) 让 6 个框
                                        // 在任何宽度（全屏/小窗）下都等宽圆角正方形，不会溢出
                                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
                                    ) {
                                        repeat(6) { index ->
                                            val digit = pairCode.getOrNull(index)?.toString() ?: ""
                                            val isFocused = focusedIndex == index
                                            val hasDigit = digit.isNotEmpty()
                                            BasicTextField(
                                                value = digit,
                                                onValueChange = { input ->
                                                    if (input.length > 1) {
                                                        // 粘贴整段：过滤数字取前 6 位
                                                        val pasted = input.filter { it.isDigit() }.take(6)
                                                        vm.setPairCode(pasted)
                                                        if (pasted.length in 1..5) {
                                                            focusRequesters[pasted.length].requestFocus()
                                                        }
                                                        return@BasicTextField
                                                    }
                                                    if (input.isEmpty()) {
                                                        // Backspace（标准验证码 UX）：
                                                        // 当前位有值 → 删当前位；当前位空 → 删前一位；
                                                        // 删除后焦点停在「最后一个有数字的框」，这样再按删除
                                                        // 就能连续回删（删9→焦点在2→删2→焦点在8…）
                                                        if (pairCode.isNotEmpty()) {
                                                            val newCode = if (index < pairCode.length) {
                                                                pairCode.removeRange(index, index + 1)
                                                            } else {
                                                                pairCode.dropLast(1)
                                                            }
                                                            vm.setPairCode(newCode)
                                                            focusRequesters[(newCode.length - 1).coerceAtLeast(0)].requestFocus()
                                                        }
                                                        return@BasicTextField
                                                    }
                                                    val c = input.last()
                                                    if (!c.isDigit()) return@BasicTextField
                                                    // 数字落到「第一个空位」（而非点击位）——
                                                    // 跳框/乱序输入不再产生中间空格死码；全部填满时替换点击位
                                                    val sb = StringBuilder(pairCode.padEnd(6, ' '))
                                                    val firstEmpty = (0 until 6).firstOrNull { sb[it] == ' ' }
                                                        ?: index.coerceAtMost(5)
                                                    sb.setCharAt(firstEmpty, c)
                                                    vm.setPairCode(sb.toString().trimEnd(' '))
                                                    if (firstEmpty < 5) focusRequesters[firstEmpty + 1].requestFocus()
                                                },
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .aspectRatio(1f)
                                                    .focusRequester(focusRequesters[index])
                                                    .onFocusChanged { focusedIndex = if (it.isFocused) index else -1 }
                                                    // 色彩：空框未聚焦用可见的淡底+边框（不再透明看不见）；
                                                    // 已填框 primary 淡底；聚焦框 primary 高亮边框
                                                    .background(
                                                        when {
                                                            hasDigit -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                                            isFocused -> MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                                                            else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
                                                        },
                                                        RoundedCornerShape(12.dp)
                                                    )
                                                    .border(
                                                        width = if (isFocused) 2.dp else 1.5.dp,
                                                        color = when {
                                                            isFocused -> MaterialTheme.colorScheme.primary
                                                            hasDigit -> MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                                                            else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
                                                        },
                                                        shape = RoundedCornerShape(12.dp)
                                                    ),
                                                textStyle = TextStyle(
                                                    fontSize = 22.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    textAlign = TextAlign.Center,
                                                    color = if (hasDigit) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                                ),
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                                singleLine = true,
                                                decorationBox = { inner ->
                                                    Box(
                                                        contentAlignment = Alignment.Center,
                                                        modifier = Modifier.fillMaxSize()
                                                    ) { inner() }
                                                }
                                            )
                                        }
                                    }
                                    // 自动聚焦当前应填的空框（无障碍自动填码完成后无需手动聚焦）。
                                    // 仅在码变长（输入/粘贴）时前进到下一个空框；码变短（删除）时
                                    // 不移动——删除逻辑已手动把焦点设在最后一个有数字的框，
                                    // 这里若无条件拉回末尾空框会覆盖删除后的连续回删体验。
                                    var prevCodeLength by remember { mutableStateOf(pairCode.length) }
                                    LaunchedEffect(pairCode) {
                                        val idx = pairCode.length
                                        if (idx > prevCodeLength && idx < 6 && !pairingNow) {
                                            focusRequesters[idx].requestFocus()
                                        }
                                        prevCodeLength = idx
                                    }
                                    if (pairError != null) {
                                            val err = pairError
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Text(err ?: "", fontSize = 12.sp, color = Color(0xFFE53935), lineHeight = 16.sp)
                                        }
                                        Spacer(modifier = Modifier.height(10.dp))

                                        Button(
                                            onClick = { vm.startPairingWithCode() },
                                            modifier = Modifier.fillMaxWidth(),
                                            // v4.10：端口可留空——码填满即可点击，端口由
                                            // mDNS/盲扫自动获取（startPairingWithCode 内部兜底）
                                            enabled = pairCode.length == 6 && !pairingNow,
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                        ) {
                                            if (pairingNow) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(16.dp),
                                                    strokeWidth = 2.dp,
                                                    color = Color.White
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text("正在配对...", fontSize = 13.sp)
                                            } else {
                                                Text("开始配对", fontSize = 13.sp)
                                            }
                                        }
                                    // 辅助入口：一键跳转系统开发者选项（方便开启无线调试/使用配对码配对）
                                    Spacer(modifier = Modifier.height(8.dp))
                                    OutlinedButton(
                                        onClick = { openDeveloperSettings(ctx) },
                                        modifier = Modifier.fillMaxWidth(),
                                        contentPadding = PaddingValues(vertical = 6.dp, horizontal = 12.dp)
                                    ) { Text("开发者选项", fontSize = 13.sp) }
                                    // 90ay：「配对完成，继续」已删除——配对成功后（通知中心点纸飞机 /
                                    // 本页点「开始配对」）由配对成功广播 + 状态轮询自动继续启动流程，
                                    // 无需手动干预。
                                }
                            }
                            else -> null
                        },
                        // 修复：无线调试配对步骤在「需要您操作」上方显示 ADB 连接端口
                        extraHeader = if (step.title == "无线调试配对" && connectPort > 0) {
                            {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Filled.Wifi,
                                        null,
                                        modifier = Modifier.size(13.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        "ADB 连接端口：$connectPort",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        } else {
                            null
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                when {
                    errorMessage != null -> {
                        // 1.1.2 交互打磨：失败分两类，两种恢复入口都给。
                        // 只有「重试启动」时，配对/连接坏掉的情况会反复撞同一堵墙；
                        // 只有「重新配对」时，只是服务没拉起来又要重敲一次配对码。
                        Button(
                            onClick = { vm.retry() },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(vertical = 8.dp, horizontal = 8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) { Text("重试启动", fontSize = 13.sp) }
                        OutlinedButton(
                            onClick = { showRepairConfirm = true },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(vertical = 8.dp, horizontal = 8.dp)
                        ) { Text("重新配对", fontSize = 13.sp) }
                    }
                    isCompleted -> {
                        OutlinedButton(
                            onClick = { showRepairConfirm = true },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(vertical = 8.dp, horizontal = 8.dp)
                        ) { Text("重新配对", fontSize = 12.sp) }
                        OutlinedButton(
                            onClick = { showRestartConfirm = true },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(vertical = 8.dp, horizontal = 8.dp)
                        ) { Text("重新启动", fontSize = 12.sp) }
                        Button(
                            onClick = onBack,
                            modifier = Modifier.weight(0.8f),
                            contentPadding = PaddingValues(vertical = 8.dp, horizontal = 8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) { Text("完成", fontSize = 12.sp) }
                    }
                    // 90aw：「配对完成，继续」已移入「无线调试配对」卡片内，底部不再重复放置
                }
                if (!isCompleted && errorMessage == null) {
                    OutlinedButton(
                        onClick = { showLog = !showLog },
                        modifier = Modifier.weight(0.8f),
                        contentPadding = PaddingValues(vertical = 8.dp, horizontal = 8.dp)
                    ) { Text(if (showLog) "隐藏日志" else "日志", fontSize = 13.sp) }
                }
            }

            // Startup log (real output from the flow)
            AnimatedVisibility(visible = showLog) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A24))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "启动日志",
                            color = Color(0xFF8888AA),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        if (outputLines.isEmpty()) {
                            Text(
                                "等待日志输出...",
                                color = Color(0xFFB8C0D0),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 16.sp
                            )
                        } else {
                            outputLines.forEach { line ->
                                val isErr = moe.shizuku.privileged.api.core.LogDiagnostics.isErrLine(line)
                                Text(
                                    line.ifBlank { " " },
                                    color = if (isErr) Color(0xFFFF5252) else Color(0xFFB8C0D0),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = 16.sp
                                )
                            }
                        }
                    }
                }
            }

            // 底部留出悬浮导航栏高度（约 100dp），保证最后的"完成"按钮能滑出遮挡
            Spacer(modifier = Modifier.height(100.dp))
        }

        // 1.1.2：重新配对 / 重新启动的二次确认
        if (showRepairConfirm) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showRepairConfirm = false },
                title = { Text("重新配对？") },
                text = {
                    Text(
                        "重新配对会清空当前配对状态，需要重新在系统「无线调试」里生成配对码。" +
                            "如果只是服务没起来，请改用「重新启动」，不必重新配对。"
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        showRepairConfirm = false
                        vm.repairPairing()
                    }) { Text("重新配对") }
                },
                dismissButton = {
                    TextButton(onClick = { showRepairConfirm = false }) { Text("取消") }
                }
            )
        }
        if (showRestartConfirm) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showRestartConfirm = false },
                title = { Text("重新启动服务？") },
                text = {
                    Text(
                        "会先停止当前服务端进程再重新启动，期间所有已授权应用的 Lin-Shizuku 连接会短暂断开。" +
                            "配对信息保留，不会要求重新输入配对码。"
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        showRestartConfirm = false
                        vm.restartViaScript()
                    }) { Text("重新启动") }
                },
                dismissButton = {
                    TextButton(onClick = { showRestartConfirm = false }) { Text("取消") }
                }
            )
        }
    }
}

@Composable
private fun TimelineStepRow(
    step: PairingStep,
    isLast: Boolean,
    onGrantNotification: (() -> Unit)? = null,
    onOpenNotificationSettings: (() -> Unit)? = null,
    inlineAction: (@Composable () -> Unit)? = null,
    // 修复：步骤卡片「需要您操作」上方的额外信息区（如 ADB 连接端口展示）
    extraHeader: (@Composable () -> Unit)? = null
) {
    val (bgColor, contentColor, borderColor) = when (step.status) {
        StepStatus.COMPLETED -> Triple(
            MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
        )
        StepStatus.RUNNING -> Triple(
            MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.primary
        )
        StepStatus.ERROR -> Triple(
            Color(0xFFE53935).copy(alpha = 0.15f),
            Color(0xFFE53935),
            Color(0xFFE53935).copy(alpha = 0.4f)
        )
        StepStatus.WARNING -> Triple(
            Color(0xFFFB8C00).copy(alpha = 0.15f),
            Color(0xFFFB8C00),
            Color(0xFFFB8C00).copy(alpha = 0.4f)
        )
        StepStatus.PENDING -> Triple(
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            Color.Transparent
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        // Icon + connector
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(44.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(bgColor)
                    .border(
                        width = if (step.status == StepStatus.RUNNING) 1.5.dp else 0.dp,
                        color = borderColor,
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                when (step.status) {
                    StepStatus.COMPLETED -> Icon(Icons.Default.Check, null, tint = contentColor, modifier = Modifier.size(18.dp))
                    StepStatus.RUNNING -> CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = contentColor
                    )
                    else -> Icon(step.icon, null, tint = contentColor, modifier = Modifier.size(18.dp))
                }
            }
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(40.dp)
                        .background(
                            if (step.status == StepStatus.COMPLETED)
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)
                        )
                )
            }
        }

        // Content card
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp, bottom = if (isLast) 0.dp else 8.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(bgColor)
                .then(
                    if (step.needsUserAction)
                        Modifier.border(1.dp, contentColor.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                    else Modifier
                )
                .padding(12.dp)
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        step.title,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (step.status == StepStatus.PENDING)
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        else
                            MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        when (step.status) {
                            StepStatus.COMPLETED -> "完成"
                            StepStatus.RUNNING -> "进行中"
                            StepStatus.ERROR -> "错误"
                            StepStatus.WARNING -> "注意"
                            StepStatus.PENDING -> "等待"
                        },
                        fontSize = 11.sp,
                        color = contentColor,
                        fontWeight = FontWeight.Medium
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    step.description,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    lineHeight = 16.sp
                )
                // 修复：「需要您操作」上方展示额外信息（如 ADB 连接端口）
                extraHeader?.let { header ->
                    Spacer(modifier = Modifier.height(6.dp))
                    header()
                }
                if (step.needsUserAction) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "需要您操作",
                        fontSize = 11.sp,
                        color = contentColor,
                        fontWeight = FontWeight.Medium
                    )
                }
                // Inline action buttons inside the step card, so the grant action is
                // always visible even when the bottom action area is showing another step.
                if (onGrantNotification != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onGrantNotification?.invoke() },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(vertical = 6.dp, horizontal = 12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) { Text("授予权限", fontSize = 13.sp) }
                        OutlinedButton(
                            onClick = { onOpenNotificationSettings?.invoke() },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(vertical = 6.dp, horizontal = 12.dp)
                        ) { Text("打开通知设置", fontSize = 13.sp) }
                    }
                }
                // 内联操作区（开启无线调试按钮 / 配对码输入框等）
                inlineAction?.let { action ->
                    Spacer(modifier = Modifier.height(10.dp))
                    action()
                }
            }
        }
    }
}

/** Open the system notification settings for this app (fallback on MIUI/HyperOS where
 *  the runtime POST_NOTIFICATIONS dialog may not appear).
 *  v5.3：配对弹窗可能持有非 Activity context，startActivity 必须带
 *  FLAG_ACTIVITY_NEW_TASK，否则 AndroidRuntimeException（实测 04:41 crash）。 */
private fun openNotificationSettings(context: android.content.Context) {
    try {
        val intent = android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    } catch (_: Throwable) {
        try {
            val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.parse("package:${context.packageName}"))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (_: Throwable) {
        }
    }
}

/** Open the system developer options page（标准 action 优先，失败回退显式组件，适配不同 ROM）。 */
private fun openDeveloperSettings(context: android.content.Context) {
    val actionIntent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    if (runCatching { context.startActivity(actionIntent) }.isSuccess) return
    // 回退：部分 ROM 不支持标准 action，直接打开「设置 → 开发者选项」组件
    runCatching {
        val explicit = android.content.Intent().setComponent(
            android.content.ComponentName(
                "com.android.settings",
                "com.android.settings.Settings\$DevelopmentSettingsActivity"
            )
        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(explicit)
    }
}
