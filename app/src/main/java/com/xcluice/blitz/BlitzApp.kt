package com.xcluice.blitz

import android.app.Application
import com.xcluice.blitz.engine.Blitz

class BlitzApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Blitz.init(this)
    }
}
