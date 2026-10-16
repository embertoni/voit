# Voit — Privacy

**Status: stub.** Expanded as privacy-affecting features land.

Voit is privacy-first and local-first. Core principles:

- **Local-first.** All core features work fully offline. Notes, tasks, parsing, and command
  execution require no network and no cloud account.
- **No cloud.** Core functionality makes no network calls and sends no user content off the
  device. There is no server-side processing of transcripts, audio, notes, or tasks.
- **Raw audio is discarded after inference.** When live capture exists (later phases), raw
  audio buffers are processed on-device and discarded immediately; they are not persisted
  or transmitted.
- **Diagnostics use codes, not content.** Any diagnostic/telemetry (opt-in only, if ever
  added) records stable error/event codes and never user content (no transcripts, note
  text, task text, or audio).
- **No screen scraping.** Voit does not read other apps' screen contents and does not use
  accessibility services to harvest on-screen data.
- **No arbitrary execution.** Commands run only through a typed, allow-listed action
  registry; there is no arbitrary code or shell execution, and higher-risk actions require
  explicit confirmation.

## Data at rest and backups

- **Diagnostics store codes, not content (enforced in the schema).** The `action_runs`
  table persists only redacted status/result/error codes. It has no column for a
  transcript, note/task text, or audio. There is nowhere for user content to leak into
  the diagnostics audit trail.
- **Voice profiles are metadata only and deletable.** The `voice_profile_metadata` table
  stores engine/model version, enrollment date, a verification threshold, and an optional
  last-verified timestamp. It holds no raw audio and no biometric template, and the user
  can delete it (DAO delete) at any time.
- **Backup encryption is behind an interface, with NO hard-coded secrets.** Full local
  backups are produced by `BackupSerializer`/`BackupManager` and passed through an
  `ExportEncryptor`. Phase 1 ships an explicit, documented no-op implementation
  (`NoOpExportEncryptor`) so the content export/restore round-trip works end to end; the
  backup is written as plaintext JSON in this phase. There are no embedded keys or
  passwords. A real user-supplied-password -> KDF -> authenticated-encryption (AEAD) flow
  is planned for a later phase and will replace the no-op implementation without changing
  the backup content model.

## Phase 2 invariants (local recording & ASR scaffolding)

- **Raw audio is NOT retained.** The push-to-talk `RecordingController` (`core:voice`)
  hands the capture buffer to recognition as a transient local that is dereferenced
  immediately after the recognize call. It is never assigned to a field, never placed on a
  `RecordingState`, and never persisted. Nothing audio- or transcript-related is added to
  `action_runs` or to any other DB table, and the Room schema is unchanged in Phase 2.
- **No network / no cloud in the core path.** Recording, recognition (via the fake default
  or, when swapped in, the device's own on-device recognizer), interpretation, and
  note/task creation all run on-device with no network calls.
- **Diagnostics remain codes, not content.** The recording state machine surfaces only
  stable `RecordingErrorCode` strings (TIMEOUT / RECOGNIZER_FAILURE / EMPTY_TRANSCRIPT /
  CANCELLED); it never records the captured transcript or audio in diagnostics.
- **Permissions requested only when the voice feature is enabled.** `RECORD_AUDIO` and
  `POST_NOTIFICATIONS` are declared in the manifest as capabilities, but the runtime
  request is staged by the pure `VoicePermissionModel` and is prompted only when the voice
  feature is enabled, with a staged rationale (rationale shown before the system prompt;
  `POST_NOTIFICATIONS` gated to API 33+). No foreground-service or Phase-4 permission is
  declared in Phase 2.
  - **ACTIVE now:** the pure decision model (`VoicePermissionModel`) and its thin Android
    adapter (`VoicePermissionRequester`) are implemented and unit-tested, and the Voice
    screen's "Start recording" action is wired through them in the app (`MainActivity`'s
    `VoicePermissionGate` registers an `ActivityResultLauncher` and consults the model
    before capture begins, with the live `listeningEnabled` setting as the feature flag).
    All decision branching lives in the pure model.
  - **DEFERRED to a real device:** the LIVE runtime grant round-trip (the system
    permission dialog, the OS `shouldShowRequestPermissionRationale` flag, and gating
    capture on an actual grant) is only exercised on real hardware, which does not exist in
    this sandbox. The default wired capture is a fake audio session that needs no
    microphone grant, so the gate proceeds gracefully when a live grant cannot complete.
    The on-device behavior is therefore NOT validated here; the wiring is validated by
    inspection plus the pure-model JVM tests. When a real capture source lands, the gate is
    the single place that must gate capture on an actual grant.

