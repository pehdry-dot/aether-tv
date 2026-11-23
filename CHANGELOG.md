# Changelog

## 1.2.0 (2026-11-23)

Built and tested with automated JVM/Robolectric tests. Hardware checks on the Toshiba 43M450SU are still to do (see
TESTING.md). **Install note:** signed with Aether's new release key. A one-time reinstall from 1.1.0 is needed; see
SIGNING.md.

**Fixed**
- **The Settings navigation trap.** After using a control in Settings and going up to the tabs, OK sometimes did nothing
  and only several Back presses helped. There were three causes:
  - Settings kept handling Back while the tabs had focus, and pulled focus back into its category list.
  - Pages kept alive in the background could still be given focus (a delayed focus request, a Search deep link, focus
    coming back after a dialog), so OK went to an invisible control.
  - Compose's focus restorer redirected programmatic focus to the wrong place.

  Now hidden pages and anything under a dialog can't take focus at all. Settings acts on Back only while its panel has
  focus. The tab that will receive OK is a solid pill, and the open page's tab keeps a glass pill. OK on the open tab
  moves into its page. Both tab modes (*When focused*, *On click*) are covered by tests that send the real key
  sequence.
- **Home while Aether is in front** closes overlays and move mode, and shows Home from the top with the first app
  focused.
- **Dialogs keep their state under pickers.** In Add Device, choosing a TV input used to throw away the device you were
  editing. Focus now always returns to the row or card that opened a dialog.
- Quick ◀ ▶ presses on sliders and choices no longer lose a step while the setting is being saved.

**New**
- **Settings ▸ Overview** is a permanent first entry. ▶ or OK enters the dashboard. Every card works with the remote:
  - Add Device and each device open their editor.
  - Wallpaper opens the picker with preview; Theme and Accent open their choices.
  - Glass & Blur opens a side panel; Typography opens its editor.

  ◀ returns to Overview, ▲ goes to Add Device and then the tabs, and Back returns to Overview. Closing a picker returns
  to the card.
- **The Settings tab can be hidden** (Home Layout ▸ Navigation tabs). The ⚙ button, Search results, the Home Inputs tile
  and Continue Watching setup all still open Settings. Older profiles keep their tab, and a hidden start tab falls back
  to Home.
- **Continue Watching refreshes itself.** While Aether is in front it watches Android TV's Watch Next list, channels
  and preview programs, so an episode that Nuvio publishes as you press Home appears on its own. Changes:
  - Sources refresh independently: an offline Plex/Jellyfin/Kodi server never delays the apps on the TV, and keeps its
    last good result, with its age shown.
  - Results are merged before the display limit, one card per episode. Ordering follows when you last watched;
    publisher order is kept where no time is given.
  - Nuvio's localized channel is recognised by its publisher id.
  - Media sessions (YouTube) appear as "Now Playing" cards that update live and disappear when the session ends.
  - Settings ▸ Integrations shows, for each source, its status, last read, item count, last error and a refresh row.
- **Wallpaper preview.** Built-in pictures, solid colours, the file picker, the TV folder and web addresses open full
  screen first. The preview uses the exact crop, dimming and glass Home will use, under a clock, tabs, the dock and a
  Continue Watching card. Apply saves; Back or Cancel changes nothing. Downloads that aren't applied are removed.
  Pictures in the TV folder are copied, never moved.
- **Typography editor**, with live samples (page heading, tab, app label, episode subtitle):
  - Six bundled typefaces (OFL): Inter, Atkinson Hyperlegible, Open Sans, Lexend, Nunito and Source Serif, plus the
    system font.
  - Text size from 90 to 150 % in 5 % steps, per profile, with Reset. Tiles, the Apps grid, dialogs and the Settings
    sidebar grow and reflow to fit.
- **Settings ▸ TV & System ▸ Storage** works with Amazon's home switched off. It shows free space, the artwork cache,
  Aether's pictures (in use or unused), backups and update downloads, and each app's info screen. You can clear the
  cache, remove unused pictures and delete backups one by one. Pictures in use by any profile and your own files are
  never deleted.
- **CleanFrame symbols** (Appearance ▸ App logos): compact monochrome brand marks with balanced sizes, on the Home dock,
  the Apps grid and Favorites.
  - Marks come from Simple Icons (CC0) and Custom Brand Icons (MIT); see `licenses/BRAND-SYMBOLS.md`.
  - Apps without a mark get a symbol cut from their own icon, or a monogram if the icon is Android's generic one.
  - Your custom icons always show.
- **Apps ▸ Store** lists the stores that can open on this TV, described for what they are. If Aurora Store is missing,
  Aether can install it from Aurora's official release, checked against Aurora's signing key.
- **Settings ▸ Updates.** Checks this project's GitHub Releases once a day while Aether is in use (switchable), or when
  you ask, and shows a notice you can dismiss.
  - An update is offered only if it matches this install: application id, numeric version code, Android version, the
    TV's 32-bit ABI and signing key.
  - It is downloaded to private storage and can resume after an interruption.
  - Package, version, checksum and signer are checked before Android's installer, where you choose Install with the
    remote.
- **Remote shortcuts** (TV & System):
  - The exact gesture for the TV's own picture and sound panel while an app or HDMI input is showing.
  - A row that tries to open that panel.
  - An optional "hold ☰ anywhere" shortcut through the Home button service; Home, Back, volume and playback are never
    touched.
  - A remote button tester.

  The Home button service never covers the TV's quick panel.
- **Backups include pictures.** Wallpapers, device photos, icon overrides and artwork are now in the backup, a ZIP.
  - The whole backup is checked before anything is replaced, and the data from before the restore is kept for Undo.
  - A restore that was interrupted completes on the next start.
  - 1.1.0 JSON backups still restore.
- **Advanced ▸ Remote response** measures key-to-frame delay on the TV, with "Build pages in advance" on or off.
- Release workflow (`.github/workflows/release.yml`): on a version tag it tests, signs, checks the key, attaches the APK,
  `aether-update.json` and checksums, and creates a draft release. Nothing is published automatically.

**Release signing**
- Release builds no longer fall back silently to a debug key. See SIGNING.md.

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
