package com.voit

import android.app.Application

/**
 * Holds the single process-lifetime [VoitContainer] (manual DI). Registered in the
 * manifest via `android:name`.
 */
class VoitApplication : Application() {
    lateinit var container: VoitContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = VoitContainer(this)
    }
}
