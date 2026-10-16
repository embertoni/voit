# Voit — Architecture & Environment Decisions

This document records foundational decisions for Voit and any deviations from the
product/context specification. It is the running log of why things are the way they are.

## 1. Toolchain & environment constraints (discovered)

The project is built in a constrained sandbox. These constraints shaped several
build-configuration choices:

- **No device or emulator.** Instrumented/`androidTest` runs cannot execute (no mic,
  overlay, foreground service, wake-word, speaker verification, or ASR at runtime).
  Only JVM unit tests and APK assembly are exercised here.
- **JDK 17 is required.** Gradle 8.14.5 rejects Java 25 (the sandbox default via mise;
  Gradle 8.14.5 supports up to Java 24). All builds run with JDK 17 at
  `/root/.local/share/mise/installs/java/17.0.2`.
- **`/root` (HOME) is read-only** and `/tmp` is wiped between shell invocations; only
  `/projects/sandbox` persists and is writable. Consequences:
  - `GRADLE_USER_HOME` is set to `/projects/sandbox/.gradle`.
  - `ANDROID_USER_HOME` is set to `/projects/sandbox/fakehome/.android`.
  - `gradle.properties` sets `org.gradle.jvmargs=-Xmx2g -Duser.home=/projects/sandbox/fakehome`
    so the Android Gradle Plugin's `AndroidLocationsBuildService` and metrics write inside
    the writable tree rather than to `/root/.android`.
  - `org.gradle.daemon=false` to avoid stale daemons across invocations.
- **Android SDK is pre-provisioned** at `/projects/sandbox/android-sdk` (platform
  `android-36`, build-tools `36.0.0`, platform-tools, licenses accepted). `local.properties`
  points `sdk.dir` there and is git-ignored (never committed).
- The build helper directories (`android-sdk`, `.gradle`, `fakehome`) live in
  `/projects/sandbox`, the parent of the repo, so they stay outside the committed tree.

## 2. Package base, SDK levels, versions

- **Package base:** `com.voit` (applicationId `com.voit`). Per-module namespaces follow
  `com.voit.<module>` (e.g. `com.voit.data`, `com.voit.design`, `com.voit.feature.notes`).
- **minSdk 26**, **compileSdk 36**, **targetSdk 36** (Google Play requires API 36+).
- **jvmTarget 17** across all modules.
- **Pinned versions** (version catalog `gradle/libs.versions.toml`), verified to build an
  APK in this sandbox: AGP 8.7.3, Kotlin 2.0.21, Compose compiler plugin
  `org.jetbrains.kotlin.plugin.compose` 2.0.21, KSP `com.google.devtools.ksp`
  2.0.21-1.0.25, Gradle 8.14.5, Room 2.6.1, DataStore 1.1.1. No dynamic version ranges.

## 3. Scope decision: Phase 1 + architectural scaffolding

This delivery scopes to **Phase 1** (offline notes/tasks, deterministic parsing, and a
text-command simulation that stands in for the microphone) plus the full module
scaffolding every later phase builds on.

Deferred and why:

- **Phase 0 (device spikes / on-device model benchmarks)** is deferred: it requires real
  hardware to benchmark local ASR / wake-word / speaker-verification / VAD / TTS models.
  The sandbox has no device, so results would be meaningless. `docs/models.md` tracks this.
- **Phases 2–4 (live microphone, ASR, wake-word, speaker verification, foreground service,
  overlay)** are deferred: they need runtime audio and system integration that cannot run
  or be validated without a device. Phase 1 instead defines engine-adapter **interfaces**
  with **fake** implementations (no native/ML dependencies).

Explicit non-goals for Phase 1, consistent with the privacy/architecture requirements:

- No cloud calls or network dependency for core features.
- No arbitrary code/shell execution. Custom commands are a typed, allow-listed action
  registry only (per-action risk level + confirmation-mandatory flag), added in later features.
- No `sherpa-onnx` / `whisper.cpp` / `llama.cpp` / Picovoice / native ML or large model
  files are bundled. Interfaces + fakes only.

## 4. Module layout

- `app` — Compose `MainActivity`, navigation scaffold, DI wiring (grows later).
- `core:domain` — pure-Kotlin domain models (Note, Task, Command, Action, Intent, the
  structured block model). No Android deps.
- `core:data` — Room entities/DAO/database/migrations, repository impls, export/import.
  Android library (Room). DB migration test added with real entities.
- `core:voice` — engine-adapter interfaces (AudioSessionController, WakeWordDetector,
  SpeakerVerifier, SpeechRecognizerEngine, IntentInterpreter, ActionExecutor) + fakes.
- `core:interpretation` — deterministic phrase / formatting / slot parsers (no LLM).
- `core:actions` — typed allow-listed action registry + workflow runner + risk/confirmation
  policy (reject unknown actions; max 5 steps per workflow).
- `core:design` — monochrome (black-and-white) Compose theme tokens, typography, components.
  Monochrome is a product requirement, not optional polish.
- `platform:android` — Android-framework boundary code behind interfaces (notification /
  permission scaffolding; no real foreground-service/overlay runtime in Phase 1).
- `feature:{notes,tasks,commands,settings,simulator}` — Compose feature modules + ViewModels.
  `feature:simulator` is the text-command simulation input.

## 5. Deviations from spec

- None material in FEAT-001 (scaffolding). All modules are minimally compilable with
  placeholder types/screens and passing placeholder tests; real content lands in later
  features. Room schema export is not yet enabled (no entities exist); `schemas/` is
  git-ignored for now and the decision will be revisited when `core:data` gains entities.

### FEAT-002 (domain core, parsers, action registry, voice interfaces)

- The standalone task domain model is named `TaskItem` to avoid a name clash with the
  in-note checkbox block `NoteBlock.Task`. Both are required by the spec; the distinct
  name keeps them unambiguous.
- Determinism is achieved by injecting a `Clock` (`FixedClock`/`MutableClock` for tests,
  `SystemClock` in production) and an `IdGenerator` (`SequentialIdGenerator` for tests,
  `UuidGenerator` in production). Note/Task ids are UUID strings; timestamps are epoch
  millis supplied by the clock.
