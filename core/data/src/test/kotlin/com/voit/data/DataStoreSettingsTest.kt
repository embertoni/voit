package com.voit.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.voit.domain.AppSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class DataStoreSettingsTest {

    private lateinit var tempFile: File
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repo: DataStoreSettingsRepository

    @Before
    fun setUp() {
        tempFile = File.createTempFile("voit-settings", ".preferences_pb").also { it.delete() }
        dataStore = PreferenceDataStoreFactory.create { tempFile }
        repo = DataStoreSettingsRepository(dataStore)
    }

    @After
    fun tearDown() {
        tempFile.delete()
    }

    @Test
    fun defaultsArePrivacySafe() = runTest {
        val s = repo.settings.first()
        assertFalse(s.listeningEnabled)
        assertFalse(s.cloudProcessingEnabled)
        assertEquals("pt-BR", s.recognitionLanguage)
        assertEquals(AppSettings.DEFAULT_WAKE_PHRASE, s.wakePhrase)
        assertTrue(s.reviewModeEnabled)
    }

    @Test
    fun updatesPersistAndAreObservable() = runTest {
        repo.setListeningEnabled(true)
        repo.setRecognitionLanguage("en-US")
        repo.setCloudProcessingEnabled(true)
        repo.update { it.copy(wakePhrase = "ola voit", asrModelId = "whisper-tiny") }

        val s = repo.settings.first()
        assertTrue(s.listeningEnabled)
        assertEquals("en-US", s.recognitionLanguage)
        assertTrue(s.cloudProcessingEnabled)
        assertEquals("ola voit", s.wakePhrase)
        assertEquals("whisper-tiny", s.asrModelId)
    }

    @Test
    fun nullableModelFieldClearsWhenSetBackToNull() = runTest {
        repo.update { it.copy(asrModelId = "x") }
        assertEquals("x", repo.settings.first().asrModelId)
        repo.update { it.copy(asrModelId = null) }
        assertEquals(null, repo.settings.first().asrModelId)
    }
}
