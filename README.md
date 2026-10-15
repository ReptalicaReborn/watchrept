# WatchRept

A third-party Discord client for Wear OS (Jetpack Compose for Wear OS).

Browse servers and text channels, read recent messages, receive new ones live over the
Gateway, and send replies.

## Authentication
Pick the account type on the login screen:
- **User account** (personal use): paste your own account token. Messages are sent as you.
  Note: Discord's ToS prohibit automating user accounts, so this carries a risk to your account.
- **Bot account**: create an application + bot at https://discord.com/developers/applications,
  enable the **Message Content** intent, invite it to your server, and paste the bot token.

The token is stored in app-private SharedPreferences (unencrypted).

## Build & run
Open in Android Studio (Koala+), start a Wear OS emulator or connect a watch, and run `app`.
Requires JDK 17, minSdk 30.
