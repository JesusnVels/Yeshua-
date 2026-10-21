# Changelog

All notable changes to Yeshua, newest first. Each entry matches one
pushed update so the GitHub history reads cleanly.

## 2026-10-21 — Bluetooth silent-playback fix
- **Fixed earbuds playing silently**: when the Audio Offload setting
  was on, tracks could be handed to the hardware offload path even
  while the output was Bluetooth — the song "played" (progress moved)
  but no sound reached the earbuds, and skipping tracks never cured
  it. Offload is now disabled automatically whenever the active
  output is Bluetooth, re-checked whenever the output device
  changes, and an already-offloaded stream recovers in place when
  playback starts — no app restart needed. Offload still applies on
  the phone speaker / wired outputs as before.

## 2026-10-21 — Qobuz web (Google) sign-in
- **Qobuz sign-in is now a web login**: Settings → Stream sources →
  Qobuz opens Qobuz's own login page inside the app, so "Continue with
  Google" accounts work — sign in there and the app captures and
  validates the session token. The email/password form is removed
  (Google-created accounts have no Qobuz password, so it could never
  work for them).

## 2026-10-21 — Tempo import resume + speed, Qobuz sign-in hint
- **Tempo import now saves its progress**: a durable checkpoint is
  written after every 100 plays (keyed to the exact export file by a
  content hash). If the app is closed, killed, or the phone restarts
  mid-import, reopening shows the saved progress and re-running the
  same export resumes where it stopped — finished chunks and YouTube
  searches are never redone, and no play is ever duplicated.
- **Faster imports**: plays are written in chunked transactions,
  YouTube searches for unmatched tracks run 4 at a time instead of
  one by one, and the already-imported check is a single in-memory
  set per song instead of a database query per play.
- **Qobuz sign-in hint**: a rejected sign-in now explains the usual
  cause — an account created with Google sign-in has no Qobuz
  password until one is set via qobuz.com's "Forgot password" flow.

## 2026-10-21 — Compile fix for the combined build
- Added the missing `AuthScopedCacheValue.kt` (quality/source-tagged
  stream URL cache values) that MusicService already referenced.
- Fixed the quality chooser calling `isLocalMediaId` as a plain
  function instead of the string extension it is.
- Wrapped the Deezer settings rows in `item { }` blocks, as the
  settings-group DSL requires.

## 2026-10-21 — Lossless upgrade, source chooser, streaming accounts
- **Upgrade to lossless**: new player-menu action that re-resolves the
  current song from a lossless source (Monochrome → Qobuz → Tidal →
  Deezer → Internet Archive) and remembers the choice per song.
- **Quality/source chooser**: tapping the quality chip under the seek
  bar now opens a chooser — Auto / High / Highest / Low plus every
  lossless source with its sign-in state. Picking anything re-resolves
  the current song immediately.
- **Quality switch fix**: cached stream URLs and cached bytes are now
  tagged with the quality (and source) they were resolved at, so
  changing quality actually takes effect on already-played songs.
- **Qobuz account**: sign in with email + password in
  Settings → Stream sources (only the auth token is stored). Joins the
  download chain and the lossless-upgrade probes.
- **Tidal account**: link with a device code at link.tidal.com
  (OAuth device flow; only tokens are stored). Download chain +
  lossless-upgrade probes.
- **Deezer account**: sign in inside the app (WebView); streams are
  decrypted on the fly with a built-in Blowfish implementation.
  Playback via the upgrade/chooser path, plus the download chain.
- **No-login playback fallbacks**: when YouTube resolution fails
  completely, playback now tries Piped → Invidious → Internet Archive
  before giving up.
- **Audius** added to the download chain (free catalog, 320 kbps MP3).
- Fixed playback of playlist-sourced songs failing with "This video
  is unavailable" (a playlist-entry token was being resolved as a
  video id).
