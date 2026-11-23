# Known limitations

Aether is honest about what the platform allows. This file separates **confirmed restrictions**, **unverified
assumptions** and **workarounds**.

## Not yet verified on the target TV
- **Partially tested on the TV (2026-11-21).** Verified on the Toshiba 43M450SU: install, cold start (0.49 s),
  memory (~56 MB PSS), D-pad navigation across tabs and rows, Home behaviour (see below), HDMI switching through
  Amazon's input service. Still unmeasured on hardware: frame timings, media integrations, Bluetooth listing.
- **The M450 runs Fire OS 14** (Android 14, API 34, build SL14SHIP1015), not Vega OS. Its userland is 32-bit only.

## 1.2.0: what still needs the TV
1.2.0 was built and tested without access to the Toshiba: no adb connection was available to the build session. The
checks below were simulated (Robolectric) or come from public sources, not from this TV:

- **TV quick panel from apps and HDMI.**
  - Amazon documents ⚙ on the remote (or holding Home) as opening Quick Settings over what's playing, with Picture
    settings kept per input.
  - The panel was reported as `com.amazon.tv.settings.v2/.hud.HudActivity` on a Fire OS 8 Fire TV Edition set.
  - Not checked on the M450:
    - whether ⚙ works with `com.amazon.tv.launcher` disabled;
    - whether ⚙ reaches apps as `KEYCODE_SETTINGS` (176);
    - whether `HudActivity` is exported;
    - whether Android's quick-settings global action does anything on Fire OS.
  - Settings ▸ TV & System ▸ Remote shortcuts has a button tester and an "open it now" row to check this on the TV.
  - The optional hold-☰ shortcut needs the Home button service. A held ☰ is not passed on to the app.
- **Updates.** The selection, download, verification and unknown-apps logic are tested against a stand-in for GitHub.
  Android's installer confirmation on Fire OS 14 hasn't been seen yet. Community reports describe it working on Fire
  OS 7, approved with the remote. Fire OS's `ACTION_MANAGE_UNKNOWN_APP_SOURCES` may open Developer options or the
  device page rather than Aether's own switch; the UI explains the path.
- **YouTube** on Fire TV publishes only a media session, as far as public evidence shows (its Cobalt app has no
  TvProvider code). Aether shows it as "Now Playing" while the session lives, and never as a watch history.
- **Nuvio** writes Watch Next when playback stops, in `onStop`, and every 15 minutes. Aether watches the tables while in
  front and reads again 2 and 4 s after coming to the front. Not timed on the TV.
