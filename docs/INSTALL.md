# Install EndGrid iQTV+

EndGrid iQTV+ runs on **Google TV and Android TV** (Android 8 or newer): TVs from TCL, Sony, Hisense, Philips,
Xiaomi, OnePlus and others, and streamers like Chromecast with Google TV, the Google TV Streamer, onn 4K and NVIDIA
Shield. On an **LG or Samsung TV**, see [LG and Samsung TVs](LG-SAMSUNG.md).

There are two parts: install the app, then make it the TV's home screen. About ten minutes.

## 1. Get the APK

Download `iQTV-<version>.apk` from the latest release:
**[github.com/endgridlabs/iQTV/releases](https://github.com/endgridlabs/iQTV/releases/latest)**.

The public APK carries no keys and no personal settings. You add your own free TMDB key on the TV (step 4).

## 2. Put it on the TV

Pick one way.

### A. With the Downloader app (no computer)

1. On the TV, install **Downloader** (by AFTVnews) from the Play Store.
2. Settings → Apps → Security & restrictions → **Unknown sources** → allow **Downloader**
   (on Google TV: Settings → System → About → press *Android TV OS build* 7 times first, then Settings → Apps →
   Security & restrictions).
3. Open Downloader, type the APK's address from the release page, download, and choose **Install**.

### B. From a USB drive

1. Copy the APK to a USB drive and plug it into the TV.
2. Open the TV's file manager (or install *File Commander* / *X-plore* from the Play Store), open the APK, and allow
   the file manager to install unknown apps when the TV asks.

### C. From a computer (adb)

1. On the TV: Settings → System → About → press *Android TV OS build* 7 times. Then Settings → System → Developer
   options → turn on **USB debugging**. Note the TV's IP address (Settings → Network & Internet).
2. On the computer, with [adb](https://developer.android.com/tools/releases/platform-tools):
   ```bash
   adb connect 192.168.1.50:5555
   adb install -r iQTV-1.0.0.apk
   ```

On Android 14, Google Play Protect may stop the install with *Risky app blocked*: choose *More details* → *Install
anyway*. Updates don't ask again.

## 3. Make it the home screen

EndGrid iQTV+ uses the TV's own developer connection (ADB) from inside the TV to become the home screen and the
screensaver, so **USB debugging must be on** (step C.1 above), even if you installed another way.

1. Open **EndGrid iQTV+** from the TV's apps.
2. Choose **Home Screen** → **EndGrid iQTV+**.
3. The TV asks *Allow USB debugging?* for the app: tick **Always allow** and accept.

It takes a few seconds; the screen blinks while it measures. Press Home: you're on EndGrid iQTV+.

On TCL TVs, also let it run in the background (TCL blocks it by default), from a computer:
```bash
adb shell "appops set com.endgrid.iqtv AUTO_START allow || appops set com.endgrid.iqtv APP_AUTO_START allow"
```

## 4. Make it yours

Settings › **Personalize**:

- **Recommendations › TMDB key:** paste your free key from
  [themoviedb.org/settings/api](https://www.themoviedb.org/settings/api) (the *API Read Access Token* or the *API
  Key*). It brings the featured titles, the rows and search. The Google TV app on your phone can type it for you.
- **Your name**, **Language & Region** (your content languages and country), **Streaming Apps**, **Home Rows**,
  **Screensaver**.

## Going back

Settings → **Home Screen** → **Original**. The factory home screen, screensaver and every app come back as they were.
To remove EndGrid iQTV+, switch to *Original* first, then uninstall it (Settings → Apps).

## Build it yourself

You need a JDK 17+, the Android SDK command-line tools (build-tools 34, platform android-34) and Git Bash on Windows.

```bash
git clone https://github.com/endgridlabs/iQTV && cd iQTV
./build.sh            # your own build: put your TMDB key, city and name in config.properties first
./build.sh --public   # a build to share: no keys -> release/iQTV-<version>.apk
```

Keep `iqtv.keystore`: updates must be signed with the same key.
