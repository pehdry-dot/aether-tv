# Aether TV

A premium, minimalist, cinematic-glass launcher for **Android TV, Google TV and Android-based Fire TV**.
Native Kotlin + Jetpack Compose. No ads, no analytics, no root.

| Home | Apps |
|---|---|
| ![Home](docs/screenshots/01_home.png) | ![Apps](docs/screenshots/02_apps.png) |
| **Favorites** | **Settings** |
| ![Favorites](docs/screenshots/03_favorites.png) | ![Settings](docs/screenshots/04_settings.png) |

*Screenshots are rendered from the real app code at 1920×1080 (the TV UI resolution) with demo data: neutral demo
apps, original artwork, and simulated input/media states. See `docs/screenshots/` for Media, Games, light theme,
overlays.*

> **Read [KNOWN_LIMITATIONS.md](KNOWN_LIMITATIONS.md) first.** Fire OS does not allow third-party apps to replace the
> Home screen or to switch HDMI inputs through an official API. Aether works within those limits and says so in the UI.

## What's new in 1.2.0
See [CHANGELOG.md](CHANGELOG.md). Highlights:
- **Settings and remote fixes.** The Settings navigation trap is fixed. Overview works fully with the remote. The
  Settings tab can be hidden, since ⚙ still opens Settings. Home always shows a usable Home screen.
- **Continue Watching updates by itself**, for example a new Nuvio episode appears as you press Home. It also shows
  per-source diagnostics.
- **Appearance:** wallpaper preview before applying, a typography editor with six bundled typefaces and text up to
  150 %, and CleanFrame-style app symbols.
- **Storage screen** that works with Amazon's home switched off.
- **Apps ▸ Store**, which can install Aurora Store.
- **Updates from GitHub Releases**, installed with the remote.
- **Backups that include your pictures.**

**Upgrading from 1.1.0:** 1.1.0 was signed with a development key that isn't available any more. 1.2.0 needs a
one-time reinstall: back up, reinstall, restore. [SIGNING.md](SIGNING.md) has the exact steps. From then on, updates
install from **Settings ▸ Updates**, without a computer.

