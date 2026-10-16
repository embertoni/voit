# Voit — Session Handoff / Resume Context

> Drop-in context so you can resume this project from another machine (e.g. the Kiro
> desktop IDE). Committed to the repo under `docs/` so it travels with the code.
> Snapshot taken at the end of the Phase-5 build session.

---

## 1. What Voit is

**Voit** is an **Android-only, local-first, privacy-first** personal voice assistant
(native **Kotlin + Jetpack Compose**) for note-taking, command-driven organization, and
small daily tasks. The authoritative product/engineering spec is the original context
document provided at project start; the running decision log lives in
**`docs/decisions.md`** (read this first — it has a per-phase entry for Phases 1–5), with
`docs/models.md` and `docs/privacy.md` as supporting docs.

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
| **Phase 1** | Offline notes/tasks, deterministic formatting + intent parsers, typed allow-listed action registry, Room persistence + export/backup, monochrome design system, text-command **Simulator** (mic stand-in) | ✅ **DONE & MERGED** (PR #1) |
| **Phase 0** | On-device benchmark spikes: pick real ASR/VAD/wake-word/speaker models (sherpa-onnx vs whisper.cpp etc.), mic foreground-service survival, overlay behavior | ⏸️ **DEFERRED** — needs real Android hardware |
| **Phase 2** | Local recording + ASR scaffolding: permission onboarding, push-to-talk recording state machine, utterance timeout/cancel/error, transcript review → note/task pipeline. Device parts stand in with fakes. | ✅ **DONE & MERGED** (PR #2) |
| **Phase 3** | Wake word + speaker verification scaffolding: pure hands-free gate state machine behind the existing detector/verifier interfaces, fakes-driven. | ✅ **DONE & MERGED** (PR #3) |
| **Phase 4** | Foreground mic service + global overlay scaffolding: pure start-eligibility/lifecycle + overlay-permission decision models behind the Android boundaries; manifest capabilities declared. | ✅ **DONE & MERGED** (PR #4) |
| **Phase 5** | Configurable commands + bounded workflow engine: pure `CommandDispatcher` connecting stored commands' trigger phrases to the `WorkflowRunner`, wired into DI. | 🟡 **BUILT & PUSHED** on branch `feat/phase-5-commands-workflow`; **PR status: being merged by the user** |
| Phase 6 | Optional local LLM + polish | ⬜ Not started |
| Phase 7 | Release hardening + personal deployment | ⬜ Not started |

Phases 2–4 all followed the same discipline, which is the template for every remaining
device-dependent phase: build the **device-independent, unit-testable** logic behind the
existing engine/boundary interfaces (fakes in `core:voice`; pure decision models in
`platform:android`), declare any new manifest capability as capability-only, and DEFER the
real on-device model/runtime work to a **Phase-0 hardware benchmark**.

---

## 3. EXACT git state at handoff (IMPORTANT)

- Default branch `main` HEAD = the **Phase-4 merge commit** (`7972e1a`, PR #4).
- Work branch: **`feat/phase-5-commands-workflow`** — **pushed to GitHub** and tracking
  `origin/feat/phase-5-commands-workflow`, based on the merged `main`.
- Phase-5 commits on the branch (oldest → newest):
  1. `e824cb3` feat: add pure CommandDispatcher connecting stored commands to the bounded WorkflowRunner
  2. `12a8b92` feat: wire the Phase-5 WorkflowRunner + CommandDispatcher into DI and document the phase
  (This handoff doc update adds one more commit on top.)
- Merge pattern used so far: each phase is a short-lived `feat/phase-N-*` branch, opened as
  a PR into `main`, reviewed, and merged by the user (PRs #1–#4 merged). Phase 5's PR is the
  one in flight.

### To resume on another machine (e.g. the Kiro desktop IDE)
```bash
git clone https://github.com/embertoni/voit.git
cd voit
git fetch origin
# If Phase 5 is already merged, branch the next phase off main:
git checkout main && git pull --ff-only origin main
git checkout -b feat/phase-6-...
# If Phase 5 is NOT yet merged, continue it:
git checkout feat/phase-5-commands-workflow
```

---

## 4. Build environment (READ THIS — differs from the original sandbox)

The project was originally built in a Linux sandbox; those notes in `docs/decisions.md`
§1 (read-only `/root`, `/projects/sandbox`, a mise JDK path) are HISTORICAL. The current
dev machine is **Windows (PowerShell)**. Two things matter to get a green build:

- **JDK 17 is required.** The Kotlin 2.0.21 toolchain the Gradle build runs CANNOT parse a
  JDK 25 version string (`IllegalArgumentException: 25.0.3` from
  `com.intellij...JavaVersion.parse`), so a JDK 21/25 launcher fails the build. On this
  machine the only JDK 17 is the one Gradle itself provisioned via the Foojay toolchain
  resolver, at:
  ```
  %USERPROFILE%\.gradle\jdks\eclipse_adoptium-17-amd64-windows.2   (Temurin 17.0.20.1)
  ```
  Run every Gradle command with that as `JAVA_HOME`, e.g. in PowerShell:
  ```powershell
  $env:JAVA_HOME="$env:USERPROFILE\.gradle\jdks\eclipse_adoptium-17-amd64-windows.2"
  .\gradlew.bat test
  ```
  If that directory is gone, install any JDK 17 (e.g. Temurin 17) and point `JAVA_HOME` at
  it, or let the Foojay toolchain re-download it.
- **`local.properties` is required and git-ignored.** It points `sdk.dir` at the Android
  SDK. On this machine:
  ```
  sdk.dir=C\:\\Users\\ember\\AppData\\Local\\Android\\Sdk
  ```
  (SDK has platforms android-35/36/37.) **Never commit `local.properties`**, and — per the
  standing rule — **never re-introduce any absolute sandbox path or `user.home` override
  into any committed file.** A prior fix removed all such paths; keep it that way.

### Build/test commands (with `JAVA_HOME` set as above)
```powershell
.\gradlew.bat test             # all JVM unit tests (Robolectric downloads android-all on 1st run)
.\gradlew.bat assembleDebug    # -> app/build/outputs/apk/debug/app-debug.apk
.\gradlew.bat installDebug     # install on a connected device/emulator
```

---

## 5. What each voice/command phase added (for quick orientation)

- **Phase 2 — `core:voice` + `platform:android` + `feature:simulator` + `app`.**
  `RecordingController` / `RecordingState` push-to-talk state machine (idle → recording →
  transcribing → review; timeout/cancel/fallback), `VoicePermissionModel` (pure permission
  decisions) + `VoicePermissionRequester` + `VoicePermissionGate`, optional
  `AndroidOnDeviceSpeechRecognizer`, and the push-to-talk → editable review →
  `TranscriptProcessor` path. Default engines are FAKES.
- **Phase 3 — `core:voice` + `app`.** `WakeWordController` / `WakeState` hands-free gate
  (listening → detected → verifying → {triggered | rejected → rearm}) over the existing
  `WakeWordDetector` / `SpeakerVerifier` interfaces, with a single `onTriggered` hand-off
  seam (where a real listening service would call `RecordingController.startPushToTalk`).
  DI defaults are fakes (detector NOT-detecting by default). Maps `requireSpeakerMatch` to
  `AppSettings.speakerVerificationEnabled`.
- **Phase 4 — `platform:android` + `app`.** `ForegroundMicServiceModel` (pure
  start-eligibility: feature on + `RECORD_AUDIO` + API-34 `FOREGROUND_SERVICE_MICROPHONE`;
  soft POST_NOTIFICATIONS gap; API-29 service-type gate; idempotent lifecycle) and
  `OverlayPermissionModel` (the `SYSTEM_ALERT_WINDOW` analogue of `VoicePermissionModel`).
  Manifest declares `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MICROPHONE` /
  `SYSTEM_ALERT_WINDOW` as CAPABILITY-ONLY. No live service/overlay started yet.
- **Phase 5 — `core:actions` + `app`.** `CommandDispatcher` matches a transcript against
  enabled commands' trigger phrases (case-insensitive, whole-word), authorizes each action
  via `ActionRegistry` (sole authorizer; HIGH/confirmation-mandatory always gated), and
  runs a clear match's bounded actions through `WorkflowRunner` (MAX_STEPS / per-step
  timeout / idempotency). Returns `NoMatch` / `NeedsChoice` / `NeedsConfirmation` /
  `Rejected` / `Dispatched(outcome)`. Wired into `VoitContainer` — the workflow engine's
  first production caller.

Privacy invariants upheld across all phases: raw audio frames/buffers are transient locals
discarded immediately, never persisted; no biometric template stored (`voice_profile_metadata`
is metadata-only + user-deletable); nothing audio/transcript-related in `action_runs` or any
DB table; diagnostics are stable codes only; no cloud/network in the core path.

---

## 6. Verification status

- **Full JVM suite passes on this machine:** `.\gradlew.bat test` → **BUILD SUCCESSFUL**,
  and `.\gradlew.bat assembleDebug` builds the APK. New per-phase tests:
  `WakeWordStateMachineTest` (10), `ForegroundMicServiceModelTest` (13) +
  `OverlayPermissionModelTest` (7), `CommandDispatcherTest` (12).
- **The `:core:data` Robolectric tests PASS here** (writable HOME + network to Maven
  Central). They only fail in a read-only-HOME CI/sandbox (Robolectric can't write its
  download lock / cache `android-all`) — a device-less-CI gotcha, NOT a code defect. Do
  **not** re-introduce any sandbox path or `user.home` override to "fix" it.
- **Device-dependent runtime is NOT validated** (no phone): real ASR/VAD/wake-word/speaker
  models, live mic foreground service, overlay window, and the pt-BR/English accuracy
  benchmark all await a **Phase-0 hardware benchmark**. The scaffolding compiles and its
  pure logic is unit-tested off-device; no model weights / native ML runtime are bundled.

---

## 7. Immediate next steps (pick up here)

1. **Merge the Phase-5 PR** (`feat/phase-5-commands-workflow` → `main`) if not already done.
2. **Finish wiring Phase 5 end-to-end** — these are device-INDEPENDENT and the natural next
   increments (all called out in `docs/decisions.md` Phase-5 "DEFERRED"):
   - **Per-action argument capture in the command editor** (today the editor models actions
     as `List<ActionType>`, so saved `ActionRequest`s have empty args and `argumentSchema`
     stays default-empty). Add an enable/disable toggle too (`enabled` is hard-coded true).
   - **Give `DefaultHandlers` real effects** — they are all `EchoActionHandler` placeholders;
     wire them to the repositories/platform services so a dispatched workflow actually
     mutates notes/tasks.
   - **Audit `ActionRunEntity`** — map a `WorkflowOutcome` to redacted status/result/error
     CODES (no content) and persist via the existing `ActionRunDao` (nothing writes it yet).
3. **Phase 6** (optional local LLM + polish) and **Phase 7** (release hardening) remain,
   per the plan. The real on-device model selection still awaits the **Phase-0 benchmark**
   on a physical Android phone.

---

## 8. How to exercise the app today

Run in an emulator (`.\gradlew.bat installDebug`). Everything runs on FAKES (no real mic /
model). The **Simulator** is the start destination (type a command → structured note/task
via the Phase-1 pipeline, high-risk actions gated). The **Voice** destination drives the
Phase-2 push-to-talk flow against a faked recognizer → editable review → same pipeline. The
Commands screen edits allow-listed, ≤5-action commands; the Phase-5 `CommandDispatcher` can
now match a transcript to a stored command and run its workflow (handlers echo for now).
Real dictation, wake-word, speaker verification, the foreground mic service, and the overlay
need a physical device and the Phase-0 model choice.
