package moe.shizuku.privileged.api.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import moe.shizuku.privileged.api.core.ShizukuManager.ExecIdentity
import moe.shizuku.server.IRemoteProcess

/**
 * 命令执行任务注册表（1.1.5）。
 *
 * 三条命令执行入口——终端页手敲、脚本运行、模块开关/动作——统一把进程句柄与
 * 输出缓冲挂到这里的任务条目上，进程级单例持有：页面切换、Activity 关闭都不取消执行协程，
 * 终端页任务面板集中查看/停止/回看。
 *
 * 句柄双持：兼容通道 [remote]（IRemoteProcess）与 Lin 直连通道 [proc]（Process），
 * 停止时两个都 destroy，补齐旧实现只能 destroy 兼容通道、destroy 不到 Lin 通道进程的缺口。
 */
enum class TaskState { RUNNING, FINISHED, STOPPED, FAILED, TIMEOUT }

data class TaskLine(val text: String, val isError: Boolean)

class CommandTask(
    val id: Long,
    /** 展示标题（命令摘要或脚本名）。 */
    val title: String,
    val command: String,
    /** 来源：终端 / 脚本 / 模块。 */
    val source: String,
    val identity: ExecIdentity,
    val startedAt: Long,
) {
    var state: TaskState by mutableStateOf(TaskState.RUNNING)
        internal set
    var exitCode: Int? by mutableStateOf(null)
        internal set

    /** 任务自有输出缓冲（UI 观察此快照实时回显）。 */
    val output = mutableStateListOf<TaskLine>()

    /** 已转入后台：离开前台界面后不再往原界面回显，但任务仍在运行。状态化以便面板即时重组。 */
    var detached: Boolean by mutableStateOf(false)
        internal set

    /** 兼容通道进程句柄。 */
    @Volatile internal var remote: IRemoteProcess? = null

    /** Lin 直连通道进程句柄。 */
    @Volatile internal var proc: Process? = null

    /** 是否由用户手动停止（区别于超时/失败）。 */
    @Volatile internal var stoppedByUser: Boolean = false

    /** 结束时间戳；任务进入终态时锚定，耗时据此固定，不再随当前时间增长。 */
    var finishedAt: Long? by mutableStateOf(null)
        internal set

    /** 命令原文摘要（截断）。 */
    val snippet: String get() = command.trim().replace(Regex("\\s+"), " ").take(60)

    fun elapsedMs(): Long = (finishedAt ?: System.currentTimeMillis()) - startedAt

    /** 挂接本次执行的双句柄（兼容通道 / Lin 直连通道）。 */
    fun attach(remote: IRemoteProcess?, proc: Process?) {
        this.remote = remote
        this.proc = proc
    }
}

object CommandTaskRegistry {
    private const val MAX_TASKS = 30
    private const val MAX_OUTPUT_LINES = 500

    /** 进程级执行作用域：绑定 SupervisorJob + Main，页面/Activity 销毁不取消协程。 */
    private val execScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Volatile
    private var nextSeq = 0L

    /** 全量任务（新任务在队首）。 */
    private val _tasks = mutableStateListOf<CommandTask>()
    val tasks: List<CommandTask> get() = _tasks

    fun runningTasks(): List<CommandTask> = _tasks.filter { it.state == TaskState.RUNNING }

    /** 登记一条任务（状态初始为 RUNNING）。 */
    fun start(title: String, command: String, source: String, identity: ExecIdentity): CommandTask {
        val seq = synchronized(_tasks) { ++nextSeq }
        val task = CommandTask(
            id = System.currentTimeMillis() * 1000 + seq % 1000,
            title = title.trim().ifBlank { command.trim().take(24) },
            command = command,
            source = source,
            identity = identity,
            startedAt = System.currentTimeMillis()
        )
        synchronized(_tasks) {
            _tasks.add(0, task)
            while (_tasks.size > MAX_TASKS) _tasks.removeAt(_tasks.size - 1)
        }
        return task
    }

    /**
     * 在进程级作用域内执行命令；输出同时写入任务缓冲与 [onFront] 前台回调。
     * 任务结束自动回写状态与退出码，并触发 [onComplete]（不依赖页面组合存活）。
     */
    fun execute(
        task: CommandTask,
        cmd: String,
        identity: ExecIdentity,
        onFront: (String, Boolean) -> Unit = { _, _ -> },
        onComplete: (CommandTask) -> Unit = {}
    ) {
        execScope.launch {
            try {
                val ok = ShizukuManager.runCommandStreamingEx(
                    cmd = cmd,
                    identity = identity,
                    task = task,
                    onLine = { line, isErr ->
                        append(task, line, isErr)
                        onFront(line, isErr)
                    },
                    timeoutMs = 300_000
                )
                finish(
                    task,
                    when {
                        task.stoppedByUser -> TaskState.STOPPED
                        !ok.finished -> TaskState.TIMEOUT
                        ok.exitCode != null && ok.exitCode != 0 -> TaskState.FAILED
                        else -> TaskState.FINISHED
                    },
                    ok.exitCode
                )
            } catch (t: Throwable) {
                append(task, "执行异常: ${t.message ?: t.javaClass.simpleName}", true)
                finish(task, TaskState.FAILED, null)
            }
            onComplete(task)
        }
    }

    fun append(task: CommandTask, line: String, isErr: Boolean) {
        task.output.add(TaskLine(line, isErr))
        if (task.output.size > MAX_OUTPUT_LINES) {
            repeat(task.output.size - MAX_OUTPUT_LINES) { task.output.removeAt(0) }
        }
    }

    fun finish(task: CommandTask, state: TaskState, exitCode: Int?) {
        releaseHandles(task)
        task.state = state
        task.exitCode = exitCode
        if (task.finishedAt == null) task.finishedAt = System.currentTimeMillis()
        // 任务结束（成功/失败/超时/停止）后即从后台任务列表移除，面板只保留运行中任务
        removeTask(task)
    }

    /** 手动停止一条运行中的指令：双句柄都 destroy，状态置 STOPPED。 */
    fun stop(taskId: Long) {
        val task = _tasks.firstOrNull { it.id == taskId } ?: return
        task.stoppedByUser = true
        releaseHandles(task)
        task.state = TaskState.STOPPED
        if (task.finishedAt == null) task.finishedAt = System.currentTimeMillis()
        removeTask(task)
    }

    private fun removeTask(task: CommandTask) {
        synchronized(_tasks) { _tasks.remove(task) }
    }

    /** 清除已结束（非 RUNNING）的历史任务。 */
    fun clearFinished() {
        synchronized(_tasks) {
            _tasks.removeAll { it.state != TaskState.RUNNING }
        }
    }

    private fun releaseHandles(task: CommandTask) {
        runCatching { task.remote?.destroy() }
        runCatching { task.proc?.destroy() }
        task.remote = null
        task.proc = null
    }
}
