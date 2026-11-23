# Roadmap

## Completed
- **Phase 1 — Foundation**: toolchain, Gradle KTS, Hilt/Room/DataStore, design tokens, platform research.
- **Phase 2 — Home**: wallpaper + cached-blur glass, clock/date, status icons, tabs, app row, Continue Watching,
  Connected Devices, remote interactions.
- **Phase 3 — Launcher core**: app discovery and launch, Apps library (search/sort/filter/grid/list), favorites,
  options menus, move mode, search, persistence.
- **Phase 4 — Settings**: all nine categories incl. appearance, layout, behavior, applications, profiles (PIN),
  backup/restore, diagnostics, event log.
- **Phase 5 — Devices**: device shortcuts and editor, Xbox Series X seed, TV input discovery + switching with
  fallbacks, Bluetooth and controllers, Quick Controls.
- **Phase 6 — Media & Games**: provider framework, Plex/Jellyfin/Kodi/media sessions, Media page, Games page with
  local/streaming/console grouping and game shortcuts.
- **Phase 7 — Hardening (JVM)**: 48 automated tests, rendered screenshots, R8 release build verified.

## Next (needs the physical TV)
1. Install on the Toshiba M450; confirm OS/API level (Settings ▸ Advanced ▸ Diagnostics).
2. Verify HDMI switching per input (Recent events log), adjust the Fire TV `inputType` mapping if needed.
3. Measure startup and frame timings (`adb shell dumpsys gfxinfo com.aether.tv framestats`); tune blur/glow.
4. Check overscan on the panel; verify notification access via adb.

## Later
- Icon pack support (ADW `appfilter.xml`).
- Baseline profile generation on device (Macrobenchmark) for faster cold start.
- Optional Emby provider; Plex PIN flow polish.
- Per-profile wallpaper slideshow.

## Technical dependencies
AGP 8.13.0 · Kotlin 2.2.20 · KSP 2.2.20-2.0.4 · Compose BOM 2025.10.00 · Hilt 2.57.2 · Room 2.8.2 ·
DataStore 1.1.7 · Coil 3.3.0 · OkHttp 4.12.0 · kotlinx-serialization 1.9.0 · Robolectric 4.16 · Roborazzi 1.50.0.
