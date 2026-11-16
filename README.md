# Smart TV

A colourful home screen for Fire TV. It has:

- a top row of apps you choose yourself
- an All apps library
- colour themes taken from each app's icon
- Spotify now-playing with controls
- the next Chelsea match
- the weather

The UI is `app/src/main/assets/index.html`. To preview it, open it in a browser and use the arrow keys. Enter is OK, M is ☰ Menu and Esc is Back.

## Settings you might change

These live at the top of `index.html`, in `CONFIG`:

- `weather`: city name, latitude and longitude (London by default), and `celsius` or `fahrenheit`.
- `spotifyClientId`: see below.
- `team`: the ESPN team id. Chelsea is `363`.

## Install on the TV

Each push to `main` builds the APK on GitHub Actions and publishes it at:

```
https://github.com/krayestation/smart-tv/releases/latest/download/smart-tv.apk
```

1. On the Fire TV, install **Downloader** from the Appstore.
2. Go to Settings → My Fire TV → Developer Options → Install unknown apps, and turn it on for Downloader.
3. In Downloader, enter the link above, then install.

## Remote controls

| Where | OK | ☰ Menu |
|---|---|---|
| App tile | Opens the app. If it isn't installed, OK opens the Appstore. | Move left/right, remove, add more apps, connect Spotify |
| All apps | Opens the app | Pins it to the top row, or unpins it |
| Cast | Opens AirScreen (AirPlay + Google Cast) | How to cast |
| Background | About today's painting | |
| Settings | Opens Fire TV settings | Home-button setup |
| Fire TV Home | Opens Amazon's home screen | |
| Spotify ⏮ ⏯ ⏭ | Previous, play/pause, next | |

## Make Home come back here

Go to Settings → Accessibility, and turn on **Smart TV Home button**.

## Spotify player (one-time setup)

This needs Spotify Premium.

1. Go to developer.spotify.com/dashboard → **Create app**.
2. Set the Redirect URI to `http://127.0.0.1:8898/callback` and tick **Web API**.
3. Copy the **Client ID** into `spotifyClientId` in `index.html`.
4. On the TV, press ☰ on the Spotify tile → Connect Spotify, then sign in once.

## Ad-free YouTube

The YouTube tile opens **SmartTube**. Install it with Downloader from `smarttubeapp.github.io`.
