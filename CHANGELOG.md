# Changelog

## Unreleased

- **Movies and TV Shows show only new titles too:** their featured titles come from the same new releases as Home
  (never the all-time popular or older shows); a tab shows fewer titles rather than old ones.

## 2.0.2 — what's new on your apps, on a clean Home

- **Home shows what's new:** the featured titles are the newest movies and shows on each of your apps (the last four
  months: films reach the apps a month or two after cinemas), app after app (Netflix, JioHotstar, Prime Video and
  ZEE5 first), in your languages first; no more all-time popular or years-old titles. Twelve of them, refreshed with
  the lists. Movies and TV Shows start with the new ones too.
- **A clean Home by default:** every row starts off; turn the ones you want on in Settings › Personalize › Home Rows.

## 2.0.1 — Gemini search, Watch Now and a new look

- **A new app ID, `com.endgrid.iqtv`:** it replaces `io.freemytv`, so this version installs as a new app. Before
  installing it, open the old one's Settings, switch to Original, then uninstall it; turn on iQTV+ mode again in the
  new one. Settings start fresh.
- **Search with Gemini:** the Search tab (and a remote's search key) opens a new Search screen: ask in plain words
  ("a feel-good Tamil family movie") or pick a suggestion, and Gemini's picks come as a row, each checked on TMDB for
  where it streams. Add a free key from aistudio.google.com in Settings › Personalize › Recommendations › Gemini key;
  without one, Search finds titles by name. The mic button stays Google Assistant's: a voice remote's sound reaches
  only the TV's assistant.
- **Watch Now opens the title itself:** with a free Watchmode key (Settings › Personalize › Recommendations ›
  Watchmode key), Watch Now opens the movie or show's own page in Prime Video, Netflix or JioHotstar instead of the
  app's home. Featured titles are looked up ahead; any other title (a search result) when you press OK. Each link is
  remembered, so the free plan's monthly lookups go far.
- **Weather in the top bar:** the temperature and city next to the clock; OK opens the full forecast (hourly, five
  days, air quality, wind, UV and more).
- **Settings behind your initial:** the avatar next to the clock opens Settings, now with icons and clearer pages
  (and a Status page).
- **Bigger apps in a glass dock:** Your Apps sit on Liquid or Frosted glass (Settings › Personalize › Dock glass, or
  off).
- **OnePlus TVs:** Oxygen Play and O Relax are turned off in iQTV+ mode too (Original brings them back).
- Google Assistant's result cards still can't open a show while the stock home screen is off; voice commands like
  "open YouTube" work.

## 1.1.0 — smoother, and easier to browse

- **No more freezes:** the home screen no longer stops for 8–9 seconds every minute (remote presses were lost); it
  also gives memory back while an app like YouTube is in front, so apps are less likely to be closed by the TV.
- **Your Apps first:** right under the featured title on Home, above Continue Watching, always on the first screen.
- **Browse the featured titles with the remote:** ← / → past the buttons go to the previous or next title; the
  carousel waits while you use the remote.
- **A real mix:** Home's featured titles alternate movies and shows and take turns between your streaming apps
  (Prime Video and ZEE5 first unless you set your own order); Movies and TV Shows keep only their kind.
- **Highlights without rows:** the featured titles stay on Home, Movies and TV Shows even with every row turned off.
- **Settings › Personalize › Recommendations › Refresh now:** the newest titles at once (it also updates by itself
  about three times a day).
- **OnePlus TVs:** OnePlus Connect, HeyTap push messages, Smart Home, the OnePlus screensaver and weather, remote
  diagnostics and the user guide are turned off in iQTV+ mode (Original brings them back); the remote, updates and
  Live TV are untouched. The remote's ☰ button opens the TV's settings from Home.

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
