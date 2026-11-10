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

class UpdateInfo(val version: String, val code: Int, val url: String, val notes: String)

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
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name").endsWith(".apk")) url = a.getString("browser_download_url")
            }
            if (url == null) return null
            UpdateInfo(tag, code, url, o.optString("body", ""))
        } catch (_: Throwable) {
            null
        }
    }

    /** Downloads the APK; [progress] gets 0..1. Returns the file or null. */
    fun download(ctx: Context, info: UpdateInfo, progress: (Float) -> Unit): File? {
        return try {
            val dir = File(ctx.cacheDir, "updates")
            dir.mkdirs()
            dir.listFiles()?.forEach { it.delete() }
            val out = File(dir, "blitz-${info.code}.apk")
            val c = URL(info.url).openConnection() as HttpURLConnection
            c.connectTimeout = 10000
            c.readTimeout = 15000
            c.setRequestProperty("User-Agent", "XcluiceBlitz")
            if (c.responseCode != 200) return null
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
            if (total > 0 && got != total) null else out
        } catch (_: Throwable) {
            null
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
