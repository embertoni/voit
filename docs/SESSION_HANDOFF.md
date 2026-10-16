# Voit — Session Handoff / Resume Context

> Drop-in context so you can resume this project from another machine (e.g. the Kiro
> desktop IDE). Committed to the repo under `docs/` so it travels with the code.
> Snapshot taken at the end of the Phase-2 build session.

---

## 1. What Voit is

**Voit** is an **Android-only, local-first, privacy-first** personal voice assistant
(native **Kotlin + Jetpack Compose**) for note-taking, command-driven organization, and
small daily tasks. The authoritative product/engineering spec is the original context
document provided at project start; the running decision log lives in
**`docs/decisions.md`** (read this first), with `docs/models.md` and `docs/privacy.md` as
supporting docs.

Core principles enforced in code: local by default (no cloud/network in the core path),
no arbitrary code execution (custom commands are a typed allow-listed action registry only),
and no raw audio/transcripts persisted by default.

- Repo: `embertoni/voit` (GitHub). Default branch: `main`.
- Package base `com.voit`; minSdk 26, compile/targetSdk 36, jvmTarget 17.
- Build: Gradle Kotlin DSL, version catalog `gradle/libs.versions.toml`, committed
  `./gradlew` wrapper. AGP 8.7.3, Kotlin 2.0.21, Gradle 8.14.5, Room 2.6.1,
  DataStore 1.1.1, Robolectric 4.13. Manual DI container `VoitContainer` (NOT Hilt).
  `settings.gradle.kts` uses the Foojay toolchain resolver plugin.

---

## 2. Phased plan & where we are

