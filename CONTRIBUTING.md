# Contributing

Thank you for helping make EndGrid iQTV+ better. It is open source under the MIT license (`LICENSE`).

## Ground rules

- **Plain Android, no new dependencies.** No Gradle, no libraries: `build.sh` and the Android SDK. Java 8 language
  level, minSdk 26.
- **Never fabricate.** A title is "on" a service only with TMDB's availability data for the region; watch progress
  only from what an app reported; a feature an app or Android doesn't allow is documented, not faked.
- **Keep the way back working:** *Original* and the watchdog (see `HomeMode` and `Watchdog`).
- **Performance first on TV hardware.** Every full-screen pass costs milliseconds on a TV GPU. Draw only what moved;
  no allocations in `onDraw`; decode images off the UI thread; measure with `dumpsys gfxinfo` before and after.
- **Secrets stay out:** `config.properties`, keystores, API keys.

## Where things go

See *How it works* in the README. Short version: a streaming service is a line in `Providers.ALL`; a preference
goes in `Prefs` (with a `config.properties` default if it needs one) and on a page in `SettingsPages`; content logic
in `Recs`; drawing in `HomeView` with values from `Tokens`.

## Before a pull request

```bash
./build.sh        # must end with "Built iQTV.apk"
tools/test.sh     # plain-logic tests
```

Then on a TV, with `adb install -r iQTV.apk`, check by hand:

1. Home: arrows between the navigation bar, the title's actions, Continue Watching, Your Apps and every row; Back from a row returns to
   the top; Home resets.
2. OK on a title opens its app; Home comes back with the focus where it was.
3. Settings › Personalize: change a value, force-stop the app (`adb shell am force-stop com.endgrid.iqtv`), and check it
   stayed.
4. The screensaver (`adb shell am start -n com.android.systemui/.Somnambulator`) moves, crossfades, and any key
   closes it.
5. Frame times while moving the focus: `adb shell dumpsys gfxinfo <pid of com.endgrid.iqtv:home>`.

Say in the pull request which TV (brand, model, Android version) you tested on.

## Reporting a problem

Open an issue with the TV's brand, model and Android version, what you did, what you expected, and the app's log:

```bash
adb logcat -d -s iQTV
```

Don't paste your `config.properties`.
