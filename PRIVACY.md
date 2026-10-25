# Privacy

EndGrid iQTV+ has no accounts, no analytics and no tracking.

## What stays on the TV

| Data | Where | Why |
|---|---|---|
| Your preferences (name, languages, region, genres, apps, rows, screensaver) | The app's private storage | Settings › Personalize |
| What the streaming apps report playing (title, series, position, how long) | `watch-log.json` in the app's private storage, the last 200 titles | Continue Watching, Recently Watched, the order of the picks. Turn off with *Use what I watch*. |
| The artwork those apps publish with what they play | `watch-art/` in the app's private storage, the last 200 pictures | Continue Watching's pictures when TMDB has none |
| How long each streaming app was in front | Read from Android's usage statistics when needed, not stored | The order of the picks (only with *Use what I watch*). |
| Titles you hid with "Not for me" | The app's private storage | They never come back. *Recommendations* shows them again. |
| Your photos | Read where they are (USB drive, TV storage); photos sent from a computer are kept in the app's storage | The screensaver |

Nothing in this table is sent anywhere. Uninstalling the app deletes all of it (photos on a USB drive or in the TV's
storage are yours and stay).

## What goes over the network

- **TMDB** (api.themoviedb.org): lists, title details, search queries (what you type or say), image downloads. With
  your own TMDB key; TMDB sees your TV's IP address, as any website does.
- **Open-Meteo**: the weather for the coordinates you set.
- **The streaming apps' own image links**, when an app gives its artwork as a web address rather than a picture.
- **Watchmode**: only with a `watchmode_key`, to find a title's page in its app.
- **Google's speech recognizer**, when you use voice search (Android's own, like any app's voice input).

Watch history, photos, preferences and usage statistics are never sent.

## Permissions, and why

Granted through the TV's own ADB when you switch to the new home screen, and taken back with *Original*:

- **Notification listener** (media sessions only; notifications themselves are never read): what the streaming apps play.
- **Usage access**: how long each streaming app was in front.
- **Photos** (READ_EXTERNAL_STORAGE / READ_MEDIA_IMAGES): the screensaver's albums.

## Secrets

`config.properties` (your TMDB and Watchmode keys) and `iqtv.keystore` are in `.gitignore`: never commit them.
The app never stores passwords or streaming-service sign-ins.

## Limits

See *Supported, and not* in the README.
