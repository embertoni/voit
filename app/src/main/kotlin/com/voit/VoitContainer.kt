package com.voit

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import com.voit.actions.ActionRegistry
import com.voit.actions.ActionRunRecorder
import com.voit.actions.CommandDispatcher
import com.voit.actions.DefaultHandlers
import com.voit.actions.TranscriptProcessor
import com.voit.actions.WorkflowRunner
import com.voit.data.RoomActionRunRepository
import com.voit.domain.ActionRunRepository
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
import com.voit.voice.FakeSpeakerVerifier
import com.voit.voice.FakeSpeechRecognizerEngine
import com.voit.voice.FakeWakeWordDetector
import com.voit.voice.RecordingConfig
import com.voit.voice.SpeakerVerifier
import com.voit.voice.SpeechRecognizerEngine
import com.voit.voice.WakeConfig
import com.voit.voice.WakeWordDetector

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

    // The registry is now built with PRODUCTION handlers: note/task actions are backed by
    // the real repositories (so a dispatched command actually persists), while actions that
    // still need platform runtime not yet built (timers, overlay, listening, open-app,
    // speak, export) remain echo placeholders. The registry stays the SOLE authorizer and
    // every handler's contract (schema/risk/confirmation) is unchanged.
    val actionRegistry: ActionRegistry = ActionRegistry(
        DefaultHandlers.withRepositories(
            notes = noteRepository,
            tasks = taskRepository,
            clock = SystemClock,
            ids = UuidGenerator,
        ),
    )

    val transcriptProcessor: TranscriptProcessor = TranscriptProcessor(
        registry = actionRegistry,
        clock = SystemClock,
        ids = UuidGenerator,
    )

    // --- Phase 5 configurable-commands / workflow engine ---------------------------------
    // The bounded WorkflowRunner and the CommandDispatcher that drives it existed as pure,
    // unit-tested components but had NO production caller: nothing matched a transcript
    // against a stored CommandDefinition and ran its actions. These wire that path. The
    // registry remains the SOLE authorizer (a HIGH / confirmation-mandatory action stays
    // gated regardless of a command's own policy), and MAX_STEPS / per-step timeout /
    // idempotency all still apply via the runner. Pure JVM: no Android, network, or model.
    val workflowRunner: WorkflowRunner = WorkflowRunner(actionRegistry)

    val commandDispatcher: CommandDispatcher = CommandDispatcher(actionRegistry, workflowRunner)

    // Redacted audit trail: a dispatched command writes exactly one code-only ActionRun
    // record (status + error CODE, never transcript/content). The recorder maps the
    // WorkflowOutcome/CommandDispatchResult to stable codes; the Room repo persists to the
    // existing `action_runs` table (metadata only, user-clearable).
    val actionRunRepository: ActionRunRepository = RoomActionRunRepository(database)

    val actionRunRecorder: ActionRunRecorder = ActionRunRecorder(
        repository = actionRunRepository,
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

    // --- Phase 3 hands-free gate pieces ---------------------------------------------------
    // Wake-word detection + speaker verification are wired here as DEFAULT-SAFE FAKES, the
    // same way the Phase-2 recognizer/audio-session are fakes. There is NO real on-device
    // wake-word or speaker-verification model bundled: the concrete model choice (Picovoice
    // vs sherpa-onnx keyword spotting / speaker embedding, etc.) still awaits the Phase-0
    // hardware benchmark (see docs/models.md), so nothing native is pulled in and the app
    // builds/runs in an emulator without a model.
    //
    // The pure WakeWordController that consumes these is the Phase-3 analogue of the
    // RecordingController: its single `onTriggered` hand-off seam is what a real always-on
    // listening service would wire to RecordingController.startPushToTalk(), so a verified
    // wake-word utterance replaces the manual push-to-talk tap. Because there is no live
    // microphone/foreground service here (that is Phase 4), the gate is NOT started by the
    // app at runtime yet; it is validated by the pure JVM unit tests. The fake detector is
    // created NOT-detecting by default so that even if a gate were started against the fake
    // audio session it would never spuriously trigger a capture.
    //
    // SpeakerVerifier is a privacy-safe GATE only: it returns a boolean + score and holds no
    // biometric template. The `voice_profile_metadata` row (metadata only, user-deletable)
    // remains the only speaker-related persistence; nothing audio-related is stored here.
    fun newWakeWordDetector(): WakeWordDetector = FakeWakeWordDetector(shouldDetect = false)

    fun newSpeakerVerifier(): SpeakerVerifier = FakeSpeakerVerifier(shouldVerify = true, score = 1.0)

    /**
     * Build the hands-free [WakeConfig] from the live privacy settings: speaker match is
     * required only when the user has enabled speaker verification. Kept as a pure mapper so
     * the policy ("gate on the enrolled speaker when the user opted in") lives in one place.
     */
    fun wakeConfig(speakerVerificationEnabled: Boolean): WakeConfig =
        WakeConfig(requireSpeakerMatch = speakerVerificationEnabled)
}
