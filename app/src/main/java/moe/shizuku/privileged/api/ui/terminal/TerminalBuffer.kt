package moe.shizuku.privileged.api.ui.terminal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.shizuku.privileged.api.core.TaskState

data class TerminalLine(val text: String, val isError: Boolean = false)

/** 最近一次执行结果：供结果状态条展示（状态 / 退出码 / 耗时 / 命令）。 */
data class ExecResult(
    val status: TaskState,
    val exitCode: Int?,
    val elapsedMs: Long,
    val cmd: String
)

/**
 * 终端全局缓冲：内容与执行状态保存在进程级单例中，
 * 页面切换不销毁；应用进程完全退出才自然清空；手动清空走 [clear]。
 *
 * P0-2 防丢帧：高频输出不再逐行触发重组——输出先写入 [backing] 数据源
 * （不触发 Compose 通知），再由 [syncJob] 以 100ms 节流批量同步到
 * [lines] 快照，单次批量通知重组，主线程布局负担大幅下降。
 */
object TerminalBuffer {
    /** 数据源：高频写入目标，不触发 Compose 重组。 */
    private val backing = ArrayList<TerminalLine>(MAX_LINES + 64)

    /** UI 快照：由 [syncJob] 节流同步（默认 100ms 一次批量通知）。 */
    val lines = mutableStateListOf<TerminalLine>()

    /** 是否有命令正在执行（跨页面保留）。 */
    var running by mutableStateOf(false)

    /** 当前在前台执行的后台任务 id（1.1.5），供「后台运行 / 停止运行」定位。 */
    var currentTaskId: Long? = null

    /** 最近一次执行结果；运行中为 null，完成后写入结果状态条。 */
    var lastResult by mutableStateOf<ExecResult?>(null)

    /** 停止回调：由执行侧注册，点停止按钮时调用，destroy 当前进程。 */
    var onStop: (() -> Unit)? = null

    /** 执行模式 0=排队 时的等待队列：cmd + 执行身份，上一条执行完自动取队首执行（跨页面保留）。 */
    val pendingQueue = ArrayDeque<Pair<String, moe.shizuku.privileged.api.core.ShizukuManager.ExecIdentity>>()

    /** 命令执行协程使用进程级作用域，切换页面不执行中断。 */
    val execScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 快照同步任务（节流防抖：高频 append 期间只启动一个）。 */
    private var syncJob: Job? = null

    fun appendLine(text: String, isError: Boolean = false) {
        synchronized(backing) {
            backing.add(TerminalLine(text, isError))
            // 达到 2 倍上限才批量裁剪一次（低频，重建一次避免逐行 removeAt(0)）
            if (backing.size > MAX_LINES * 2) {
                val keep = backing.subList(backing.size - MAX_LINES, backing.size).toList()
                backing.clear()
                backing.addAll(keep)
            }
        }
        scheduleSync()
    }

    /** 手动清空：同时清数据源与 UI 快照。 */
    fun clear() {
        syncJob?.cancel()
        synchronized(backing) { backing.clear() }
        lines.clear()
    }

    /** 节流调度：一次 100ms 延迟批量同步，高频 append 期间只生效一次。 */
    private fun scheduleSync() {
        if (syncJob?.isActive == true) return
        syncJob = execScope.launch {
            delay(SYNC_INTERVAL_MS)
            flushToUi()
        }
    }

    /** 把数据源快照批量同步到 UI。 */
    private fun flushToUi() {
        val snapshot: List<TerminalLine>
        synchronized(backing) { snapshot = backing.toList() }

        if (lines.size > snapshot.size) {
            // 裁剪/清空后快照更短：整体替换（单次批量通知）
            lines.clear()
            lines.addAll(snapshot)
        } else {
            // 常规追加：只补增量
            for (i in lines.size until snapshot.size) {
                lines.add(snapshot[i])
            }
        }
    }

    private const val MAX_LINES = 2000
    private const val SYNC_INTERVAL_MS = 100L
}