- **Aurora Store** installs from its official GitLab release (signer pinned). D-pad use of Aurora on Fire OS has known
  rough edges (Aurora issues #1250, #1545). Google Play isn't normally installable on Fire OS.
- **Storage** can't show other apps' sizes: that needs Usage Access, which Fire TV has no screen for. Each app's own
  info screen is offered where the TV has one.
- **Remote response** with and without building pages in advance is measured in the app (Advanced ▸ Remote response),
  but no measurement from the M450 exists yet.

## Fire OS restrictions (confirmed by public sources)
- **Home button.** Fire OS always routes Home to Amazon's launcher while it is enabled; a preferred-home setting is
  accepted but ignored (verified on the M450). Two owner-chosen workarounds, both set up with adb:
  - **"Aether only" mode** (in use on the owner's TV since 2026-11-21): `com.amazon.tv.launcher` disabled for user 0
    and Aether set as preferred home. Home opens Aether directly; the TV boots to Aether. Cost: Amazon's main Settings
    menu is unavailable until the launcher is re-enabled (`scripts/launcher-mode.sh full-settings --apply` in the
    maintenance kit). Network, Applications, Controllers & Bluetooth and Device stay reachable from Aether (the latter
    two behind the parental-controls PIN when it is on); Picture/Sound/Inputs via the remote's ⚙ quick settings.
  - **`HomeButtonService`** (accessibility, window events only): when Amazon's home appears, Aether comes back after
    ~1 s. Amazon's screen still flashes. Paused automatically while Aether has opened Fire TV Settings. Fire TV has no
    UI to enable it; see the commands in Settings ▸ Behavior. Can break with any Fire OS update.
- **Settings intents.** Several standard `android.settings.*` actions resolve to a stub (`CTSDummySettingsHandler`)
  on Fire OS. Aether filters stubs out and tries Amazon's own screens first; screens with no working entry point are
  shown as "Not available on this TV" rather than as dead buttons.
- **Notification access / media sessions.** Fire OS may hide the special-access screen. Grant via adb:
  `adb shell cmd notification allow_listener com.aether.tv/com.aether.tv.platform.AetherNotificationListener`.
  Android ignores this permission on some low-RAM devices.
- **Bluetooth.** On Fire OS 14 (API 31+) reading paired devices needs the runtime `BLUETOOTH_CONNECT` permission
  (Aether asks in Settings ▸ Devices). Aether never scans for new devices.

## HDMI input switching
- There is **no official public API** for third-party apps to switch HDMI inputs on Fire TV.
- Aether tries, in order, logging each attempt to *Settings ▸ Advanced ▸ Recent events*:
  1. Amazon's `com.amazon.tv.inputpreference.service/.KaminoInputSwitchService` with `inputType` = the hardware
     index from the input id (e.g. `…/HW2`). Community-documented, undocumented by Amazon; may be unexported or
     refused on some builds.
  2. `ACTION_VIEW` on `TvContract.buildChannelUriForPassthroughInput(inputId)` (works on many Android TV/Google TV
     sets; on Fire TV it may open Live TV without switching).
  3. Opening Live TV / Settings with instructions.
- Aether **cannot observe whether the TV actually switched**, so it never reports success.
- Input state ("Connected", "Standby", "No signal") comes from `TvInputManager.getInputState`. Whether a given TV's
  HDMI HAL reports these accurately is unverified; if the TV lists no inputs, devices show as "Input shortcut".
- `TV_INPUT_HARDWARE`, HDMI-CEC control and key injection (HDMI keycodes) are system-only and are not used.

## Media
- **Netflix, Disney+, Prime Video, YouTube etc. do not expose watch history to other apps.** Aether cannot show their
  progress. With notification access it can show an app's *active* media session (title, artwork, position) while
  that app keeps the session alive.
- Plex resume uses a `plex://` deep link that is not officially documented; if it fails, the Plex app is opened.
- Jellyfin's Android TV app has no documented deep link; Aether opens the app.
- Kodi requires "Allow remote control via HTTP".

## Other
- **Open after boot**: Android 10+ blocks background activity starts unless "Display over other apps" is granted;
  Fire TV may have no UI for it (`adb shell appops set com.aether.tv SYSTEM_ALERT_WINDOW allow`).
- **File pickers**: many TVs have no document picker. Wallpaper import, icon overrides and backup restore therefore
  also work via web URL or the app folder `Android/data/com.aether.tv/files/` (adb push).
- **Voice search** appears only if a `RecognizerIntent` handler exists; Aether cannot use the remote's Alexa/Assistant
  button.
- **Icon packs** are not supported yet. Icon overrides by URL are, and so is the CleanFrame symbols style.
- **Brand symbols** cover 27 services (YouTube, Netflix, Prime Video, Spotify, Disney+, Plex, Kodi and others). Nuvio
  has no published mark: its symbol is cut from its own icon on the TV, or shown as a monogram.
- **Usage-based "Recently used"** sorting counts launches made from Aether only (no Usage Access permission needed).
- **Brand logos and product photos**: app icons are read from the installed apps. The bundled Xbox Series X photo was
  cut out of the owner's own reference screenshots for this personal, non-distributed build (Xbox is a Microsoft
  trademark; the photo is Microsoft's). Remove `res/drawable-nodpi/device_xbox_series_x.png` before sharing the APK
  with anyone. Other consoles get a photo you add (web address, file picker, or the app's `files/devices` folder).
  The build environment couldn't reach image hosts, so no higher-resolution official photo was downloaded.
- **Performance figures are not yet measured** on target hardware (see TESTING.md for how to measure).

## Google TV / Android TV
- Recent Google TV builds block launcher overrides through accessibility services; the reliable route is disabling
  the stock launcher with adb (`pm disable-user --user 0 <stock launcher package>`), which is risky and not
  recommended. Aether keeps working as a normal app.
