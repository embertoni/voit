package com.voit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.voit.design.theme.ThemeMode
import com.voit.design.theme.VoitTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as VoitApplication).container
        val factory = VoitViewModelFactory(container)

        setContent {
            // The in-app theme override is read from persisted settings; SYSTEM follows the
            // device setting. Both painted modes remain strictly monochrome.
            val settings by container.settingsRepository.settings
                .collectAsState(initial = com.voit.domain.AppSettings.DEFAULT)
            val themeMode = runCatching { ThemeMode.valueOf(settings.themeMode) }
                .getOrDefault(ThemeMode.SYSTEM)

            VoitTheme(themeMode = themeMode) {
                VoitApp(factory = factory)
            }
        }
    }
}
