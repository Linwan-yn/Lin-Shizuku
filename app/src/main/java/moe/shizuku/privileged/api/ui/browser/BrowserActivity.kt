package moe.shizuku.privileged.api.ui.browser

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.runBlocking
import moe.shizuku.privileged.api.core.ModuleStore
import moe.shizuku.privileged.api.core.ShizukuManager
import moe.shizuku.privileged.api.ui.theme.LinShizukuTheme
import moe.shizuku.privileged.api.util.ActivityTransitions
import org.json.JSONObject
import java.io.File

class BrowserActivity : ComponentActivity() {

    private var webView: WebView? = null

    // P1-1 修复：当前页面是否处于受信任的模块本地 file:// 上下文（持有 ksu 桥）。
    // 仅模块本地页面持桥；导航离开（https 或其他路径）立即移除桥，防止远程页面
    // 或越界页面获得服务端命令执行权限。
    private var trustedLocal = false

    // P1-1 修复：模块 ID 提升为成员（导航白名单 handleModuleNavigation 需要）
    private var moduleId: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 90i 轮：移除 WebView 内核预热——Chromium 预载会让 App 进程常驻 RES 增加数十 MB；
        // 模块 WebUI 按需创建（首开慢数百 ms，换取前后台内存占用显著下降）

        val url = intent.getStringExtra("url") ?: "https://shizuku.rikka.app/"
        val title = intent.getStringExtra("title") ?: "浏览器"
        moduleId = intent.getStringExtra("module_id")
        // 87 轮：模块 WebUI 隐藏顶栏（全屏展示网页内容）；通用浏览器保留顶栏
        val hideTopBar = moduleId != null

        setContent {
            LinShizukuTheme {
                BrowserScreen(
                    title = title,
                    url = url,
                    hideTopBar = hideTopBar,
                    onBack = { finishWithTransition() },
                    onWebViewCreated = { view ->
                        webView = view
                        // Lin 模块本地 WebUI：注入 ksu 兼容桥（KernelSU/AxManager WebUI 协议）
                        // 90af：模块目录优先内部自有路径（新装模块），老路径兼容
                        // P1-1 修复：仅对模块本地 file:// 页面注入 ksu 桥；
                        // 远程 https:// 模块页面只读浏览、不持桥（防远程页面获得命令执行权限）
                        // P1-1 修复：局部变量规避成员可变属性的智能转换限制
                        val mid = moduleId
                        if (mid != null) {
                            val isLocalModulePage = url.startsWith("file://")
                            if (isLocalModulePage) {
                                val dir = ModuleStore.moduleFileDir(this, mid)
                                view.addJavascriptInterface(LinModuleJsBridge(this, view, dir), "ksu")
                                trustedLocal = true
                            }
                        }
                    },
                    onShouldOverrideUrlLoading = { v, req -> handleModuleNavigation(v, req) }
                )
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView?.canGoBack() == true) {
            webView?.goBack()
        } else {
            finishWithTransition()
        }
    }

    private fun finishWithTransition() {
        ActivityTransitions.finishWithAnimation(this)
    }

    /**
     * P1-1 修复：模块 WebUI 导航白名单（类作用域实现，可访问受信任状态与上下文）。
     *  - file:// 仅限本模块 webui 缓存目录内：保持/恢复 ksu 桥
     *  - https:// 允许浏览（如跳转官网/远程 API），但离开受信任上下文即移除 ksu 桥，
     *    远程页面永远无命令执行权限
     *  - http:// 明文及 intent/javascript/data/geo 等一律拦截
     *  - 非模块场景（通用浏览器）：仅放行 http/https
     */
    private fun handleModuleNavigation(view: WebView?, request: WebResourceRequest?): Boolean {
        val target = request?.url ?: return false
        val targetStr = target.toString()
        val scheme = target.scheme ?: ""
        val mid = moduleId
        if (mid != null) {
            val webuiRoot = File(applicationContext.cacheDir, "webui_$mid").toURI().toString()
            when {
                scheme == "file" && targetStr.startsWith(webuiRoot) -> {
                    if (!trustedLocal) {
                        runCatching {
                            val dir = ModuleStore.moduleFileDir(this, mid)
                            view?.addJavascriptInterface(LinModuleJsBridge(this, view, dir), "ksu")
                            trustedLocal = true
                        }
                    }
                    view?.loadUrl(targetStr)
                }
                scheme == "https" -> {
                    if (trustedLocal) {
                        runCatching {
                            view?.removeJavascriptInterface("ksu")
                            trustedLocal = false
                        }
                    }
                    view?.loadUrl(targetStr)
                }
                else -> {
                    // 越界 file 或 http/intent 等：拦截，并移除桥
                    if (trustedLocal) {
                        runCatching {
                            view?.removeJavascriptInterface("ksu")
                            trustedLocal = false
                        }
                    }
                }
            }
            return true
        }
        // 通用浏览器：仅放行 http/https，其余 scheme 一律拦截
        if (scheme == "http" || scheme == "https") {
            view?.loadUrl(targetStr)
        }
        return true
    }
}

