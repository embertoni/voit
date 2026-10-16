package com.voit.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.voit.domain.AppSettings
import com.voit.domain.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * DataStore (preferences)-backed [SettingsRepository].
 *
 * Defaults are supplied from [AppSettings.DEFAULT] so an empty store yields the privacy-safe
 * configuration (listening OFF, cloud OFF, pt-BR recognition). Exposed as a [Flow].
 */
class DataStoreSettingsRepository(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {

    override val settings: Flow<AppSettings> = dataStore.data.map { it.toAppSettings() }

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        dataStore.edit { prefs ->
            val updated = transform(prefs.toAppSettings())
            prefs.writeAppSettings(updated)
        }
    }

    override suspend fun setListeningEnabled(enabled: Boolean) =
        update { it.copy(listeningEnabled = enabled) }

    override suspend fun setRecognitionLanguage(language: String) =
        update { it.copy(recognitionLanguage = language) }

    override suspend fun setCloudProcessingEnabled(enabled: Boolean) =
        update { it.copy(cloudProcessingEnabled = enabled) }

    private fun Preferences.toAppSettings(): AppSettings = AppSettings(
        listeningEnabled = this[Keys.LISTENING] ?: AppSettings.DEFAULT.listeningEnabled,
        wakePhrase = this[Keys.WAKE_PHRASE] ?: AppSettings.DEFAULT_WAKE_PHRASE,
        recognitionLanguage = this[Keys.RECOGNITION_LANGUAGE]
            ?: AppSettings.DEFAULT_RECOGNITION_LANGUAGE,
        replyVoice = this[Keys.REPLY_VOICE] ?: AppSettings.DEFAULT_REPLY_VOICE,
        asrModelId = this[Keys.ASR_MODEL],
        ttsModelId = this[Keys.TTS_MODEL],
        wakeWordModelId = this[Keys.WAKE_MODEL],
        cloudProcessingEnabled = this[Keys.CLOUD] ?: AppSettings.DEFAULT.cloudProcessingEnabled,
        speakerVerificationEnabled = this[Keys.SPEAKER_VERIFICATION]
            ?: AppSettings.DEFAULT.speakerVerificationEnabled,
        diagnosticsEnabled = this[Keys.DIAGNOSTICS] ?: AppSettings.DEFAULT.diagnosticsEnabled,
        reviewModeEnabled = this[Keys.REVIEW_MODE] ?: AppSettings.DEFAULT.reviewModeEnabled,
    )

    private fun androidx.datastore.preferences.core.MutablePreferences.writeAppSettings(
        s: AppSettings,
    ) {
        this[Keys.LISTENING] = s.listeningEnabled
        this[Keys.WAKE_PHRASE] = s.wakePhrase
        this[Keys.RECOGNITION_LANGUAGE] = s.recognitionLanguage
        this[Keys.REPLY_VOICE] = s.replyVoice
        s.asrModelId?.let { this[Keys.ASR_MODEL] = it } ?: remove(Keys.ASR_MODEL)
        s.ttsModelId?.let { this[Keys.TTS_MODEL] = it } ?: remove(Keys.TTS_MODEL)
        s.wakeWordModelId?.let { this[Keys.WAKE_MODEL] = it } ?: remove(Keys.WAKE_MODEL)
        this[Keys.CLOUD] = s.cloudProcessingEnabled
        this[Keys.SPEAKER_VERIFICATION] = s.speakerVerificationEnabled
        this[Keys.DIAGNOSTICS] = s.diagnosticsEnabled
        this[Keys.REVIEW_MODE] = s.reviewModeEnabled
    }

    private object Keys {
        val LISTENING = booleanPreferencesKey("listening_enabled")
        val WAKE_PHRASE = stringPreferencesKey("wake_phrase")
        val RECOGNITION_LANGUAGE = stringPreferencesKey("recognition_language")
        val REPLY_VOICE = stringPreferencesKey("reply_voice")
        val ASR_MODEL = stringPreferencesKey("asr_model_id")
        val TTS_MODEL = stringPreferencesKey("tts_model_id")
        val WAKE_MODEL = stringPreferencesKey("wake_word_model_id")
        val CLOUD = booleanPreferencesKey("cloud_processing_enabled")
        val SPEAKER_VERIFICATION = booleanPreferencesKey("speaker_verification_enabled")
        val DIAGNOSTICS = booleanPreferencesKey("diagnostics_enabled")
        val REVIEW_MODE = booleanPreferencesKey("review_mode_enabled")
    }
}
