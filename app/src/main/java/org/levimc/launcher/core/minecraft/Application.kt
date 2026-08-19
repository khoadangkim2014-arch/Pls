package org.levimc.launcher.core.minecraft

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import org.levimc.launcher.core.crash.CrashReporter
import org.levimc.launcher.settings.FeatureSettings
import org.levimc.launcher.ui.dialogs.LogcatOverlayManager

class LauncherApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        context = applicationContext
        CrashReporter.init(this)

        try {
            FeatureSettings.init(applicationContext)
        } catch (_: Throwable) {
        }

        val processName = try {
            Application.getProcessName()
        } catch (_: Throwable) {
            ""
        }
        if (processName.endsWith(":crash")) return

        try {
            LogcatOverlayManager.init(this)
        } catch (_: Throwable) {
        }

        try {
            preferences = PreferenceManager.getDefaultSharedPreferences(this)
        } catch (_: Throwable) {
        }
    }

    companion object {
        @JvmStatic
        lateinit var context: Context
            private set

        @JvmStatic
        lateinit var preferences: SharedPreferences
            private set
    }
}
