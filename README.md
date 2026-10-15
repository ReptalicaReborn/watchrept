# WatchRept

A third-party Discord client for Wear OS (Jetpack Compose for Wear OS).

Browse servers and text channels, read recent messages, receive new ones live over the
Gateway, and send replies.

## Authentication (important)
Discord's ToS prohibit automating regular user accounts, so this app signs in with a **bot token**:
1. Create an application + bot at https://discord.com/developers/applications.
2. On the Bot page, enable the **Message Content** privileged intent.
3. Invite the bot to your server (OAuth2 URL generator, `bot` scope; permissions: View Channels,
   Read Message History, Send Messages).
4. Paste the bot token into the app (the watch keyboard or a paste from a paired phone/emulator).

The token is stored in app-private SharedPreferences. Messages you send appear as the bot.

## Build & run
Open in Android Studio (Koala+), start a Wear OS emulator or connect a watch, and run `app`.
Requires JDK 17, minSdk 30.
