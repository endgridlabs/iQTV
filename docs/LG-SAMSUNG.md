# LG and Samsung TVs

LG (webOS) and Samsung (Tizen) TVs don't run Android apps, and they don't let another app replace their home screen.
There are two ways to get EndGrid iQTV+ on them:

| | Google TV streamer on HDMI | Native app on the TV |
|---|---|---|
| What it is | The full Android app, as the streamer's home screen | A web version of the Top Shelf, installed on the TV as an app |
| Home screen | Yes: everything starts from EndGrid iQTV+ | No: the TV's Home button still opens LG's or Samsung's home; iQTV+ is one press away |
| Featured title, rows in your languages, More Info, Not for me, Search, All Apps | Yes | Yes |
| Continue Watching, Recently Watched | Yes (from what the apps report while they play) | No: webOS and Tizen don't tell apps what's playing |
| Open a title's own page (Watchmode) | Yes, in apps that support it | No: Open starts the title's app at its home |
| Screensaver with your photos, weather | Yes | No |
| Streaming apps | Every app from the Play Store | The TV's own apps |
| Cost | A streamer (from about ₹3,000 / $20) | Free; needs a computer once to install |

**The streamer is the full-featured option.** The native app is for when you'd rather not add a box.

## Option 1: a Google TV streamer (full-featured)

Plug a Google TV or Android TV streamer into one of the TV's HDMI ports. You get the full EndGrid iQTV+ home screen,
every streaming app from the Play Store, and the TV's remote keeps working through HDMI-CEC.

| Streamer | Notes |
|---|---|
| Google TV Streamer | Google's current box |
| Chromecast with Google TV (4K or HD) | Small, cheap; on Android 14, see the note in [INSTALL.md](INSTALL.md) about wireless debugging |
| onn 4K / onn 4K Pro | Budget Google TV boxes |
| NVIDIA Shield TV | Android TV, fastest |
| Xiaomi Mi Box / TV Stick, Realme / Airtel Xstream boxes | Android TV boxes common in India |

1. Plug the streamer into an HDMI port and power, and go through its setup.
2. Install EndGrid iQTV+ on it and make it the home screen: [INSTALL.md](INSTALL.md).
3. Make the TV feel like one device (menu names vary a little between models and years):

**LG (webOS)**

- **HDMI-CEC:** Settings → General → Devices → HDMI Settings → **SIMPLINK (HDMI-CEC)** → On. The LG remote then
  controls the streamer, and turning the streamer on turns the TV on.
- **Start on the streamer:** Settings → General → System → **Home Settings** → turn off *Home Auto Launch*, and on
  webOS 6 and newer turn on **Start with last input** (*Additional Settings*), so the TV opens on the streamer's HDMI.

**Samsung (Tizen)**

- **HDMI-CEC:** Settings → Connection → External Device Manager → **Anynet+ (HDMI-CEC)** → On.
- **Start on the streamer:** Settings → General → Start Screen Options (or *Smart Features*) → **Autorun Last Source**
  (on some models *Start with: Last Source*), so the TV turns on into the streamer's HDMI.

The TV's own home screen stays one press of its Home button away; everything you watch starts from EndGrid iQTV+.

## Option 2: the native app

The same Top Shelf design in plain HTML, CSS and JavaScript ([`web/`](../web)), written for the browser engine of
older TVs: the iQTV+ wordmark and glass navigation (Home, Movies, TV Shows, Apps, Search, Settings), the featured
title with its logo art, details and **Open** / **More Info** / **Not for me**, rotating every few seconds, then
**Your Apps** and the rows from TMDB in your content languages: Trending Now, Popular and Latest movies, TV Shows, Web
Series and New Releases. As on Android, a title shows only when TMDB lists it as streaming in your region on an app
installed on the TV. **All Apps** also has the TV's inputs (Live TV, HDMI) and its settings.

**On webOS and Tizen it runs as an app, not as the home screen.** Neither TV lets an app replace its launcher, so
the TV still turns on into its own home (or its last input), and its Home button opens LG's or Samsung's home. Open
EndGrid iQTV+ from the TV's app list, or with a number key on LG; on Samsung, *Autorun Last App* can start the TV
in it.

