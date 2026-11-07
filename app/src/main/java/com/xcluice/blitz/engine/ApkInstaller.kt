package com.xcluice.blitz.engine

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import java.io.IOException

/** Installs a base APK plus its split APKs together in one PackageInstaller session. */
object ApkInstaller {
    private const val ACTION = "com.xcluice.blitz.INSTALL_RESULT"

    /** Returns an error message, or null when the system install prompt has been handed off. */
    fun install(ctx: Context, parts: List<Item>): String? {
        return try {
            val pi = ctx.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val id = pi.createSession(params)
            val session = pi.openSession(id)
            try {
                for (i in parts.indices) {
                    val part = parts[i]
                    val uri = part.uri ?: throw IOException("${part.name} is missing")
                    val input = ctx.contentResolver.openInputStream(uri)
                        ?: throw IOException("Can't read ${part.name}")
                    input.use { ins ->
                        val entry = if (i == 0) "base.apk" else "split$i.apk"
                        session.openWrite(entry, 0, part.size).use { out ->
                            ins.copyTo(out, 1 shl 20)
                            session.fsync(out)
                        }
                    }
                }
                val intent = Intent(ctx, InstallReceiver::class.java).setAction(ACTION)
                var flags = PendingIntent.FLAG_UPDATE_CURRENT
                if (Build.VERSION.SDK_INT >= 31) flags = flags or PendingIntent.FLAG_MUTABLE
                val pending = PendingIntent.getBroadcast(ctx, id, intent, flags)
                session.commit(pending.intentSender)
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
            null
        } catch (e: Exception) {
            e.message ?: "Install failed"
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
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Install failed"
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
        }
    }
}
