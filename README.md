<div align="center">

  <img src="assets/lunartune-icon-rounded.png" width="160" height="160" alt="Yeshua Logo">

  <h1>Yeshua</h1>

  <p align="center">
    <strong>A revamped YouTube Music experience on Android — rebuilt around lossless sources, your own streaming accounts, and your full listening history.</strong>
  </p>

  <p align="center">
    <img src="https://img.shields.io/github/v/release/ReCapzPhyco-420/Yeshua?style=for-the-badge&color=6366f1&labelColor=1e1e2e&logo=github" alt="Latest Version" />
    <img src="https://img.shields.io/github/downloads/ReCapzPhyco-420/Yeshua/total?style=for-the-badge&color=6366f1&labelColor=1e1e2e&logo=github" alt="Downloads" />
    <img src="https://img.shields.io/github/stars/ReCapzPhyco-420/Yeshua?style=for-the-badge&color=6366f1&labelColor=1e1e2e&logo=github" alt="Stars" />
    <img src="https://img.shields.io/github/license/ReCapzPhyco-420/Yeshua?style=for-the-badge&color=6366f1&labelColor=1e1e2e" alt="License" />
  </p>

  <p align="center">
    Lead developer: <a href="https://github.com/ReCapzPhyco-420"><b>ReCapzPhyco-420</b></a>
  </p>

</div>

## About

Yeshua is a personal fork of [LunarTune](https://github.com/cognitiveshadows03/LunarTune), developed and maintained by ReCapzPhyco-420. It keeps the fast, privacy-focused core and rebuilds the parts its developer actually wanted: real lossless playback from multiple sources, sign-in with your own streaming accounts, a custom player UI, and a stats importer that treats your listening history like it matters.

The in-app updater checks **this repository only** — releases, build runs, and commits all come from `ReCapzPhyco-420/Yeshua`. Auto-update checks are off by default.

## What makes Yeshua different

- **Upgrade to lossless** — one tap in the player menu re-resolves the current song from a lossless source (Monochrome → Qobuz → Tidal → Deezer → Internet Archive) and remembers the choice per song.
- **Quality & source chooser** — tap the quality chip under the seek bar to switch Auto / High / Highest / Low or jump straight to a lossless source; the switch actually takes effect on already-played songs.
- **Your accounts, your tokens** — Qobuz (web/Google sign-in), Tidal (device-code link), and Deezer (in-app sign-in) plug into playback, downloads, and lossless upgrades. Only auth tokens are stored — never passwords.
- **No-login fallbacks** — when YouTube resolution fails completely, playback falls back through Piped → Invidious → Internet Archive before giving up, and Audius joins the download chain.
- **Tempo history import, done properly** — import your listening history from the Tempo stats app by backup file or straight from Google Drive. Imports are resumable (a checkpoint every 100 plays), run in the foreground with a live progress notification, and match every play — local library first, then YouTube search; nothing is silently dropped.
- **Fusion player UI** — a reworked player with deep customization, settings favorites, and a sleep timer.
- **Cleaned out** — no Telegram or Discord integrations, no third-party update channels. Yeshua only talks to the services you sign into.

## Updates

### 2026-10-22 — Reliability & build fixes

- Tempo import: the resumable importer's checkpoint system is fully in
  place (an interrupted import now genuinely resumes where it stopped),
  and the live progress notification posts reliably at import start.
- Qobuz web sign-in no longer gets stuck on "Checking your Qobuz
  account…" — a timed-out check now surfaces as an error instead of a
  permanent spinner.
- Build pipeline: fixed the Gradle script failure the Drive
  dependencies introduced, and universal release builds are signed
  again.

### 2026-10-21 — Tempo import from Google Drive + live progress notification
- Pull Tempo backups straight from your Google Drive ("Tempo Backups" folder) after a one-time Google sign-in — no manual export. Re-picking an unchanged backup returns instantly as already imported.
- Every Tempo import now runs as a foreground service with a live progress notification (download %, then plays processed), so it keeps running with the app closed.

### 2026-10-21 — Bluetooth silent-playback fix
- Fixed songs "playing" silently over Bluetooth earbuds when Audio Offload was on: offload is now disabled automatically whenever the output is Bluetooth, and a stuck stream recovers in place — no restart, no track change.

### 2026-10-21 — Qobuz web (Google) sign-in
- Qobuz sign-in now uses Qobuz's own login page in-app, so "Continue with Google" accounts work. The email/password form is gone — Google-created accounts have no Qobuz password for it to accept.

### 2026-10-21 — Tempo import resume + speed
- Tempo imports save a durable checkpoint every 100 plays: close the app, reboot, get killed — reopen and the same export resumes where it stopped, with no duplicated plays.
- Faster matching: chunked writes, YouTube searches 4 at a time, in-memory already-imported checks.

### 2026-10-21 — Lossless upgrade, source chooser, streaming accounts
- "Upgrade to lossless" player action with per-song source memory, the tap-to-open quality/source chooser, and the quality-switch fix (cached streams are tagged with the quality and source they were resolved at).
- Qobuz, Tidal, and Deezer accounts join playback, downloads, and lossless upgrades; Audius joins the download chain; Piped → Invidious → Archive no-login playback fallbacks added.
- Fixed playlist-sourced songs failing with "This video is unavailable".

### 2026-10-16 — Yeshua begins
- Full rebrand from LunarTune to Yeshua (package `dev.yeshua.player`), updater and links repointed to this repository, Telegram actions removed, and Piped/Invidious FLOSS stream fallbacks added.

## Credits

Yeshua is built on [LunarTune](https://github.com/cognitiveshadows03/LunarTune) by cognitiveshadows03 and contributors; full upstream credits are preserved in the app (Settings → About) and in source headers, as the GPL requires.

## License

GPL-3.0 — see [LICENSE](LICENSE).
