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
