package com.xcluice.blitz

import android.app.Application
import com.xcluice.blitz.engine.Blitz
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class BlitzApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                File(filesDir, "crash.txt").writeText("Thread: ${t.name}\n$sw")
            } catch (_: Throwable) {
            }
            prev?.uncaughtException(t, e)
        }
        Blitz.init(this)
    }
}

fun readCrash(app: android.content.Context): String? = try {
    val f = File(app.filesDir, "crash.txt")
    if (f.exists()) f.readText().take(3500) else null
} catch (_: Throwable) {
    null
}

fun clearCrash(app: android.content.Context) {
    try {
        File(app.filesDir, "crash.txt").delete()
    } catch (_: Throwable) {
    }
}
