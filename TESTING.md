# Testing

## Commands
```bash
./gradlew :app:testDebugUnitTest                                   # everything (JVM + Robolectric)
./gradlew :app:testDebugUnitTest --tests 'com.aether.tv.ui.NavigationTest'
./gradlew :app:recordRoborazziDebug --tests 'com.aether.tv.ui.ScreenshotTest'   # writes docs/screenshots/*.png
```
```bash
AETHER_REVIEW_SHOTS=1 ./gradlew :app:recordRoborazziDebug --tests 'com.aether.tv.ui.ReviewShotsTest' --rerun   # review PNGs of 1.2 screens
```
Robolectric runs with `graphicsMode=NATIVE`, so screenshots are real Compose/Skia renders of the production UI at
`w960dp-h540dp-xhdpi` (1920×1080 px), the Android TV / Fire TV UI resolution.

## Latest results (executed 2026-11-23, JVM, Robolectric SDK 34, version 1.2.0)
`./gradlew :app:testDebugUnitTest --rerun`, twice in a row: **177 tests, 160 run, 0 failed, 17 skipped**. The 17
skipped are `ReviewShotsTest`, which only render review screenshots when `AETHER_REVIEW_SHOTS=1` is set; they were run
that way during development and their PNGs inspected, but they assert nothing.

UI tests send keys through `Activity.dispatchKeyEvent` (`LauncherUiTest.remote()/up()/ok()/back()`), the same path
as the remote's keys, including Back through the Activity's back dispatcher.

| Suite | Tests | Covers |
|---|---|---|
| `ui.RemotePathTest` | 7 | **Settings trap:** Appearance ▸ slider ▸ Up to tabs ▸ Favorites ▸ open a favorite with zero Back presses, in "When focused" and "On click (OK)" modes, with fast key presses, after closing a dialog; Back on the tabs never pulls focus into Settings; Back from a panel goes to its category, then the tab; a search deep link opens Settings visibly |
| `ui.SettingsOverviewTest` | 7 | Overview: Right and OK both enter; Add Device (with a nested picker, keeping the edit), Xbox, Wallpaper (picker ▸ preview ▸ Apply/Cancel), Typography, Theme/Accent/Glass open their own controls; focus returns to the card each time |
| `ui.HiddenSettingsTabTest` | 6 | Hide/restore while editing the layout; ⚙ opens Settings without a tab and Back returns; internal shortcuts work without a tab; per-profile tab rows; a hidden start tab falls back to Home; the Home button closes overlays and shows Home |
| `ui.NavigationTest` | 11 | Initial focus, D-pad rows, Up to the selected tab, tab switching, pages built once and kept, sweeping tabs only builds the one you stop on, focus never enters a hidden page, Menu key options, move mode, slider ◀ ▶, search |
| `ui.HomeContinueWatchingTest` | 1 | A new Nuvio episode appears first, focus stays on its card through the reorder, OK resumes it in Nuvio |
| `ui.StorageAndStoreTest` | 2 | Storage measures and deletes only what you pick (with confirmation, assets in use kept); Store lists only stores that open and offers Aurora |
| `ui.IpAddressTest` | 4 | The IP address next to the date and its switch, nothing shown without an address, Quick Controls, the TV & System row and its `adb connect` command (Back returns to the row) |
| `ui.ScreenshotTest` | 11 | Renders docs/screenshots (Home, Apps, Favorites, Settings Overview, Media, Games, Appearance, app options, Quick Controls, light theme, icons only), driven by remote keys |
| `media.ContinueWatchingTest` | 10 | A new Nuvio episode after 12 old entries (late write), bursts coalesced, observers removed when Aether leaves the front, same-title episodes stay two cards, an offline server never delays local sources and keeps its last result, empty ≠ failed, account change drops old cards, Now Playing card, bounded artwork cache |
| `media.ContinueWatchingRankerTest` | 5 | Merge before the limit by engagement, Watch Next + channel duplicates become one card, untimed items keep publisher order, sessions yield to resumable listings, localized channel names |
| `update.UpdateRulesTest` | 7 | Newest compatible release, drafts/prereleases/other apps skipped, numeric versionCode (not names), Android version and signer, 32-bit TV needs armeabi-v7a code, GitHub JSON and notes, wrong-file verification |
| `update.UpdateManagerTest` | 8 | Check ▸ download ▸ verify ▸ install (against a local stand-in for GitHub), unknown-apps permission detour resumes, signer mismatch refused before downloading, tampered file deleted, interrupted download resumes with Range, cancel removes the partial file, rate limit and offline explained, not enough space |
| `data.BackupTest` | 6 | Round trip onto a fresh install keeps wallpaper, device photos and icon overrides; a damaged backup changes nothing; an interrupted restore is finished on the next start; undo restore; cleanup never deletes what another profile uses; 1.1.0 backups still restore |
| `data.WallpaperImportTest` | 2 | Apply keeps a preview, Cancel removes it, the TV folder is never touched; download errors are explained |
| `data.RepositoryTest` | 7 | Favorites, per-profile metadata, game override, profiles + PIN + settings isolation, PIN hashing, backup data round trip, Xbox seed is an unconfigured shortcut |
| `data.ProviderTest` | 3 | Jellyfin sign-in and resume, Kodi resume points, unreachable server → error (no fake data) |
| `unit.RemoteShortcutTest` | 6 | Holding ☰ opens the quick panel once, a short ☰ still reaches the app, other keys are never touched, the Home-button service never covers the panel, panel session expiry, Aurora release parsing and pinned signer |
| `unit.HomeRedirectPolicyTest` | 9 | Home redirect rules measured on the M450 (kept from 1.1) |
| `unit.IpAddressesTest` | 4 | IPv4 on the LAN first, never loopback or link-local, IPv6-only networks, `adb connect` targets (IPv6 in brackets) |
| `unit.NavRulesTest` | 5 | Older profiles keep their Settings tab and start tab, hidden start tab → Home, Home can't be hidden, detached Settings page, text-size steps |
| `unit.*` (other) | 39 | Settings JSON, app library logic, device status, parsing, logo mask, photo scaling, Watch Next text, weather |

