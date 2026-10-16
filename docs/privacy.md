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
