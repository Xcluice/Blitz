package com.xcluice.blitz.engine

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class UpdateInfo(
    val version: String,
    val code: Int,
    val url: String,
    val notes: String,
    val fullSize: Long,
    val newSha: String?,
    val patchUrl: String?,
    val patchFromSha: String?,
    val patchSize: Long
)

class Downloaded(val file: File, val delta: Boolean)

/** Checks the project's latest GitHub release and installs it over the current app (same signing key). */
object Updater {
    private const val API = "https://api.github.com/repos/Xcluice/Blitz/releases/latest"

    @Suppress("DEPRECATION")
    fun currentCode(ctx: Context): Int = try {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else pi.versionCode
    } catch (_: Throwable) {
        0
    }

    fun currentName(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
    } catch (_: Throwable) {
        ""
    }

    /** Returns info when a newer release exists, otherwise null (also null when offline). */
    fun check(ctx: Context): UpdateInfo? {
        return try {
            val c = URL(API).openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "XcluiceBlitz")
            if (c.responseCode != 200) return null
            val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val tag = o.getString("tag_name")
            val code = tag.substringAfterLast('.').toIntOrNull() ?: return null
            if (code <= currentCode(ctx)) return null
            val assets = o.getJSONArray("assets")
            var url: String? = null
            var fullSize = 0L
            val urls = HashMap<String, String>()
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val name = a.getString("name")
                urls[name] = a.getString("browser_download_url")
                if (name.endsWith(".apk")) {
                    url = a.getString("browser_download_url")
                    fullSize = a.optLong("size", 0L)
                }
            }
            if (url == null) return null
            var newSha: String? = null
            var patchUrl: String? = null
            var patchFromSha: String? = null
            var patchSize = 0L
            val metaUrl = urls["update.json"]
            if (metaUrl != null) {
                try {
                    val m = JSONObject(httpText(metaUrl))
                    newSha = m.optString("sha256", "").ifEmpty { null }
                    val ps = m.optJSONArray("patches")
                    val have = currentCode(ctx)
                    if (ps != null) {
                        for (i in 0 until ps.length()) {
                            val pj = ps.getJSONObject(i)
                            if (pj.getInt("from") == have) {
                                patchUrl = urls[pj.getString("file")]
                                patchFromSha = pj.getString("fromSha")
                                patchSize = pj.optLong("size", 0L)
                            }
                        }
                    }
                } catch (_: Throwable) {
                }
            }
            UpdateInfo(tag, code, url, o.optString("body", ""), fullSize, newSha, patchUrl, patchFromSha, patchSize)
        } catch (_: Throwable) {
            null
        }
    }

    private fun httpText(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("User-Agent", "XcluiceBlitz")
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    private fun sha256(f: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Small download: only the changed bytes, applied to the installed APK. Null if anything doesn't check out. */
    private fun downloadDelta(ctx: Context, info: UpdateInfo, dir: File, progress: (Float) -> Unit): File? {
        val pu = info.patchUrl ?: return null
        val fromSha = info.patchFromSha ?: return null
        val newSha = info.newSha ?: return null
        return try {
            val installed = File(ctx.applicationInfo.sourceDir)
            if (sha256(installed) != fromSha) return null
            val patch = File(dir, "patch.bin")
            if (!fetch(pu, patch, progress)) return null
            val out = File(dir, "blitz-${info.code}.apk")
            BsPatch.apply(installed, patch, out)
            patch.delete()
            if (sha256(out) == newSha) out else {
                out.delete()
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** Downloads the update, trying the small delta first and falling back to the full APK. */
    fun download(ctx: Context, info: UpdateInfo, progress: (Float) -> Unit): Downloaded? {
        val dir = File(ctx.cacheDir, "updates")
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        val d = downloadDelta(ctx, info, dir, progress)
        if (d != null) return Downloaded(d, true)
        val out = File(dir, "blitz-${info.code}.apk")
        if (!fetch(info.url, out, progress)) return null
        val want = info.newSha
        if (want != null && sha256(out) != want) {
            out.delete()
            return null
        }
        return Downloaded(out, false)
    }

    private fun fetch(url: String, out: File, progress: (Float) -> Unit): Boolean {
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10000
            c.readTimeout = 15000
            c.setRequestProperty("User-Agent", "XcluiceBlitz")
            if (c.responseCode != 200) return false
            val total = c.contentLengthLong
            var got = 0L
            c.inputStream.use { ins ->
                out.outputStream().use { os ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        os.write(buf, 0, n)
                        got += n
                        if (total > 0) progress((got.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            !(total > 0 && got != total)
        } catch (_: Throwable) {
            false
        }
    }

    /** Opens the system installer. Returns an error message, or null when the installer was launched. */
    fun install(ctx: Context, apk: File): String? {
        return try {
            if (!ctx.packageManager.canRequestPackageInstalls()) {
                val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(i)
                return "Allow Blitz to install apps, then tap Update again."
            }
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", apk)
            val i = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
            null
        } catch (e: Throwable) {
            "Couldn't open the installer."
        }
    }
}