- `FormattingParser` keeps all three representations (`rawTranscript`,
  `normalizedTranscript`, `documentBlocks`). Numbered-list / "next item" directives are
  modeled as `NoteBlock.Bullet` (ordering is positional) since the block model has no
  dedicated ordered-list variant. Code blocks are copied verbatim and are NOT
  re-interpreted for directives (literal). Unmatched bold/italic boundaries and unclosed
  code blocks preserve the content and set `reviewRequired = true`; nothing is discarded.
- `ActionRegistry` is the sole authorizer. Risk tiers: create/list/search/transient-UI =
  LOW; append/update/archive/complete/reschedule/reminder/open-app = MEDIUM; export =
  HIGH (confirmation mandatory). Match confidence can gate a LOW-risk auto-run but can
  never authorize a HIGH / confirmation-mandatory action. Multiple candidate targets
  always return `NeedsChoice` (never auto-pick). Unknown types are rejected on authorize
  and throw `UnknownActionException` on execute.
- `WorkflowRunner` enforces `MAX_STEPS = 5`, rejects empty workflows, checks a
  `CancellationToken` before each step, applies each handler's own timeout via
  `withTimeout`, stops on the first failure (`PartialFailure`), and de-duplicates by
  idempotency key (`AlreadyRun`).
- `core:voice` ships adapter INTERFACES (`AudioSessionController`, `WakeWordDetector`,
  `SpeakerVerifier`, `SpeechRecognizerEngine`, `IntentInterpreter`, `ActionExecutor`) with
  pure in-memory fakes only. No native/ML dependency (sherpa-onnx / whisper.cpp /
  llama.cpp / Picovoice) is introduced, per the privacy/Phase-1 constraints.
- Verified: `./gradlew :core:domain:test :core:interpretation:test :core:actions:test
  :core:voice:test` and the full `./gradlew test` suite pass (67 JVM unit tests, 0
  failures).

### FEAT-003 (persistence layer: Room, repositories, export/backup, settings)

- **Room is wired into `core:data`** (already a `com.android.library` module from
  scaffolding) with runtime + ktx + the KSP compiler. Entities: `NoteEntity`,
  `TaskEntity`, `CommandDefinitionEntity`, `ActionRunEntity`, `VoiceProfileMetadataEntity`.
- **Privacy-shaped entities.** `ActionRunEntity` stores ONLY redacted codes (status /
  result code / error code), never a transcript, note/task text, or raw audio.
  `VoiceProfileMetadataEntity` is metadata only (engine, model version, enrollment date,
  threshold, optional `lastVerifiedAt`) with a DAO delete, so it is user-deletable and
  holds no raw audio or biometric template.
- **Single-column serialization.** A note's structured `List<NoteBlock>` and its tag list
  are serialized to single JSON string columns via `NoteBlockCodec` / `TagListCodec` (in
  `core:domain`). Command argument schema and action sequence use `CommandCodec`. A tiny
  dependency-free JSON codec (`com.voit.domain.Json`) backs all of them so the pure domain
  core pulls in no serialization framework; the same codec is reused for backup, giving a
  single on-disk/in-backup format. Round-trips are covered by tests.
- **Repository interfaces live in `core:domain`** (`NoteRepository`, `TaskRepository`,
  `CommandRepository`, `SettingsRepository`) so UI/ViewModels depend on abstractions.
  Room-backed impls (`RoomNoteRepository`, `RoomTaskRepository`, `RoomCommandRepository`,
  `DataStoreSettingsRepository`) live in `core:data`; in-memory `Fake*` impls live in
  `core:domain` for ViewModel tests and the simulator. Note/task create and update run in
  a single `withTransaction` (atomic); create aborts on a duplicate id and update aborts
  on a missing id, surfaced as `RepositoryConflictException`. Note update reads the row
  inside the transaction to preserve the archived/deleted flags the domain `Note` does not
  carry. Search covers title + tags + block content (notes) and title + body (tasks).
- **Export / backup.** `NoteExporter` renders a note to markdown and plain text.
  `Backup` + `BackupSerializer` + `BackupManager` do a complete local backup/restore of all
  notes/tasks/commands/settings with a tested content round-trip (fakes and Room-backed).
  Backup encryption sits behind the `ExportEncryptor` interface; Phase 1 ships the
  explicit, documented `NoOpExportEncryptor` pass-through, with NO hard-coded secrets. A
  real password/KDF/AEAD impl is deferred (see docs/privacy.md).
- **AppSettings** persist via DataStore preferences (`DataStoreSettingsRepository`),
  exposed as a `Flow`. Defaults are privacy-safe: listening OFF, cloud OFF, recognition
  language `pt-BR` (configurable), review mode ON; model-config fields are placeholders.
- **Room migration + test (no-emulator limitation).** The DB is version 2 with an additive
  `MIGRATION_1_2` adding the nullable `voice_profile_metadata.lastVerifiedAt` column.
  Schema export is enabled (`room.schemaLocation` -> `core/data/schemas/`) and the exported
  v1 + v2 schema JSON are committed (`.gitignore` un-ignores `**/schemas/`). Because this
  sandbox has NO Android emulator/device, Room's instrumented `MigrationTestHelper` cannot
  run here. Per the spec's fallback, the migration is instead validated by a JVM test
  (`MigrationSchemaTest`) that diffs the committed v1 vs v2 schema JSON and asserts the
  delta is exactly the one column the migration SQL adds (and that no other table changes).
  This is schema-level validation, not a live runtime migration run; the limitation is
  recorded here as required by the spec.
- **Robolectric for JVM-runnable Room tests.** The Room-backed repositories, the Room
  backup round-trip, and DataStore are exercised on the JVM via Robolectric against real
  in-memory SQLite / a temp-file DataStore (no device needed). Robolectric's download lock
  and runtime cache are redirected into the writable sandbox tree by setting
  `-Duser.home=/projects/sandbox/fakehome` on the unit-test task (the real `/root` HOME is
  read-only). This is a sandbox-specific test configuration.
- Verified: `./gradlew :core:data:test` (16 tests), full `./gradlew test` (120 JVM unit
  tests, 0 failures), and `./gradlew assembleDebug` (APK built) all pass.

### Phase 2 (local recording & ASR scaffolding)

Phase 2 wires the push-to-talk path end to end **behind fakes**, exactly mirroring how
Phase 1 wired the typed Simulator path. No real audio or model runs here.