Bugs found by these tests during 1.2 and fixed: `focusRestorer` redirected programmatic focus (dialogs and deep
links landed on the sidebar); OK on the selected tab followed geometry into a Home tile; an Overview row overflowed
and Left/Up landed on a zero-width tile; quick slider presses lost steps; the Home button raced overlay focus restore;
the Storage dialog lost focus after measuring; the 150 % Apps grid clipped names; a first-run favorites write could
outlive a short test; the launcher's database observers could outlive a test (the harness now stops them first).

## Emulator checks
None. This build environment has no KVM, so no Android emulator could run. Everything above is JVM + Robolectric.

## Physical TV (Toshiba 43M450SU)
Nothing in 1.2.0 has been run on the TV yet: no adb connection to it was available to the build session
(`adb devices` listed nothing). The 1.1.0 hardware checks from 2026-11-21 (launch, navigation, Home, HDMI) are in
CLAUDE.md and FEATURE_MATRIX.md.

### Awaiting hardware (1.2.0)
Each is simulated or reasoned from public sources only; see KNOWN_LIMITATIONS.md ▸ "1.2.0: what still needs the TV".
- First install or upgrade of the release-signed APK (see SIGNING.md) and Fire OS 14's installer confirmation.
- Settings ▸ Updates end to end against a real GitHub release, including the "Install unknown apps" detour.
- ⚙ (and holding Home) for the quick panel over Nuvio, YouTube and HDMI 1 with Amazon's launcher disabled; whether ⚙
  reaches Aether's key filter as `KEYCODE_SETTINGS`; whether `HudActivity` opens from Aether.
- Continue Watching timing with the real Nuvio (Watch Next written in `onStop`), and what YouTube exposes.
- Aurora Store install from GitLab and Amazon Appstore launch.
- Remote response (Advanced ▸ Remote response) with "Build pages in advance" on and off, and frame timing of the new
  fonts and the Overview.
- Storage numbers (`StatFs`) and app info screens on Fire OS.
- The IP address Fire OS reports on Wi-Fi and Ethernet.

### Measuring remote response on the TV
Settings ▸ Advanced ▸ Remote response shows median, 95th percentile and worst time from a key press to the next frame,
split by whether pages were built in advance, plus the worst press while a page was being built. To compare: use the
launcher for a minute with "Build pages in advance" on, note the line, switch it off, force-stop Aether (so pages are
built again), repeat, then select the row to refresh both lines.

## Visual fidelity
`docs/comparison/*.png` place each reference next to the rendered screen. Matched: layout grid, margins (48 dp),
tile/card proportions, pill tabs, typography scale, glass opacity, thin focus border with minimal glow, wallpaper
composition. Intentional differences: demo app icons instead of third-party logos; the Xbox photo is cut from the reference screenshot, so it is lower resolution than an official asset; the
procedurally generated wallpaper is moodier than the reference photo.

## How to measure performance on the TV
```bash
adb shell am force-stop com.aether.tv
```
```bash
adb shell am start -W -n com.aether.tv/.MainActivity
```
(`TotalTime` is the cold start.) Then browse for 30 s with the remote and run:
```bash
adb shell dumpsys gfxinfo com.aether.tv
```
(janky-frame percentage, 90th/99th percentile frame times). Memory:
```bash
adb shell dumpsys meminfo com.aether.tv
```
Idle CPU (should be ~0% while idle — no polling; the clock wakes once a minute):
```bash
adb shell top -n 1 -p $(adb shell pidof com.aether.tv)
```
If frames are slow: Appearance ▸ Glass rendering → Flat tint, Focus glow → 0%, Animations → Reduced.
