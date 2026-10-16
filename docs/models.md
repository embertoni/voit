# Voit — On-Device Models

**Status: stub.** No models are bundled in Phase 1.

Voit is local-first: all speech/intent processing must run on-device. The concrete model
choices for the following roles will be benchmarked on real hardware during Phase 0 and
recorded here, each with its download source, SHA-256 checksum, size, and license:

- **ASR** (speech-to-text)
- **Wake-word** detection
- **Speaker verification**
- **VAD** (voice activity detection)
- **TTS** (optional, for spoken feedback)

Until Phase 0 runs on a device:

- No native ML runtime (e.g. `sherpa-onnx`, `whisper.cpp`, `llama.cpp`, Picovoice) and no
  model weight files are included in the repository or the APK.
- `core:voice` exposes engine-adapter **interfaces** with **fake** implementations so the
  rest of the app can be built and tested deterministically without any model.

When a model is adopted it MUST be listed here with:

| Role | Model | Version | Source | SHA-256 | Size | License |
| ---- | ----- | ------- | ------ | ------- | ---- | ------- |
| _(none yet)_ | | | | | | |

## Phase 2 status (local recording & ASR scaffolding)

- The real **ASR / VAD model choice is still pending the Phase-0 hardware benchmark**. No
  model weight file and no native ML runtime (sherpa-onnx / whisper.cpp / llama.cpp /
  Picovoice) has been added in Phase 2.
- `core:voice` still ships **interfaces + fakes only**. The DEFAULT recognizer wired into
  the running app is the `FakeSpeechRecognizerEngine`, so the app builds and runs without
  any model.
- An **optional** Android-framework on-device recognizer adapter
  (`AndroidOnDeviceSpeechRecognizer` in `platform:android`, wrapping
  `SpeechRecognizer.createOnDeviceSpeechRecognizer()`, API 31+) exists as a possible
  provider/fallback behind the `SpeechRecognizerEngine` interface. It is **not a bundled
  model** (it uses the device's own recognizer when available) and is **not** the default;
  it is unvalidated off-device and routes to the editable-transcript fallback when
  unavailable.

## Phase 3 status (wake word + speaker verification scaffolding)

- The real **wake-word and speaker-verification model choices are still pending the Phase-0
  hardware benchmark**. No model weight file and no native ML runtime (Picovoice /
  sherpa-onnx keyword spotting / speaker-embedding models, etc.) has been added in Phase 3.
- `core:voice` still ships **interfaces + fakes only** for `WakeWordDetector` and
  `SpeakerVerifier`. The new pure `WakeWordController` gate consumes those interfaces; the
  DEFAULT detector/verifier wired into the app are `FakeWakeWordDetector(shouldDetect =
  false)` and `FakeSpeakerVerifier`, so the app builds and runs without any model and never
  spuriously triggers.
- No enrollment flow and no on-device embedding are added. The `voice_profile_metadata` row
  stays metadata-only and user-deletable; a real speaker model + enrollment lands on a
  device during/after the Phase-0 benchmark.
