package com.watranscribe.engine

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.watranscribe.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

data class ReleaseInfo(
    val tagName: String,
    val versionName: String,
    val name: String,
    val body: String,
    val apkUrl: String,
    val apkSize: Long,
    val htmlUrl: String,
)

/**
 * Checks GitHub releases for new APK builds and installs them in-app.
 *
 * Expects each release to attach an `.apk` asset. Version comparison is a
 * dot-separated integer compare with a `v` prefix stripped — e.g. `v1.2.3`
 * beats `1.2` because `[1,2,3] > [1,2]`.
 */
@Singleton
class UpdateChecker @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val TAG = "UpdateChecker"
        private const val RELEASES_URL =
            "https://api.github.com/repos/thevolcanomanishere/voice-note-hater/releases/latest"
    }

    val currentVersion: String = BuildConfig.VERSION_NAME

    suspend fun fetchLatestRelease(): ReleaseInfo? = withContext(Dispatchers.IO) {
        val conn = (URL(RELEASES_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "voice-note-hater-android")
        }
        try {
            val code = conn.responseCode
            if (code == 404) {
                Log.w(TAG, "No releases published yet")
                return@withContext null
            }
            if (code !in 200..299) error("HTTP $code from GitHub releases API")
            val json = conn.inputStream.bufferedReader().use { it.readText() }
            parseRelease(JSONObject(json))
        } finally {
            conn.disconnect()
        }
    }

    private fun parseRelease(obj: JSONObject): ReleaseInfo? {
        val tag = obj.optString("tag_name").ifBlank { return null }
        val assets = obj.optJSONArray("assets") ?: return null
        var apkUrl: String? = null
        var apkSize = 0L
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.optString("name")
            if (name.endsWith(".apk", ignoreCase = true)) {
                apkUrl = asset.optString("browser_download_url")
                apkSize = asset.optLong("size", 0L)
                break
            }
        }
        if (apkUrl.isNullOrBlank()) {
            Log.w(TAG, "Release $tag has no .apk asset")
            return null
        }
        return ReleaseInfo(
            tagName = tag,
            versionName = tag.removePrefix("v").removePrefix("V"),
            name = obj.optString("name").ifBlank { tag },
            body = obj.optString("body"),
            apkUrl = apkUrl,
            apkSize = apkSize,
            htmlUrl = obj.optString("html_url"),
        )
    }

    fun isNewer(remote: String, local: String): Int {
        val r = remote.split(".", "-").mapNotNull { it.toIntOrNull() }
        val l = local.split(".", "-").mapNotNull { it.toIntOrNull() }
        val n = maxOf(r.size, l.size)
        for (i in 0 until n) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a.compareTo(b)
        }
        return 0
    }

    suspend fun downloadApk(
        release: ReleaseInfo,
        onProgress: (Float) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val dest = File(dir, "update-${release.tagName}.apk")
        val tmp = File(dir, dest.name + ".part")

        var current = release.apkUrl
        var redirects = 0
        while (true) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 60_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "voice-note-hater-android")
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location")
                    ?: error("Redirect without Location at $current")
                current = if (loc.startsWith("http")) loc else URL(URL(current), loc).toString()
                conn.disconnect()
                if (++redirects > 5) error("Too many redirects fetching ${release.apkUrl}")
                continue
            }
            if (code !in 200..299) {
                conn.disconnect()
                error("HTTP $code fetching $current")
            }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.apkSize
            conn.inputStream.buffered(16 * 1024).use { input ->
                tmp.outputStream().buffered(16 * 1024).use { output ->
                    var downloaded = 0L
                    val buffer = ByteArray(16 * 1024)
                    var lastUpdate = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > 200) {
                            val frac = if (total > 0) {
                                (downloaded.toFloat() / total).coerceIn(0f, 1f)
                            } else 0f
                            onProgress(frac)
                            lastUpdate = now
                        }
                    }
                }
            }
            conn.disconnect()
            break
        }
        if (!tmp.renameTo(dest)) error("Could not finalize APK at ${dest.absolutePath}")
        onProgress(1f)
        dest
    }

    fun canRequestInstall(): Boolean =
        context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings() {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun launchInstall(apk: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }
}
