package com.xcluice.blitz.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.xcluice.blitz.MainActivity
import com.xcluice.blitz.R

/** Keeps the process alive (and the radios busy) while a transfer runs with the screen off. */
class KeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        goForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        return START_NOT_STICKY
    }

    // Must run as soon as the service exists, or Android kills the app for not calling startForeground in time.
    private fun goForeground() {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel("blitz", "Transfers", NotificationManager.IMPORTANCE_LOW)
            )
            val open = PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
            )
            val n: Notification = NotificationCompat.Builder(this, "blitz")
                .setSmallIcon(R.drawable.ic_stat_bolt)
                .setContentTitle("Xcluice Blitz")
                .setContentText("Transfer in progress")
                .setOngoing(true)
                .setContentIntent(open)
                .build()
            ServiceCompat.startForeground(this, 1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (_: Throwable) {
            try {
                stopSelf()
            } catch (_: Throwable) {
            }
        }
    }

    companion object {
        fun start(c: Context) {
            try {
                ContextCompat.startForegroundService(c, Intent(c, KeepAliveService::class.java))
            } catch (_: Throwable) {
            }
        }

        fun stop(c: Context) {
            try {
                c.stopService(Intent(c, KeepAliveService::class.java))
            } catch (_: Throwable) {
            }
        }
    }
}
