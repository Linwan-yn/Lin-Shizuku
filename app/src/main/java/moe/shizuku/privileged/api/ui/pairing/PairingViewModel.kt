package moe.shizuku.privileged.api.ui.pairing

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.adb.AdbInvalidPairingCodeException
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbPairingClient
import moe.shizuku.manager.adb.AdbPairingService
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
import moe.shizuku.privileged.api.core.DiagnosticLog
import moe.shizuku.privileged.api.core.LinAccessibilityService
import moe.shizuku.privileged.api.core.Prefs
import moe.shizuku.privileged.api.core.ShizukuManager
import moe.shizuku.privileged.api.util.AdbMdns
import moe.shizuku.privileged.api.util.AdbWirelessHelper
import moe.shizuku.privileged.api.util.EnvironmentUtils
import moe.shizuku.privileged.api.util.Starter
import rikka.shizuku.Shizuku
import java.io.EOFException
import java.net.ConnectException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import kotlin.time.Duration.Companion.milliseconds

/** 配对/启动输出缓冲封顶行数（90o 性能轮：防长期使用内存无限增长）。 */
private const val OUTPUT_MAX_LINES = 500

enum class StepStatus { PENDING, RUNNING, COMPLETED, ERROR, WARNING }

data class PairingStep(
    val title: String,
    val icon: ImageVector,
    val status: StepStatus = StepStatus.PENDING,
    val description: String = "",
    val needsUserAction: Boolean = false
)

/**
 * Wireless-debugging pairing & startup flow, migrated from Lin-Shizuku `StarterViewModel`
 * (wireless branch). Steps advance automatically from real ADB / mDNS / shell output;
 * user actions (enable wireless debugging, grant notification permission, finish
 * pairing) are surfaced as [PairingStep.needsUserAction] and resumed via
 * [continueAfterSetup].
 */
class PairingViewModel(private val context: Context) : ViewModel() {

    private val _steps = MutableStateFlow<List<PairingStep>>(emptyList())
    val steps: StateFlow<List<PairingStep>> = _steps.asStateFlow()

    private val _currentStepIndex = MutableStateFlow(0)
    val currentStepIndex: StateFlow<Int> = _currentStepIndex.asStateFlow()

    private val _isCompleted = MutableStateFlow(false)
    val isCompleted: StateFlow<Boolean> = _isCompleted.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _outputLines = MutableStateFlow<List<String>>(emptyList())
    val outputLines: StateFlow<List<String>> = _outputLines.asStateFlow()

    private val _pairCode = MutableStateFlow("")
    val pairCode: StateFlow<String> = _pairCode.asStateFlow()

    /** 1.1.1：手动输入的配对端口（替代 mDNS 自动发现，不触发本地网络权限弹窗）。 */
    private val _pairingPort = MutableStateFlow("")
    val pairingPort: StateFlow<String> = _pairingPort.asStateFlow()

    /** v4.14：端口自动获取状态（"正在搜索…"/"已自动获取端口: 37319"/失败提示），
     *  UI 端口框旁绿色/灰色展示——对齐玄戒/Stellar「端口自动填好、只填配对码」体验。 */
    private val _portStatus = MutableStateFlow("")
    val portStatus: StateFlow<String> = _portStatus.asStateFlow()

    /** 分层重试：是否已尝试过配对（区分「首次弹窗出现自动配对」与「失败后是否自动重试」）。 */
    private var hasPairAttempted = false

    /** 分层重试：仅端口类失败（Socket is closed 等，码是对的但端口失效）置 true——
     *  弹窗重开端口轮换时可自动重试；码错/超时保持 false，绝不自动重试。 */
    private var retryOnNewPort = false

    private val _pairingNow = MutableStateFlow(false)
    val pairingNow: StateFlow<Boolean> = _pairingNow.asStateFlow()

    private val _pairError = MutableStateFlow<String?>(null)
    val pairError: StateFlow<String?> = _pairError.asStateFlow()

    private val helper = AdbWirelessHelper()
    private var detectedPort = 0

    /** 修复：暴露 ADB 连接端口给 UI（配对卡片「需要您操作」上方显示）。
     *  这是连接端口（_adb-tls-connect / service.adb.wifi.tcp.port），
     *  不是配对端口（配对端口仅弹窗期间有效，不展示给用户）。 */
    private val _connectPort = MutableStateFlow(0)
    val connectPort: StateFlow<Int> = _connectPort.asStateFlow()

    private fun updateDetectedPort(port: Int) {
        detectedPort = port
        _connectPort.value = port
    }

    /** v4.5：mDNS 配对端口发现（官方系机制，Stellar 健壮实现移植）——
     *  端口双通道之一（无障碍抓取 + mDNS 发现互备）。
     *  v5.2：mDNS 同时发现配对端口与连接端口（_adb-tls-connect）。 */
    private var adbMdns: AdbMdns? = null
    private var mdnsPairPort = 0

    /** v5.2：mDNS 发现的连接端口（官方机制，替代系统属性/盲扫优先）。 */
    private var mdnsConnectPort = 0

    private var hasNotificationPermission = context.getSystemService(
        android.app.NotificationManager::class.java
    ).areNotificationsEnabled()

    private enum class PairingPhase { NONE, ENABLE_WIRELESS, PAIRING }
    private var pairingPhase = PairingPhase.NONE

