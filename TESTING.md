# Testing

## Commands
```bash
./gradlew :app:testDebugUnitTest                                   # everything (JVM + Robolectric)
./gradlew :app:testDebugUnitTest --tests 'com.aether.tv.ui.NavigationTest'
./gradlew :app:recordRoborazziDebug --tests 'com.aether.tv.ui.ScreenshotTest'   # writes docs/screenshots/*.png
```
Robolectric runs with `graphicsMode=NATIVE`, so screenshots are real Compose/Skia renders of the production UI at
`w960dp-h540dp-xhdpi` (1920×1080 px), the Android TV / Fire TV UI resolution.

## Latest results (executed 2026-11-20, JVM, Robolectric SDK 34)
| Suite | Tests | Result | Covers |
|---|---|---|---|
| `unit.SettingsSerializationTest` | 4 | ✅ pass | defaults, corrupt JSON fallback, unknown fields, round trip |
| `unit.AppLibraryLogicTest` | 7 | ✅ pass | sorting (name/recent/usage/installed), hidden & non-TV filtering, search ranking, category/game/hidden filters, custom labels, list moves |
| `unit.DeviceStatusTest` | 5 | ✅ pass | shortcuts never "Connected", input state mapping, unavailable vs unknown, Bluetooth connected/paired/missing/no-permission, Home device selection |
| `unit.ParsingTest` | 3 | ✅ pass | HDMI hardware index (Fire TV `inputType`), HDMI port labels, server URL normalisation |
| `data.RepositoryTest` | 7 | ✅ pass | favorites add/dedupe/toggle/scoped reordering, per-profile app metadata, game override, profiles + PIN + settings isolation, PBKDF2 PIN hashing, backup round trip (no tokens), Xbox seed is an unconfigured shortcut |
| `data.ProviderTest` | 3 | ✅ pass | Jellyfin sign-in + resume parsing + auth headers, Kodi JSON-RPC resume points + artwork URLs, unreachable server → error (no fake data) — against a local MockWebServer |
| `ui.NavigationTest` | 8 | ✅ pass | initial focus, D-pad along rows, Up lands on the *selected* tab, tab switching, Back from content → tabs → Home, Menu key options + Back + focus restoration, move mode reordering via remote, settings slider via ◀ ▶, search overlay |
| `ui.ScreenshotTest` | 11 | ✅ pass | Home, Apps, Favorites, Settings, Media, Games, Appearance detail, app options, Quick Controls, light theme, icons-only |
| **Total** | **48** | **✅ 48 / 48** | |

Bugs found and fixed by these tests: tiles weren't focusable outside keyboard input mode; focus was lost after
closing overlays; Down from the Settings tab skipped the category list; placeholder missing in editing text fields.

## Visual fidelity
`docs/comparison/*.png` place each reference next to the rendered screen. Matched: layout grid, margins (48 dp),
tile/card proportions, pill tabs, typography scale, glass opacity, thin focus border with minimal glow, wallpaper
composition. Intentional differences: demo app icons instead of third-party logos; the Xbox photo is cut from the reference screenshot, so it is lower resolution than an official asset; the
procedurally generated wallpaper is moodier than the reference photo.

## Not executed (requires hardware)
These can't be run without the physical TV, so none of them is marked as passing:
- Install and launch on the Toshiba 43M450SU; OS/API confirmation.
- HDMI input switching (Amazon service / passthrough) and input-state accuracy.
- Bluetooth runtime permission flow on Fire OS 14; A2DP connection state.
- Notification-listener grant via adb; media sessions from real apps.
- Performance: cold start, frame timing, memory, idle CPU.

### How to measure on the TV
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
