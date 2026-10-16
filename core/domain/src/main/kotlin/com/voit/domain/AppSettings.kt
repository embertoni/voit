package com.voit.domain

import kotlinx.coroutines.flow.Flow

/**
 * User-facing application settings, persisted locally (DataStore in `core:data`).
 *
 * Defaults embody Voit's privacy-first stance:
 * - listening is OFF until the user explicitly enables it,
 * - cloud processing is OFF (local-first),
 * - recognition language defaults to Brazilian Portuguese (pt-BR) per the spec open
 *   decision, but is configurable.
 *
 * Model config fields are placeholders in Phase 1 (no native/ML engines are bundled).
 */
data class AppSettings(
    val listeningEnabled: Boolean = false,
    val wakePhrase: String = DEFAULT_WAKE_PHRASE,
    val recognitionLanguage: String = DEFAULT_RECOGNITION_LANGUAGE,
    val replyVoice: String = DEFAULT_REPLY_VOICE,
    // Model configuration placeholders (filled when real engines land in later phases).
    val asrModelId: String? = null,
    val ttsModelId: String? = null,
    val wakeWordModelId: String? = null,
    // Privacy toggles.
    val cloudProcessingEnabled: Boolean = false,
    val speakerVerificationEnabled: Boolean = false,
    val diagnosticsEnabled: Boolean = false,
    // When true, parsed results are shown for review before being committed/executed.
    val reviewModeEnabled: Boolean = true,
    // In-app theme override. Kept as a plain string ("SYSTEM"/"LIGHT"/"DARK") so the pure
    // domain never depends on the Compose design module. Defaults to following the system.
    val themeMode: String = DEFAULT_THEME_MODE,
) {
    companion object {
        const val DEFAULT_WAKE_PHRASE = "hey voit"
        const val DEFAULT_RECOGNITION_LANGUAGE = "pt-BR"
        const val DEFAULT_REPLY_VOICE = "default"
        const val DEFAULT_THEME_MODE = "SYSTEM"

        val DEFAULT = AppSettings()
    }
}

/**
 * Persistence boundary for [AppSettings], exposed as an observable [Flow].
 * Declared in the domain so ViewModels depend on the abstraction, not DataStore.
 */
interface SettingsRepository {
    val settings: Flow<AppSettings>

    suspend fun update(transform: (AppSettings) -> AppSettings)

    suspend fun setListeningEnabled(enabled: Boolean)

    suspend fun setRecognitionLanguage(language: String)

    suspend fun setCloudProcessingEnabled(enabled: Boolean)

    suspend fun setThemeMode(themeMode: String)
}