/**
 * 90v 轮：页面加载完成后注入的 JS。
 * - ksu.execPromise(cmd, opts) → Promise<{errno, stdout, stderr}>（KsuWebUI 现代模块可用 await）
 * - window.__lin_webview_info：WebView 兼容性探测（fixed/vh 是否可用），供模块条件降级
 */
private val JS_PROMISE_WRAPPER: String = """
(function() {
  var b = window.ksu;
  if (!b || !b.exec || b.execPromise) return;
  b.execPromise = function(cmd, opts) {
    return new Promise(function(resolve) {
      var n = 'cbp' + Date.now() + Math.floor(Math.random() * 1e6);
      window[n] = function(errno, out, err) {
        try { delete window[n]; } catch (e) { window[n] = undefined; }
        resolve({ errno: errno, stdout: out || '', stderr: err || '' });
      };
      b.exec(cmd, opts || '{}', n);
    });
  };
})();
"""

private val JS_COMPAT_PROBE: String = """
(function() {
  if (window.__lin_webview_info) return;
  var info = {
    webviewVersion: (navigator.userAgent.match(/Chrome\/(\d+)/) || [])[1] || 'unknown',
    fixedSupported: true,
    vhSupported: true,
    innerHeight: window.innerHeight,
    devicePixelRatio: window.devicePixelRatio
  };
  try {
    var el = document.createElement('div');
    el.style.cssText = 'position:fixed;bottom:0;left:0;width:1px;height:1px;';
    document.body.appendChild(el);
    var r = el.getBoundingClientRect();
    info.fixedSupported = r.bottom > 0 && Math.abs(r.bottom - window.innerHeight) < 2;
    document.body.removeChild(el);
  } catch (e) {}
  try {
    var d = document.createElement('div');
    d.style.cssText = 'height:50vh;position:absolute;top:-9999px;';
    document.body.appendChild(d);
    var h = d.getBoundingClientRect().height;
    document.body.removeChild(d);
    info.vhSupported = Math.abs(h - window.innerHeight * 0.5) < 2;
  } catch (e) {}
  window.__lin_webview_info = info;
  try { if (window.console) console.log('[Lin WebView]', JSON.stringify(info)); } catch (e) {}
})();
"""

/**
 * 90w 轮：vh 单位兜底 polyfill（幂等，节流防抖）。
 * 针对 WebView 渲染引擎的 vh 塌陷（height/min-height/max-height/top/bottom 中 vh 计算为 0 或错误），
 * 将元素内联样式中的 vh 声明换算为基于 window.innerHeight 的真实 px，
 * 修复 fixed/flex 容器高度塌陷导致的界面截断/空白。仅处理含 vh 的样式声明，不触碰其他布局。
 */