| Phase | Scope | Status |
|-------|-------|--------|
| **Phase 1** | Offline notes/tasks, deterministic formatting + intent parsers, typed allow-listed action registry, Room persistence + export/backup, monochrome design system, text-command **Simulator** (mic stand-in) | ✅ **DONE & MERGED to `main`** (was PR #1) |
| **Phase 0** | On-device benchmark spikes: pick real ASR/VAD/wake-word/speaker models (sherpa-onnx vs whisper.cpp etc.), mic foreground-service survival, overlay behavior | ⏸️ **DEFERRED** — needs real Android hardware |
| **Phase 2** | Local recording + ASR: `RECORD_AUDIO`/`POST_NOTIFICATIONS` permission onboarding, push-to-talk recording state machine, utterance timeout/cancel/error, transcript review → existing note/task pipeline. Device parts stand in with fakes. | 🟡 **BUILT & PUSHED** on branch `feat/phase-2-recording-asr`; **PR not opened yet** |
| Phase 3 | Wake word + speaker verification | ⬜ Not started |
| Phase 4 | Foreground mic service + global overlay | ⬜ Not started |
| Phase 5 | Configurable commands & workflow engine (editor + bounded runner) | ⬜ Not started |
| Phase 6 | Optional local LLM + polish | ⬜ Not started |
| Phase 7 | Release hardening + personal deployment | ⬜ Not started |

---

## 3. EXACT git state at handoff (IMPORTANT)

- Work branch: **`feat/phase-2-recording-asr`** — **pushed to GitHub** and tracking
  `origin/feat/phase-2-recording-asr`.
- Based on the merged `origin/main` (HEAD of main = the Phase-1 merge commit).
- **No PR has been opened for Phase 2 yet** — the user intends to review and open/merge it.
- Phase-2 commits on the branch (oldest → newest):
  1. `1d5ea9b` feat: add pure push-to-talk recording state machine in core:voice
  2. `307617d` feat: add pure permission decision model + optional Android on-device ASR adapter in platform:android
  3. `a5f5599` feat: wire push-to-talk voice capture to editable review and TranscriptProcessor
  4. `1da5c73` fix: address Phase-2 review - wire permission model, rethrow CancellationException, mark empty audio buffer, document acceptReview
  (This handoff doc adds one more commit on top.)

### To resume on the Kiro desktop IDE
```bash
git clone https://github.com/embertoni/voit.git
cd voit
git fetch origin
git checkout feat/phase-2-recording-asr   # the pushed Phase-2 branch
```
Then open a PR into `main` when ready, review, and merge.

---

## 4. What Phase 2 added (files on this branch, ~2.3k lines)

New/changed, grouped by module:

- **`core:voice`**
  - `RecordingStateMachine.kt` — pure, testable push-to-talk state machine (idle →
    recording → transcribing → review; cancel + max-utterance timeout + silence/stop),
    driving the existing `AudioSessionController` + `SpeechRecognizerEngine` interfaces.
  - `RecordingStateMachineTest.kt` — drives it with `FakeAudioSessionController` +
    `FakeSpeechRecognizerEngine`, injectable clock/dispatcher; asserts success → transcript,
    timeout → bounded capture + recoverable error, cancel → clean idle, empty/failed
    recognition → fallback to editable transcript / manual entry.
- **`platform:android`**
  - `VoicePermissionModel.kt` + `VoicePermissionModelTest.kt` — pure, unit-testable
    permission **decision** logic (staged rationale, request-only-when-enabled).
  - `VoicePermissionRequester.kt` — thin Android runtime-grant wrapper.
  - `AndroidOnDeviceSpeechRecognizer.kt` + `OnDeviceAsrSupportTest.kt` — OPTIONAL adapter
    around Android `SpeechRecognizer.createOnDeviceSpeechRecognizer()` (API 31+) behind the
    `SpeechRecognizerEngine` interface, guarded by an availability check. **Compiles but
    cannot be RUN/validated in a device-less sandbox.** NOT wired as the default engine.
  - (removed the old `Placeholder.kt`)
- **`app`**
  - `VoicePermissionGate.kt` — Compose permission gate; `MainActivity.kt`, `VoitApp.kt`,
    `VoitContainer.kt`, `VoitViewModelFactory.kt` wiring; `AndroidManifest.xml` gains
    `RECORD_AUDIO` + `POST_NOTIFICATIONS`. **Default ASR engine wired into the app is still
    a safe fake/placeholder** so the app runs in an emulator without a real model.
- **`feature:simulator`**
  - `PushToTalkScreen.kt` + `PushToTalkViewModel.kt` + `PushToTalkViewModelTest.kt` —
    end-to-end "speak (faked) → editable transcript review → existing `TranscriptProcessor`
    / `FormattingParser` pipeline → structured note/task", high-risk actions still gated.
- **`docs/`** — `decisions.md`, `models.md`, `privacy.md` updated with Phase-2 decisions,
  what's deferred and why (no device; Phase-0 model selection still pending), and the
  "no raw audio retained" invariant.

Privacy invariants upheld: audio buffer discarded after recognition; nothing audio-related
added to the `ActionRun` audit entity or any DB table; no cloud/network in the core path.

---

## 5. Verification status

- **Phase-2 modules pass on the JVM:** `./gradlew :core:voice:test :platform:android:test
  :feature:simulator:test` → **BUILD SUCCESSFUL** (verified).
- **Known device-less-CI gotcha (NOT a code defect):** the 11 `:core:data` Robolectric tests
  (`RoomRepositoryTest`, `RoomBackupRoundTripTest`, `DataStoreSettingsTest`) fail in a
  read-only-HOME CI/sandbox with `MavenDependencyResolver` / `FileNotFoundException` because
  Robolectric can't write its download lock / cache the `android-all` jar under a read-only
  home. They **pass on a normal machine** (writable HOME + network to Maven Central), which
  was confirmed earlier. A prior fix removed all hard-coded sandbox paths from committed
  files — **do NOT re-introduce any absolute sandbox path or `user.home` override into
  committed files.**

### How to build/test on a normal dev machine (Kiro desktop)
```bash
./gradlew test             # all JVM unit tests (Robolectric downloads android-all on 1st run)
./gradlew assembleDebug    # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug     # install on a connected device/emulator
```
Requires JDK 17 (Gradle 8.14.5 rejects newer JDKs) + Android SDK API 36 (Android Studio
sets `sdk.dir` in the git-ignored `local.properties`).

---

## 6. Immediate next steps (pick up here)

1. **Open the Phase-2 PR** from `feat/phase-2-recording-asr` into `main`
   (e.g. title "Voit Phase 2: push-to-talk recording + ASR scaffolding + permission
   onboarding"), review, and merge when satisfied. The branch is already on GitHub.
2. After merge, **Phase 3** (wake word + speaker verification) is the next phase — but like
   Phase 2/0 it is heavily device-dependent, so expect the same pattern: build the
   device-independent, unit-testable logic behind the existing `WakeWordDetector` /
   `SpeakerVerifier` interfaces (fakes already exist in `core:voice/Fakes.kt`), and defer
   real on-device model work to a Phase-0 benchmark on hardware.
3. The real ASR/VAD/wake-word/speaker model selection (sherpa-onnx vs whisper.cpp vs
   Picovoice, etc.) still awaits a **Phase-0 on-device benchmark** that can only run on a
   physical Android phone.

---

## 7. How to exercise the new Phase-2 flow

Run the app in an emulator (`./gradlew installDebug`). The push-to-talk entry point drives
a **faked** recognizer (no real mic/model yet): trigger capture → an editable transcript
appears for review → accept → it flows through the Phase-1 parsing pipeline into a
structured note/task, with high-risk actions (e.g. export) still gated behind explicit
confirmation. This mirrors how the Phase-1 Simulator stood in for the microphone.
Real on-device dictation (and the pt-BR/English accuracy benchmark) needs a physical device
and the Phase-0 model choice.
