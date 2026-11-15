package com.xcluice.blitz.engine

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/** Installs a base APK plus its split APKs together in one PackageInstaller session. */
object ApkInstaller {
    private const val ACTION = "com.xcluice.blitz.INSTALL_RESULT"

    private class Staged(val name: String, val file: File) {
        var abis: Set<String> = emptySet()
        var abiSplit = false
    }

    private val ABI_NAME = Regex("(?:^|[._/])(arm64_v8a|armeabi_v7a|x86_64|x86|armeabi)(?=[._]|$)")

    private fun deviceAbis(): List<String> =
        Build.SUPPORTED_ABIS.map { it.lowercase().replace('-', '_') }

    private fun inspect(s: Staged) {
        val found = HashSet<String>()
        val n = s.name.lowercase().replace('-', '_')
        val m = ABI_NAME.find(n)
        if (m != null) {
            found.add(m.groupValues[1])
            s.abiSplit = true
        }
        try {
            ZipFile(s.file).use { z ->
                val en = z.entries()
                while (en.hasMoreElements()) {
                    val name = en.nextElement().name
                    if (!name.startsWith("lib/")) continue
                    val parts = name.split('/')
                    if (parts.size >= 3) found.add(parts[1].lowercase().replace('-', '_'))
                }
            }
        } catch (_: Throwable) {
        }
        s.abis = found
    }

    /** Drops splits built for CPUs this phone doesn't have (they make the installer fail with "no ABIs"). */
    private fun choose(all: List<Staged>): Pair<List<Staged>, String?> {
        for (s in all) inspect(s)
        var pool = all
        // bundletool .apks: prefer the split set over standalone / universal variants
        val hasSplits = pool.any { it.name.startsWith("splits/") }
        if (hasSplits) {
            pool = pool.filter { it.name.startsWith("splits/") }
        } else if (pool.any { it.name.startsWith("standalones/") || it.name.equals("universal.apk", true) }) {
            val dev = deviceAbis()
            val one = pool.firstOrNull { p -> p.abis.isNotEmpty() && p.abis.any { it in dev } }
                ?: pool.firstOrNull { it.abis.isEmpty() }
                ?: pool.first()
            pool = listOf(one)
        }
        val dev = deviceAbis()
        val needsNative = pool.any { it.abis.isNotEmpty() }
        var primary: String? = null
        for (a in dev) {
            if (pool.any { it.abis.contains(a) }) {
                primary = a
                break
            }
        }
        if (needsNative && primary == null) {
            val have = pool.flatMap { it.abis }.toSortedSet().joinToString(", ")
            val phone = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
            return Pair(emptyList(), "This app only ships $have code, but this phone runs $phone. Send the app from a phone with the same CPU type.")
        }
        val kept = pool.filter { s ->
            if (s.abis.isEmpty()) true
            else if (s.abiSplit) primary != null && s.abis.contains(primary)
            else s.abis.any { it in dev }
        }
        return Pair(kept, null)
    }

    private fun safe(name: String): String = name.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun stage(ctx: Context, sources: List<Pair<String, () -> InputStream?>>, dir: File): List<Staged> {
        val out = ArrayList<Staged>()
        for ((i, src) in sources.withIndex()) {
            val ins = src.second() ?: throw IOException("Can't read ${src.first}")
            val f = File(dir, "p$i.apk")
            ins.use { input -> f.outputStream().buffered(1 shl 16).use { input.copyTo(it, 1 shl 20) } }
            out.add(Staged(src.first, f))
        }
        return out
    }

    /** Installs a Label.apks bundle (a zip holding base.apk and split APKs). */
    fun installBundle(ctx: Context, bundle: Item): String? {
        val dir = File(ctx.cacheDir, "inst")
        return try {
            dir.deleteRecursively()
            dir.mkdirs()
            val uri = bundle.uri ?: throw IOException("${bundle.name} is missing")
            val raw = ctx.contentResolver.openInputStream(uri) ?: throw IOException("Can't read ${bundle.name}")
            val staged = ArrayList<Staged>()
            ZipInputStream(raw.buffered(1 shl 16)).use { zin ->
                var n = 0
                while (true) {
                    val e = zin.nextEntry ?: break
                    if (e.isDirectory || !e.name.endsWith(".apk", ignoreCase = true)) continue
                    val f = File(dir, "p${n++}.apk")
                    f.outputStream().buffered(1 shl 16).use { zin.copyTo(it, 1 shl 20) }
                    staged.add(Staged(e.name, f))
                }
            }
            if (staged.isEmpty()) throw IOException("No APKs inside ${bundle.name}")
            commitAll(ctx, staged)
        } catch (e: Exception) {
            e.message ?: "Install failed"
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun commitAll(ctx: Context, staged: List<Staged>): String? {
        val (kept, err) = choose(staged)
        if (err != null) return err
        if (kept.isEmpty()) return "Nothing installable in this file"
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val id = pi.createSession(params)
        val session = pi.openSession(id)
        try {
            for ((i, s) in kept.withIndex()) {
                val entry = i.toString() + "_" + safe(s.name.substringAfterLast('/'))
                s.file.inputStream().use { ins ->
                    session.openWrite(entry, 0, s.file.length()).use { out ->
                        ins.copyTo(out, 1 shl 20)
                        session.fsync(out)
                    }
                }
            }
            commit(ctx, session, id)
        } catch (e: Throwable) {
            try {
                session.abandon()
            } catch (_: Throwable) {
            }
            throw e
        } finally {
            try {
                session.close()
            } catch (_: Throwable) {
            }
        }
        return null
    }

    private fun commit(ctx: Context, session: PackageInstaller.Session, id: Int) {
        val intent = Intent(ctx, InstallReceiver::class.java).setAction(ACTION)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= 31) flags = flags or PendingIntent.FLAG_MUTABLE
        val pending = PendingIntent.getBroadcast(ctx, id, intent, flags)
        session.commit(pending.intentSender)
    }

    /** Returns an error message, or null when the system install prompt has been handed off. */
    fun install(ctx: Context, parts: List<Item>): String? {
        val dir = File(ctx.cacheDir, "inst")
        return try {
            dir.deleteRecursively()
            dir.mkdirs()
            val srcs = parts.mapIndexed { i, part ->
                val uri = part.uri ?: throw IOException("${part.name} is missing")
                Pair<String, () -> InputStream?>(if (i == 0) "base.apk" else part.name, { ctx.contentResolver.openInputStream(uri) })
            }
            commitAll(ctx, stage(ctx, srcs, dir))
        } catch (e: Exception) {
            e.message ?: "Install failed"
        } finally {
            dir.deleteRecursively()
        }
    }
}

/** Receives the installer's status: shows the confirm screen, then reports the outcome. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                }
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        context.startActivity(confirm)
                    } catch (e: Exception) {
                        Toast.makeText(context, "Couldn't open the installer", Toast.LENGTH_LONG).show()
                    }
                }
            }
            PackageInstaller.STATUS_SUCCESS ->
                Toast.makeText(context, "Installed", Toast.LENGTH_SHORT).show()
            PackageInstaller.STATUS_FAILURE_ABORTED ->
                Toast.makeText(context, "Install cancelled", Toast.LENGTH_SHORT).show()
            else -> {
                val raw = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
                val msg = when {
                    raw.contains("ABI", ignoreCase = true) ->
                        "This app was built for a different CPU than this phone (${Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"})."
                    status == PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage to install this app."
                    status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                        "This app isn't compatible with this phone."
                    raw.isNotEmpty() -> raw
                    else -> "Install failed"
                }
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
        }
    }
}
