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
