# Changelog

## Unreleased

- **LG (webOS) and Samsung (Tizen):** EndGrid iQTV+ as a native web app, with the Top Shelf design: the iQTV+ top bar
  and glass navigation, the featured title with logo art and Open / More Info / Not for me, Your Apps, and rows from
  TMDB in your content languages; All Apps with the TV's inputs; Search; Settings typed on the TV (TMDB key, region,
  languages, name). It runs as an app, not as the TV's home screen. `./webos/build.sh` makes the `.ipk` and
  `./webos/install.sh` installs it through LG's Developer Mode; `./tizen/build.sh` makes the `.wgt` (`--sign` with
  Samsung's tools) and `java tizen/Install.java` installs it. JDK and bash only; `--public` builds carry no keys or
  personal settings. Not yet tried on a real LG or Samsung TV: see [docs/LG-SAMSUNG.md](docs/LG-SAMSUNG.md).

## 1.0.0 — the first release from EndGrid Labs

EndGrid iQTV+: a cinematic, ad-free home screen for Google TV and Android TV.

- **Home:** the iQTV+ top bar with a glass navigation bar (Home, Movies, TV Shows, Apps, Search, Settings), your
  initial and the clock; a featured title that fills the screen with its logo art, details, description and
  Open / More Info / Not for me, rotating through your recommendations.
- **Rows:** Continue Watching, Your Apps, Trending Now, Popular and Latest movies in your languages (posters), TV Shows,
  Web Series, Recommended for you, your genres, Recently Watched and New Releases; each on or off, in your order.
- **Continue Watching:** the 10 most recent, from what the streaming apps report while they play, with the apps' own
  artwork when TMDB has none; hold OK on one to remove it (Settings brings removed titles back).
- **All Apps** lists every app on the TV; hold OK to add one to Your Apps or take it off.
- **Search** by title, actor, director or year, typed or spoken.
- **Settings:** content languages and region, streaming apps, recommendations (with the TMDB key typed on the TV),
  home rows, screensaver, and About; a black design with the iQTV+ logo.
- **Only real availability:** a title shows only when it streams in your region on an app installed on the TV.
- **Private:** no accounts, no analytics; everything stays on the TV.
- **Public APK** (`./build.sh --public`) carries no keys or personal settings.