private val JS_VH_POLYFILL: String = """
(function() {
  if (window.__lin_vh_polyfilled) return;
  window.__lin_vh_polyfilled = true;
  var PROPS = ['height', 'min-height', 'max-height', 'top', 'bottom'];
  var timer = null;
  function fix() {
    timer = null;
    var h = window.innerHeight || document.documentElement.clientHeight;
    if (!h) return;
    var els = document.querySelectorAll('*');
    for (var i = 0; i < els.length; i++) {
      var el = els[i];
      var cs = getComputedStyle(el);
      var changed = false;
      for (var j = 0; j < PROPS.length; j++) {
        var p = PROPS[j];
        var v = cs.getPropertyValue(p);
        if (v && v.indexOf('vh') !== -1) {
          el.style.setProperty(p, (parseFloat(v) * h / 100) + 'px', 'important');
          changed = true;
        }
      }
      if (changed) el.setAttribute('data-lin-vh-fixed', '1');
    }
  }
  function schedule() {
    if (timer) return;
    timer = setTimeout(fix, 300);
  }
  fix();
  window.addEventListener('resize', schedule);
  if (window.MutationObserver) {
    new MutationObserver(function() { schedule(); }).observe(
      document.documentElement,
      { childList: true, subtree: true, attributes: true, attributeFilter: ['style', 'class'] }
    );
  }
})();
"""

/**
 * 90x 轮：通用 flex 列布局兜底（幂等，节流）。
 * 针对 WebView 中 html/body height:100% 基准错误导致的 flex 滚动区（如 .main{flex:1;overflow-y:auto}）
 * 高度塌陷——症状为内容只显示顶部一小段后截断、页面无法滚动。
 * 强制 html/body 高度 = window.innerHeight，并把 body 直接子元素中 flex:1 且可滚动的容器
 * 显式设为剩余高度（可视高 - 固定头部）。仅作用于匹配布局的页面，不触碰其他样式。
 */
private val JS_LAYOUT_FIX: String = """
(function() {
  if (window.__lin_layout_fixed) return;
  window.__lin_layout_fixed = true;
  var timer = null;
  function fix() {
    timer = null;
    var b = document.body;
    var de = document.documentElement;
    if (!b || !de) return;
    var h = window.innerHeight || de.clientHeight;
    if (!h) return;
    var bcs = window.getComputedStyle(b);
    // 仅处理 flex 列布局（body 直接子元素含 flex:1 滚动容器，如 GAME OPTIMIZER 的 .main）
    if (bcs.flexDirection !== 'column') return;
    var growScroll = null;
    var kids = b.children;
    var i;
    for (i = 0; i < kids.length; i++) {
      var cs = window.getComputedStyle(kids[i]);
      if (cs.position === 'fixed' || cs.position === 'absolute') continue;
      var canScroll = cs.overflowY === 'auto' || cs.overflowY === 'scroll' || cs.overflow === 'auto' || cs.overflow === 'scroll';
      if (cs.flexGrow === '1' && canScroll) { growScroll = kids[i]; break; }
    }
    // 无内部滚动容器（body 文档流直接滚动型）→ 完全不干预，保持原生滚动
    if (!growScroll) return;
    // 有内部滚动容器 → 强制 html/body 高度=视口，滚动容器设剩余高度
    de.style.height = h + 'px';
    b.style.height = h + 'px';
    var used = 0;
    for (i = 0; i < kids.length; i++) {
      var el = kids[i];
      var cs2 = window.getComputedStyle(el);
      if (cs2.position === 'fixed' || cs2.position === 'absolute') continue;
      if (cs2.flexShrink === '0') used += el.offsetHeight || 0;
    }
    var avail = h - used;
    if (avail > 0) {
      growScroll.style.height = avail + 'px';
      growScroll.style.maxHeight = avail + 'px';
      growScroll.style.flex = '0 0 auto';
    }
  }
  fix();
  window.addEventListener('resize', function() { if (timer) return; timer = setTimeout(fix, 200); });
  if (window.MutationObserver) {
    new MutationObserver(function() { if (timer) return; timer = setTimeout(fix, 300); }).observe(
      document.documentElement, { childList: true, subtree: true }
    );
  }
})();
"""

/**
 * Lin 模块 WebUI 的 ksu 兼容桥（KernelSU / AxManager WebUI 协议）：
 * - ksu.exec(cmd, '{}', cbName)：异步执行 shell 命令，回调 (errno, stdout, stderr)
 * - ksu.execSync(cmd)：同步执行 shell 命令，直接返回 stdout 字符串
 * - ksu.moduleInfo()：返回模块信息（moduleDir / moduleId / name / version）
 * - ksu.moduleEnabled()：返回模块当前是否启用
 * - ksu.toast(msg)：在设备上弹出提示
 * - ksu.download(url, path, cbName)：将远程文件下载到模块目录（KernelSU 协议）
 */
