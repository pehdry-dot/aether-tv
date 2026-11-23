# Changelog

## 1.1.0 (2026-11-23)

Tested on a Toshiba 43M450SU Fire TV (Fire OS 14, Android 14).

**New**
- **Home button on Fire TV.** An optional accessibility service, `HomeButtonService`, brings Aether back when Home
  opens Amazon's home screen. It reads window changes only: no screen content, keys or screenshots. It leaves Fire TV
  Settings opened from Aether alone. Alternatively, with Amazon's launcher disabled over adb ("Aether only" mode),
  Home opens Aether directly. See KNOWN_LIMITATIONS.md.
- **Continue Watching from apps on the TV.** Reads Android TV's Watch Next list and apps' "Continue …" channels, so
  apps such as Nuvio, Netflix or Prime Video show up with episode, artwork and progress. Needs the TV listings
  permission (Settings ▸ Integrations ▸ Apps on this TV). A resume link can only open the app that published it.
- **Weather next to the date** from Open-Meteo, with no account or key. The city comes from Settings, or from the TV's
  time zone. It refreshes when Aether is in front and every 30 minutes while it stays there, never in the background.
  Options are under Settings ▸ Home Layout ▸ Status bar (on/off, city such as "Cambridge, MA", °C/°F).
- **White app logos and a glass dock** (Settings ▸ Appearance ▸ App logos). Logos are cut out of each app's own banner
  or icon: nothing is bundled or downloaded. On Home, the favourite apps sit in one glass dock. "Original colours"
  brings back the tiles.

**Faster and sharper**
- Glass is drawn in one GPU pass on Android 13+. Settings scrolling went from a 45 ms to a 27 ms typical frame on
  the Fire TV.
- Pages are kept alive after their first visit, and tabs switch once focus rests. The worst frame when switching
  tabs went from 300–450 ms to under ~100 ms.
- Logos and device photos are shrunk once, by area averaging, to their exact on-screen size and drawn pixel for
  pixel. That removes jagged edges and moiré.
- The settings master–detail panel follows the category list; fixes for focus, Back and text fields on hidden pages.

**Other**
- Xbox Series X card: higher-resolution product photo. Xbox Series X photo © Microsoft, from xbox.com.
- 80 unit and UI tests (was 48).

**Install note:** this build is signed with a development key. If you installed a 1.0.0 signed with a different key,
uninstall it first (settings reset). Then turn the Home service on with
`adb shell settings put secure enabled_accessibility_services com.aether.tv/com.aether.tv.platform.HomeButtonService`
and `adb shell settings put secure accessibility_enabled 1`. The first command replaces any other enabled
accessibility services; if you use others, add Aether to the existing list, separated by `:`.