**What was BUILT:**

- **Pure recording state machine (`core:voice`).** `RecordingController` +
  `RecordingState` (Idle / Recording / Transcribing / Review) + `RecordingConfig`
  (`maxUtteranceMillis`) + `RecordingErrorCode` (TIMEOUT / RECOGNIZER_FAILURE /
  EMPTY_TRANSCRIPT / CANCELLED, codes only) drive the EXISTING `AudioSessionController` +
  `SpeechRecognizerEngine` interfaces through idle -> recording -> transcribing -> review
  with cancel, a max-utterance timeout, and a fallback to an editable/empty transcript so
  manual entry always stays reachable. This is the **Phase-2 analogue of the Phase-1
  `TranscriptProcessor` wiring**: a pure, device-independent, fully unit-tested state
  holder with no Android, no network, no model.
- **Pure permission decision model + optional on-device ASR adapter (`platform:android`).**
  `VoicePermissionModel` encodes "request only when the feature is enabled", a staged
  rationale (rationale before the system prompt), API-33 gating of `POST_NOTIFICATIONS`,
  and the permanently-denied -> settings path — all unit-tested off-device. The thin
  Android-facing requester delegates every branch to that pure model.
  `AndroidOnDeviceSpeechRecognizer` wraps the Android framework
  `SpeechRecognizer.createOnDeviceSpeechRecognizer()` (API 31+) behind an availability
  guard and routes unavailability to the Phase-2 fallback. It COMPILES but is **not
  runnable/validated here (no device)** and is **not** the default engine.
- **Push-to-talk -> editable review -> `TranscriptProcessor` path wired into the app.** A
  `PushToTalkViewModel` (in `feature:simulator` to avoid `settings.gradle.kts` churn) owns
  a `RecordingController` built from injected engines + config on its own scope (the
  Phase-1 injected-scope pattern), exposes an immutable `PushToTalkUiState` via
  `StateFlow`, and on review-accept hands the (possibly edited) transcript to the EXISTING
  `TranscriptProcessor.process(transcript, source = NoteSource.VOICE)`, persisting via the
  repositories exactly like `SimulatorViewModel.persist`. The high-risk `EXPORT_NOTE`
  `PendingConfirmation` gate is intact (never auto-run). `PushToTalkScreen` is a strictly
  monochrome Compose screen (reusing `VoitScaffold`/`VoitButton`/`VoitTextField`/
  `VoitSoundRing`) with a push-to-talk control, an ALWAYS-editable transcript-review field
  (empty + clearly labelled on the fallback path), an accept action, and the confirm/dismiss
  dialog. A new "Voice" bottom-nav destination reaches it; **the Simulator remains the start
  destination** so the Phase-1 path is unchanged and the app still opens working.
- **DI default is a FAKE.** `VoitContainer` constructs a `FakeSpeechRecognizerEngine`
  (default, safe placeholder) and a `FakeAudioSessionController` with a `RecordingConfig`;
  `VoitViewModelFactory` builds the new ViewModel mirroring the Simulator branch.
- **Manifest.** `RECORD_AUDIO` and `POST_NOTIFICATIONS` are declared (capability only; the
  runtime request is staged by the permission model when the feature is enabled). No
  foreground-service or any Phase-4 permission is added.

**What was DEFERRED and WHY:**

- There is **no device or microphone here**, so real audio capture, on-device ASR
  execution, airplane-mode dictation acceptance, and the **pt-BR / en-US benchmark** are
  deferred to **Phase 0 on hardware** and are NOT claimed as validated.
- The **Phase-0 model selection** (sherpa-onnx vs whisper.cpp vs the Android framework
  on-device recognizer, etc.) is still **pending**; no model weight or native ML dependency
  is bundled, and the DEFAULT wired recognizer is the `FakeSpeechRecognizerEngine` so the
  app runs in an emulator without a model. `AndroidOnDeviceSpeechRecognizer` exists as an
  optional provider/fallback to swap in behind the interface once validated on a device.
- **Privacy invariant (unchanged, re-affirmed):** the raw audio buffer is a transient local
  discarded immediately after recognition; nothing audio/transcript-related is added to
  `action_runs` or any DB table, and the DB schema is unchanged in Phase 2.

### Phase 2 post-review hardening (review-driven, NEEDS_CHANGES -> addressed)

A semantic review of Phase 2 returned NEEDS_CHANGES with four confirmed findings. All four
are addressed here; scope is unchanged (default recognizer stays the fake, no model/native
dependency, no DB/schema change, no sandbox path committed).

- **Permission model wired into the app (was dead code at runtime).** The pure
  `VoicePermissionModel` / `VoicePermissionRequester` were built and unit-tested but never
  invoked, so the Voice screen started capture with no runtime permission check. A new
  Activity-side `VoicePermissionGate` (in `app`) registers an `ActivityResultLauncher` and
  routes the Voice screen's "Start recording" action through the pure model
  (`requester.nextStep(...)`), with the live `listeningEnabled` setting as the feature
  flag; `MainActivity` builds the gate and hands `VoitApp` -> `PushToTalkScreen` an
  `onStartRecording` hook. ALL decision branching stays inside `VoicePermissionModel`. The
  LIVE grant round-trip needs a real device (none here), so the gate proceeds gracefully
  against the default fake session; the wiring is validated by inspection + the existing
  pure-model JVM tests, and NO device behavior is claimed validated. `docs/privacy.md` now
  states precisely what is ACTIVE vs DEFERRED. `app` gains a dependency on `:platform:android`.
- **`CancellationException` no longer swallowed (real bug).** `recognizeTransient` caught
  `Throwable`, turning a coroutine cancellation into a `null` "recognizer failure" result;
  a `cancel()` firing mid-recognition could then publish a `Review` state AFTER `cancel()`
  had already returned to `Idle`, racing cancel's clean-idle contract. It now catches and
  rethrows `CancellationException` before the generic `Throwable -> null` mapping. Pinned by
  `RecordingStateMachineTest.cancel during the transcribing window returns cleanly to idle
  and publishes no review`, which blocks a recognizer mid-flight, cancels, and asserts the
  final state is `Idle` with no `Review`.
