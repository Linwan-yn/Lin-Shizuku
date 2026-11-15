package moe.shizuku.privileged.api.ui.modules

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.privileged.api.core.ModuleStore
import moe.shizuku.privileged.api.ui.theme.LinShizukuTheme
import moe.shizuku.privileged.api.util.ActivityTransitions
import java.util.UUID

class ScriptEditActivity : ComponentActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var scriptId: String? = null
    private var scriptName by mutableStateOf("新脚本")
    private var scriptContent by mutableStateOf("")
    private var scriptDesc by mutableStateOf("")
    private var scriptAuthor by mutableStateOf("")
    private var scriptBrand by mutableStateOf("通用")
    // 1.0.11：脚本类型——执行(单段) / 开关(#on/#off 两段)
    private var scriptIsSwitch by mutableStateOf(false)
    private var scriptOnContent by mutableStateOf("")
    private var scriptOffContent by mutableStateOf("")

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/x-sh")
    ) { uri ->
        uri?.let { saveToUri(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        scriptId = intent.getStringExtra("script_id")

        scriptId?.let { id ->
            // 已有脚本：shell 读取内容（存储位于 /data/local/tmp/Lin-Shizuku/module/）
            scope.launch(Dispatchers.IO) {
                val content = ModuleStore.scriptContent(this@ScriptEditActivity, id)
                if (content.isNotBlank()) {
                    val name = Regex("#name:\\s*(.+)").find(content)
                        ?.groupValues?.get(1)?.trim() ?: id
                    scriptName = name
                    scriptDesc = Regex("#description:\\s*(.*)").find(content)?.groupValues?.get(1)?.trim() ?: ""
                    scriptAuthor = Regex("#author:\\s*(.*)").find(content)?.groupValues?.get(1)?.trim() ?: ""
                    scriptBrand = Regex("#brand:\\s*(.*)").find(content)?.groupValues?.get(1)?.trim()?.ifBlank { "通用" } ?: "通用"
                    scriptContent = content
                    // 检测开关型：存在独立的 #on / #off 行则拆分两段回填
                    val onIdx = content.lines().indexOfFirst { it.trim() == "#on" }
                    val offIdx = content.lines().indexOfFirst { it.trim() == "#off" }
                    if (onIdx >= 0 && offIdx > onIdx) {
                        scriptIsSwitch = true
                        val lines = content.lines()
                        val endIdx = (offIdx + 1 until lines.size).firstOrNull { lines[it].trim() == "#---" } ?: lines.size
                        scriptOnContent = lines.subList(onIdx + 1, offIdx).joinToString("\n").trim()
                        scriptOffContent = lines.subList(offIdx + 1, endIdx).joinToString("\n").trim()
                    }
                }
            }
        } ?: run {
            // 1.0.10：新建脚本全部默认为空（脚本名称/介绍/作者/适用品牌/内容均不预填）
            scriptName = ""
            scriptDesc = ""
            scriptAuthor = ""
            scriptBrand = ""
            scriptContent = ""
            // 「存为脚本」入口：把命令执行/终端里的命令文本预填进内容，名称取首个非空行
            intent.getStringExtra("prefill_content")?.let { prefill ->
                if (prefill.isNotBlank()) {
                    scriptContent = prefill
                    scriptName = prefill.lineSequence().firstOrNull {
                        it.isNotBlank() && !it.trimStart().startsWith("#")
                    }?.trim()?.take(24) ?: "快捷脚本"
                }
            }
        }

        setContent {
            LinShizukuTheme {
                EditScreen(
                    name = scriptName,
                    content = scriptContent,
                    onNameChange = { scriptName = it },
                    onContentChange = { scriptContent = it },
                    desc = scriptDesc,
                    onDescChange = { scriptDesc = it },
                    author = scriptAuthor,
                    onAuthorChange = { scriptAuthor = it },
                    brand = scriptBrand,
                    onBrandChange = { scriptBrand = it },
                    isSwitch = scriptIsSwitch,
                    onSwitchChange = { scriptIsSwitch = it },
                    onContent = scriptOnContent,
                    onOnContentChange = { scriptOnContent = it },
                    offContent = scriptOffContent,
                    onOffContentChange = { scriptOffContent = it },
                    onBack = { finishWithTransition() },
                    onSave = { saveScript() },
                    onRun = { runScript() },
                    onExport = {
                        exportLauncher.launch("${scriptName.ifBlank { "script" }}.sh")
                    }
                )
            }
        }
    }

    private fun saveScript() {
        val id = scriptId ?: UUID.randomUUID().toString().take(8)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                ModuleStore.saveScript(this@ScriptEditActivity, id, scriptName, syncMeta())
            }
            result.onSuccess { newId ->
                scriptId = newId
                finishWithTransition()
            }.onFailure { e ->
                Toast.makeText(this@ScriptEditActivity, e.message ?: "保存失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun runScript() {
        val id = scriptId ?: UUID.randomUUID().toString().take(8)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                ModuleStore.saveScript(this@ScriptEditActivity, id, scriptName, syncMeta())
            }
            result.onFailure { e ->
                Toast.makeText(this@ScriptEditActivity, e.message ?: "保存失败", Toast.LENGTH_SHORT).show()
                return@launch
            }.onSuccess { newId ->
                scriptId = newId
                val intent = Intent(this@ScriptEditActivity, OutputActivity::class.java)
                intent.putExtra("script_id", newId)
                startActivity(intent)
                ActivityTransitions.overrideForward(this@ScriptEditActivity)
            }
        }
    }

    private fun saveToUri(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(syncMeta().toByteArray()) }
        } catch (_: Throwable) {}
    }

    /** 把三个元数据输入框（及开关型 on/off 两段）同步成最终脚本内容。 */
    private fun syncMeta(): String {
        if (scriptIsSwitch) {
            // 开关型：完整重建元数据块 + #on/#off 两段
            return buildString {
                append("#---\n")
                append("#name: ").append(scriptName).append("\n")
                append("#description: ").append(scriptDesc).append("\n")
                append("#author: ").append(scriptAuthor).append("\n")
                append("#brand: ").append(scriptBrand).append("\n")
                append("#on\n").append(scriptOnContent.trim()).append("\n")
                append("#off\n").append(scriptOffContent.trim()).append("\n")
                append("#---\n")
            }
        }
        var c = scriptContent
        c = upsertMeta(c, "description", scriptDesc)
        c = upsertMeta(c, "author", scriptAuthor)
        c = upsertMeta(c, "brand", scriptBrand)
        return c
    }

    /** 更新/插入 #key 行：有则替换；无则插入元数据块结束标记前；无块时在文件头新建完整块（ModuleStore 保存时只会再替换 #name，不会重复建块）。 */
    private fun upsertMeta(c: String, key: String, value: String): String {
        val newline = "#$key: $value"
        if (c.contains(Regex("#$key:\\s*.*"))) {
            return c.replace(Regex("#$key:\\s*.*"), newline)
        }
        val lines = c.lines().toMutableList()
        val start = lines.indexOfFirst { it.trim() == "#---" }
        if (start < 0) {
            return "#---\n#name: $scriptName\n#description: $scriptDesc\n#author: $scriptAuthor\n#brand: $scriptBrand\n#---\n\n${c.trimStart('\n')}"
        }
        var end = lines.size
        for (i in start + 1 until lines.size) {
            if (lines[i].trim() == "#---") { end = i; break }
        }
        lines.add(end, newline)
        return lines.joinToString("\n")
    }

    private fun finishWithTransition() {
        ActivityTransitions.finishWithAnimation(this)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditScreen(
    name: String,
    content: String,
    onNameChange: (String) -> Unit,
    onContentChange: (String) -> Unit,
    desc: String,
    onDescChange: (String) -> Unit,
    author: String,
    onAuthorChange: (String) -> Unit,
    brand: String,
    onBrandChange: (String) -> Unit,
    isSwitch: Boolean,
    onSwitchChange: (Boolean) -> Unit,
    onContent: String,
    onOnContentChange: (String) -> Unit,
    offContent: String,
    onOffContentChange: (String) -> Unit,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onRun: () -> Unit,
    onExport: () -> Unit
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("编辑脚本", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    IconButton(onClick = onExport) { Icon(Icons.Filled.IosShare, "导出") }
                    IconButton(onClick = onRun) { Icon(Icons.Filled.PlayArrow, "运行") }
                    IconButton(onClick = onSave) { Icon(Icons.Filled.Save, "保存") }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = onNameChange,
                label = { Text("脚本名称") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = desc,
                onValueChange = onDescChange,
                label = { Text("脚本介绍") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = author,
                onValueChange = onAuthorChange,
                label = { Text("作者") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = brand,
                onValueChange = onBrandChange,
                label = { Text("适用品牌") },
                modifier = Modifier.fillMaxWidth()
            )
            // 脚本类型：执行 / 开关
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = !isSwitch,
                    onClick = { onSwitchChange(false) },
                    label = { Text("执行") }
                )
                FilterChip(
                    selected = isSwitch,
                    onClick = { onSwitchChange(true) },
                    label = { Text("开关") }
                )
            }
            if (!isSwitch) {
                OutlinedTextField(
                    value = content,
                    onValueChange = onContentChange,
                    label = { Text("脚本内容") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(400.dp),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp
                    )
                )
            } else {
                OutlinedTextField(
                    value = onContent,
                    onValueChange = onOnContentChange,
                    label = { Text("开（执行时运行）") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp
                    )
                )
                OutlinedTextField(
                    value = offContent,
                    onValueChange = onOffContentChange,
                    label = { Text("关（关闭时运行）") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp
                    )
                )
            }
            Text(
                if (isSwitch) "开关型：保存后含 #on/#off 两段，运行时按开/关切换执行"
                else "支持 #--- 元数据块；选「开关」可分别编写开/关两段命令",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
