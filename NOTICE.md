# Notice

Dihcord is free software licensed under the GNU General Public License v3.0 (see `LICENSE`).

It is derived from **Discord-WearOS** by Zaffox (https://github.com/Zaffox/Discord-WearOS),
also GPL-3.0. The upstream feature set (chat UI, embeds, threads, QR login, voice messages, photo
picker, updater, etc.) comes from that project. Changes made here include: package rename, a
PIN-protected and size-limited token web server, Keystore-encrypted token storage, a restricted
update downloader pointing at this repository, removal of committed build outputs and unused
dependencies/permissions, and optional release signing.

## Third-party references

The custom typing indicator support (`TypingStyle` in `api/Models.kt`, `TypingIndicator` in
`screens/ChatScreen.kt`) was written with reference to **TypingIndicators** by ItzMeShadow999
(https://github.com/ItzMeShadow999/TypingIndicators), GPL-3.0: the shape of the data Discord sends,
the list of words and the timing of the animations were learned from it. No code was copied.

Dihcord is not affiliated with or endorsed by Discord. It bundles none of Discord's artwork other than
the logo in its launcher icon; badges, emoji and stickers are loaded from Discord at run time.