## What you get
- Home with favorite apps, Continue Watching (Plex / Jellyfin / Kodi / any app's live media session) and Connected
  Devices (HDMI consoles incl. **Xbox Series X**, Bluetooth headphones, controllers) with honest status.
- Apps library: search, sort, filters, categories, hidden apps, rename, icon overrides, grid or list.
- Favorites dashboard for apps, games, HDMI inputs, devices, system screens and media.
- Games page split into *Play on this TV*, *Stream* and *Consoles* (HDMI).
- Settings: Overview dashboard, appearance (wallpaper with preview, theme, accent, glass, typography editor, app logo
  style), home layout, behavior, applications, devices, TV & system (storage, remote shortcuts), integrations with
  diagnostics, profiles with PINs, updates, backup/restore, diagnostics.
- Everything works with a TV remote: D-pad, OK, Back, long-press OK or the ☰ Menu key for options.

## Install on the Toshiba M450 / Fire TV

1. **Check the OS.** *Settings ▸ My Fire TV ▸ About*. It must be **Fire OS** (Android-based). Vega OS devices can't
   install APKs.
2. **Enable developer options.** *Settings ▸ My Fire TV ▸ About*, then click the device name 7 times. Then in
   *Developer options*, turn on **ADB debugging** and **Apps from Unknown Sources** (or allow your installer app).
3. **Install**, using one of the two methods below.

### Option A — ADB from a computer
```bash
adb connect <TV-IP-address>:5555
```
```bash
adb install -r app-release.apk
```
Find the IP under *Settings ▸ My Fire TV ▸ About ▸ Network*. Accept the debugging prompt on the TV.

### Option B — the Downloader app
Host the APK on your network or a URL you control, open Downloader on the TV, enter the URL and install.

### After installing
- Open **Aether TV** from *Your Apps & Channels* and move it to the front of the app row.
- Optional, recommended (one-time, via adb):
```bash
adb shell cmd notification allow_listener com.aether.tv/com.aether.tv.platform.AetherNotificationListener
```
  This enables the notifications panel and "Now playing" in Continue Watching.
- Optional: "Open Aether after boot" (Settings ▸ Behavior) needs:
```bash
adb shell appops set com.aether.tv SYSTEM_ALERT_WINDOW allow
```
- **Set up your Xbox:** Home ▸ Connected Devices ▸ *Xbox Series X* ▸ choose its TV input (or HDMI port label).
  Selecting the card then tries to switch to that input; see *Settings ▸ Advanced ▸ Recent events* if it doesn't.
- **Add a real photo of your console:** press ☰ (or hold OK) on the Xbox card ▸ *Add picture*, then either paste a
  direct link to a photo (downloaded once, kept on the TV), pick a file, or copy one to the TV and choose
  *From the TV folder*:
```bash
adb push xbox-series-x.png /sdcard/Android/data/com.aether.tv/files/devices/
```
  The Xbox Series X comes with a real photo of the console (taken from your reference screenshots). Other devices
  show their icon and status until you add a photo. To bundle a better product shot on a white background, run
  `python3 tools/cutout_product_photo.py photo.jpg app/src/main/res/drawable-nodpi/device_xbox_series_x.png`
  and rebuild.

### Android TV / Google TV
Install with `adb install -r app-release.apk`. On devices that offer a Home-app chooser, Aether can be picked as Home
(*Settings ▸ Behavior ▸ Default home app*). Otherwise launch it like any app.

## Build from source
Requirements: JDK 17+ and the Android SDK (platform 36, build-tools 36).
```bash
export ANDROID_HOME=/path/to/android-sdk
```
```bash
./gradlew :app:assembleRelease
```
Output: `app/build/outputs/apk/release/app-release.apk` (R8-minified; package `com.aether.tv`).
`./gradlew :app:assembleDebug` builds `com.aether.tv.debug`, which can sit alongside the release.

### Release signing
A release build needs a signing key, given in a git-ignored `keystore.properties` or through environment variables
in CI. Without one, the build stops with an explanation; it never signs with a debug key silently. Keys and passwords
are never committed. [SIGNING.md](SIGNING.md) covers setup, the GitHub Actions release workflow, and why the key must
stay the same for in-place updates.

## Troubleshooting
| Problem | Fix |
|---|---|
| Home button goes to Amazon's screen | Expected on Fire OS (see limitations). Launch Aether from the app row. |
| Settings tab is gone | It can be hidden (Home Layout ▸ Navigation tabs). The ⚙ button at the top right always opens Settings. |
| Picture or sound while watching an app or the Xbox | Press ⚙ on the remote (or hold Home) over the app or HDMI input; see Settings ▸ TV & System ▸ Remote shortcuts. |
| An update isn't offered | Settings ▸ Updates says why (no newer release, other signing key, Android version). See SIGNING.md. |
| Selecting a console doesn't switch input | *Settings ▸ Advanced ▸ Recent events* shows what was tried. Make sure the device has a TV input assigned (*Settings ▸ Devices*). Some Fire OS builds refuse the input service; Aether then opens Live TV/Settings. |
| A device shows "Input shortcut" | The TV didn't report that input to apps, so Aether can't verify its status. |
| Continue Watching is empty | Connect Plex/Jellyfin/Kodi in *Settings ▸ Integrations*, or grant notification access. Streaming apps don't share history. |
| A system setting shows "Not available on this TV" | The TV has no app-accessible screen for it; use the remote's ⚙ button. |
| Edges cut off | *Settings ▸ Behavior ▸ Overscan margin* or *Interface scale*. |
| Laggy on an older TV | *Settings ▸ Appearance*: Glass rendering → Flat tint, Animations → Reduced, Focus glow → 0%. |
| Icons/labels too small | *Appearance ▸ Icon size*, *Text size*. |

## Recovery and uninstall
- Aether never modifies system packages, so uninstalling restores the TV completely:
  *Settings ▸ Applications ▸ Manage Installed Applications ▸ Aether TV ▸ Uninstall*, or:
```bash
adb uninstall com.aether.tv
```
- Reset Aether's data without uninstalling:
```bash
adb shell pm clear com.aether.tv
```
- Back up first: *Settings ▸ Advanced ▸ Back up to TV storage*, then:
```bash
adb pull /sdcard/Android/data/com.aether.tv/files/backups/
```
- If Aether became the default Home on an Android TV device and you want the stock launcher back, choose it again in
  the system's Home-app setting, or uninstall Aether.

## Project docs
[ARCHITECTURE.md](ARCHITECTURE.md) · [FEATURE_MATRIX.md](FEATURE_MATRIX.md) · [KNOWN_LIMITATIONS.md](KNOWN_LIMITATIONS.md) ·
[TESTING.md](TESTING.md) · [ROADMAP.md](ROADMAP.md) · [CLAUDE.md](CLAUDE.md)

## Licenses
Apache-2.0 libraries (AndroidX, Compose, Hilt, Room, Coil, OkHttp, kotlinx). Typefaces (Inter, Atkinson Hyperlegible
Next, Open Sans, Lexend, Nunito, Source Serif 4): SIL OFL 1.1 (`licenses/*-OFL.txt`). CleanFrame symbols: brand marks
from Simple Icons (CC0) and Custom Brand Icons (MIT), sizing after CleanFrame (MIT); see `licenses/BRAND-SYMBOLS.md`. Icons derived from Lucide: ISC (`licenses/Lucide-LICENSE.txt`). Wallpapers are original
procedurally generated artwork (`tools/generate_wallpaper.py`). No third-party logos are bundled.
