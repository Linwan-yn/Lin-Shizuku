package moe.shizuku.privileged.api.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * GitHub Release 检查工具：支持直连 + 国内代理兜底，能检测预发布版本。
 * 内置下载器，不依赖系统 DownloadManager。
 * 下载到应用专属外部目录，避免 Scoped Storage 权限问题。
 */
object UpdateChecker {

    /**
     * 数值化版本比较（70 轮）：a > b 返回 1，a < b 返回 -1，相等返回 0。
     * 用于「云端 tag vs 本地版本」，避免仅做「!=」比较导致本地比云端新时误报更新。
     */
    fun compareVersion(a: String, b: String): Int {
        val pa = a.trim().split(".").map { it.toIntOrNull() ?: 0 }
        val pb = b.trim().split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return if (x > y) 1 else -1
        }
        return 0
    }

    data class ReleaseInfo(
        val tag: String,
        val publishedAt: String,
        val htmlUrl: String,
        val apkUrl: String,
        val body: String = ""
    )

    @Volatile
    var progress: Int = -1
        private set

    @Volatile
    var isDownloading: Boolean = false
        private set

    @Volatile
    var downloadFailed: Boolean = false

    @Volatile
    var downloadCompleted: Boolean = false

    @Volatile
    var errorMessage: String = ""
        private set

    @Volatile
    var installedFile: File? = null

    private val apiEndpoints = listOf(
        "https://api.github.com/repos/Linwan-yn/Lin-Shizuku/releases?per_page=30",
        "https://gh-proxy.com/https://api.github.com/repos/Linwan-yn/Lin-Shizuku/releases?per_page=30",
        "https://ghproxy.net/https://api.github.com/repos/Linwan-yn/Lin-Shizuku/releases?per_page=30",
        "https://mirror.ghproxy.com/https://api.github.com/repos/Linwan-yn/Lin-Shizuku/releases?per_page=30",
        "https://ghps.cc/https://api.github.com/repos/Linwan-yn/Lin-Shizuku/releases?per_page=30",
        "https://gh.api.99988866.xyz/https://api.github.com/repos/Linwan-yn/Lin-Shizuku/releases?per_page=30"
    )

    /** 拉取全部历史版本（非 draft、含 APK 附件），按发布时间倒序，最新在前。 */
    fun fetchReleases(): List<ReleaseInfo> {
        var lastError: Exception? = null
        for (endpoint in apiEndpoints) {
            try {
                val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5_000
                    readTimeout = 5_000
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/vnd.github.v3+json")
                    setRequestProperty("User-Agent", "Lin-Shizuku-App")
                }
                val code = conn.responseCode
                val body = if (code in 200..299) conn.inputStream.bufferedReader().readText()
                else conn.errorStream?.bufferedReader()?.readText() ?: ""
                conn.disconnect()
                if (body.isBlank()) continue
                val arr = JSONArray(body)
                if (arr.length() == 0) continue
                val out = mutableListOf<ReleaseInfo>()
                for (i in 0 until arr.length()) {
                    val json = arr.getJSONObject(i)
                    if (json.optBoolean("draft", false)) continue
                    val tag = json.optString("tag_name", "")
                    val pub = json.optString("published_at", "")
                    val html = json.optString("html_url", "https://github.com/Linwan-yn/Lin-Shizuku/releases/latest")
                    var apkUrl = ""
                    val assets = json.optJSONArray("assets")
                    if (assets != null) {
                        for (j in 0 until assets.length()) {
                            val a = assets.getJSONObject(j)
                            if (a.optString("name", "").endsWith(".apk")) {
                                apkUrl = a.optString("browser_download_url", "")
                                break
                            }
                        }
                    }
                    if (tag.isNotBlank() && apkUrl.isNotBlank()) {
                        out.add(ReleaseInfo(tag, pub, html, apkUrl, json.optString("body", "")))
                    }
                }
                if (out.isNotEmpty()) return out
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: RuntimeException("所有更新源均不可用")
    }

    /** 最新版本 = fetchReleases 的第一个（GitHub API 按发布时间倒序）。 */
    fun fetchLatest(): ReleaseInfo =
        fetchReleases().firstOrNull() ?: throw RuntimeException("仓库暂无可用版本")

    /** 应用专属下载目录 */
    private fun getDownloadDir(context: Context): File =
        File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "apks").apply { mkdirs() }

    // P1-2 修复：校验 APK 签名与当前已安装版本一致。
    // 任何代理/镜像都是中间人，仅校验 PK 头（2 字节）无法防投毒；
    // 签名一致才是"官方同源"的可信证据，不一致直接拒绝安装。
    @Suppress("DEPRECATION")
    private fun verifyApkSignature(context: Context, file: File): Boolean {
        return try {
            val pm = context.packageManager
            val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                ?: return false
            val installedSigs = installed.signatures?.map { it.toByteArray().sha256() } ?: return false
            if (installedSigs.isEmpty()) return false
            val archive = pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNATURES)
                ?: return false
            val archiveSigs = archive.signatures?.map { it.toByteArray().sha256() } ?: return false
            archiveSigs.any { it in installedSigs }
        } catch (_: Throwable) {
            false
        }
    }

    private fun ByteArray.sha256(): String {
        return try {
            MessageDigest.getInstance("SHA-256").digest(this)
                .joinToString("") { "%02x".format(it) }
        } catch (_: Throwable) {
            ""
        }
    }

    /** 检查本地是否已有该版本的 APK（应用专属目录，始终可读） */
    fun checkExistingApk(context: Context, apkUrl: String): File? {
        val fileName = apkUrl.substringAfterLast('/').substringBefore('?').ifBlank { return null }
        val file = File(getDownloadDir(context), fileName)
        if (!file.exists() || file.length() < 100_000) return null
        return try {
            file.inputStream().use {
                val b1 = it.read()
                val b2 = it.read()
                if (b1 == 0x50 && b2 == 0x4B && verifyApkSignature(context, file)) file else null
            }
        } catch (_: Exception) { null }
    }

    suspend fun downloadAndInstall(activity: Activity, apkUrl: String, tag: String, onSuccess: () -> Unit = {}) = withContext(Dispatchers.IO) {
        downloadFailed = false
        downloadCompleted = false
        installedFile = null
        errorMessage = ""
        val urls = listOf(
            apkUrl, // P1-2 修复：官方 GitHub 直连优先，代理仅作兜底
            "https://gh-proxy.com/$apkUrl",
            "https://ghproxy.net/$apkUrl",
            "https://mirror.ghproxy.com/$apkUrl",
            "https://ghps.cc/$apkUrl",
            "https://gh.api.99988866.xyz/$apkUrl"
        ).filter { it.startsWith("https://") } // P1-2 修复：仅允许 HTTPS，杜绝明文投毒
        val downloadDir = getDownloadDir(activity)
        val fileName = apkUrl.substringAfterLast('/').substringBefore('?').ifBlank { "lin-shizuku-update.apk" }
        val file = File(downloadDir, fileName)

        for (downloadUrl in urls) {
            try {
                isDownloading = true
                downloadFailed = false
                progress = -2
                if (file.exists()) file.delete()

                val conn = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8_000
                    readTimeout = 20_000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                    setRequestProperty("Accept", "application/vnd.android.package-archive, application/octet-stream, */*")
                    instanceFollowRedirects = true
                }
                val code = conn.responseCode
                if (code !in 200..299) {
                    errorMessage = "HTTP $code: $downloadUrl"
                    conn.disconnect()
                    continue
                }
                progress = 0
                val total = conn.contentLengthLong
                var downloaded = 0L
                val tmpFile = File(downloadDir, "$fileName.tmp")
                if (tmpFile.exists()) tmpFile.delete()
                conn.inputStream.use { input ->
                    tmpFile.outputStream().use { output ->
                        val buf = ByteArray(8192)
                        var n: Int
                        while (input.read(buf).also { n = it } != -1) {
                            output.write(buf, 0, n)
                            downloaded += n
                            if (total > 0) {
                                progress = ((downloaded * 100) / total).toInt()
                            }
                        }
                    }
                }
                conn.disconnect()
                if (tmpFile.exists() && tmpFile.length() > 100_000) {
                    val validApk = try {
                        tmpFile.inputStream().use {
                            val b1 = it.read()
                            val b2 = it.read()
                            b1 == 0x50 && b2 == 0x4B
                        }
                    } catch (_: Exception) { false }
                    if (!validApk) {
                        errorMessage = "下载的文件不是有效的 APK"
                        if (tmpFile.exists()) tmpFile.delete()
                        continue
                    }
                    // P1-2 修复：签名完整性校验——与已安装版本签名一致才允许安装。
                    // 校验失败说明文件被劫持/投毒（非官方同源），直接终止，不再尝试其他代理。
                    if (!verifyApkSignature(activity, tmpFile)) {
                        errorMessage = "下载的 APK 签名与当前版本不一致，已拒绝安装（可能被劫持/投毒）"
                        if (tmpFile.exists()) tmpFile.delete()
                        isDownloading = false
                        progress = -1
                        downloadFailed = true
                        return@withContext
                    }
                    if (file.exists()) file.delete()
                    tmpFile.renameTo(file)
                    installedFile = file
                    downloadCompleted = true
                    isDownloading = false
                    progress = -1
                    onSuccess()
                    return@withContext
                } else {
                    errorMessage = "文件过小 (${tmpFile.length()} bytes): $downloadUrl"
                    if (tmpFile.exists()) tmpFile.delete()
                }
            } catch (e: Exception) {
                errorMessage = e.javaClass.simpleName + ": " + (e.message ?: "")
                continue
            }
        }
        isDownloading = false
        progress = -1
        downloadFailed = true
    }

    fun canInstallPackages(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun requestInstallPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${activity.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        }
    }

    private fun tryInstall(activity: Activity, file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                activity, "${activity.packageName}.fileprovider", file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        } catch (_: Exception) {}
    }

    fun installApk(activity: Activity): Boolean {
        val file = installedFile ?: return false
        if (!canInstallPackages(activity)) {
            requestInstallPermission(activity)
            return false
        }
        tryInstall(activity, file)
        return true
    }
}
