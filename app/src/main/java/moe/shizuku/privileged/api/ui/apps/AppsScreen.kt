package moe.shizuku.privileged.api.ui.apps

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.delay
import moe.shizuku.privileged.api.data.AppInfo
import moe.shizuku.privileged.api.ui.widget.EmptyState

/**
 * 电池优化白名单管理页（底栏「电池」）。
 * 列出全部已安装应用与电池优化白名单状态，支持一键加入 / 移出。
 */
@Composable
fun AppsScreen() {
    val ctx = LocalContext.current
    val vm: AppsViewModel = viewModel(factory = viewModelFactory {
        initializer { AppsViewModel(ctx.applicationContext) }
    })

    val apps by vm.apps.collectAsState()
    val whitelist by vm.whitelist.collectAsState()
    val loading by vm.loading.collectAsState()
    val serviceRunning by vm.serviceRunning.collectAsState()
    val whitelistLoaded by vm.whitelistLoaded.collectAsState()
    val message by vm.message.collectAsState()

    var filter by remember { mutableIntStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }

    LaunchedEffect(message) {
        val msg = message
        if (msg != null) {
            delay(2500)
            vm.clearMessage()
        }
    }

    // 授予"获取应用列表"权限（系统权限弹窗为独立 Activity）返回本页时，立即重新加载应用列表
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // P0-3 防重组浪费：过滤结果按输入记忆化——只有 apps/filter/searchQuery/whitelist
    // 变化时才重算，避免其他状态（键盘、消息、加载态）触发重组时重复全量过滤。
    val filtered = remember(filter, apps, searchQuery, whitelist) {
        when (filter) {
            1 -> apps.filter { whitelist.contains(it.packageName) }
            2 -> apps.filter { !whitelist.contains(it.packageName) }
            else -> apps
        }.filter {
            searchQuery.isBlank() ||
            it.name.contains(searchQuery as CharSequence, ignoreCase = true) ||
            it.packageName.contains(searchQuery as CharSequence, ignoreCase = true)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp)
    ) {
        // 90 轮：说明弹窗状态（右上角 Info 图标按钮持有），弹窗内容与文档保持不变
        var showBatteryDoc by remember { mutableStateOf(false) }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "电池优化管理",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    when {
                        !serviceRunning -> "Shizuku 未运行，无法管理白名单"
                        !whitelistLoaded -> "白名单状态读取中…"
                        else -> "已加入白名单 ${whitelist.size} / ${apps.size} 个应用"
                    },
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Button(
                onClick = { vm.refresh() },
                enabled = !loading,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("刷新", fontSize = 13.sp)
            }
            Spacer(modifier = Modifier.width(4.dp))
            // 90 轮：说明入口收敛为右上角 Info 图标按钮（点击弹窗打开完整文档），
            // 移除占用整行的「电池优化管理说明」卡片，页面头部更简洁
            IconButton(onClick = { showBatteryDoc = true }) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = "电池优化管理说明",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (showBatteryDoc) {
            AlertDialog(
                onDismissRequest = { showBatteryDoc = false },
                title = { Text("电池优化管理说明", fontWeight = FontWeight.SemiBold) },
                text = {
                    Column(
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .height(340.dp)
                    ) {
                        Text(
                            BatteryDocText,
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showBatteryDoc = false }) { Text("知道了") }
                }
            )
        }

        if (message != null) {
            val msg = message ?: ""
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .background(Color(0xFF4A1A1A), RoundedCornerShape(8.dp))
                    .padding(10.dp)
            ) {
                Text(msg, color = Color(0xFFFF6B6B), fontSize = 13.sp)
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Filter chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = filter == 0,
                onClick = { filter = 0 },
                label = { Text("全部") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
            FilterChip(
                selected = filter == 1,
                onClick = { filter = 1 },
                label = { Text("白名单") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
            FilterChip(
                selected = filter == 2,
                onClick = { filter = 2 },
                label = { Text("黑名单") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 搜索框
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("搜索应用名或包名", fontSize = 14.sp) },
            leadingIcon = {
                Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Filled.Clear, "清空", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                unfocusedBorderColor = MaterialTheme.colorScheme.surfaceVariant
            )
        )

        Spacer(modifier = Modifier.height(12.dp))

        if (!serviceRunning) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .background(
                        Color(0xFF4A1A1A),
                        RoundedCornerShape(8.dp)
                    )
                    .padding(10.dp)
            ) {
                Text(
                    "请先启动 Shizuku 服务后再管理电池白名单",
                    color = Color(0xFFFF6B6B),
                    fontSize = 13.sp
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (loading) {
            // 刷新中：大号加载动画显示在「未找到应用」位置（列表区中央）
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 48.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(64.dp),
                        strokeWidth = 6.dp
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "刷新中…",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else if (filtered.isEmpty()) {
            EmptyState(
                title = when {
                    searchQuery.isNotBlank() -> "未找到匹配的应用"
                    filter == 1 -> "暂无白名单应用"
                    filter == 2 -> "暂无黑名单应用"
                    else -> "未找到应用"
                },
                subtitle = if (!serviceRunning) "请先启动 Shizuku 服务"
                           else if (searchQuery.isNotBlank()) "换个关键词试试"
                           else "白名单中的应用将豁免深度省电限制",
                modifier = Modifier.padding(bottom = 48.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                // 90 轮：底部留白 80→120dp，避免末项应用条目被悬浮底部导航遮挡
                contentPadding = PaddingValues(bottom = 48.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filtered, key = { "${it.packageName}_${it.uid}" }) { app ->
                    val inList = whitelist.contains(app.packageName)
                    AppRow(
                        app = app,
                        inWhitelist = inList,
                        // 白名单状态读取完成后才允许操作，避免基于空状态误切换
                        enabled = serviceRunning && whitelistLoaded,
                        onToggle = { vm.toggle(app) }
                    )
                }
                item { Spacer(modifier = Modifier.height(8.dp)) }
            }
        }
    }
}

@Composable
private fun AppRow(
    app: AppInfo,
    inWhitelist: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                RoundedCornerShape(14.dp)
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            val icon = app.icon
            val bitmap = icon?.toFixedBitmap(96)
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(30.dp)
                )
            } else {
                Text(
                    app.name.firstOrNull()?.toString() ?: "?",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                app.name,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
            Text(
                app.packageName,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
        Switch(
            checked = inWhitelist,
            onCheckedChange = { onToggle() },
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.primary,
                checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
            )
        )
    }
}

private fun Drawable.toFixedBitmap(sizePx: Int): Bitmap? {
    return runCatching {
        val width = if (intrinsicWidth > 0) intrinsicWidth else sizePx
        val height = if (intrinsicHeight > 0) intrinsicHeight else sizePx
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        setBounds(0, 0, width, height)
        draw(canvas)
        bitmap
    }.getOrNull()
}

/** 电池优化管理完整文档（弹窗展示）。 */
private val BatteryDocText = """
电池优化管理
────────────────
系统会对后台应用做电池优化，可能回收（杀死）
不活跃的应用进程。本页用于管理应用的
「忽略电池优化」白名单状态。

【白名单】
· 加入后系统不再限制该应用后台，适合需要
  常驻的进程（本应用、通知类、同步类、音乐类）；
· 操作：将条目右侧开关拨到「开启」状态。

【黑名单】
· 显示「未加入白名单」的应用，即会被系统
  电池优化回收的应用；
· 可在此查看哪些应用将被回收，或把需要的
  应用一键加入白名单。

【全部】
· 展示设备上所有应用，可统一查看与操作。

【注意事项】
· 操作需 Shizuku 服务运行（本页由 Lin-Shizuku
  提供系统级读写权限，服务未运行时不可管理）；
· 加入白名单会增加后台耗电，建议只给必要
  应用加入；
· 移出白名单（关闭开关）后，系统可能回收该
  应用后台，重新打开应用即可恢复。
""".trimIndent()
