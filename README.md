# Voit

Voit is an Android-only, local-first, privacy-first personal voice assistant for
note-taking, command-driven organization, and small daily tasks. It is built as a
native Kotlin + Jetpack Compose application and keeps the core path entirely on
the device.

## Architecture (brief)

Voit is a multi-module Gradle project (Kotlin DSL, with a pinned version catalog
at `gradle/libs.versions.toml`). It follows MVVM with unidirectional state,
repository interfaces, Coroutines + Flow, and a small manual DI container
(`VoitContainer`, not Hilt).

Modules:

- **`app`** — Compose `MainActivity`, navigation, and DI wiring.
- **`core:domain`** — pure-Kotlin models (structured `NoteBlock`, `Note`,
  `TaskItem`, `CommandDefinition`, `Intent`), `Clock`/`IdGenerator`, repository
  interfaces and in-memory fakes.
- **`core:data`** — Room entities/DAOs/database + migrations, repository
  implementations, export/backup, and DataStore settings.
- **`core:interpretation`** — deterministic `FormattingParser` and
  `IntentParser`/`SlotParser` (no LLM).
- **`core:actions`** — typed, allow-listed `ActionRegistry` (the sole
  authorizer), a bounded `WorkflowRunner`, and `TranscriptProcessor`.
- **`core:voice`** — engine-adapter interfaces (`AudioSessionController`,
  `WakeWordDetector`, `SpeakerVerifier`, `SpeechRecognizerEngine`,
  `IntentInterpreter`, `ActionExecutor`) with fakes only — no native/ML deps yet.
- **`core:design`** — monochrome black/white/grey Compose design system.
- **`platform:android`** — Android-framework boundary (foreground
  service/overlay/permission scaffolding; non-functional in Phase 1).
- **`feature:{notes,tasks,commands,settings,simulator}`** — Compose screens and
  ViewModels. The **Simulator** is the text-command stand-in for the microphone
  and is the app's start destination.

Privacy invariants: local-first with no cloud/network in the core path; no
arbitrary code execution (custom commands are typed-registry only); the
action-audit record holds only redacted status/result/error codes — never
transcripts or audio. See [`docs/decisions.md`](docs/decisions.md),
[`docs/models.md`](docs/models.md), and [`docs/privacy.md`](docs/privacy.md).

## Project status / phases

- **Phase 1 (done)** — foundation: modules, domain model, persistence,
  deterministic interpretation, typed action registry, design system, and the
  Simulator-driven end-to-end flow. All JVM unit tests pass.
- **Phase 0 (deferred)** — on-device benchmark go/no-go that selects the local
  ASR / wake-word / speaker-verification models. Later phases depend on it.
- **Phases 2–4 (deferred)** — live mic/ASR, wake word, speaker verification,
  foreground service, and the global overlay. These require a real device and
  native ML runtimes that are intentionally not bundled yet.

## Requirements

- JDK 17 (the project builds under JDK 17; Gradle 8.14.5 does not support newer
  JDKs — JDK 17 is recommended).
- Android SDK with API 36 and build-tools 36.0.0, normally installed via Android
  Studio.
- `local.properties` (git-ignored) must point `sdk.dir` at your SDK location.
  Android Studio writes this automatically.

Key versions: AGP 8.7.3, Kotlin 2.0.21, Gradle 8.14.5 (via the committed
`./gradlew` wrapper), Room 2.6.1, DataStore 1.1.1. Package base `com.voit`;
minSdk 26, compileSdk 36, targetSdk 36, jvmTarget 17.

> **Note (CI/sandbox):** when the home directory is read-only, builds may need
> `GRADLE_USER_HOME` and/or a `-Duser.home` override. See
> [`docs/decisions.md`](docs/decisions.md) for details. The normal developer
> workflow below does not require these.

## Testing

JVM unit tests are the current test surface and they run without a device. Use
the committed Gradle wrapper (`./gradlew`, or `gradlew.bat` on Windows).

Run all module unit tests:

```bash
./gradlew test
```

As of Phase 1 this runs 166 passing tests. For a clean run:

```bash
./gradlew clean test
```

Run a single module's tests:

```bash
./gradlew :core:actions:test
./gradlew :core:domain:test
./gradlew :core:data:test
```

HTML reports land under each module's `build/reports/tests/`.

The `core:data` Room/DataStore tests run on the JVM via Robolectric (real
in-memory SQLite and a temp-file DataStore), so no device is needed.

Notes on Phase 1 test scope:

- There are intentionally **no instrumented / `androidTest` tests yet** — those
  require a device or emulator and arrive with later phases.
- Room migrations are validated by diffing the committed exported-schema JSON
  (schemas are committed on purpose) rather than an instrumented
  `MigrationTestHelper`.
- The end-to-end flow is demonstrable **without a device** via the Simulator
  screen: type a transcript and watch it become a structured note/task.
  High-risk actions such as export are gated behind an explicit confirmation.

## Deployment

### Phase 1

Phase 1 "deployment" means installing the debug APK on a device or emulator.

Build the debug APK:

```bash
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`.

Install onto a connected device/emulator:

```bash
./gradlew installDebug
```

Alternatively, build `assembleDebug` and sideload the APK manually.

The full local verification used in Phase 1 is:

```bash
./gradlew clean test assembleDebug
```

Because the app has no real microphone/overlay/foreground-service behavior yet
(the Simulator stands in), most of Phase 1 is exercisable in an emulator.

### Later phases (2–4)

Live mic/ASR, wake word, speaker verification, the foreground service, and the
global overlay require a **real Android device** — an emulator is not
sufficient. These phases depend on the Phase-0 on-device benchmark go/no-go that
selects the local ASR / wake-word / speaker-verification models.

Native ML runtimes (for example sherpa-onnx, whisper.cpp, llama.cpp, Picovoice)
and model assets are deliberately not bundled yet and will be added behind the
existing engine-adapter interfaces when those phases land. Large model files
should not be committed to Git — see [`docs/models.md`](docs/models.md).

### Release / distribution

A release build is R8-minified and targets API 36+ for Google Play. Distribution
options are personal sideload or the Play Store. Verify current Play target-API
and policy requirements before a store release. Keep signing keys and secrets
out of the repo — never commit a keystore or an API secret.
