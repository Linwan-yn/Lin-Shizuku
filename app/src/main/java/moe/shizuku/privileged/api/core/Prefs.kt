package moe.shizuku.privileged.api.core

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private const val FILE = "lin_shizuku_prefs"
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        // 1.1.2：direct boot 阶段 getSharedPreferences 会抛异常，
        // 不能吞掉——吞掉后 sp 一直未初始化，后续访问直接 crash。
        // 这里只在成功时赋值，失败时保持 lateinit 未初始化状态，
        // 由调用方（Service.onCreate 等）决定是否降级。
        sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    /** 是否已初始化（未初始化时访问 sp 会 crash，调用方应先检查）。 */
    val isReady: Boolean
        get() = ::sp.isInitialized

    var dynamicColor: Boolean
        get() = sp.getBoolean("dynamic_color", false)
        set(v) = sp.edit().putBoolean("dynamic_color", v).apply()

    var amoled: Boolean
        get() = sp.getBoolean("amoled", false)
        set(v) = sp.edit().putBoolean("amoled", v).apply()

    var darkMode: Boolean
        get() = sp.getBoolean("night", false)
        set(v) = sp.edit().putBoolean("night", v).apply()

    var hideRecents: Boolean
        get() = sp.getBoolean("hide_bg", false)
        set(v) = sp.edit().putBoolean("hide_bg", v).apply()

    /** 底部导航材质五选一：0=普通 1=穿透玻璃 2=高斯模糊 3=毛玻璃 4=液态玻璃。默认液态玻璃。 */
    var glassMode: Int
        get() {
            // 兼容迁移：旧版是布尔开关（glass），开启→液态玻璃(4)，关闭→普通(0)
            if (!sp.contains("glass_mode")) {
                return if (sp.getBoolean("glass", true)) 4 else 0
            }
            val v = sp.getInt("glass_mode", 4)
            // 旧档位 → 新版五档(0普通/1穿透/2高斯模糊/3毛玻璃/4液态)：
            // 旧1毛玻璃→新3；旧2(四档前液态)→4；旧3(四档液态)→4；新值(≥4)原样保留
            return when (v) {
                0 -> 0
                1 -> 3
                2 -> 4
                3 -> 4
                else -> v
            }
        }
        set(v) = sp.edit().putInt("glass_mode", v).apply()

    var startOnBoot: Boolean
        get() = sp.getBoolean("start_boot", false)
        set(v) = sp.edit().putBoolean("start_boot", v).apply()

    /** 开机启动模式：关闭 / 开机广播 / 预热 / Root脚本。
     *  "开机广播" 等价于旧的 startOnBoot=true。 */
    var bootMode: String
        get() = sp.getString("boot_mode", "关闭") ?: "关闭"
        set(v) {
            sp.edit().putString("boot_mode", v).apply()
            // 兼容旧逻辑：开机广播模式视为 startOnBoot 开启
            startOnBoot = (v == "开机广播")
        }

    /** 无障碍自启：通过无障碍服务唤醒开机广播。90bb：默认开启（强化保活——系统强杀
     *  无障碍后由 15s 心跳自动回写，accessibilityBoot 控制开机唤醒序列）。 */
    var accessibilityBoot: Boolean
        get() = sp.getBoolean("access_boot", true)
        set(v) = sp.edit().putBoolean("access_boot", v).apply()

    /** 最近一次开机自动启动结果（90l 轮新增）：时间 + 结果摘要，设置页展示，避免"静默空壳"。 */
    var lastBootResult: String
        get() = sp.getString("last_boot_result", "") ?: ""
        set(v) = sp.edit().putString("last_boot_result", v).apply()

    /** Shizuku 兼容层：允许应用通过 Lin-Shizuku 服务运行。 */
    var shizukuCompat: Boolean
        get() = sp.getBoolean("shizuku_compat", true)
        set(v) = sp.edit().putBoolean("shizuku_compat", v).apply()

    /** 无线调试提权 su：尝试通过无线调试使用 su 提权启动服务。 */
    var wirelessSu: Boolean
        get() = sp.getBoolean("wireless_su", false)
        set(v) = sp.edit().putBoolean("wireless_su", v).apply()

    /** 降权激活：Root 启动后降权到 Shell 用户运行。 */
    var dropPriv: Boolean
        get() = sp.getBoolean("drop_priv", false)
        set(v) = sp.edit().putBoolean("drop_priv", v).apply()

    /** 进程守护：双进程互守，自动重启。 */
    var processGuard: Boolean
        get() = sp.getBoolean("process_guard", false)
        set(v) = sp.edit().putBoolean("process_guard", v).apply()

    /** 命令执行身份（68 轮）：0=跟随服务 1=Shell(com.android.shell) 2=Root。默认跟随服务。 */
    var execIdentity: moe.shizuku.privileged.api.core.ShizukuManager.ExecIdentity
        get() = moe.shizuku.privileged.api.core.ShizukuManager.ExecIdentity.fromKey(
            sp.getInt("exec_identity", 0)
        )
        set(v) = sp.edit().putInt("exec_identity", v.key).apply()

    var highRefresh: Boolean
        get() = sp.getBoolean("high_refresh", false)
        set(v) = sp.edit().putBoolean("high_refresh", v).apply()

    /** 高刷档位：0=设备最高 1=120Hz 2=90Hz 3=60Hz。 */
    var highRefreshLevel: Int
        get() = sp.getInt("high_refresh_level", 0)
        set(v) = sp.edit().putInt("high_refresh_level", v).apply()

    /** Hyper OS 专区：游戏启动动画加速（debug.game.video.* setprop，小米/红米三倍速进游戏）。
     *  true=开（setprop ... true），false=关（setprop ... false）。 */
    var gameBoostAccel: Boolean
        get() = sp.getBoolean("game_boost_accel", false)
        set(v) = sp.edit().putBoolean("game_boost_accel", v).apply()

    /** HyperOS 专区：触摸预加载（settings put system touchprestartoptconfig）。
     *  true=开启（featureDisable:true），false=关闭（featureDisable:false）。 */
    var touchPreload: Boolean
        get() = sp.getBoolean("touch_preload", false)
        set(v) = sp.edit().putBoolean("touch_preload", v).apply()

    /** HyperOS 专区：隐藏手势提示线（settings put global hide_gesture_line）。
     *  true=隐藏（值 1），false=显示（值 0）。 */
    var hideGestureLine: Boolean
        get() = sp.getBoolean("hide_gesture_line", false)
        set(v) = sp.edit().putBoolean("hide_gesture_line", v).apply()

    /** HyperOS 专区：最近任务样式（settings put global task_stack_view_layout_style）。
     *  0=纵向排布 1=横向排布 2=堆叠后台 3=堆叠后台。 */
    var recentsStyle: Int
        get() = sp.getInt("recents_style", 0)
        set(v) = sp.edit().putInt("recents_style", v).apply()

    /** ColorOS 专区：AI电池助手（settings put global oplus_battery_agent_debug_force_entry）。
     *  true=强开（值 1），false=关（值 0）。 */
    var aiBatteryAgent: Boolean
        get() = sp.getBoolean("ai_battery_agent", false)
        set(v) = sp.edit().putBoolean("ai_battery_agent", v).apply()

    /** 模块页上次停留的 Tab（0=脚本 1=模块 2=推荐 3=日志），切走再回来保留。 */
    var modulesTab: Int
        get() = sp.getInt("modules_tab", 0)
        set(v) = sp.edit().putInt("modules_tab", v).apply()

    var wlanAutoStart: Boolean
        get() = sp.getBoolean("wlan_auto", false)
        set(v) = sp.edit().putBoolean("wlan_auto", v).apply()

    var darkModeOption: String
        get() = sp.getString("dark_option", "跟随系统") ?: "跟随系统"
        set(v) = sp.edit().putString("dark_option", v).apply()

    /** TCP 模式：无线调试启动成功后把 adbd 切换到固定 TCP 端口（thedjchi 特性）。
     *  v5.3 实测（04:42）+ Stellar 对照（18:01/18:02）：无线调试（Android 11+）下
     *  执行 `adb tcpip:<port>` 会重启 adbd、释放动态连接端口（实测 36859→45013），
     *  且等待切换端口可用常超时（Stellar「等待ADB端口4303可用超时」）——TCP 模式在
     *  无线调试下不可用。v5.3 起切换已在 AdbWirelessHelper 内禁用（参数保留不生效）；
     *  v5.5 默认值改为关闭，避免误导。保留开关仅为兼容旧用户配置与「关闭时恢复 adbd」。
     */
    var tcpMode: Boolean
        get() = sp.getBoolean("tcp_mode", false)
        set(v) = sp.edit().putBoolean("tcp_mode", v).apply()

    /** TCP 模式固定端口（adb tcpip）。 */
    var tcpPort: Int
        get() = sp.getInt("tcp_port", 5555)
        set(v) = sp.edit().putInt("tcp_port", v).apply()

    /** 自动化配对（90ah）：无障碍服务读取系统「无线调试配对」弹窗的 6 位配对码并自动完成配对。默认开启。 */
    var autoPairEnabled: Boolean
        get() = sp.getBoolean("auto_pair_enabled", true)
        set(v) = sp.edit().putBoolean("auto_pair_enabled", v).apply()

    /** 无障碍服务提取到的待配对码（跨组件暂存；配对页消费后清空）。 */
    var pendingPairCode: String
        get() = sp.getString("pending_pair_code", "") ?: ""
        set(v) {
            sp.edit().putString("pending_pair_code", v).apply()
            // 同步记录写入时间戳：服务端早退检查按「60 秒内有效」判定，
            // 防配对页未打开时残留码永久阻塞后续抓取（1.1.1 v4.1 修复）
            sp.edit().putLong("pending_pair_time", System.currentTimeMillis()).apply()
        }

    /** 待配对码写入时间戳（v4.1）：用于服务端判定残留是否过期。 */
    var pendingPairTime: Long
        get() = sp.getLong("pending_pair_time", 0L)
        set(v) = sp.edit().putLong("pending_pair_time", v).apply()

    /** 1.1.1：无障碍服务从系统「无线调试配对」弹窗一并提取的配对端口
     *  （IP地址与端口 127.0.0.1:xxxxx 中的 xxxxx），跨组件暂存；配对页/通知栏消费后清空。
     *   无 mDNS 后端口需手动输入是体验倒退，此字段让「只填配对码」成为默认体验。 */
    var pendingPairPort: String
        get() = sp.getString("pending_pair_port", "") ?: ""
        set(v) = sp.edit().putString("pending_pair_port", v).apply()

    /** v4.1：一键清空待配对码/端口/时间戳（配对页消费完成或残留过期时调用）。 */
    fun clearPendingPair() {
        sp.edit()
            .putString("pending_pair_code", "")
            .putString("pending_pair_port", "")
            .putLong("pending_pair_time", 0L)
            .apply()
    }

    /** 1.0.11：全自动无线配对——常规启动失败后用 root 命令开 Wi-Fi/无线调试/拉起配对界面，无需触屏。 */
    var autoWirelessBoot: Boolean
        get() = sp.getBoolean("auto_wireless_boot", true)
        set(v) = sp.edit().putBoolean("auto_wireless_boot", v).apply()

    /** 崩溃自动恢复（watchdog）：服务进程异常退出时自动重新启动。 */
    var watchdog: Boolean
        get() = sp.getBoolean("watchdog", true)
        set(v) = sp.edit().putBoolean("watchdog", v).apply()

    /** 强力保活（76 轮）：闹钟心跳兜底 + 息屏持续检测 + 划掉任务后自动复活。
     *  90bb：默认开启——ColorOS 17 / 澎湃OS 会强杀无障碍与后台服务，保活是稳定运行前提。 */
    var strongKeepAlive: Boolean
        get() = sp.getBoolean("strong_keep_alive", true)
        set(v) = sp.edit().putBoolean("strong_keep_alive", v).apply()

    /** Scene/黑域自动激活（76 轮）：进入 Scene、黑域时弹通知，点击即可激活服务。 */
    var autoActivate: Boolean
        get() = sp.getBoolean("auto_activate", false)
        set(v) = sp.edit().putBoolean("auto_activate", v).apply()

    /** 自动开启 USB 调试（76 轮）：具备 Root/Shell 写权限时保持 adb_enabled 开启。 */
    var autoUsbDebug: Boolean
        get() = sp.getBoolean("auto_usb_debug", false)
        set(v) = sp.edit().putBoolean("auto_usb_debug", v).apply()

    /** 触感反馈全局开关：卡片按下、底栏点击、长按拖拽的震动。默认开启。 */
    var hapticEnabled: Boolean
        get() = sp.getBoolean("haptic_enabled", true)
        set(v) = sp.edit().putBoolean("haptic_enabled", v).apply()

    /** 终端输入模式：0=超长自动换行（多行输入），1=单行横向滚动跟随光标。 */
    var terminalInputMode: Int
        get() = sp.getInt("terminal_input_mode", 0)
        set(v) = sp.edit().putInt("terminal_input_mode", v).apply()

    /** 终端执行模式：0=排队（上一条执行完自动执行下一条），1=并发（立即另起执行），2=阻止（提示等待）。 */
    var terminalExecMode: Int
        get() = sp.getInt("terminal_exec_mode", 0)
        set(v) = sp.edit().putInt("terminal_exec_mode", v).apply()

    /** 终端历史命令：持久化、最新在前、去重、上限 50 条；点按回填输入框，可单条删除。 */
    var terminalHistory: List<String>
        get() = try {
            val raw = sp.getString("terminal_history", "[]") ?: "[]"
            com.google.gson.Gson().fromJson(raw, Array<String>::class.java)?.toList() ?: emptyList()
        } catch (_: Throwable) { emptyList() }
        set(v) = sp.edit().putString(
            "terminal_history",
            com.google.gson.Gson().toJson(v.take(MAX_TERMINAL_HISTORY))
        ).apply()

    private const val MAX_TERMINAL_HISTORY = 50
}
