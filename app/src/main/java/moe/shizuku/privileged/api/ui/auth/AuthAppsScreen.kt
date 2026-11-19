package moe.shizuku.privileged.api.ui.auth

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Job
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.privileged.api.core.ShizukuManager
import moe.shizuku.privileged.api.data.AppInfo
import moe.shizuku.privileged.api.ui.widget.EmptyState
import moe.shizuku.privileged.api.ui.widget.LoadingIndicator

@Composable
fun AuthAppsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val apps = remember { mutableStateListOf<AppInfo>() }
    var secondFilter by remember { mutableIntStateOf(-2) } // -2=all, 1=allowed, 2=asking, -1=denied
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var multiSelect by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<String>() } // packageName

    // 防并发叠加：同一时刻只保留一个加载任务；新任务启动前取消旧任务。
    // clear 放在 IO 返回之后、addAll 之前，确保填充的始终是最新一批结果，
    // 避免"进入页面 + ON_RESUME 重扫"两个加载并发时 addAll 叠加成 24 条。
    val loadJob = remember { mutableStateOf<Job?>(null) }
    fun loadApps() {
        loadJob.value?.cancel()
        loadJob.value = scope.launch {
            loading = true
            val list = ShizukuManager.getShizukuApps(ctx)
            apps.clear()
            apps.addAll(list)
            loading = false
        }
    }

    LaunchedEffect(Unit) { loadApps() }

    // 授予"获取应用列表"权限（系统权限弹窗为独立 Activity）返回本页时，立即重新扫描应用列表
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) loadApps()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val filtered = apps.filter { app ->
        (secondFilter == -2 || app.state == secondFilter) &&
            (query.isBlank() ||
                app.name.contains(query.trim(), ignoreCase = true) ||
                app.packageName.contains(query.trim(), ignoreCase = true))
    }

    fun toggleMultiSelect() {
        multiSelect = !multiSelect
        selected.clear()
    }

    fun applyBatch(state: Int) {
        if (selected.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            selected.forEach { pkg ->
                val app = apps.find { it.packageName == pkg } ?: return@forEach
                when (state) {
                    1 -> ShizukuManager.setAppPermission(app.uid, true, ctx)
                    -1 -> ShizukuManager.setAppPermission(app.uid, false, ctx)
                    else -> ShizukuManager.setAppPermissionAsk(app.uid, ctx)
                }
            }
            withContext(Dispatchers.Main) {
                selected.forEach { pkg ->
                    val idx = apps.indexOfFirst { it.packageName == pkg }
                    if (idx >= 0) {
                        apps[idx] = apps[idx].copy(
                            state = ShizukuManager.getAppPermissionState(apps[idx].uid, ctx)
                        )
                    }
                }
                multiSelect = false
                selected.clear()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp)
            .padding(top = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "应用授权管理",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { toggleMultiSelect() }) {
                Text(
                    if (multiSelect) "取消" else "多选",
                    color = if (multiSelect) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        Spacer(modifier = Modifier.height(10.dp))

        // 搜索框：按应用名 / 包名过滤
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("搜索应用名或包名", fontSize = 14.sp) },
            leadingIcon = {
                Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
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
        Spacer(modifier = Modifier.height(10.dp))

        // Status filter: 全部 / 允许 / 询问 / 拒绝
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(selected = secondFilter == -2, onClick = { secondFilter = -2 }, label = { Text("全部") })
            FilterChip(selected = secondFilter == 1, onClick = { secondFilter = 1 }, label = { Text("允许") })
            FilterChip(selected = secondFilter == 2, onClick = { secondFilter = 2 }, label = { Text("询问") })
            FilterChip(selected = secondFilter == -1, onClick = { secondFilter = -1 }, label = { Text("拒绝") })
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 多选操作栏
        AnimatedVisibility(visible = multiSelect) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        "已选 ${selected.size} 个",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = {
                            val addable = filtered.map { it.packageName }.filter { it !in selected }
                            selected.addAll(addable)
                        }
                    ) { Text("全选") }
                    BatchAction("允许", Color(0xFF66BB6A), enabled = selected.isNotEmpty()) { applyBatch(1) }
                    BatchAction("询问", MaterialTheme.colorScheme.onSurfaceVariant, enabled = selected.isNotEmpty()) { applyBatch(2) }
                    BatchAction("拒绝", MaterialTheme.colorScheme.error, enabled = selected.isNotEmpty()) { applyBatch(-1) }
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // 计数：全部时显示"总数 · 已允许数"，与首页"已授权 N 个应用"口径一致；
        // 按状态筛选时只显示当前筛选数
        Text(
            if (secondFilter == -2) {
                "共 ${filtered.size} 个应用 · 已允许 ${apps.count { it.state == 1 }} 个"
            } else {
                "${filtered.size} 个应用"
            },
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (loading) {
            LoadingIndicator(label = "加载已授权应用...")
        } else if (filtered.isEmpty()) {
            EmptyState(
                title = "暂无匹配的应用",
                subtitle = "尝试调整筛选或搜索条件，或在应用中请求 Shizuku 权限",
                modifier = Modifier.padding(bottom = 72.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(bottom = 72.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 88 轮：key 唯一化（包名+uid）——双开/多用户下同一包名可能多行，纯包名 key 会触发
                // LazyColumn "Key already used" 崩溃（智能应用助手日志 09-17 02:21/02:30 崩溃即此因）
                items(filtered, key = { "${it.packageName}_${it.uid}" }) { app ->
                    AppItem(
                        app = app,
                        multiSelect = multiSelect,
                        checked = app.packageName in selected,
                        onToggleSelect = {
                            if (app.packageName in selected) selected.remove(app.packageName)
                            else selected.add(app.packageName)
                        },
                        onSelectState = { state ->
                            when (state) {
                                1 -> ShizukuManager.setAppPermission(app.uid, true, ctx)
                                -1 -> ShizukuManager.setAppPermission(app.uid, false, ctx)
                                else -> ShizukuManager.setAppPermissionAsk(app.uid, ctx)
                            }
                            // 刷新条目，回读持久化后的真实状态
                            val idx = apps.indexOfFirst { it.packageName == app.packageName }
                            if (idx >= 0) {
                                apps[idx] = apps[idx].copy(state = ShizukuManager.getAppPermissionState(app.uid, ctx))
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun BatchAction(label: String, color: Color, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled) {
        Text(label, color = if (enabled) color else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun AppItem(
    app: AppInfo,
    multiSelect: Boolean,
    checked: Boolean,
    onToggleSelect: () -> Unit,
    onSelectState: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .clickable {
                if (multiSelect) onToggleSelect() else expanded = !expanded
            }
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (multiSelect) {
                Checkbox(
                    checked = checked,
                    onCheckedChange = { onToggleSelect() },
                    colors = CheckboxDefaults.colors(
                        checkedColor = MaterialTheme.colorScheme.primary
                    )
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            // App icon（个别应用图标 Drawable 转 Bitmap 会抛异常/尺寸为 0，
            // 未捕获会直接杀死管理器进程；这里安全转换，失败回退首字母占位）
            val iconBitmap = remember(app.icon) {
                app.icon?.let { runCatching { it.safeToBitmap() }.getOrNull() }
            }
            if (iconBitmap != null) {
                Image(
                    bitmap = iconBitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Text(app.name.first().toString(), color = Color.White, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(app.name, fontWeight = FontWeight.Medium, fontSize = 15.sp, maxLines = 1)
                Text(
                    app.packageName,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            if (!multiSelect) {
                Text(
                    app.stateLabel,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = when (app.state) {
                        1 -> Color(0xFF66BB6A)
                        -1 -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }

        // 展开的权限设置：询问 / 允许 / 拒绝（一致的授权管理样式）
        AnimatedVisibility(visible = expanded && !multiSelect) {
            Column(modifier = Modifier.padding(start = 56.dp, top = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Shizuku 权限",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "使用 Shizuku 功能",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    PermissionOption(
                        label = "询问",
                        selected = app.state != 1 && app.state != -1,
                        onClick = { onSelectState(2) },
                        modifier = Modifier.weight(1f)
                    )
                    PermissionOption(
                        label = "允许",
                        selected = app.state == 1,
                        onClick = { onSelectState(1) },
                        modifier = Modifier.weight(1f)
                    )
                    PermissionOption(
                        label = "拒绝",
                        selected = app.state == -1,
                        onClick = { onSelectState(-1) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionOption(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val container = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
    val content = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = container,
        modifier = modifier.height(36.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() }
        ) {
            Text(
                label,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = content
            )
        }
    }
}

/**
 * 将应用图标 Drawable 转为 Bitmap：固有尺寸为 0 时回退到固定尺寸；
 * 调用方再包一层 runCatching，绘制异常时返回 null 走占位，避免坏图标拖崩列表。
 */
private fun Drawable.safeToBitmap(sizePx: Int = 96): Bitmap {
    val width = if (intrinsicWidth > 0) intrinsicWidth else sizePx
    val height = if (intrinsicHeight > 0) intrinsicHeight else sizePx
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    setBounds(0, 0, width, height)
    draw(canvas)
    return bitmap
}