> **Status:** the packages build and the app is tested in a desktop browser (with stand-in apps), but it has not yet
> been tried on a real LG or Samsung TV. The TV-side code (listing and opening apps, the inputs, the Back key) comes
> from an earlier web app that ran on Samsung TVs with Tizen 3, 4 and 5 and on LG TVs up to webOS 11. If something doesn't work on your TV, please
> [open an issue](https://github.com/endgridlabs/iQTV/issues) with the model and year.

### What you need

- The TV: **LG** with webOS 3 or newer (2016+), or **Samsung** with Tizen 3 or newer (2017+), on the same Wi-Fi as
  your computer.
- A computer with **bash** and a **JDK 17** (the same as for the Android build; on Windows, Git Bash). No LG or
  Samsung tools to build, and no Python.
- Optional but recommended: your free **TMDB key** ([how to get one](../README.md#the-tmdb-key)). Without it you get
  Your Apps, All Apps and the clock, but no rows. You can put it in `config.properties` before building, or type it on
  the TV later in **Settings**.

### Build

```bash
git clone https://github.com/endgridlabs/iQTV && cd iQTV
./webos/build.sh        # LG:      iQTV-webos.ipk
./tizen/build.sh        # Samsung: iQTV-tizen.wgt (not signed yet; see below)
```

Both read `config.properties`, the same file as the Android build (the first run creates it): `tmdb_key`,
`tmdb_region`, `content_language`, `viewer_name`, `language`, `hide_animation` and `hide_services` apply. To share a
build, use `--public`: it reads only `config.example.properties` (no key, no name; the region comes from the TV's
language) and writes `release/iQTV-webos-<version>.ipk` and `release/iQTV-tizen-<version>.wgt`. The build refuses to
finish if a public package would carry a key or a name.

`--preview` also leaves the app in `out/webos-preview/` (or `out/tizen-preview/`) to open in a browser, where sample
apps stand in for the TV's.

### Install on an LG TV (webOS)

1. **On the TV**, open the LG Content Store and install **Developer Mode**. Sign in with a free
   [LG developer account](https://webostv.developer.lge.com), turn on **Dev Mode Status** (the TV restarts), then turn
   on **Key Server**. Write down the IP address and the passphrase it shows.
2. **On your computer**:
   ```bash
   ./webos/install.sh 192.168.1.50
   ```
   The first time, it downloads the TV's key from its Key Server and asks for the passphrase (typed in your terminal,
   not stored). The unlocked key stays in `~/.ssh/iqtv_webos_<IP>`, so updates need neither. It copies the app over
   SSH and installs it with the TV's own installer, then opens it.
3. **On the TV**, while EndGrid iQTV+ is open, hold a number key on the remote: from then on, that key opens it from
   anywhere. On a remote without number keys, open it from LG's app list.

On webOS 6 and newer, LG doesn't let Developer Mode apps list the TV's apps. EndGrid iQTV+ then shows the well-known
ones it finds (Netflix, YouTube, Prime Video, Disney+, Apple TV and more) and asks, once, for the access LG's phone app
uses: a message on the TV asks whether to allow it. Allow it, and from the next start all your apps show, with icons.

Developer Mode lasts about 40 days: open the Developer Mode app now and then and press **EXTEND** (if it runs out, the
TV removes developer apps; installing again brings it back). To remove EndGrid iQTV+:
`./webos/install.sh 192.168.1.50 --uninstall`.

### Install on a Samsung TV (Tizen)

Samsung TVs only install **signed** apps, and 2023 and newer TVs (Tizen 7+) only with a certificate made with your
Samsung account for that TV. Two ways:

**With TizenBrew Installer (no Samsung tools).** [TizenBrew Installer](https://github.com/reisxd/TizenBrewInstaller)
installs the first `.wgt` of a GitHub repository's latest release and, on 2023+ TVs, signs it with your Samsung
account. On the TV: *Apps* → type `1 2 3 4 5` on the remote → *Developer mode* on, with your computer's IP as *Host PC
IP* → restart the TV. Then open TizenBrew Installer on your computer, connect to the TV and install from GitHub with
`endgridlabs/iQTV`. This works once a release carries `iQTV-tizen-<version>.wgt` (`./tizen/build.sh --public`,
signed; the 1.0.0 release is Android-only). That build has no TMDB key: type yours on the TV in **Settings**.

**Your own build, signed by you.**

1. Install Samsung's Tizen tools (Tizen Studio's CLI, `tizen`) and make a certificate profile: a Samsung certificate
   for 2023+ TVs (Certificate Manager, signed in with your Samsung account, for your TV's DUID), or a Tizen
   certificate for 2017–2022 TVs. Certificate passwords stay in your own terminal.
2. On the TV: *Apps* → `1 2 3 4 5` → *Developer mode* on with your computer's IP as *Host PC IP* → restart the TV.
3. Build, sign and install:
   ```bash
   ./tizen/build.sh --sign <your profile>
   java tizen/Install.java 192.168.1.50
   ```
   `tizen/Install.java` speaks Samsung's SDB protocol itself (port 26101), so it needs only the JDK: no `sdb`. It
   pushes the package, installs it with the TV's `vd_appinstall`, and opens it. With Samsung's tools instead:
   `sdb connect <IP>`, then `tizen install -n iQTV-tizen.wgt -t <serial from sdb devices>`.
4. Optional: turn on **Autorun Last App** (Settings → General → Start Screen Options or Smart Features) so the TV
   turns on into EndGrid iQTV+.

Updates need the same certificate: the TV refuses one signed by another (remove it first: Apps → Settings →
EndGrid iQTV+ → Delete, or `java tizen/Install.java <IP> --uninstall`).

### Using it

- **Arrows, OK and Back** move around, as on Android: up from the hero goes to the navigation; down goes to Your Apps
  and the rows; Back goes to the top, then to Home, then leaves the app. LG's Magic Remote pointer works too.
- **Open *app*** starts the title's streaming app (at its home: webOS and Tizen give apps no way to open a title
  inside another app). **More Info** shows the description and where it streams. **Not for me** takes it off the rows.
- **Search** uses the TV's on-screen keyboard; results show as the first row, each saying where it streams.
- **Settings**: your TMDB key, region, content languages (TMDB's codes: `ta, te, hi`…), your name, the menu
  language, refreshing the rows, and the TV's own settings. What you set on the TV wins over `config.properties`
  and stays on the TV.

### For developers

| File | What it is |
|---|---|
| [`web/home.js`](../web/home.js) | The screen: top bar, hero and carousel, the stack (Your Apps, rows), More Info, the sheets, remote and pointer |
| [`web/data.js`](../web/data.js) | Settings on the TV, and the rows from TMDB (as `Recs`: providers on the TV, content languages, details, logo art) |
| [`web/tv.js`](../web/tv.js) | The TV's side: LG's service bus (Luna) and Samsung's Tizen APIs for apps, inputs, settings and Back |
| [`web/strings.js`](../web/strings.js) | Text from the Android app's catalogues (`app/assets/i18n`): `tr`, `trf`, `plural` |
| [`web/home.css`](../web/home.css) | The Top Shelf look, on a 1920 × 1080 stage |
| [`tools/webapp.sh`](../tools/webapp.sh) | What both builds share: the settings (`config.js`), catalogues, symbols and font |
| [`tools/WebPack.java`](../tools/WebPack.java) | Packs the `.ipk` (as LG's `ares-package`) and the `.wgt`; `icons` redraws the TV icons from the logo |
| [`webos/`](../webos), [`tizen/`](../tizen) | Manifests, icons, build and install scripts |

`web/` is plain ES5 JavaScript and CSS without variables, grid or backdrop-filter, because older TVs (webOS 3 and 4,
Tizen 3) run a Chromium from 2015–2017; only transform and opacity animate. A design change on Android
(`HomeView`'s Top Shelf) belongs in `web/home.js` and `web/home.css` too.