- **Empty-buffer marker at the recognize call site.** `recognizeInto` -> `recognizeTransient`
  passes `ByteArray(0)` to `recognize`; the default `FakeSpeechRecognizerEngine` ignores
  its input so this is fine today, but a real engine would get no audio. A clear
  `TODO(phase-0 real capture)` now marks the `ByteArray(0)` site explaining that a real
  capture source must supply the actual captured buffer here, kept transient (never stored
  or persisted) to preserve the privacy invariant.
- **`acceptReview` trim/return behavior documented.** The `PushToTalkViewModel.acceptReview`
  KDoc now states that a whitespace-only (blank-after-`trim`) edit intentionally does
  nothing (no empty note is ever persisted, manual entry stays required) and that the
  controller's returned value is deliberately ignored (the ViewModel owns the trimmed text
  it routes into `TranscriptProcessor`; only the controller's Review -> Idle transition
  matters). No behavior change.

### FEAT-004 (design system + Phase-1 UI, DI, navigation, end-to-end simulator)

- **Monochrome design system (`core:design`).** The palette is strictly black / white /
  neutral grey (`Color.kt`); there are NO accent hues. Material3's `error`/`onError` slots
  are deliberately mapped to black/white too, so even error surfaces stay monochrome. State
  is therefore NEVER encoded by colour alone: the UI uses explicit text labels, iconography,
  checkboxes and shape instead (e.g. notes show a "⚠ NEEDS REVIEW" overline, tasks show a
  checkbox plus a "Done"/"Open" label, disabled settings append "(disabled)"). Added
  `Spacing.kt` (a generous, coarse `VoitSpacing` scale exposed via a CompositionLocal),
  `Type.kt` (system `FontFamily.SansSerif`, a few `sp`-sized styles so font scaling / a11y
  is respected), `Shape.kt` (restrained, mostly-flat rounded corners), a `ThemeMode` enum
  (SYSTEM/LIGHT/DARK) giving the required in-app override hook on top of the system theme,
  and reusable components (`VoitScaffold`, `VoitTopBar`, `VoitButton`, `VoitIconButton`,
  `VoitTextField`, `VoitListItem`, `VoitSectionHeader`) plus the `VoitSoundRing` brand
  symbol (a monochrome ring + waveform drawn with Canvas strokes). Interactive elements set
  content descriptions and keep >= 48dp touch targets for TalkBack / accessibility.
- **`material-icons-core`** (not `-extended`) is used for the handful of needed glyphs
  (Add, Share, Delete, Edit, Build, CheckCircle, Settings, AutoMirrored List/ArrowBack);
  all were confirmed present in the core artifact, avoiding the large extended icon pack.
  `core:design` re-exports Compose foundation/material3/ui + `lifecycle-runtime-compose`
  (`collectAsStateWithLifecycle`) as `api` so feature modules inherit them.
- **MVVM / unidirectional state.** Each feature has a ViewModel exposing an immutable
  `*UiState` via `StateFlow`, depending only on the domain repository abstractions (never
  Room). Every ViewModel accepts an optional injected `CoroutineScope` so the JVM unit
  tests drive them with a `TestScope` + `StandardTestDispatcher` and never touch
  `viewModelScope`/the Android main looper — no Robolectric needed for the ViewModel tests.
- **`TranscriptProcessor` (in `core:actions`).** The transcript -> structured-result
  orchestration (the Phase-1 keystone) is a pure, deterministic class wiring the REAL
  `FormattingParser` + `IntentParser` + `ActionRegistry`. It always produces a structured
  `Note` (title from the first heading, blocks, tags, reviewRequired) and, when a
  `create_task` intent matches, a `TaskItem` linked to the note. The registry remains the
  SOLE authorizer: `export_note` is HIGH risk / confirmation-mandatory, so the processor
  returns a `PendingConfirmation` and NEVER auto-runs it; `confirm()` re-authorizes with
  `confirmed = true`. `core:actions` now depends on `core:interpretation` (both pure JVM
  modules) to host this. This keeps the end-to-end path fully unit-testable off-device.
- **Simulator as the home screen.** The Simulator (text-command stand-in for the mic) is
  the Navigation-Compose start destination because it is what makes the Phase-1 exit
  criteria demonstrable without a device. Routes: Simulator (home), Notes, Note editor
  (`note_editor/{noteId}`), Tasks, Commands, Command editor, Settings. The command editor
  shares the Commands ViewModel scoped to the Commands back-stack entry so the draft
  prepared by the list screen is visible to the editor.
- **Manual DI container (not Hilt).** `VoitContainer` constructs the Room DB (with
  `ALL_MIGRATIONS`), the preferences DataStore, the four repositories, the action registry
  and the transcript processor. `VoitViewModelFactory` builds every ViewModel from it;
  the note editor has a dedicated `noteEditor(noteId)` factory builder for its per-note id.
  A manual container was chosen over Hilt to avoid an extra KSP/plugin and keep the whole
  object graph explicit in one file. `VoitApplication` holds the single process-lifetime
  container; `MainActivity` applies `VoitTheme` with the persisted `themeMode` override and
  hosts the nav graph. The app module gains `room-runtime`/`room-ktx` on its classpath
  because `core:data` keeps Room as `implementation` (not exposed transitively).
- **`AppSettings.themeMode`.** Added a plain `String` theme-override field ("SYSTEM" /
  "LIGHT" / "DARK") to the domain settings (kept a string so the pure domain never depends
  on the Compose `ThemeMode` enum), persisted in DataStore and included in the backup
  round-trip. Settings screen also surfaces recognition language (pt-BR / en-US), a wake
  phrase placeholder, review mode, privacy toggles (cloud processing shown but DISABLED,
  speaker verification disabled placeholder, diagnostics), a clearly-labelled
  "Listening (not yet active)" section, and delete-my-data entry points for notes / tasks /
  commands (voice-profile metadata deletion is noted as arriving with the voice phase).
- **Compose UI tests need instrumentation (no device here), so screens are validated by
  inspection** and by their backing ViewModel JVM unit tests. The end-to-end path is proven
  by `TranscriptProcessorTest` (pure) and `SimulatorViewModelTest` (feeds a transcript,
  asserts a structured note + task are persisted to the fakes, and that the high-risk
  export is gated behind an explicit confirmation). `CommandsViewModelTest` asserts the
  editor only offers the registry allow-list, bounds the action list at 5, and never
  exposes a free-text/code path.
