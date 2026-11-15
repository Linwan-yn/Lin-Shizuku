package moe.shizuku.privileged.api.core

/**
 * 日志诊断工具：错误/警告行判定（模块页日志中心 / 服务日志页 / 启动日志区共用），
 * 让所有日志入口用同一套判定标准，方便定位问题。
 */
object LogDiagnostics {

    /** 错误行判定：命中任一关键词视为错误行（宽松匹配，用于定位问题）。 */
    fun isErrLine(line: String): Boolean {
        val l = line.lowercase()
        return l.contains("error") || l.contains("failed") || l.contains("失败") ||
            l.contains("错误") || l.contains("exception") || l.contains("denied") ||
            l.contains("拒绝") || l.contains("超时") || l.contains("异常") ||
            l.contains("crash") || l.contains("fatal") || l.contains("not found") ||
            l.contains("无法") || l.contains("找不到")
    }

    /** 警告行判定（次一级信号）。 */
    fun isWarnLine(line: String): Boolean {
        val l = line.lowercase()
        return l.contains("warn") || l.contains("警告") || l.contains("注意") ||
            l.contains("retry") || l.contains("重试") || l.contains("降级")
    }

    /** 从多行文本提取所有错误行。 */
    fun errLines(content: String): List<String> = content.lines().filter { isErrLine(it) }

    /** 统计文本中错误行 / 警告行数量。 */
    fun stats(content: String): Pair<Int, Int> {
        var err = 0
        var warn = 0
        content.lines().forEach { line ->
            if (isErrLine(line)) err++
            else if (isWarnLine(line)) warn++
        }
        return err to warn
    }
}