    /** 通知栏配对成功广播 → 自动继续启动流程（无需手动点「配对完成，继续」）。 */
    private val pairSuccessReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == AdbPairingService.ACTION_ADB_PAIR_SUCCESS) {
                // 防重入：广播（含延迟重发）与落盘标记可能双通道触发，只执行一次
                if (autoContinueLock.compareAndSet(false, true)) {
                    addOutputLine("通知栏配对成功，自动继续启动...")
                    DiagnosticLog.append("[配对] 通知栏配对成功，自动继续启动")
                    continueAfterSetup()
                }
            }
        }
    }

    /** 自动继续防重入锁（广播重发 / 落盘标记 / 轮询兜底多通道只执行一次） */
    private val autoContinueLock = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 自动化配对广播（90ah + 1.1.1）：无障碍服务提取到系统配对弹窗的 6 位码
     *  （以及配对端口）→ 自动填入并配对。端口抓到 → 全自动「只填码/零输入」；
     *  端口未抓到 → 码已自动填入，仅需手动补端口（不再倒退到全手动抄端口）。
     *  v4.1：消费逻辑抽为 [consumePendingPairInfo]，广播与配对页主动消费共用。 */
    private val autoPairCodeReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == LinAccessibilityService.ACTION_AUTO_PAIR_CODE) {
                consumePendingPairInfo()
            }
        }
    }

    /** v4.1：消费无障碍服务抓取的配对码/端口（Pre 剩余残留不依赖广播时序——
     *  配对页晚于配对弹窗打开时广播无人接收，残留码会阻塞服务再次抓取；
     *  配对页打开即主动消费，与广播路径行为一致）。
     *  auto_pair.py 时序版：配对端口由 mDNS 在弹窗出现时广播，未到时等待，不盲扫。 */
    private fun consumePendingPairInfo() {
        val code = Prefs.pendingPairCode
        if (code.length != 6 || !code.all { it.isDigit() }) return
        Prefs.clearPendingPair()
        _pairCode.value = code
        val port = Prefs.pendingPairPort
        if (port.toIntOrNull() in 1..65535) {
            _pairingPort.value = port
            addOutputLine("检测到系统配对码 $code，自动识别配对端口 $port，自动配对...")
            DiagnosticLog.append("[配对] 自动化配对：系统配对码 $code，端口 $port")
            startPairingWithCode()
            return
        }
        val mdnsPort = mdnsPairPort
        if (mdnsPort in 1..65535) {
            _pairingPort.value = mdnsPort.toString()
            addOutputLine("检测到系统配对码 $code，mDNS 发现配对端口 $mdnsPort，自动配对...")
            DiagnosticLog.append("[配对] 自动化配对：系统配对码 $code，mDNS 端口 $mdnsPort")
            startPairingWithCode()
            return
        }
        // auto_pair.py 时序：配对端口由 mDNS 在弹窗出现时广播，未到时等待，不盲扫
        addOutputLine("已自动填入配对码 $code，正在等待系统配对弹窗广播配对端口...")
        _portStatus.value = "等待配对弹窗广播端口..."
        DiagnosticLog.append("[配对] 配对码已填，端口等待 mDNS（不盲扫）")
    }

    /**
     * 67 轮：轮询兜底自动继续。
     *
     * 背景：广播通道依赖 ViewModel 注册时序（后台配对/晚进入可能收不到），
     * 落盘标记只在 init 读一次（ViewModel 已存在时错过）。两种通道任一断点
     * 都会导致"配对成功后仍要手动点「配对完成，继续」"。
     *
     * 兜底：配对/开启无线调试步骤需要用户操作期间，每 2 秒检测"配对已完成"证据：
     *  ① 服务端落盘标记 pair_success（任何配对通道成功后都会写入，同进程立即可见）；
     *  ② ADB 配对状态由"未配对"翻转为"已配对"（避免"本来就配对过"误触发，
     *     重新配对场景：旧配对失效时先翻转为 false，新配对成功后再翻转 true）。
     * 命中任一证据 → 清除标记 → 带锁自动 continueAfterSetup()，无需手动点按钮。
     */
    private fun startAutoContinuePolling() {
        viewModelScope.launch {
            var lastAdbPaired: Boolean? = null
            while (true) {
                delay(2000)
                val step = _steps.value.getOrNull(_currentStepIndex.value)
                val needsPolling = pairingPhase == PairingPhase.PAIRING ||
                    pairingPhase == PairingPhase.ENABLE_WIRELESS ||
                    (step?.title == "无线调试配对" && step.needsUserAction)
                if (!needsPolling) {
                    lastAdbPaired = null
                    continue
                }
                if (autoContinueLock.get()) continue

                // 证据①：服务端配对成功落盘标记（通知中心/应用内任意通道都写入）
                val flag = runCatching {
                    context.getSharedPreferences("pair_state", Context.MODE_PRIVATE)
                        .getBoolean("pair_success", false)
                }.getOrDefault(false)

                // 证据②：ADB 配对状态翻转（false→true 才算"刚配对成功"）。
                // 90aw：探测端口扩展——mDNS 未发现端口（detectedPort==0）但开启了 TCP 自定义
                // 端口时，直接用 Prefs.tcpPort 探测，避免「系统设置配对成功后页面仍停留需要您操作」。
                val probePort = when {
                    detectedPort > 0 -> detectedPort
                    Prefs.tcpMode && Prefs.tcpPort in 1..65535 -> Prefs.tcpPort
                    else -> 0
                }
                val pairedNow = if (flag) true else (probePort > 0 && runCatching {
                    // v4.2：socket 探测移入 IO 线程，杜绝主线程阻塞导致的「应用没有响应」ANR
                    withContext(Dispatchers.IO) {
                        helper.hasAdbPermission(context, "127.0.0.1", probePort)
                    }
                }.getOrDefault(false))
                val flipped = lastAdbPaired == false && pairedNow
                lastAdbPaired = pairedNow

                if ((flag || flipped) && autoContinueLock.compareAndSet(false, true)) {
                    // 90az：不再清除 pair_success——持久配对证据保留到用户主动重新配对，
                    // 配对页后台被回收重建 / 广播错过时新实例仍能读到标记自动继续。
                    addOutputLine("检测到配对已完成，自动继续启动...")
                    DiagnosticLog.append("[配对] 轮询检测到配对完成，自动继续启动")
                    continueAfterSetup()
                }
            }
        }
    }

    /**
     * 90az：配对页（重新）进入时重新评估配对状态并自动继续。
     *
     * 背景：配对成功回调可能发生在配对页后台 / Activity 被回收期间（广播错过、
     * 标记被旧实例消费、ViewModel 保留但状态未推进）——重新进入配对页时
     * 仍显示「需要我的操作」。这里在组合恢复时重新读取持久配对标记 + ADB
     * 连通性，命中即自动 continueAfterSetup（自动走检测端口→启动→回首页）。
     */
    fun reEvaluatePairedState() {
        viewModelScope.launch {
            if (_isCompleted.value || autoContinueLock.get()) return@launch
            val step = _steps.value.getOrNull(_currentStepIndex.value)
            val onPairingStep = pairingPhase == PairingPhase.PAIRING ||
                (step?.title == "无线调试配对" && step.needsUserAction)
            if (!onPairingStep) return@launch
            val pairFlag = runCatching {
                context.getSharedPreferences("pair_state", Context.MODE_PRIVATE)
                    .getBoolean("pair_success", false)
            }.getOrDefault(false)
            val port = when {
                detectedPort > 0 -> detectedPort
                Prefs.tcpMode && Prefs.tcpPort in 1..65535 -> Prefs.tcpPort
                else -> EnvironmentUtils.getAdbTcpPort()
            }
            val paired = pairFlag || (port in 1..65535 && runCatching {
                // v4.2：socket 探测移入 IO 线程，防主线程阻塞 ANR
                withContext(Dispatchers.IO) {
                    helper.hasAdbPermission(context, "127.0.0.1", port)
                }
            }.getOrDefault(false))
            if (paired && autoContinueLock.compareAndSet(false, true)) {
                addOutputLine("检测到已配对，自动继续启动...")
                DiagnosticLog.append("[配对] 页面恢复检测到已配对，自动继续启动")
                _outputLines.value = emptyList()
                _errorMessage.value = null
                pairingPhase = PairingPhase.NONE
                if (lin.Lin.pingBinder()) {
                    setSuccess()
                } else {
                    initializeSteps()
                    launch(Dispatchers.Main) {
                        updateStepByTitle("检测配对状态", StepStatus.COMPLETED, "已配对")
                        insertNotificationStep()
                        startDetection()
                    }
                }
            }
        }
    }

    init {
        // 兼容场景：通知配对成功时配对页尚未创建（广播已错过），读取落盘标记自动继续。
        // 必须在默认 initializeSteps/startProcess 之前处理，避免与默认检测流程并发。
        // 90az：pair_success 只读不删——作为持久配对证据保留到用户主动重新配对
        //（repairPairing 清除），避免「配对页后台被回收重建 / 标记被旧实例消费」后
        // 新实例读不到标记、回退到再次弹配对「需要我的操作」。
        val pairFlag = runCatching {
            val sp = context.getSharedPreferences("pair_state", Context.MODE_PRIVATE)
            sp.getBoolean("pair_success", false)
        }.getOrDefault(false)

        // 注册通知栏配对成功广播（Android 13+ 动态注册需 RECEIVER_NOT_EXPORTED）
        val filter = IntentFilter(AdbPairingService.ACTION_ADB_PAIR_SUCCESS)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(pairSuccessReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(pairSuccessReceiver, filter)
            }
        }

        // 90ah：注册自动化配对广播（无障碍服务提取到系统配对码后触发自动配对）
        val autoPairFilter = IntentFilter(LinAccessibilityService.ACTION_AUTO_PAIR_CODE)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(autoPairCodeReceiver, autoPairFilter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(autoPairCodeReceiver, autoPairFilter)
            }
        }

        if (pairFlag && autoContinueLock.compareAndSet(false, true)) {
            addOutputLine("检测到已完成配对，自动继续启动...")
            DiagnosticLog.append("[配对] 检测到配对完成标记，自动继续启动")
            _outputLines.value = emptyList()
            _errorMessage.value = null
            pairingPhase = PairingPhase.NONE
            // 90az：服务已在运行（历史配对已启动过）→ 直接标记成功回首页，避免重复重启服务
            if (lin.Lin.pingBinder()) {
                setSuccess()
            } else {
                // 1.1.2（配对后进入不闪"无线调试配对"）：pair_success 已存在，
                // 直接走检测端口→ADB连接→启动流程，不要再走 continueAfterSetup 的
                // NONE 分支（它会 initializeSteps+startProcess，detectPort 失败时
                // 会插入"无线调试配对"步骤，与"已配对"事实矛盾）。
                initializeSteps()
                viewModelScope.launch(Dispatchers.Main) {
                    updateStepByTitle("检测配对状态", StepStatus.COMPLETED, "已配对")
                    insertNotificationStep()
                    startDetection()
                }
            }
        } else {
            initializeSteps()
            startProcess()
            // v4.1：配对页打开即主动消费无障碍抓取的残留码/端口（广播晚到时不再丢）
            consumePendingPairInfo()
        }
        // 轮询兜底：无论 init 走哪条分支都启动，覆盖广播/标记单点失效
        startAutoContinuePolling()
    }

    // ---------- steps ----------

    private fun step(title: String, icon: ImageVector, desc: String) =
        PairingStep(title, icon, StepStatus.PENDING, desc)

    private fun initializeSteps() {
        // 「授权通知权限」「无线调试配对」不预置：仅当未配对时由 showPairingSteps()
        // 动态插入，避免已配对自动启动时残留"等待"步骤且按钮区空白。
        _steps.value = listOf(
            step("检测 ADB 端口", Icons.Filled.Wifi, "正在检测可用的 ADB 端口..."),
            step("检测配对状态", Icons.Filled.Key, "等待检测"),
            step("连接 ADB 服务", Icons.Filled.SettingsEthernet, "等待连接"),
            step("验证连接状态", Icons.Filled.Verified, "等待验证"),
            step("检查现有服务", Icons.Filled.Search, "等待执行"),
            step("启动服务进程", Icons.Filled.RocketLaunch, "等待执行"),
            step("等待 Binder 响应", Icons.Filled.Refresh, "等待执行"),
            step("启动完成", Icons.Filled.Check, "等待完成")
        )
        _currentStepIndex.value = 0
    }

    private fun updateStep(index: Int, status: StepStatus, description: String, needsUserAction: Boolean = false) {
        viewModelScope.launch {
            if (status == StepStatus.RUNNING || status == StepStatus.COMPLETED) {
                delay(300.milliseconds)
            }
            // 流程已完成（setSuccess）后，禁止迟到的 RUNNING 更新把步骤打回"进行中"、
            // 或把计数拉回旧值——成功态不允许回退。
            if (_isCompleted.value && status == StepStatus.RUNNING) return@launch
            val currentSteps = _steps.value.toMutableList()
            if (index in currentSteps.indices) {
                currentSteps[index] = currentSteps[index].copy(
                    status = status,
                    description = description,
                    needsUserAction = needsUserAction
                )
                _steps.value = currentSteps
                if (status == StepStatus.RUNNING) {
                    _currentStepIndex.value = index
                }
            }
        }
    }

    /**
     * 90ay：按标题定位步骤更新（免疫步骤插入/移位导致的索引错位）。
     * 「无线调试配对」「授权通知权限」插入列表头部后，原先按固定索引
     * （0=检测端口、1=检测配对状态）的迟到更新会打在错误的步骤上——
     * 典型现象：授权通知权限步骤显示"正在检测可用的 ADB 端口..."。
     */
    private fun updateStepByTitle(title: String, status: StepStatus, description: String, needsUserAction: Boolean = false) {
        viewModelScope.launch {
            if (status == StepStatus.RUNNING || status == StepStatus.COMPLETED) {
                delay(300.milliseconds)
            }
            if (_isCompleted.value && status == StepStatus.RUNNING) return@launch
            val currentSteps = _steps.value
            val index = currentSteps.indexOfFirst { it.title == title }
            if (index >= 0) {
                val list = currentSteps.toMutableList()
                list[index] = list[index].copy(
                    status = status,
                    description = description,
                    needsUserAction = needsUserAction
                )
                _steps.value = list
                // 修改：如果当前正在等待用户输入（如配对），后台步骤的 RUNNING 更新不应抢走 UI 焦点
                if (status == StepStatus.RUNNING) {
                    val currentFocusedStep = _steps.value.getOrNull(_currentStepIndex.value)
                    if (currentFocusedStep?.needsUserAction != true) {
                        _currentStepIndex.value = index
                    }
                }
            }
        }
    }

    private fun insertStep(afterIndex: Int, newStep: PairingStep) {
        val currentSteps = _steps.value.toMutableList()
        if (afterIndex in -1 until currentSteps.size) {
            currentSteps.add(afterIndex + 1, newStep)
            _steps.value = currentSteps
        }
    }

    private fun addOutputLine(line: String) {
        viewModelScope.launch {
            // 90o 性能轮：输出缓冲封顶（原无上限，长时间反复配对时 List 无限增长拖内存）
            val current = _outputLines.value
            _outputLines.value = (current + line).takeLast(OUTPUT_MAX_LINES)
        }
    }

    // ---------- entry ----------

    private fun startProcess() = startDetection()

    private fun setSuccess() {
        viewModelScope.launch {
            // 90au：成功时清除历史错误——之前失败残留的 errorMessage 会阻断
            // PairingScreen「成功后自动返回首页」的判定（isCompleted && errorMessage==null）。
            _errorMessage.value = null
            // 90ba：服务就绪 → 广播通知首页状态卡立即刷新「正在运行中」（Binder
            // sticky 监听之外的双保险；无监听方时广播无害）
            runCatching {
                moe.shizuku.privileged.api.core.ShizukuManager.notifyServiceStateChanged(context)
            }
            // 原子整体更新：一次性替换全部步骤为完成态。
            // 之前用 N 个异步 updateStep 并发改同一 StateFlow，协程基于旧快照
            // 读-改-写会互相覆盖，可能丢状态/计数错位；这里整体替换一次完成。
            val current = _steps.value
            val lastIndex = current.lastIndex
            val newSteps = current.mapIndexed { index, s ->
                if (s.status != StepStatus.COMPLETED && s.status != StepStatus.WARNING) {
                    s.copy(
                        status = StepStatus.COMPLETED,
                        description = if (index == lastIndex) "Shizuku 服务已成功启动" else "完成"
                    )
                } else s
            }
            _steps.value = newSteps
            // 顶部状态同步到最后一格（"启动完成"），避免显示停留在 8/9 之类的错位计数
            _currentStepIndex.value = lastIndex.coerceAtLeast(0)
            _isCompleted.value = true
        }
    }

    private fun setError(error: Throwable, stepIndex: Int) {
        viewModelScope.launch {
            updateStep(stepIndex, StepStatus.ERROR, error.message ?: "执行失败")
            _errorMessage.value = error.message ?: "未知错误"
        }
    }

    // ---------- step 1-2: detect port & pairing status ----------

    private fun startDetection(forcePairing: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            launch(Dispatchers.Main) {
                updateStepByTitle("检测 ADB 端口", StepStatus.RUNNING, "正在检测可用的 ADB 端口...")
            }

            // 90bj：配对流程统一（撤回 90ax/90bd/90be 的「TCP 开关影响配对、配对分两种」
            // 设计——用户确认该设计有误）。无论 TCP 开关状态，启动前都统一检测无线调试
            // 端口：已配对 → 直连启动（startShizukuViaAdb 内部按 tcpMode 决定是否把 adbd
            // 切到固定端口 5555，TCP 开关只保留这个作用）；未配对 → 引导无线调试配对；
            // 端口为 0 → mDNS 兜底补检测。
            val systemPort = EnvironmentUtils.getAdbTcpPort()
            val pairFlag = runCatching {
                context.getSharedPreferences("pair_state", Context.MODE_PRIVATE)
                    .getBoolean("pair_success", false)
            }.getOrDefault(false)
            val alreadyPaired = pairFlag || (systemPort in 1..65535 &&
                helper.hasAdbPermission(context, "127.0.0.1", systemPort))
            if (alreadyPaired && !forcePairing) {
                if (systemPort in 1..65535) {
                    updateDetectedPort(systemPort)
                    launch(Dispatchers.Main) {
                        updateStepByTitle("检测 ADB 端口", StepStatus.COMPLETED, "端口可用: $systemPort")
                        updateStepByTitle("检测配对状态", StepStatus.COMPLETED, "已配对")
                        insertNotificationStep()
                        delay(300.milliseconds)
                        startAdbSteps()
                    }
                } else {
                    // 90bg：已配对（pair_success 标记）但系统无线调试端口为 0——
                    // 服务被停止后无线调试端口可能未就绪/读取失败，配对标记只表示
                    // adb 密钥授权过，不代表端口此刻可用。绝不能连接 127.0.0.1:0
                    // （此前直接 detectedPort=systemPort=0 → 15s 超时 → 反复失败）。
                    // 1.1.1：改用系统属性直读（service.adb.wifi.tcp.port），不再 mDNS 扫描；
                    // 读不到则引导开启无线调试/TCP 模式。
                    launch(Dispatchers.Main) {
                        updateStepByTitle("检测 ADB 端口", StepStatus.RUNNING, "正在读取可用端口...")
                        updateStepByTitle("检测配对状态", StepStatus.COMPLETED, "已配对")
                        delay(300.milliseconds)
                        detectPortWithFallback()
                    }
                }
                return@launch
            }

            // 未配对 / 需重新配对：先检测端口（立即显示「端口可用: X」），
            // 检测完成后 detectPortWithFallback 内部会引导无线调试配对——
            // 顺序自然：检测ADB端口(完成) → 检测配对状态(需要配对) → 无线调试配对(RUNNING)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                launch(Dispatchers.Main) {
                    detectPortWithFallback(forcePairing)
                }
            } else {
                launch(Dispatchers.Main) {
                    updateStepByTitle("检测 ADB 端口", StepStatus.WARNING, "未检测到端口")
                    showEnableWirelessAdbStep()
                }
            }
        }
    }

    /**
     * 端口检测：先系统属性直读（无线调试开启即有 service.adb.tls.port，秒级返回并显示
     * 「端口可用: X」）→ mDNS 连接端口作为刷新/兜底（配对后端口可能轮换，mDNS 广播的是
     * 最新端口）。不再盲扫本机端口。未配对时检测完成后引导配对（showPairingSteps）。
     */
    private fun detectPortWithFallback(forcePairing: Boolean = false) {
        viewModelScope.launch {
            updateStepByTitle("检测 ADB 端口", StepStatus.RUNNING, "正在检测可用的 ADB 端口...")
            // 确保 mDNS 已启动（配对+连接双服务；幂等）——属性读不到时靠它兜底
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startMdnsPairingDiscovery()
            }
            // 1. 系统属性直读：无线调试开启即有，秒级返回
            var connPort = withContext(Dispatchers.IO) { EnvironmentUtils.getAdbTcpPort() }
                .takeIf { it in 1..65535 }
            if (connPort != null) {
                // 已有属性端口；若 mDNS 已广播不同连接端口（配对后轮换）则以 mDNS 为准
                connPort = mdnsConnectPort.takeIf { it in 1..65535 } ?: connPort
            } else {
                // 2. 属性读不到：等 mDNS 连接广播（最多 10 秒）
                repeat(20) {
                    connPort = mdnsConnectPort.takeIf { it in 1..65535 }
                    if (connPort != null) return@repeat
                    delay(500)
                }
            }
            // 非空化：后续 if/canConnect/检测步骤引用需要非空 Int（EnvironmentUtils
            // 返回 Int（-1 无），takeIf 后为 Int?——统一转 Int? 再 ?: -1）
            val systemPort = connPort ?: -1
            if (systemPort in 1..65535) {
                // v4.2：socket 探测移入 IO 线程，防主线程阻塞 ANR
                val canConnect = withContext(Dispatchers.IO) {
                    helper.hasAdbPermission(context, "127.0.0.1", systemPort)
                }
                launch(Dispatchers.Main) {
                    updateStepByTitle("检测 ADB 端口", StepStatus.COMPLETED, "端口可用: $systemPort")
                    updateDetectedPort(systemPort)
                    if (canConnect && !forcePairing) {
                        updateStepByTitle("检测配对状态", StepStatus.COMPLETED, "已配对")
                        insertNotificationStep()
                        delay(300.milliseconds)
                        startAdbSteps()
                    } else {
                        updateStepByTitle("检测配对状态", StepStatus.WARNING,
                            if (forcePairing) "需要重新配对" else "需要配对")
                        delay(300.milliseconds)
                        showPairingSteps()
                    }
                }
            } else {
                // 系统属性也读不到：无线调试未开启或广播未到——引导开启，不盲扫
                updateStepByTitle("检测 ADB 端口", StepStatus.WARNING, "未发现无线调试连接端口")
                _errorMessage.value = "未发现无线调试端口：请确认「开发者选项 → 无线调试」已开启并保持网络连接；" +
                    "开启后回到本页重试（App 会通过 mDNS 自动获取端口）。"
                showEnableWirelessAdbStep()
            }
        }
    }

    // ---------- user action: enable wireless debugging ----------

    private fun showEnableWirelessAdbStep() {
        pairingPhase = PairingPhase.ENABLE_WIRELESS
        insertStep(
            0,
            PairingStep(
                title = "开启无线调试",
                icon = Icons.Filled.WifiOff,
                status = StepStatus.RUNNING,
                description = "请打开 设置 → 开发者选项 → 无线调试，开启后返回本页点击「继续」",
                needsUserAction = true
            )
        )
        _currentStepIndex.value = 1
    }

    // ---------- pairing ----------

    /**
     * 已配对自动启动时也显示「授权通知权限」步骤（非阻塞）：
     * 权限已授予 → 完成；未授予 → 黄色提示，不阻断启动。
     */
    private fun insertNotificationStep() {
        if (_steps.value.any { it.title == "授权通知权限" }) return
        val hasPermission = hasNotificationPermission
        insertStep(
            1,
            PairingStep(
                title = "授权通知权限",
                icon = Icons.Filled.Notifications,
                status = if (hasPermission) StepStatus.COMPLETED else StepStatus.WARNING,
                description = if (hasPermission) "通知权限已授予" else "通知权限未授予，可稍后在系统设置中开启",
                needsUserAction = false
            )
        )
    }

    /**
     * 90ay：实时刷新通知权限状态（用户在系统设置授权/关闭后返回本页时，
     * 避免沿用 ViewModel 创建时的旧值导致「授权通知权限」状态展示滞后）。
     */
    private fun refreshNotificationPermission() {
        hasNotificationPermission = runCatching {
            context.getSystemService(android.app.NotificationManager::class.java)
                .areNotificationsEnabled()
        }.getOrDefault(false)
    }

    private fun showPairingSteps(statusDesc: String = "需要配对") {
        pairingPhase = PairingPhase.PAIRING
        refreshNotificationPermission()
        val hasPermission = hasNotificationPermission

        // 90bo：悬浮窗配对方式已删除（ROM 在系统开发者选项/无线调试页不显示悬浮窗，前台服务无效）。
        // 配对码输入方式保留两种：小窗（默认，App 内配对页）/ 通知（兜底，通知栏 RemoteInput 输入）。

        // 更新「检测配对状态」为待配对（如果存在）
        val currentSteps = _steps.value
        val statusIndex = currentSteps.indexOfFirst { it.title == "检测配对状态" }
        if (statusIndex >= 0) {
            updateStep(statusIndex, StepStatus.WARNING, statusDesc)
        }

        // 1. 先插入「授权通知权限」（最前面）
        insertStep(
            -1,
            PairingStep(
                title = "授权通知权限",
                icon = Icons.Filled.Notifications,
                status = if (hasPermission) StepStatus.COMPLETED else StepStatus.RUNNING,
                description = if (hasPermission) "通知权限已授予" else "需要通知权限，用于在通知栏输入配对码",
                needsUserAction = !hasPermission
            )
        )

        // 2. 找到「检测 ADB 端口」的当前索引，将「无线调试配对」插入到它后面——
        // 先检测端口、再配对、后检测配对状态，顺序自然顺畅
        val adbPortIndex = _steps.value.indexOfFirst { it.title == "检测 ADB 端口" }
        insertStep(
            adbPortIndex, // 插在「检测 ADB 端口」之后
            PairingStep(
                title = "无线调试配对",
                icon = Icons.Filled.QrCodeScanner,
                status = if (hasPermission) StepStatus.RUNNING else StepStatus.PENDING,
                description = "先点系统「使用配对码配对设备」让配对弹窗保持显示；" +
                    "App 正在通过 mDNS 持续监听，弹窗出现后会自动获取配对端口（无需手动抄端口）。" +
                    "配对码自动识别或手动输入后点「开始配对」。",
                needsUserAction = hasPermission
            )
        )

        // 3. 更新当前焦点步骤索引：如果没授权通知，焦点在通知；授权了则焦点在配对
        val notifIndex = _steps.value.indexOfFirst { it.title == "授权通知权限" }
        val pairIndex = _steps.value.indexOfFirst { it.title == "无线调试配对" }
        _currentStepIndex.value = if (hasPermission) pairIndex else notifIndex

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && hasPermission) {
            startPairingService()
            // v4.5：同步启动 mDNS 配对端口发现（官方系机制）——与无障碍抓取双通道互备
            startMdnsPairingDiscovery()
        }
    }

    /** v4.5：mDNS 发现配对端口（_adb-tls-pairing）——官方系机制（Stellar/thedjchi 同款）。
     *  发现后存入 mdnsPairPort；已有配对码（手动输入）时立即自动配对。
     *  与无障碍抓取双通道互备：任一通道拿到端口+码 → 自动配对。
     *  auto_pair.py 持续监听版：超时只提示（继续等待弹窗），不再转盲扫。 */
    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.R)
    private fun startMdnsPairingDiscovery() {
        if (adbMdns != null) return
        runCatching {
            adbMdns = AdbMdns(
                context = context,
                serviceType = AdbMdns.TLS_PAIRING,
                onFound = { (_, port) ->
                    if (port in 1..65535) {
                        mdnsPairPort = port
                        _pairingPort.value = port.toString()
                        _portStatus.value = "已自动获取端口: $port"
                        DiagnosticLog.append("[配对] mDNS 发现配对端口 $port，已回填")
                        addOutputLine("mDNS 已发现配对端口: $port")
                        // 分层重试：首次（未尝试过）→ 自动配对；端口类失败后新端口 → 自动重试；
                        // 码错/超时失败 → 不自动重试（等用户改码/重开弹窗手动触发）
                        val code = _pairCode.value
                        if (code.length == 6 && code.all { it.isDigit() } && !_pairingNow.value) {
                            if (retryOnNewPort) {
                                retryOnNewPort = false
                                addOutputLine("检测到配对端口变化，自动重试配对...")
                                DiagnosticLog.append("[配对] 端口类失败后检测到新端口，自动重试")
                                startPairingWithCode()
                            } else if (!hasPairAttempted) {
                                startPairingWithCode()
                            }
                        }
                    }
                },
                onConnectFound = { port ->
                    // v5.2：mDNS 发现连接端口（官方机制）——检测端口时优先使用
                    // 90bx：端口未变化时不重复打日志，避免 mDNS 周期回调刷屏
                    if (port in 1..65535 && port != mdnsConnectPort) {
                        mdnsConnectPort = port
                        DiagnosticLog.append("[配对] mDNS 发现连接端口 $port")
                    } else if (port in 1..65535) {
                        mdnsConnectPort = port
                    }
                },
                onMaxRefresh = {
                    // 不再转盲扫：mDNS 持续监听，等用户点开弹窗
                    DiagnosticLog.append("[配对] mDNS 已持续监听 100 秒，继续等待配对弹窗（不盲扫）")
                    if (mdnsPairPort == 0) {
                        _portStatus.value = "持续监听中，请点开系统「使用配对码配对设备」弹窗"
                    }
                }
            ).also { it.start() }
        }
    }

    fun setNotificationPermission(granted: Boolean) {
        hasNotificationPermission = granted
        if (granted) {
            val currentSteps = _steps.value
            val notificationIndex = currentSteps.indexOfFirst { it.title == "授权通知权限" }
            if (notificationIndex >= 0) {
                updateStep(notificationIndex, StepStatus.COMPLETED, "通知权限已授予")
                val pairingIndex = currentSteps.indexOfFirst { it.title == "无线调试配对" }
                if (pairingIndex >= 0) {
                    updateStep(
                        pairingIndex,
                        StepStatus.RUNNING,
                        "先点系统「使用配对码配对设备」让配对弹窗保持显示；App 正在通过 mDNS 持续监听，弹窗出现后会自动获取配对端口（无需手动抄端口）。配对码自动识别或手动输入后点「开始配对」。",
                        needsUserAction = true
                    )
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startPairingService()
            }
        } else {
            // Permission dialog denied (or never shown, e.g. on MIUI). Keep the step
            // active and tell the user to enable notifications manually.
            val currentSteps = _steps.value
            val notificationIndex = currentSteps.indexOfFirst { it.title == "授权通知权限" }
            if (notificationIndex >= 0) {
                updateStep(
                    notificationIndex,
                    StepStatus.WARNING,
                    "未获得通知权限，请在系统「通知」设置中允许本应用通知（或点击「打开通知设置」手动开启）",
                    needsUserAction = true
                )
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun startPairingService() {
        val intent = AdbPairingService.startIntent(context)
        try {
            context.startForegroundService(intent)
        } catch (_: Throwable) {
            // foreground service may be blocked by the system; pairing UI still works
        }
    }

    /** Resume after the user finished the current action (enable / pair). */
    fun continueAfterSetup() {
        // Android 15+：配对完成后立刻停止 mDNS 监听，避免系统「选择要连接的设备」弹窗反复弹出
        runCatching { adbMdns?.stop() }
        viewModelScope.launch {
            // 1.1.2 修复（重复启动）：配对成功后的自动继续与「手动/自动启动」可能先后命中，
            // 此前无条件再走一遍启动流程，starter 会把刚拉起的 lin_server 杀掉重新拉起
            // （实测 07:51:58 起 pid 15315 → 07:52:00 killed 15315 → pid 15466，
            //  已授权的应用在重启窗口内全部掉线）。服务已在运行则直接标记完成。
            if (moe.shizuku.privileged.api.core.ShizukuManager.isRunningCompat()) {
                addOutputLine("服务已在运行，跳过重复启动")
                DiagnosticLog.append("[配对] 服务已在运行，自动继续跳过重复启动")
                setSuccess()
                return@launch
            }
            when (pairingPhase) {
                PairingPhase.ENABLE_WIRELESS -> {
                    _outputLines.value = emptyList()
                    _errorMessage.value = null
                    pairingPhase = PairingPhase.NONE
                    initializeSteps()
                    delay(300.milliseconds)
                    startProcess()
                }
                PairingPhase.PAIRING -> {
                    _outputLines.value = emptyList()
                    _errorMessage.value = null
                    // 修复：配对成功后立即停止 AdbPairingService 前台服务，
                    // 避免「已找到配对服务」通知常驻通知中心
                    runCatching { AdbPairingService.stop(context) }
                    // 不停止 adbMdns：配对成功后连接端口可能轮换，继续监听连接服务
                    val currentSteps = _steps.value
                    val pairingIndex = currentSteps.indexOfFirst { it.title == "无线调试配对" }
                    if (pairingIndex >= 0) {
                        updateStep(pairingIndex, StepStatus.COMPLETED, "配对完成")
                    }
                    // 同步「检测配对状态」步骤（通知栏/重新配对路径可能仍显示"需要重新配对"）
                    val pairStatusIndex = currentSteps.indexOfFirst { it.title == "检测配对状态" }
                    if (pairStatusIndex >= 0) {
                        updateStep(pairStatusIndex, StepStatus.COMPLETED, "已配对")
                    }
                    // 90bj 修复：配对成功后，配对端口和连接端口是不同的端口。
                    // 配对时用的 pairing port 配对完成后就关闭了，需要重新读取连接端口。
                    // auto_pair.py 版：连接端口由 mDNS 持续监听（配对后可能轮换），
                    // detectPortWithFallback 等 mDNS 连接端口 → 系统属性兜底。
                    pairingPhase = PairingPhase.NONE
                    updateDetectedPort(0)  // 清空旧端口，重新检测
                    addOutputLine("配对成功，正在等待无线调试连接端口...")
                    delay(500)  // 等一下让系统更新端口
                    initializeSteps()
                    delay(300.milliseconds)
                    detectPortWithFallback()
                }
                PairingPhase.NONE -> {
                    _outputLines.value = emptyList()
                    _errorMessage.value = null
                    initializeSteps()
                    delay(300.milliseconds)
                    startProcess()
                }
            }
        }
    }

    // ---------- in-app pairing code input ----------

    fun setPairCode(code: String) {
        // P2-1 修复：防御性过滤非数字并限长 6 位，
        // 保证配对码永远是纯数字串（杜绝跳框输入产生的空格/符号死码）
        val clean = code.filter { it.isDigit() }.take(6)
        _pairCode.value = clean
        if (clean.length == 6) {
            _pairError.value = null
        }
    }

    /**
     * Pair directly from the in-app inputs: 6-digit code (+ optional manual port).
     * auto_pair.py 时序版：端口来源 = 手动输入 > mDNS 已发现（弹窗出现时广播）；
     * 无候选端口时等待 mDNS 最多 5 秒，仍无则提示用户先点开配对弹窗，不盲扫。
     * On success the flow advances automatically to the ADB start steps.
     */
    fun startPairingWithCode() {
        val code = _pairCode.value.trim()
        if (code.length != 6 || !code.all { it.isDigit() }) {
            _pairError.value = "请输入 6 位数字配对码"
            return
        }
        if (_pairingNow.value) return
        viewModelScope.launch {
            _pairingNow.value = true
            _pairError.value = null

            val manualPort = _pairingPort.value.trim().toIntOrNull()
            var mdnsPort = mdnsPairPort
            // 弹窗刚出现时广播可能在途：等待最多 5 秒
            if (mdnsPort !in 1..65535 && (manualPort == null || manualPort !in 1..65535)) {
                _portStatus.value = "正在等待配对弹窗广播端口..."
                repeat(10) {
                    if (mdnsPort in 1..65535) return@repeat
                    delay(500)
                    mdnsPort = mdnsPairPort
                }
            }
            val candidates = buildList<Int> {
                // v5.1 构建修复：buildList 内显式 Int? 判空，避免智能转换
                // 在 lambda 内失效导致 List<Int?> 类型不匹配
                if (manualPort != null && manualPort in 1..65535) add(manualPort)
                if (mdnsPort in 1..65535 && mdnsPort != manualPort) add(mdnsPort)
            }

            if (candidates.isEmpty()) {
                _pairingNow.value = false
                _pairError.value = "尚未发现配对端口：请先在系统无线调试页点「使用配对码配对设备」，" +
                    "保持弹窗显示，App 会自动收到配对端口（无需手动抄端口），再点「开始配对」。"
                _portStatus.value = "等待配对弹窗广播端口..."
                return@launch
            }

            addOutputLine("正在连接配对服务端口 ${candidates.first()} ...")
            val result = pairWithPorts(code, candidates)
            _pairingNow.value = false

            if (result == null) {
                addOutputLine("配对成功")
                DiagnosticLog.append("[配对] 配对成功")
                _pairError.value = null
                _pairCode.value = ""
                // 分层重试状态重置：下次重新配对视为「首次」
                hasPairAttempted = false
                retryOnNewPort = false
                // 不停 AdbMdns——配对后连接端口可能轮换，继续监听
                mdnsConnectPort = 0  // 清空旧值，等待 mDNS 重新广播
                _portStatus.value = "配对成功，等待连接端口..."
                continueAfterSetup()
            } else {
                addOutputLine("错误：$result")
                DiagnosticLog.append("[配对] $result")
                _pairError.value = result
            }
        }
    }

    /**
     * 对候选端口逐个尝试 TLS+SPAKE2 配对（后台 IO）。
     * 成功返回 null 并记忆端口；配对码错误立即返回（换端口无用）；
     * 单端口超时/失败继续尝试下一个候选。
     * 分层重试（auto_pair.py 时序下的失败策略）：按异常类型决定失败后是否
     * 由「弹窗重开 + mDNS 新端口」自动重试——码错/超时不自动，端口类失败自动。
     */
    private suspend fun pairWithPorts(code: String, ports: List<Int>): String? {
        val key = AdbKey(
            PreferenceAdbKeyStore(context.getSharedPreferences("adb_key", Context.MODE_PRIVATE)),
            "Lin-Shizuku"
        )
        val prefs = context.getSharedPreferences("pair_state", Context.MODE_PRIVATE)
        return withContext(Dispatchers.IO) {
            hasPairAttempted = true
            var lastError: String? = null
            for (port in ports) {
                DiagnosticLog.append("[配对] 尝试端口 $port")
                try {
                    AdbPairingClient("127.0.0.1", port, code, key).use { it.start() }
                    prefs.edit().putInt("last_pairing_port", port).apply()
                    // v4.13：配对端口用完即销毁，不再回填输入框（避免用户误用旧端口重试）
                    _pairingPort.value = ""
                    return@withContext null
                } catch (e: AdbInvalidPairingCodeException) {
                    // 分层重试：码错绝不自动重试——重连还是错，且避免「报错同时 App 偷偷重试」
                    retryOnNewPort = false
                    return@withContext "配对码错误，请重新输入"
                } catch (e: SocketTimeoutException) {
                    // 连接/握手超时：弹窗大概率已关/端口过期——等用户重开弹窗，不自动重试
                    retryOnNewPort = false
                    lastError = "连接配对服务超时：请保持系统「配对码」弹窗显示（不要关闭），再点击「开始配对」重试；若弹窗已消失请重新点「使用配对码配对设备」"
                    DiagnosticLog.append("[配对] 端口 $port 超时，尝试下一个候选")
                } catch (e: Throwable) {
                    // 分层重试：Socket is closed = 连到非配对端口（连接端口/其它监听端口），
                    // 码是对的、端口失效——弹窗重开新端口可自动重试（净收益）
                    retryOnNewPort = true
                    lastError = if (e is java.net.SocketException || e.message?.contains("Socket is closed") == true) {
                        "端口 $port 非配对服务（可能为连接端口），请保持系统配对弹窗显示后重试"
                    } else {
                        "配对失败：${e.message ?: e.javaClass.simpleName}"
                    }
                    DiagnosticLog.append("[配对] 端口 $port 失败：${e.message}")
                }
            }
            lastError ?: "未能与配对服务建立连接"
        }
    }

    /** 手动输入配对端口（UI 输入框回调）。 */
    fun setPairingPort(port: String) {
        val filtered = port.filter { it.isDigit() }.take(5)
        _pairingPort.value = filtered
        if (filtered.toIntOrNull() in 1..65535) {
            _pairError.value = null
        }
    }

    // ---------- connect & start ----------

    private fun startAdbSteps() {
        val connectIndex = _steps.value.indexOfFirst { it.title == "连接 ADB 服务" }
        if (connectIndex >= 0) {
            updateStep(connectIndex, StepStatus.RUNNING, "正在连接...")
        }
        startAdbConnection("127.0.0.1", detectedPort)
    }

    private fun startAdbConnection(host: String, port: Int) {
        // v5.3：连接端口取最新值——无线调试动态端口可能在配对后轮换
        // （实测 36859→45013），锁定旧端口会 ECONNREFUSED 死循环
        val usePort = mdnsConnectPort.takeIf { it in 1..65535 } ?: port
        addOutputLine("Connecting to $host:$usePort...")
        // 保存端口到 boot_auto，供首页「启动」按钮复用
        context.getSharedPreferences("boot_auto", android.content.Context.MODE_PRIVATE)
            .edit().putInt("last_adb_port", usePort).apply()
        val tcpMode = Prefs.tcpMode
        val tcpPort = Prefs.tcpPort
        helper.startShizukuViaAdb(
            ctx = context,
            host = host,
            port = usePort,
            coroutineScope = viewModelScope,
            onOutput = { output ->
                addOutputLine(output)
                handleOutput(output)
            },
            onError = { error -> handleConnectError(error) },
            onSuccess = { handleConnectSuccess() },
            tcpMode = tcpMode,
            tcpPort = tcpPort,
            useSu = Prefs.wirelessSu
        )
    }

    private fun handleOutput(output: String) {
        viewModelScope.launch(Dispatchers.Main) {
            val steps = _steps.value
            val connectIndex = steps.indexOfFirst { it.title == "连接 ADB 服务" }
            val verifyIndex = steps.indexOfFirst { it.title == "验证连接状态" }
            val checkIndex = steps.indexOfFirst { it.title == "检查现有服务" }
            val startIndex = steps.indexOfFirst { it.title == "启动服务进程" }
            val binderIndex = steps.indexOfFirst { it.title == "等待 Binder 响应" }

            when {
                output.contains("检查权限: ADB") || output.contains("检查权限: Root")
                    || output.contains("info: start.sh begin") || output.contains("connected") -> {
                    if (connectIndex >= 0 && steps[connectIndex].status != StepStatus.COMPLETED) {
                        updateStep(connectIndex, StepStatus.COMPLETED, "连接成功")
                    }
                    if (verifyIndex >= 0 && steps[verifyIndex].status != StepStatus.COMPLETED) {
                        updateStep(verifyIndex, StepStatus.COMPLETED, "验证通过")
                    }
                }
                output.contains("info: attempt to copy") || output.contains("info: exec")
                    || output.contains("检查现有服务") || output.contains("未发现现有服务")
                    || output.contains("终止现有服务") -> {
                    if (connectIndex >= 0 && steps[connectIndex].status != StepStatus.COMPLETED) {
                        updateStep(connectIndex, StepStatus.COMPLETED, "连接成功")
                    }
                    if (verifyIndex >= 0 && steps[verifyIndex].status != StepStatus.COMPLETED) {
                        updateStep(verifyIndex, StepStatus.COMPLETED, "验证通过")
                    }
                    if (checkIndex >= 0 && steps[checkIndex].status != StepStatus.COMPLETED) {
                        updateStep(checkIndex, StepStatus.COMPLETED, "完成")
                    }
                    if (startIndex >= 0 && steps[startIndex].status != StepStatus.RUNNING) {
                        updateStep(startIndex, StepStatus.RUNNING, "正在启动...")
                    }
                }
                output.contains("启动服务进程") -> {
                    if (startIndex >= 0 && steps[startIndex].status != StepStatus.RUNNING) {
                        updateStep(startIndex, StepStatus.RUNNING, "正在启动...")
                    }
                }
                // v5.1：liblin.so（native 启动器）输出匹配——官方 starter.cpp 改编版
                // 输出 "info: lin_server pid is %d"（fork 出服务进程）与
                // "info: lin_starter exit with 0"（启动器正常退出）；旧匹配只认
                // start.sh 时代的 "shizuku_starter exit with 0"，v5.0 换启动器后
                // 步骤停在 PENDING 不推进（图 1 实测：三步全"等待"）。
                output.contains("lin_server pid is") -> {
                    if (startIndex >= 0 && steps[startIndex].status != StepStatus.COMPLETED) {
                        updateStep(startIndex, StepStatus.COMPLETED, "进程已创建")
                    }
                    if (binderIndex >= 0 &&
                        steps[binderIndex].status != StepStatus.RUNNING &&
                        steps[binderIndex].status != StepStatus.COMPLETED
                    ) {
                        updateStep(binderIndex, StepStatus.RUNNING, "等待响应")
                        waitForService()
                    }
                }
                output.contains("shizuku_starter exit with 0") || output.contains("lin_starter exit with 0")
                    || output.contains("lin_starter 正常退出")
                    || output.contains("正常退出（退出码 0）") -> {
                    if (startIndex >= 0 && steps[startIndex].status != StepStatus.COMPLETED) {
                        updateStep(startIndex, StepStatus.COMPLETED, "完成")
                    }
                    if (binderIndex >= 0 &&
                        steps[binderIndex].status != StepStatus.RUNNING &&
                        steps[binderIndex].status != StepStatus.COMPLETED
                    ) {
                        updateStep(binderIndex, StepStatus.RUNNING, "等待响应")
                        waitForService()
                    }
                }
            }

            output.lines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("错误：")) {
                    setError(Exception(trimmed.substringAfter("错误：").trim()), _currentStepIndex.value)
                }
            }
        }
    }

    private fun handleConnectSuccess() {
        viewModelScope.launch(Dispatchers.Main) {
            val steps = _steps.value
            val connectIndex = steps.indexOfFirst { it.title == "连接 ADB 服务" }
            val verifyIndex = steps.indexOfFirst { it.title == "验证连接状态" }
            val checkIndex = steps.indexOfFirst { it.title == "检查现有服务" }
            val startIndex = steps.indexOfFirst { it.title == "启动服务进程" }
            val binderIndex = steps.indexOfFirst { it.title == "等待 Binder 响应" }

            if (connectIndex >= 0 && steps[connectIndex].status != StepStatus.COMPLETED) {
                updateStep(connectIndex, StepStatus.COMPLETED, "连接成功")
            }
            if (verifyIndex >= 0 && steps[verifyIndex].status != StepStatus.COMPLETED) {
                updateStep(verifyIndex, StepStatus.COMPLETED, "验证通过")
            }
            if (checkIndex >= 0 && steps[checkIndex].status != StepStatus.COMPLETED) {
                updateStep(checkIndex, StepStatus.COMPLETED, "完成")
            }
            if (startIndex >= 0 && steps[startIndex].status != StepStatus.COMPLETED) {
                updateStep(startIndex, StepStatus.COMPLETED, "完成")
            }
            if (binderIndex >= 0 &&
                steps[binderIndex].status != StepStatus.RUNNING &&
                steps[binderIndex].status != StepStatus.COMPLETED
            ) {
                updateStep(binderIndex, StepStatus.RUNNING, "等待响应")
                waitForService()
            }
        }
    }

    private fun handleConnectError(error: Throwable) {
        if (_isCompleted.value) return
        addOutputLine("错误：${error.message}")
        viewModelScope.launch(Dispatchers.Main) {
            // 90bj 修复：连接失败不应该标记配对步骤为错误——配对已经成功了，
            // 连接超时只是 adb 服务还没就绪或端口变化，不需要重新配对。
            // 之前的逻辑：连接失败就把"无线调试配对"标记为 ERROR，导致用户看到
            // "配对失败或已失效"，但实际上配对已经成功了，只是连接超时。
            val needsRetry = error is SSLException ||
                error is ConnectException ||
                error is SocketTimeoutException ||
                error is EOFException ||
                error is java.net.SocketException

            if (needsRetry) {
                // 连接失败：标记连接步骤为错误，提供重试，不影响配对状态
                val connectIndex = _steps.value.indexOfFirst { it.title == "连接 ADB 服务" }
                if (connectIndex >= 0) {
                    updateStep(connectIndex, StepStatus.ERROR, "连接失败：${error.message ?: "端口不可用"}，正在重试...")
                }
                // 不清除配对成功状态，不提示重新配对
                // 延迟 2 秒后自动重试一次连接（adb 服务可能需要时间就绪）
                viewModelScope.launch {
                    delay(2000)
                    if (!_isCompleted.value) {
                        // v5.3：重试前刷新连接端口——动态端口可能已轮换（实测
                        // 36859→45013），锁定旧端口会无限 ECONNREFUSED
                        val freshPort = mdnsConnectPort.takeIf { it in 1..65535 }
                            ?: runCatching { EnvironmentUtils.getAdbTcpPort() }
                                .getOrNull()?.takeIf { it in 1..65535 }
                            ?: detectedPort
                        if (freshPort in 1..65535) {
                            addOutputLine("正在重试连接 $freshPort...")
                            startAdbConnection("127.0.0.1", freshPort)
                        }
                    }
                }
            } else {
                val connectIndex = _steps.value.indexOfFirst { it.title == "连接 ADB 服务" }
                setError(error, if (connectIndex >= 0) connectIndex else _currentStepIndex.value)
            }
        }
    }

    // ---------- wait for binder ----------

    private fun waitForService() {
        // 90bf：轮询改 IO 协程——此前在主线程每 500ms 同步 Binder ping（isRunningCompat）
        // + 每 1.5s requestBinderForNonProviderProcess + linServerProcessAlive 同步 exec，
        // 服务端启动瞬间高负载（模块重启/Binder 全量补发/FileObserver 风暴）时每个调用
        // 变慢，主线程被反复阻塞超过 5s → 系统弹「Lin-Shizuku 应用无响应（等待/继续）」。
        // setSuccess/setError 内部自带 viewModelScope.launch 回主线程，从 IO 调用安全。
        viewModelScope.launch(Dispatchers.IO) {
            val binderIndex = _steps.value.indexOfFirst { it.title == "等待 Binder 响应" }
            var binderReceived = false
            val listener = object : Shizuku.OnBinderReceivedListener {
                override fun onBinderReceived() {
                    if (!binderReceived) {
                        binderReceived = true
                        Shizuku.removeBinderReceivedListener(this)
                        setSuccess()
                    }
                }
            }
            Shizuku.addBinderReceivedListener(listener)

            if (moe.shizuku.privileged.api.core.ShizukuManager.isRunningCompat()) {
                binderReceived = true
                Shizuku.removeBinderReceivedListener(listener)
                setSuccess()
                return@launch
            }

            // 90as：服务端 app_process（Lin_server）已启动但主进程未桥接 Binder 时，
            // 只被动等监听/轮询永远等不到（Lin/Shizuku 单例仍为 null）→ 15s 必超时误报。
            // 先主动拉一次，再在轮询中周期重拉（服务端 Binder 注册是异步的，注册后再拉才成功）。
            runCatching {
                lin.LinProvider.requestBinderForNonProviderProcess(context)
            }

            val maxWaitTime = 45_000L
            val checkInterval = 500L
            var elapsed = 0L
            var lastPullAt = 0L
            while (elapsed < maxWaitTime && !binderReceived) {
                delay(checkInterval.milliseconds)
                elapsed += checkInterval

                if (!binderReceived && moe.shizuku.privileged.api.core.ShizukuManager.isRunningCompat()) {
                    binderReceived = true
                    Shizuku.removeBinderReceivedListener(listener)
                    setSuccess()
                    return@launch
                }

                if (hasErrorInOutput()) {
                    Shizuku.removeBinderReceivedListener(listener)
                    setError(Exception(getLastErrorMessage()), if (binderIndex >= 0) binderIndex else _currentStepIndex.value)
                    return@launch
                }

                // 周期主动拉取：服务端 Binder 注册完成后下一次拉取立即桥接成功
                if (elapsed - lastPullAt >= 1500) {
                    lastPullAt = elapsed
                    runCatching {
                        lin.LinProvider.requestBinderForNonProviderProcess(context)
                    }
                }
            }

            if (!binderReceived) {
                // 90ba：服务进程已存在但 Binder 未桥接（典型：provider 进程被系统
                // 杀掉重建后 Binder 丢失）→ 不立即报超时：再给 15 秒等服务端
                // Binder 心跳重发（LinService.startBinderResendLoop 每 15s 一次）
                // + 主动拉取，仍失败才报错并给出可操作提示。
                if (linServerProcessAlive()) {
                    val graceDeadline = System.currentTimeMillis() + 15_000L
                    while (System.currentTimeMillis() < graceDeadline && !binderReceived) {
                        delay(checkInterval.milliseconds)
                        if (!binderReceived && moe.shizuku.privileged.api.core.ShizukuManager.isRunningCompat()) {
                            binderReceived = true
                            break
                        }
                        runCatching {
                            lin.LinProvider.requestBinderForNonProviderProcess(context)
                        }
                    }
                }
                if (!binderReceived) {
                    Shizuku.removeBinderReceivedListener(listener)
                    val msg = if (linServerProcessAlive()) {
                        "服务进程已运行但 Binder 桥接暂未就绪，请重试或稍后再试（无需重新配对）"
                    } else {
                        "等待服务启动超时"
                    }
                    setError(Exception(msg), if (binderIndex >= 0) binderIndex else _currentStepIndex.value)
                } else {
                    Shizuku.removeBinderReceivedListener(listener)
                    setSuccess()
                }
            }
        }
    }

    /** 90ba：检测 Lin_server 服务进程是否存活（进程在但 Binder 未桥接的判据）。 */
    private fun linServerProcessAlive(): Boolean = runCatching {
        val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", "pidof Lin_server 2>/dev/null || echo ''"))
        val out = proc.inputStream.bufferedReader().readText().trim()
        proc.waitFor()
        out.isNotEmpty()
    }.getOrDefault(false)

    private fun hasErrorInOutput(): Boolean = _outputLines.value.any {
        it.contains("错误：") || it.contains("Error:") ||
            it.contains("Exception") || it.contains("FATAL") || it.contains("fatal:")
    }

    private fun getLastErrorMessage(): String {
        val errorLines = _outputLines.value.filter {
            it.contains("错误：") || it.contains("Error:")
        }
        return if (errorLines.isNotEmpty()) {
            errorLines.last()
                .substringAfter("错误：")
                .substringAfter("Error:")
                .trim()
        } else {
            "启动失败，请查看日志"
        }
    }

    fun retry() {
        viewModelScope.launch {
            _outputLines.value = emptyList()
            _errorMessage.value = null
            _isCompleted.value = false
            initializeSteps()
            delay(500.milliseconds)
            startProcess()
        }
    }

    // ---------- re-pair / re-start after success ----------

    /** 重置配对/启动全状态（供「重新配对」「重新启动」复用）。 */
    private fun resetState() {
        updateDetectedPort(0)
        // 1.1.2（重新配对失效修复）：mDNS 发现必须一起重置。
        // 之前只清 UI 状态，adbMdns 实例还活着，而 startMdnsPairingDiscovery()
        // 第一行就是 `if (adbMdns != null) return`——重新配对时不会重建监听，
        // 表现为「重新配对后不再自动搜索配对服务」。mdnsPairPort 同样会残留，
        // 于是先拿上一次的旧端口去配对（日志里连续 40787 / 43857 / 33273 三次尝试）。
        runCatching { adbMdns?.destroy() }
        adbMdns = null
        mdnsPairPort = 0
        // 配对成功时 AdbPairingService 会被 stop（通知撤掉），重新配对要重新拉起来，
        // 否则通知栏收配对码这条通道再也不会出现。
        runCatching { AdbPairingService.stop(context) }
        pairingPhase = PairingPhase.NONE
        _steps.value = emptyList()
        _outputLines.value = emptyList()
        _pairError.value = null
        _pairCode.value = ""
        _pairingPort.value = ""
        _pairingNow.value = false
        _errorMessage.value = null
        _isCompleted.value = false
        _currentStepIndex.value = 0
    }

    /** 服务已启动后重新执行完整启动流程（重新检测 → 连接 → 启动）。 */
    fun restartService() {
        viewModelScope.launch {
            resetState()
            initializeSteps()
            delay(300.milliseconds)
            startProcess()
        }
    }

    /** 90bc：「重新启动」改为直接执行 start.sh（先启后停，重启服务端进程），
     *  输出实时流入启动日志区；完成后刷新服务状态并广播 UI 联动。 */
    fun restartViaScript() {
        viewModelScope.launch {
            _errorMessage.value = null
            _outputLines.value = emptyList()
            _isCompleted.value = false
            // 90bc+：重新启动只跑脚本，用与脚本实际步骤一致的三步展示。
            // 之前不重置步骤，会沿用上一次无线调试流程残留的 9 个绿色步骤，
            // 出现「9/9 全部完成 + 启动失败」或「9/9 + 脚本执行超时」的自相矛盾界面。
            val localSteps = mutableListOf(
                step("执行启动脚本", Icons.Filled.RocketLaunch, "正在重启服务端进程..."),
                step("等待 Binder 响应", Icons.Filled.Refresh, "等待服务端就绪"),
                step("启动完成", Icons.Filled.Check, "等待完成")
            )
            localSteps[0] = localSteps[0].copy(status = StepStatus.RUNNING)
            _steps.value = localSteps
            _currentStepIndex.value = 0

            addOutputLine("执行启动脚本（先启后停，重启服务端进程）...")
            val cmd = Starter.getSdcardCommand(context)
            val result = ShizukuManager.runCommandStreamingEx(
                cmd,
                identity = Prefs.execIdentity,
                onLine = { line, isErr ->
                    addOutputLine(if (isErr) "[错误] $line" else line)
                },
                timeoutMs = 45_000
            )
            if (!result.finished) {
                // 45 秒没有新输出不等于失败：脚本可能正在等旧进程退出/新进程 fork，
                // 服务端是否可用由下面的 Binder 探测说了算，这里只作提示。
                addOutputLine("脚本 45 秒无新输出，继续等待服务端 Binder 就绪...")
            } else {
                addOutputLine("脚本执行完成（退出码 ${result.exitCode ?: 0}）")
            }
            runCatching { ShizukuManager.notifyServiceStateChanged(context) }

            // 脚本只验证「进程出现过」，而 Lin_server 的 binder 是 app_process 起 JVM
            // 之后才 publish 的（实测 1~8 秒）。脚本退出码 0 时立刻 isRunningCompat()
            // 必然拿到 false，之前就是这样误报「服务未就绪」。
            val currentSteps = _steps.value.toMutableList()
            if (currentSteps.size >= 2) {
                currentSteps[0] = currentSteps[0].copy(status = StepStatus.COMPLETED, description = "完成")
                currentSteps[1] = currentSteps[1].copy(status = StepStatus.RUNNING, description = "等待响应")
                _steps.value = currentSteps
            }
            val ready = ShizukuManager.waitForLinBinder(12_000) || ShizukuManager.isRunningCompat()
            if (ready) {
                setSuccess()
            } else {
                val stepsNow = _steps.value.toMutableList()
                if (stepsNow.size >= 2) {
                    stepsNow[1] = stepsNow[1].copy(status = StepStatus.ERROR, description = "Binder 未就绪")
                    _steps.value = stepsNow
                }
                _errorMessage.value = "服务脚本已执行，但服务端 Binder 12 秒内未就绪，请查看启动日志"
            }
        }
    }

    /** 服务已启动后重新配对（强制走配对流程，适用于换设备/换电脑/配对失效）。 */
    fun repairPairing() {
        viewModelScope.launch {
            // 90az：用户主动重新配对时清除持久配对标记（配对证据以最新配对结果为准）
            runCatching {
                context.getSharedPreferences("pair_state", Context.MODE_PRIVATE)
                    .edit().remove("pair_success").apply()
            }
            resetState()
            initializeSteps()
            delay(300.milliseconds)
            startDetection(forcePairing = true)
        }
    }

    override fun onCleared() {
        super.onCleared()
        runCatching { context.unregisterReceiver(pairSuccessReceiver) }
        runCatching { context.unregisterReceiver(autoPairCodeReceiver) }
        // v4.5：配对页销毁 → 停止 mDNS 发现（防泄漏）
        runCatching { adbMdns?.destroy() }
        adbMdns = null
        // 修复：配对页销毁时停止 AdbPairingService 前台服务，
        // 避免「已找到配对服务」通知常驻通知中心
        runCatching { AdbPairingService.stop(context) }
    }
}