- **Mic / overlay / foreground-service remain intentionally non-functional placeholders in
  Phase 1.** No audio is captured; the Simulator is the manual stand-in. This is unchanged
  from the earlier phases and is reflected in the Settings copy.
- Verified: full `./gradlew test` (161 JVM unit tests, 0 failures) and
  `./gradlew assembleDebug` (BUILD SUCCESSFUL, `app/build/outputs/apk/debug/app-debug.apk`
  produced). Design system reviewed: no accent colours, no colour-only state encoding.

### Post-review hardening (review-driven, Phase 1)

A semantic review of the Phase-1 foundation (APPROVED) raised four confirmed, non-blocking
findings. All four are addressed here, each pinned by a test; scope is unchanged (no native
ML/model deps, no cloud, no real mic/overlay/FGS runtime).

- **Substring intent matching tightened (`IntentParser`).** The lowest-confidence
  `SUBSTRING` rung used to fire whenever a trigger appeared *anywhere* in the input, so an
  incidental fragment could match (e.g. "timer" inside "timers"). It now requires the
  trigger to appear on whole-word boundaries (a standalone run of tokens: each end is
  either the string edge or a space), and single-token aliases shorter than
  `MIN_SUBSTRING_TOKEN_LEN` (4) are excluded from substring matching entirely. The
  ergonomic case "hey could you start a timer please" still matches because the full phrase
  is present as whole words. Pinned by `IntentParserTest.incidentalSubstringDoesNotMatchInsideLongerWord`
  and `...incidentalTriggerPhraseBuriedMidSentenceStillMatchesAsStandaloneTokens`.
- **MEDIUM risk now honours match confidence (`ActionRegistry.authorize`).** Previously a
  MEDIUM-risk mutation (append/update/archive/complete) authorized at ANY confidence as
  long as the target was unambiguous — a strictly weaker gate than LOW risk. A MEDIUM
  mutation is now at least as well-gated as a LOW-risk read: below
  `MEDIUM_RISK_CONFIDENCE_THRESHOLD` (0.6, same as LOW) it falls through to
  `NeedsConfirmation` instead of auto-authorizing. HIGH risk is unchanged (always gated;
  confidence can never authorize it). Pinned by `ActionRegistryTest.mediumRiskLowConfidenceNeedsConfirmation`
  and `...mediumRiskConfidentMatchAuthorized`.
- **`NeedsChoice` / target-resolution contract documented.** The registry does NOT resolve
  targets; it returns `NeedsChoice` ONLY when the caller passes more than one candidate in
  `candidateTargets`. An empty list asserts "unambiguous (or no target)". A caller that
  handles references to existing notes/tasks MUST resolve them and pass the candidates,
  otherwise a genuinely ambiguous reference silently skips the choice gate. This is now
  spelled out in the KDoc on `authorize`. For Phase 1 the simulator never names an existing
  target, so this is latent; the contract is recorded here for the next integrator. (A
  deeper fix — pushing resolution into the authorizer — is deferred as it needs repository
  access the pure `core:actions` module intentionally does not have.)
- **Fallback task title capped (`TranscriptProcessor.firstLine`).** Because
  `FormattingParser` normalization collapses newlines to spaces, the "first line" was the
  whole transcript, so a create-task with no title slot/heading produced an entire
  paragraph as its title. The fallback is now capped to the first `MAX_FALLBACK_TITLE_WORDS`
  (8) words and `MAX_FALLBACK_TITLE_CHARS` (60) characters, with an ellipsis marking
  truncation. Pinned by `TranscriptProcessorTest.create-task fallback title is capped and
  not the entire transcript`.

### Phase 3 (wake word + speaker verification scaffolding)

Phase 3 adds the hands-free trigger path **behind fakes**, exactly mirroring how Phase 2
wired the push-to-talk path and how Phase 1 wired the typed Simulator path. No real audio,
wake-word model, or speaker-verification model runs here.

**What was BUILT:**

- **Pure wake-word + speaker-verification gate state machine (`core:voice`).**
  `WakeWordController` + `WakeState` (Idle / Listening / Detected / Verifying / Triggered /
  Rejected) + `WakeConfig` (`requireSpeakerMatch`, `minSpeakerScore`) + `WakeErrorCode`
  (SPEAKER_MISMATCH / VERIFIER_FAILURE / DETECTOR_FAILURE, codes only) drive the EXISTING
  `WakeWordDetector` + `SpeakerVerifier` interfaces through the always-listening trigger
  lifecycle (idle -> listening -> detected -> verifying -> {triggered | rejected -> rearm})
  over an injected `AudioFrameSource`. On an accepted trigger it invokes a single
  `onTriggered` hand-off seam. This is the **Phase-3 analogue of the Phase-2
  `RecordingController`**: a pure, device-independent, fully unit-tested state holder with
  no Android, no network, and no model. It uses the same patterns as the recorder: injected
  `Clock` + scope + dispatcher, a `StateFlow<WakeState>`, codes-only diagnostics, and
  `CancellationException` rethrown (never swallowed) so a `stop()`/scope-cancel mid-scan
  unwinds cleanly with no late state published.
- **Hand-off seam, not a recorder.** The gate OWNS no recording: `onTriggered` is the one
  place a real always-on listening service would call
  `RecordingController.startPushToTalk()`, so a verified wake-word utterance replaces the
  manual push-to-talk tap. Keeping recording out of the gate preserves the single
  responsibility and lets the two state machines be tested in isolation.
- **DI defaults are FAKES (`VoitContainer`).** `newWakeWordDetector()` returns a
  `FakeWakeWordDetector(shouldDetect = false)` (so even if a gate were started against the
  fake audio session it would never spuriously trigger a capture) and `newSpeakerVerifier()`
  returns a `FakeSpeakerVerifier`. A pure `wakeConfig(speakerVerificationEnabled)` mapper
  derives `WakeConfig.requireSpeakerMatch` from the existing `AppSettings
  .speakerVerificationEnabled` flag, so the "gate on the enrolled speaker only when the user
  opted in" policy lives in one place. No new settings field was needed - `listeningEnabled`,
  `wakePhrase`, `speakerVerificationEnabled`, and `wakeWordModelId` already exist from
  FEAT-003/FEAT-004.
