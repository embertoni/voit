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
