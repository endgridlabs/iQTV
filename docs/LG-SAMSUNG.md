# LG and Samsung TVs

EndGrid iQTV+ is an Android TV app. **LG (webOS) and Samsung (Tizen) TVs don't run Android apps**, and they don't let
another app replace their home screen, so EndGrid iQTV+ can't be installed on them directly.

The way to get it on an LG or Samsung TV today is a **Google TV streamer** on one of its HDMI ports. You get the full
EndGrid iQTV+ home screen, every streaming app from the Play Store, and the TV's remote keeps working through HDMI-CEC.

## What you need

A Google TV or Android TV streamer, for example:

| Streamer | Notes |
|---|---|
| Google TV Streamer | Google's current box |
| Chromecast with Google TV (4K or HD) | Small, cheap; on Android 14, see the note in [INSTALL.md](INSTALL.md) about wireless debugging |
| onn 4K / onn 4K Pro | Budget Google TV boxes |
| NVIDIA Shield TV | Android TV, fastest |
| Xiaomi Mi Box / TV Stick, Realme / Airtel Xstream boxes | Android TV boxes common in India |

## Set it up

1. Plug the streamer into an HDMI port and power, and go through its setup.
2. Install EndGrid iQTV+ on it and make it the home screen: [INSTALL.md](INSTALL.md).
3. Make the TV feel like one device (menu names vary a little between models and years):

### LG (webOS)

- **HDMI-CEC:** Settings → General → Devices → HDMI Settings → **SIMPLINK (HDMI-CEC)** → On. The LG remote then
  controls the streamer, and turning the streamer on turns the TV on.
- **Start on the streamer:** Settings → General → System → **Home Settings** → turn off *Home Auto Launch*, and on
  webOS 6 and newer turn on **Start with last input** (*Additional Settings*), so the TV opens on the streamer's HDMI.

### Samsung (Tizen)

- **HDMI-CEC:** Settings → Connection → External Device Manager → **Anynet+ (HDMI-CEC)** → On.
- **Start on the streamer:** Settings → General → Start Screen Options (or *Smart Features*) → **Autorun Last Source**
  (on some models *Start with: Last Source*), so the TV turns on into the streamer's HDMI.

The TV's own home screen stays one press of its Home button away; everything you watch starts from EndGrid iQTV+.

## A native LG / Samsung app

A version of EndGrid iQTV+ that installs on webOS (through LG's Developer Mode) and Tizen (through TizenBrew) as a web
app is planned. It can't replace those TVs' home screens, only open as an app. Follow
[github.com/endgridlabs/iQTV](https://github.com/endgridlabs/iQTV) and [endgrid.in/iQTV](https://endgrid.in/iQTV) for news.