- **Config threshold only ever TIGHTENS the verifier.** An accepted speaker must clear BOTH
  the verifier's own `verified` boolean AND `WakeConfig.minSpeakerScore`; the threshold can
  only reject a borderline true, never authorize a false. This mirrors the Phase-1 rule that
  match confidence can gate down but never authorize up.

**What was DEFERRED and WHY:**

- There is **no device, microphone, or foreground service here** (always-on listening and
  the mic foreground service are Phase 4), so the gate is **NOT started by the app at
  runtime yet**; it is validated by its pure JVM unit tests. Real continuous capture,
  on-device wake-word spotting, and on-device speaker verification are deferred to a
  **Phase-0 hardware benchmark** and are NOT claimed as validated.
- The **Phase-0 model selection** for wake-word and speaker verification (Picovoice vs
  sherpa-onnx keyword spotting / speaker-embedding models, etc.) is still **pending**; no
  model weight or native ML dependency is bundled, and the DEFAULT wired detector/verifier
  are fakes so the app builds and runs in an emulator without a model.
- **Enrollment UI is out of scope for Phase 3.** The gate consumes a `SpeakerVerifier`; the
  enroll-my-voice flow (and the write of the metadata-only `voice_profile_metadata` row)
  lands with the real speaker model on a device. The existing user-deletable metadata entity
  and settings delete entry points already anticipate it.
- **Privacy invariant (unchanged, re-affirmed):** the raw audio frames handed to the
  detector/verifier are transient locals discarded immediately after each scan iteration;
  no biometric template or voice embedding is stored by the gate, and nothing audio- or
  score-related is added to `action_runs` or any DB table. The DB schema is unchanged in
  Phase 3.

- Verified on a real dev machine (JDK 17 via the Gradle-provisioned Adoptium 17 toolchain):
  `./gradlew :core:voice:test` passes (existing Fakes/Recording tests + the 10 new
  `WakeWordStateMachineTest` cases) and `./gradlew :app:compileDebugKotlin` succeeds with
  the Phase-3 container wiring. The 11 `:core:data` Robolectric tests remain a device-less-CI
  gotcha only (they pass on a normal machine); no sandbox path was re-introduced.

### Phase 4 (foreground mic service + global overlay scaffolding)

Phase 4 adds the always-on-listening infrastructure **as pure decision logic behind the
Android boundaries**, exactly mirroring how Phase 2's `VoicePermissionModel` encoded the
permission flow off-device. No live foreground service, notification, or overlay runs here.

**What was BUILT (all in `platform:android`, pure + JVM-unit-tested):**

- **`ForegroundMicServiceModel`** — a pure policy + lifecycle state machine for the
  microphone foreground service. `evaluateStart(MicServiceConditions)` encodes the
  start-eligibility rules with every Android value (SDK_INT + grants) as a plain input:
  hard-refuses when listening is disabled, when `RECORD_AUDIO` is not granted, or (API 34+)
  when `FOREGROUND_SERVICE_MICROPHONE` is missing; otherwise allows the start with the
  `microphone` service type, flagging `notificationVisible = false` as a SOFT gap when
  POST_NOTIFICATIONS is missing on API 33+ (Android still lets the service run; the ongoing
  notification is just hidden). `requiresServiceType(sdkInt)` gates the API-29+ type
  declaration, and `nextState(current, transition)` is an idempotent STOPPED -> STARTING ->
  RUNNING -> STOPPING -> STOPPED lifecycle that refuses illegal/duplicate transitions.
  Diagnostics are codes only (`MicServiceErrorCode`).
- **`OverlayPermissionModel`** — the Phase-4 analogue of `VoicePermissionModel` for the
  `SYSTEM_ALERT_WINDOW` grant. Because an overlay cannot be requested with a system dialog,
  the flow is grant? proceed : (staged in-app explanation -> open the
  `ACTION_MANAGE_OVERLAY_PERMISSION` settings screen). Feature-disabled does nothing;
  below API 23 the grant is implicit. `canDrawOverlays` + SDK_INT + a
  `rationaleAcknowledged` flag are plain inputs, so the staged-explanation-before-settings
  ordering is unit-pinned.
