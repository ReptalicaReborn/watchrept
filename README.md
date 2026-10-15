# Dihcord

Dihcord is a third-party Discord client for Wear OS (Compose for Wear OS, Material 3). GPL-3.0; derived from
[Discord-WearOS](https://github.com/Zaffox/Discord-WearOS) — see `NOTICE.md`.

> **Third-party clients violate Discord's Terms of Service.** Use at your own risk, on your own account.

## Features
Messages, embeds, replies, forwarded messages, threads, DMs, server emoji/stickers, profile images,
voice-message playback and recording, photo uploads, pinned/hidden servers,
tap-to-enlarge images (zoom, save to gallery), colour and pride flag themes with an AMOLED-black option, opt-in background notifications for DMs and
mentions with inline reply, a mentions tile and a watch-face complication.
Not supported: voice calls, joining servers, bot commands.

## Signing in
- **QR code** — scan from the Discord mobile app.
- **Browser** — "Enter via Browser" shows a URL and a one-time **PIN**; open the URL from a device on
  the same Wi-Fi and enter the PIN plus your token. The server accepts one token and locks after 5 wrong
  PINs. It is plain HTTP, so use a network you trust.
- **Clipboard / keyboard** — paste the token directly (use Gboard; Samsung's keyboard is buggy).

The token is stored encrypted with an Android Keystore key and excluded from backups.

## Build
Open in Android Studio or run `./gradlew :app:assembleDebug` (JDK 17, minSdk 30). Signed release builds
read `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` from the environment.