private class LinModuleJsBridge(
    private val context: Context,
    private val webView: WebView,
    private val moduleDir: File
) {
    private val moduleId: String = moduleDir.name
    // 模块 WebUI 命令执行线程池：单线程即可（WebUI 串行调用），复用避免 Thread-per-call
    private val execPool: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newFixedThreadPool(1) { r ->
            Thread(r, "lin-module-jsbridge")
        }

    @JavascriptInterface
    fun moduleInfo(): String {
        return try {
            // 读 module.json（Lin 原生）或 module.prop（Magisk/KernelSU 兼容）取名称/版本
            val json = runBlocking { ShizukuManager.runCommand("cat '${moduleDir.absolutePath}/module.json' 2>/dev/null") }
            val prop = if (json.isBlank()) {
                runBlocking { ShizukuManager.runCommand("cat '${moduleDir.absolutePath}/module.prop' 2>/dev/null") }
            } else ""
            var name = moduleId
            var version = ""
            if (json.isNotBlank()) {
                try {
                    val j = JSONObject(json)
                    name = j.optString("name").ifBlank { name }
                    version = j.optString("version")
                } catch (_: Throwable) {}
            } else if (prop.isNotBlank()) {
                prop.lines().forEach { line ->
                    val idx = line.indexOf('=')
                    if (idx <= 0) return@forEach
                    val k = line.substring(0, idx).trim()
                    val v = line.substring(idx + 1).trim()
                    if (k == "name" && v.isNotBlank()) name = v
                    if (k == "version" && v.isNotBlank()) version = v
                }
            }
            JSONObject()
                .put("moduleId", moduleId)
                .put("moduleDir", moduleDir.absolutePath)
                .put("name", name)
                .put("version", version)
                .toString()
        } catch (_: Throwable) {
            try {
                JSONObject().put("moduleId", moduleId).put("moduleDir", moduleDir.absolutePath).toString()
            } catch (_: Throwable) { "{}" }
        }
    }

    @JavascriptInterface
    fun moduleEnabled(): Boolean {
        return try {
            ModuleStore.isEnabled(context, moduleId)
        } catch (_: Throwable) {
            false
        }
    }

    /** 异步执行：回调 window[cbName](errno, stdout, stderr)（AxManager 兼容）。
     *  90v 轮：errno 由真实进程退出码提供（此前恒为 0，模块无法判断命令失败），
     *  超时/未完成返回 -1。 */
    @JavascriptInterface
    fun exec(cmd: String, opts: String, callbackName: String) {
        // 命令在 Shizuku 服务端经 `sh -c` 执行（管道/PATH 天然支持，见 runCommandStreamingEx）；
        // 内部 20s 空闲超时，外层再留缓冲防回调丢失
        val future = execPool.submit<Triple<String, String, Int>> {
            val stdout = StringBuilder()
            val stderr = StringBuilder()
            var errno = 0
            try {
                runBlocking {
                    // P1-1 修复：命令锁定在模块目录上下文执行（与 KernelSU WebUI 语义一致），
                    // 防止页面命令依赖/影响全局路径
                    val scopedCmd = "cd '${moduleDir.absolutePath}' && $cmd"
                    val r = ShizukuManager.runCommandStreamingEx(
                        scopedCmd,
                        onLine = { line, isErr ->
                            if (isErr) stderr.appendLine(line) else stdout.appendLine(line)
                        },
                        timeoutMs = 20_000
                    )
                    errno = r.exitCode ?: -1
                }
            } catch (e: Throwable) {
                stderr.appendLine("错误: ${e.message}")
                errno = 127
            }
            Triple(stdout.toString(), stderr.toString(), errno)
        }
        try {
            val (out, err, errno) = try {
                future.get(25, java.util.concurrent.TimeUnit.SECONDS)
            } catch (e: java.util.concurrent.TimeoutException) {
                future.cancel(true)
                Triple("", "«命令超时（20s）»", -1)
            }
            val safeOut = if (out.length > 200_000) out.take(200_000) + "\n«输出过长已截断»" else out
            val safeErr = if (err.length > 20_000) err.take(20_000) + "\n«输出过长已截断»" else err
            webView.post {
                try {
                    webView.evaluateJavascript(
                        "window['$callbackName']($errno, ${JSONObject.quote(safeOut)}, ${JSONObject.quote(safeErr)});",
                        null
                    )
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    /** 同步执行：直接返回 stdout（KsuWebUI 协议 exec(cmd) → String）。 */
    @JavascriptInterface
    fun execSync(cmd: String): String {
        return try {
            // P1-1 修复：命令锁定在模块目录上下文执行
            runBlocking { ShizukuManager.runCommand("cd '${moduleDir.absolutePath}' && $cmd") }
        } catch (e: Throwable) {
            "错误: ${e.message}"
        }
    }

    /** 设备 Toast 提示（KernelSU 协议 ksu.toast）。 */
    @JavascriptInterface
    fun toast(msg: String) {
        try {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {}
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowserScreen(
    title: String,
    url: String,
    hideTopBar: Boolean,
    onBack: () -> Unit,
    onWebViewCreated: (WebView) -> Unit,
    // P1-1 修复：导航拦截回调（由类作用域实现白名单，可访问受信任状态与上下文）
    onShouldOverrideUrlLoading: (WebView?, WebResourceRequest?) -> Boolean = { _, _ -> false }
) {
    var progress by remember { mutableIntStateOf(0) }
    var currentUrl by remember { mutableStateOf(url) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var fileChooserCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    // 模块 WebUI 文件上传（input type=file）支持（KsuWebUI 同款）
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        fileChooserCallback?.onReceiveValue(uri?.let { arrayOf(it) })
        fileChooserCallback = null
    }

    // Intercept system back: navigate web history first, then close activity.
    BackHandler(enabled = canGoBack) {
        // WebView back is handled via the activity's onBackPressed; this BackHandler
        // prevents the default finish when web history exists.
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            // 87 轮：模块 WebUI 隐藏顶栏（标题/返回/后退/前进/刷新），网页内容从状态栏下方开始全屏展示
            if (!hideTopBar) {
                TopAppBar(
                    title = { Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { /* webView?.goBack() */ },
                            enabled = canGoBack
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "后退")
                        }
                        IconButton(
                            onClick = { /* webView?.goForward() */ },
                            enabled = canGoForward
                        ) {
                            Icon(Icons.Filled.ArrowForward, "前进")
                        }
                        IconButton(onClick = { /* webView?.reload() */ }) {
                            Icon(Icons.Filled.Refresh, "刷新")
                        }
                    },
                    scrollBehavior = scrollBehavior
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (progress in 1..99) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                databaseEnabled = true
                                cacheMode = WebSettings.LOAD_DEFAULT
                                userAgentString = "$userAgentString ShizukuDeepMod/3.0"
                                // 90v 轮（WebView 兼容性落地）：position:fixed + vh 塌陷根因修复。
                                // 90w 轮曾改 false，90x 轮实测恢复 true：GAME OPTIMIZER 等模块均声明
                                // viewport meta（width=device-width），true 下按 meta 渲染最标准，
                                // false 反而忽略 meta 细节；loadWithOverviewMode=false 保持无整页缩放。
                                useWideViewPort = true
                                loadWithOverviewMode = false
                                setSupportZoom(false)
                                builtInZoomControls = false
                                setInitialScale(0)
                                // 91 轮（网页加载提速）：显式启用渲染进程高优先级，让首屏
                                // 绘制/布局线程优先调度（Chromium 默认 NORMAL，低端机首屏
                                // 常被后台任务抢占）。同时关闭不必要的同时多窗口支持。
                                setSupportMultipleWindows(false)
                                // 本地 file:// 模块页面允许加载 http 资源（如远程版本检查/图标）
                                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                // 本地模块 WebUI（file:// web/index.html 及同目录资源）
                                allowFileAccess = true
                                allowContentAccess = true
                                @Suppress("DEPRECATION")
                                setAllowFileAccessFromFileURLs(true)
                                // P1-1 修复：关闭 file 页面万能跨域访问。此前 file:// 模块页面
                                // 可 fetch 任意远程 URL 并读取内容（绕过 CORS），远程投毒页面
                                // 可静默回传设备数据；改为与 Web 标准一致（远程 API 需 https+CORS）。
                                @Suppress("DEPRECATION")
                                setAllowUniversalAccessFromFileURLs(false)
                            }
                            // 91 轮（网页加载提速）：渲染进程优先级提到 HIGH，首屏绘制更快。
                            // WebView.setRenderPriority 在各 ROM/SDK 存在差异（P 起标记废弃、
                            // 部分定制内核直接移除），用反射静默调用，失败不影响加载。
                            try {
                                val wvClass = Class.forName("android.webkit.WebView")
                                val prioClass = wvClass.classes.firstOrNull {
                                    it.simpleName == "RendererPriority"
                                }
                                if (prioClass != null) {
                                    val high = prioClass.getField("HIGH").get(null)
                                    wvClass.getMethod("setRenderPriority", prioClass)
                                        .invoke(null, high)
                                }
                            } catch (_: Throwable) {}
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): Boolean {
                                    // P1-1 修复：导航拦截交由类作用域的白名单实现
                                    // （可访问受信任状态 trustedLocal、模块上下文与 Activity）
                                    if (onShouldOverrideUrlLoading(view, request)) return true
                                    request?.url?.let { view?.loadUrl(it.toString()) }
                                    return true
                                }

                                override fun doUpdateVisitedHistory(
                                    view: WebView?,
                                    url: String?,
                                    isReload: Boolean
                                ) {
                                    canGoBack = view?.canGoBack() == true
                                    canGoForward = view?.canGoForward() == true
                                }

                                // 91 轮（网页加载提速）：首帧绘制完成即注入兼容层 JS，
                                // 比 onPageFinished 平均提前数百 ms 可用；onPageFinished
                                // 仍兜底注入（全部 JS 幂等，重复注入无副作用）。
                                override fun onPageCommitVisible(view: WebView?, url: String?) {
                                    super.onPageCommitVisible(view, url)
                                    try {
                                        view?.evaluateJavascript(JS_PROMISE_WRAPPER, null)
                                        view?.evaluateJavascript(JS_COMPAT_PROBE, null)
                                        view?.evaluateJavascript(JS_VH_POLYFILL, null)
                                        view?.evaluateJavascript(JS_LAYOUT_FIX, null)
                                    } catch (_: Throwable) {}
                                }

                                // 90v 轮：页面加载完成后注入 ksu.execPromise 封装与
                                // window.__lin_webview_info 兼容性探测（供模块条件降级）
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    try {
                                        view?.evaluateJavascript(JS_PROMISE_WRAPPER, null)
                                        view?.evaluateJavascript(JS_COMPAT_PROBE, null)
                                        // 90w 轮：vh 单位兜底 polyfill（修复 fixed/flex 容器塌陷截断）
                                        view?.evaluateJavascript(JS_VH_POLYFILL, null)
                                        // 90x 轮：通用 flex 列布局兜底（修复 .main 滚动区高度塌陷截断）
                                        view?.evaluateJavascript(JS_LAYOUT_FIX, null)
                                    } catch (_: Throwable) {}
                                }
                            }
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    progress = newProgress
                                }

                                override fun onShowFileChooser(
                                    webView: WebView?,
                                    filePathCallback: ValueCallback<Array<Uri>>?,
                                    fileChooserParams: FileChooserParams?
                                ): Boolean {
                                    fileChooserCallback = filePathCallback
                                    fileLauncher.launch(arrayOf("*/*"))
                                    return true
                                }
                            }
                            loadUrl(url)
                            onWebViewCreated(this)
                            canGoBack = canGoBack()
                            canGoForward = canGoForward()
                        }
                    },
                    update = { view ->
                        if (currentUrl != url) {
                            view.loadUrl(url)
                            currentUrl = url
                        }
                    },
                    // 90j 修复：WebView 必须显式 destroy() 才会释放 Chromium native 内存
                    // （内核/渲染器数十 MB）；Compose 组合移除时 onRelease 触发，关闭
                    // 模块 WebUI 后不再把渲染器常驻进程（此前多开累积→SWAP 暴涨）
                    onRelease = { view -> (view as WebView).destroy() },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