- **Manifest capability (app).** `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, and
  `SYSTEM_ALERT_WINDOW` are declared as CAPABILITY ONLY, the same way Phase 2 declared
  RECORD_AUDIO/POST_NOTIFICATIONS without starting anything. The APK still assembles.

**Design choices:**

- **Models, not a live `Service`/`WindowManager`.** Android 14 throws at runtime for a
  mis-declared foreground-service type / missing `FOREGROUND_SERVICE_MICROPHONE`; encoding
  those rules as pure logic lets us pin them with ordinary tests instead of only
  discovering them on a device. The thin Android service shell + overlay view (added when a
  real device is available) will delegate every start/stop/type/overlay-grant decision to
  these models, keeping them the single source of lifecycle/grant truth.
- **The overlay is Voit's OWN status affordance.** It is explicitly NOT a screen reader and
  NOT an accessibility service; it never reads other apps' screen contents (re-affirming
  docs/privacy.md "No screen scraping"). The model governs only whether Voit may draw its
  own overlay.

**What was DEFERRED and WHY:**

- The **live foreground service, the ongoing notification, and the actual overlay window**
  need a real device/emulator with the runtime permissions actually granted (none here), so
  they are **not wired into the app at runtime yet** and are NOT claimed as validated. The
  always-on `WakeWordController` from Phase 3 is the consumer that a real mic service would
  host; connecting the gate's `onTriggered` seam to `RecordingController.startPushToTalk()`
  inside that service is the on-device integration step.
- **Privacy invariant (unchanged, re-affirmed):** these models govern LIFECYCLE and GRANTS
  only; they never touch audio. The raw-audio-not-retained invariant stays enforced in
  `core:voice`, and nothing audio-related is persisted. Diagnostics remain codes only.

- Verified on a real dev machine (JDK 17 via the Gradle-provisioned Adoptium 17 toolchain):
  `./gradlew :platform:android:test` passes (13 `ForegroundMicServiceModelTest` + 7
  `OverlayPermissionModelTest` added, alongside the existing permission/ASR tests), the full
  `./gradlew test` suite passes, and `./gradlew :app:assembleDebug` builds the APK with the
  Phase-4 manifest capabilities merged. No sandbox path was re-introduced.

### Phase 5 (configurable commands + bounded workflow engine)

Phase 5 closes the gap between the stored command editor and the bounded workflow engine.
Before this phase the pieces existed but were ORPHANED: `feature:commands` persisted a
`CommandDefinition` (allow-listed actions, capped at 5) and `WorkflowRunner` could run a
`Workflow`, but NOTHING matched a transcript against a command's trigger phrases and
dispatched its actions - `WorkflowRunner` had no production caller and no saved command was
ever executed. Phase 5 adds that seam as pure, device-independent logic.

**What was BUILT:**

- **`CommandDispatcher` (`core:actions`).** A pure component that:
  1. matches a transcript against the ENABLED commands' trigger phrases
     (case-insensitive, whole-word boundaries - the same discipline the `IntentParser`
     uses, so "note" never fires inside "notebook"; blank phrases ignored);
  2. applies the command's `ConfirmationPolicy` together with the `ActionRegistry`
     authorization of EACH action - the registry stays the SOLE authorizer, so a HIGH /
     confirmation-mandatory action (e.g. `EXPORT_NOTE`) is ALWAYS gated even under a
     command's `NONE` policy;
  3. on a clear, authorized match builds a bounded `Workflow` from the command's ordered
     actions and runs it through the existing `WorkflowRunner`, so `MAX_STEPS`, per-step
     timeout, cancellation, and idempotency all still apply.
  It returns a sealed `CommandDispatchResult` (`NoMatch` / `NeedsChoice` /
  `NeedsConfirmation` / `Rejected` / `Dispatched(outcome)`). Multiple matching commands
  return `NeedsChoice` and are NEVER auto-picked (mirroring the registry's target-ambiguity
  rule). The default idempotency key is the matched command id, so re-issuing the same
  command is de-duplicated by the runner.
- **DI wiring (`VoitContainer`).** A `WorkflowRunner` and `CommandDispatcher` are now
  constructed in the container, giving the workflow engine its first production caller. No
  new module dependency: `CommandDispatcher` lives in `core:actions` alongside the registry
  and runner and depends only on `core:domain`.

**Design choices:**

- **`IF_AMBIGUOUS` maps to the multi-command choice gate.** A single matched command is
  unambiguous, so the command's own `IF_AMBIGUOUS` policy adds no confirmation; ambiguity
  is surfaced as the multi-match `NeedsChoice`. Per-target ambiguity INSIDE an action stays
  the registry's `NeedsChoice` contract, which the caller resolves (by passing resolved
  `candidateTargets`) before dispatch - unchanged from the FEAT-002 contract.
- **The registry is never bypassed.** Authorization is computed per action; an unknown or
  schema-invalid action rejects the whole command, and any action needing confirmation (or
  an `ALWAYS` policy) gates the command until `confirmed = true` is passed back.

**What was DEFERRED and WHY (honest scope):**

- **No per-action argument capture in the editor yet.** The command editor still models
  actions as `List<ActionType>`, so saved `ActionRequest`s carry empty args and the
  per-command `argumentSchema` stays the default empty schema from the UI (the JSON codec
  and backup already round-trip non-empty schemas). Adding argument capture to the editor is
  a follow-up; the dispatcher already authorizes/validates whatever args a command carries,
  so an action that REQUIRES an arg is correctly `Rejected` today rather than run with a
  missing arg.
- **Handlers still echo.** All `DefaultHandlers` remain `EchoActionHandler` placeholders;
  wiring them to the real repositories/platform services (so a dispatched workflow actually
  mutates notes/tasks) is deferred. The dispatch PATH is complete and tested; the handler
  SIDE-EFFECTS are the next increment.
- **No `ActionRunEntity` audit write yet.** The redacted-code audit sink
  (`action_runs`) exists but nothing writes a `WorkflowOutcome` to it. Mapping outcome ->
  status/resultCode/errorCode (codes only, no content) is a small follow-up kept out of this
  change to keep the phase focused on the execution seam.
- **Privacy invariant (unchanged):** the dispatcher handles typed actions only; no raw
  audio, no transcript content, and nothing is persisted by it. Diagnostics stay codes only.

- Verified on a real dev machine (JDK 17 via the Gradle-provisioned Adoptium 17 toolchain):
  `./gradlew :core:actions:test` passes (12 new `CommandDispatcherTest` cases alongside the
  existing registry/runner/processor tests), the full `./gradlew test` suite passes, and
  `./gradlew :app:assembleDebug` builds the APK with the Phase-5 DI wiring. No sandbox path
  was re-introduced.

### Phase 5 follow-up (real command execution effects + redacted audit trail)

The first Phase-5 change built the pure `CommandDispatcher` execution SEAM but left two
gaps it documented: the handlers still echoed (a dispatched command had no real effect) and
nothing wrote the `action_runs` audit record. This follow-up closes both.

**What was BUILT:**

- **Real, repository-backed handlers (`core:actions/RealHandlers.kt`).** Concrete
  `ActionHandler`s for the persistence-capable note/task actions - `CREATE_NOTE`,
  `APPEND_TO_NOTE`, `ARCHIVE_NOTE`, `CREATE_TASK`, `COMPLETE_TASK`, `RESCHEDULE_TASK` -
  that call the existing `NoteRepository` / `TaskRepository` instead of echoing. Each keeps
  the EXACT contract (type/schema/permissions/risk/confirmationMandatory) of its echo
  counterpart, so the `ActionRegistry` authorization policy and the `WorkflowRunner` bounds
  are unchanged; only `execute` is real. Each handler's `ActionResult.output` carries only
  the created/affected id (never content).
- **`DefaultHandlers.withRepositories(notes, tasks, clock, ids)`.** A production handler-set
  factory that overrides the echo defaults with the real handlers BY `ActionHandler.type`,
  so the registry still has exactly one handler per allow-listed type (real handlers
  replace, never add). The no-arg `DefaultHandlers.all()` (all echo) stays the default so
  the pure `core:actions` module and its tests need no repository. `VoitContainer` now
  builds its registry via `withRepositories`, giving the command engine real effects.
- **Redacted audit trail.** A domain `ActionRun` model + `ActionRunRepository` interface
  (`core:domain`) with fixed code vocabularies (`ActionRunStatus`: SUCCESS / PARTIAL_FAILURE
  / CANCELLED / REJECTED / ALREADY_RUN; `ActionRunErrorCode`: STEP_TIMEOUT / UNKNOWN_ACTION
  / STEP_FAILED / WORKFLOW_REJECTED). `RoomActionRunRepository` (`core:data`) persists to the
  pre-existing `action_runs` table (append-only + user `clear`). `ActionRunRecorder`
  (`core:actions`) maps a `CommandDispatchResult` / `WorkflowOutcome` to ONE code-only
  record. All three are wired in `VoitContainer`.

**Design choices:**

- **Redaction happens in the recorder.** The runner's per-step failure reason can contain a
  handler's exception message (potentially echoing user content), so it is NEVER stored.
  `ActionRunRecorder` classifies the failure CLASS from the outcome SHAPE (and from the
  runner's own fixed, content-free reason prefixes like "Unknown action type ..."/"... timed
  out") into a stable `ActionRunErrorCode`. A test pins that a reason containing fake
  "sensitive" content is reduced to `STEP_FAILED` and never appears on the stored record.
- **Only executed results are audited.** `NoMatch` / `NeedsChoice` / `NeedsConfirmation`
  write nothing (nothing ran / waiting on the user); `Rejected` and `Dispatched` write one
  record each.
- **Trigger matching tolerates punctuation.** `CommandDispatcher`'s whole-word match now
  treats any non-alphanumeric char (not just a space) as a boundary, so "take a note."
  matches the phrase "note" while "notebook" still does not - the natural punctuation a
  transcript carries no longer blocks a legitimate match.

**What is STILL deferred (unchanged from the first Phase-5 note):**

- **Per-action argument capture in the command editor.** The editor still models actions as
  `List<ActionType>`, so a command's saved `ActionRequest`s carry empty args. A command that
  needs an arg (e.g. `APPEND_TO_NOTE` requires `noteId` + `body`) therefore validates to
  `Rejected` at dispatch today rather than running - correct and safe, but the editor UI to
  supply those args (keyed off each handler's `ParamSpec` schema) plus an enable/disable
  toggle remains a follow-up.
- **Non-persistence handlers remain echo** (timers, overlay, listening, open-app, speak,
  export) - they need platform runtime from later phases.
- **Privacy invariant (re-affirmed):** the only new persistence is the redacted
  `action_runs` record (codes + ids only). No transcript, matched phrase, note/task content,
  or audio is stored. The DB schema is unchanged (the table already existed from FEAT-003).

- Verified on a real dev machine (JDK 17): `./gradlew :core:actions:test` (8 new
  `ActionRunRecorderTest` + 6 new `RealHandlersTest` alongside the existing suite),
  `./gradlew :core:data:test`, the full `./gradlew test`, and `./gradlew :app:assembleDebug`
  all pass. No sandbox path re-introduced.

### Phase 5 completion (editor per-action arguments, enable toggle, run-from-UI)

This closes the last Phase-5 TODO: the command editor now captures PER-ACTION arguments
and an enable/disable flag, and a stored command can be RUN from the UI, dispatched through
the engine wired earlier and audited.

**What was BUILT:**

- **Per-action arguments in the editor (`feature:commands`).** `CommandDraft.actions` is now
  `List<DraftAction>` where each `DraftAction(type, rawArgs: Map<String, String>)` holds the
  user's raw string input per parameter. The editor renders one text field per declared
  schema parameter (name + required/optional label), sourced from
  `registry.handlerFor(type)?.schema`, so it never offers a field the action does not
  declare and still has NO free-text action path. At save, `CommandsViewModel.buildArgs`
  parses each raw string into the typed `ParamValue` for its `ParamType` (TEXT/NUMBER/BOOL/
  DURATION/TIMESTAMP/TAGS); a blank optional arg is omitted, and a blank-required or
  unparseable arg ABORTS the save with a precise error rather than persisting a command that
  would only be `Rejected` at dispatch. `editExisting` round-trips typed args back to raw
  strings so an existing command edits cleanly.
- **Enable/disable toggle.** `CommandDraft.enabled` + a `Switch` in the editor; the saved
  `CommandDefinition.enabled` is now user-controlled (was hard-coded true). A disabled
  command shows the existing "DISABLED" overline and offers no Run action (the dispatcher
  also filters disabled commands, so this is defense in depth).
- **Run a command from the list.** An enabled command's row has a Run (PlayArrow) action.
  `CommandsViewModel.runCommand` dispatches the command's first trigger phrase through the
  injected `CommandDispatcher` and records the result via the injected `ActionRunRecorder`,
  surfacing a short `runMessage` (status only, never content). The dispatcher/recorder are
  OPTIONAL constructor params (null in the editor-only tests), and `VoitViewModelFactory`
  now passes the container's real `commandDispatcher` + `actionRunRecorder` — closing the
  "wired in DI but never invoked from a screen" gap noted in the previous entry.

**Design choices:**

- **Raw-string draft, typed at the boundary.** The editor holds raw strings so a
  partially-typed number/timestamp does not crash the UI; conversion to the strict
  `ParamValue` happens once, at save, keeping the registry's "free text is never executed
  untyped" invariant intact.
- **Run matches the command against ITSELF.** `runCommand` dispatches `listOf(command)`
  with the command's own trigger as the transcript, so a tapped run is deterministic and
  cannot be hijacked by another command's phrase; the normal multi-command matching still
  applies on the real transcript path.

**What remains for later phases (unchanged):** non-persistence handlers (timers, overlay,
listening, open-app, speak, export) stay echo placeholders pending platform runtime; the
real on-device voice models await the Phase-0 benchmark. Privacy invariant re-affirmed: the
only new persistence is the redacted `action_runs` record; argument values live only on the
command the user authored, never in diagnostics.

- Verified on a real dev machine (JDK 17): `./gradlew :feature:commands:test` (9 tests: the
  4 originals + 5 new covering arg capture/parse/persist, required-arg abort, enable toggle,
  edit round-trip, and run-with-audit), the full `./gradlew test`, and
  `./gradlew :app:assembleDebug` all pass. No sandbox path re-introduced.