## Phase 3 invariants (wake word & speaker verification scaffolding)

- **Raw audio is NOT retained by the gate.** The hands-free `WakeWordController`
  (`core:voice`) hands each capture frame to `WakeWordDetector.detect` / `SpeakerVerifier
  .verify` as a transient local scoped to one scan-loop iteration; it is never assigned to a
  field, never placed on a `WakeState`, and never persisted. Nothing audio-related is added
  to `action_runs` or any other DB table, and the Room schema is unchanged in Phase 3.
- **Speaker verification is a GATE, not a capture, and stores no biometric template.** The
  verifier yields only a boolean + a numeric score. The only speaker-related persistence is
  the pre-existing `voice_profile_metadata` row (engine/model version, enrollment date,
  threshold, optional last-verified timestamp) - metadata only, no raw audio and no
  embedding - and it remains user-deletable at any time. The gate itself persists nothing.
- **A false trigger never starts a capture.** A wake-word hit that fails speaker
  verification (or falls below the configured score threshold) silently rearms the gate back
  to listening; it never invokes the `onTriggered` hand-off, so a mismatched speaker cannot
  start recording. Speaker verification is required only when the user has enabled it
  (`AppSettings.speakerVerificationEnabled`, privacy-safe default off).
- **Diagnostics remain codes, not content.** A rejected trigger surfaces only a stable
  `WakeErrorCode` (SPEAKER_MISMATCH / VERIFIER_FAILURE / DETECTOR_FAILURE) plus, for a
  mismatch, the numeric verifier score. No captured audio is ever recorded in diagnostics.
- **Still no always-on listening at runtime.** The gate is not started by the app yet;
  continuous capture and the mic foreground service are Phase 4. Phase 3 adds only the pure,
  unit-tested decision logic behind the existing detector/verifier interfaces, validated
  off-device.

## Phase 4 invariants (foreground mic service & overlay scaffolding)

- **The overlay is Voit's OWN affordance, not screen scraping.** The `SYSTEM_ALERT_WINDOW`
  capability is for Voit to draw its own small listening-status overlay. Voit does NOT use
  an accessibility service and does NOT read other apps' screen contents; the
  `OverlayPermissionModel` governs only whether Voit may draw its own overlay (re-affirming
  the "No screen scraping" principle above).
- **Foreground-service models touch lifecycle only, never audio.** `ForegroundMicServiceModel`
  decides WHEN the mic service may legally start and its STATE transitions; it never handles
  an audio buffer. The raw-audio-not-retained invariant stays enforced in `core:voice`
  (`RecordingController` / `WakeWordController`), and nothing audio-related is persisted.
- **Listening still requires explicit opt-in + granted permissions.** A mic foreground
  service start is hard-refused unless the user enabled listening AND `RECORD_AUDIO` is
  granted (plus `FOREGROUND_SERVICE_MICROPHONE` on API 34+). An ongoing notification is
  shown whenever POST_NOTIFICATIONS allows it, so the user always has a visible, dismissible
  indication that the mic service is running.
- **Diagnostics remain codes, not content.** A refused start surfaces only a stable
  `MicServiceErrorCode` (FEATURE_DISABLED / RECORD_AUDIO_NOT_GRANTED /
  FGS_MIC_PERMISSION_MISSING / POST_NOTIFICATIONS_NOT_GRANTED); no captured audio is ever
  recorded in diagnostics.
- **Still no always-on listening at runtime.** The live foreground service, notification,
  and overlay window are deferred to a real device. Phase 4 adds only the pure, unit-tested
  policy/lifecycle/grant logic behind the Android boundaries, validated off-device; the
  manifest declares the capabilities but nothing is started.
