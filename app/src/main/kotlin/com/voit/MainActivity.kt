package com.voit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.voit.design.theme.ThemeMode
import com.voit.design.theme.VoitTheme
import com.voit.domain.AppSettings

class MainActivity : ComponentActivity() {

    /**
     * Latest persisted settings snapshot, kept current by the Compose collector below. It
     * backs [voicePermissionGate]'s "is the voice feature enabled?" input WITHOUT handing a
     * repository/Flow to the pure permission model: the gate only asks for the current
     * boolean. Defaults to the privacy-safe [AppSettings.DEFAULT] (listening OFF).
     */
    @Volatile
    private var latestSettings: AppSettings = AppSettings.DEFAULT

    /**
     * Activity-scoped voice permission gate. MUST be created during [onCreate] (before the
     * Activity is STARTED) because it registers an [androidx.activity.result.ActivityResultLauncher].
     * It routes the Voice screen's start-capture action through the pure
     * `VoicePermissionModel`, closing the Phase-2 "permission model never wired" gap.
     */
    private lateinit var voicePermissionGate: VoicePermissionGate

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as VoitApplication).container
        val factory = VoitViewModelFactory(container)

        // Register the permission launcher now (contract registration must happen before the
        // Activity is STARTED). The gate consults the pure model on each capture attempt; its
        // "feature enabled" input is the live `listeningEnabled` setting.
        voicePermissionGate = VoicePermissionGate.create(
            activity = this,
            featureEnabledProvider = { latestSettings.listeningEnabled },
        )

        setContent {
            // The in-app theme override is read from persisted settings; SYSTEM follows the
            // device setting. Both painted modes remain strictly monochrome.
            val settings by container.settingsRepository.settings
                .collectAsState(initial = AppSettings.DEFAULT)
            latestSettings = settings
            val themeMode = runCatching { ThemeMode.valueOf(settings.themeMode) }
                .getOrDefault(ThemeMode.SYSTEM)

            VoitTheme(themeMode = themeMode) {
                VoitApp(
                    factory = factory,
                    onStartVoiceCapture = voicePermissionGate::ensurePermission,
                )
            }
        }
    }
}
