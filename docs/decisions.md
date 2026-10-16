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
