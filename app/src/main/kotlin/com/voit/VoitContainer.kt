package com.voit

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import com.voit.actions.ActionRegistry
import com.voit.actions.TranscriptProcessor
import com.voit.data.DataStoreSettingsRepository
import com.voit.data.RoomCommandRepository
import com.voit.data.RoomNoteRepository
import com.voit.data.RoomTaskRepository
import com.voit.data.db.VoitDatabase
import com.voit.domain.CommandRepository
import com.voit.domain.NoteRepository
import com.voit.domain.SettingsRepository
import com.voit.domain.SystemClock
import com.voit.domain.TaskRepository
import com.voit.domain.UuidGenerator
import com.voit.voice.AudioSessionController
import com.voit.voice.FakeAudioSessionController
import com.voit.voice.FakeSpeechRecognizerEngine
import com.voit.voice.RecordingConfig
import com.voit.voice.SpeechRecognizerEngine

/**
 * A tiny MANUAL dependency-injection container.
 *
 * Hilt would also work, but a plain container keeps the build simple (no extra KSP/plugin)
 * and makes the full object graph explicit in one place. It constructs the Room database,
 * the DataStore, the repositories, the parsers/registry, and the transcript processor, and
 * hands them to a [VoitViewModelFactory].
 *
 * One instance lives for the process lifetime (held by [VoitApplication]).
 */
class VoitContainer(context: Context) {

    private val appContext = context.applicationContext

    private val database: VoitDatabase = Room.databaseBuilder(
        appContext,
        VoitDatabase::class.java,
        VoitDatabase.NAME,
    ).addMigrations(*VoitDatabase.ALL_MIGRATIONS).build()

    private val settingsDataStore: DataStore<Preferences> =
        PreferenceDataStoreFactory.create {
            appContext.preferencesDataStoreFile("voit_settings")
        }

    val noteRepository: NoteRepository = RoomNoteRepository(database, clock = SystemClock)
    val taskRepository: TaskRepository = RoomTaskRepository(database, clock = SystemClock)
    val commandRepository: CommandRepository = RoomCommandRepository(database)
    val settingsRepository: SettingsRepository = DataStoreSettingsRepository(settingsDataStore)

    val actionRegistry: ActionRegistry = ActionRegistry()

    val transcriptProcessor: TranscriptProcessor = TranscriptProcessor(
        registry = actionRegistry,
        clock = SystemClock,
        ids = UuidGenerator,
    )

    // --- Phase 2 voice pieces -------------------------------------------------------------
    // The DEFAULT recognizer wired here is a FAKE PLACEHOLDER. There is no real on-device
    // ASR/VAD model or microphone capture wired yet: that awaits the Phase-0 hardware
    // benchmark (model selection - sherpa-onnx vs whisper.cpp etc - is still pending), so no
    // model/native dependency is bundled and the app runs in an emulator without a model.
    //
    // platform:android's AndroidOnDeviceSpeechRecognizer (the framework on-device
    // SpeechRecognizer adapter) is available to SWAP IN behind the SpeechRecognizerEngine
    // interface, but it is UNVALIDATED off-device and is intentionally NOT the default.
    //
    // The FakeAudioSessionController stands in for real audio capture the same way the
    // Phase-1 Simulator stood in for the microphone. The RecordingController that consumes
    // these is built INSIDE the PushToTalkViewModel from these engines + config so it can
    // run on the ViewModel's scope (viewModelScope / an injected test scope).
    val speechRecognizer: SpeechRecognizerEngine = FakeSpeechRecognizerEngine(
        cannedTranscript = "title: Voice note bullet point captured by push to talk tag voice",
    )

    /** Fresh audio session per capture ViewModel; the fake only tracks state transitions. */
    fun newAudioSessionController(): AudioSessionController = FakeAudioSessionController()

    val recordingConfig: RecordingConfig = RecordingConfig()
}
