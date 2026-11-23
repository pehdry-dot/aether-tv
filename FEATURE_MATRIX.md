# Feature matrix

Status: ✅ implemented · 🟡 partial / depends on platform · ⛔ not possible on that platform · — n/a
Verification: **JVM** = unit/Robolectric tests or rendered screenshots; **HW** = verified on a physical device
(none yet).

| Feature | Status | Android TV | Google TV | Fire TV | Verified |
|---|---|---|---|---|---|
| Home / Apps / Media / Games / Favorites / Settings pages | ✅ | ✅ | ✅ | ✅ | JVM (screenshots, D-pad tests) |
| Full D-pad navigation, focus restoration, Back semantics | ✅ | ✅ | ✅ | ✅ | JVM (NavigationTest) |
| Long-press and Menu (☰) key options | ✅ | ✅ | ✅ | ✅ | JVM |
| Real installed-app discovery (LEANBACK + LAUNCHER), live updates | ✅ | ✅ | ✅ | ✅ | JVM (repository tests); HW pending |
| Launch apps, app info, uninstall dialog | ✅ | ✅ | ✅ | 🟡 app-info screen may be stubbed → falls back | HW pending |
| Favorites: apps, games, inputs, devices, system settings, media | ✅ | ✅ | ✅ | ✅ | JVM |
| Remote-friendly reordering (move mode) | ✅ | ✅ | ✅ | ✅ | JVM |
| Hidden apps, categories, rename, icon override (URL) | ✅ | ✅ | ✅ | ✅ | JVM |
| Search (apps, devices, games, settings, media) | ✅ | ✅ | ✅ | ✅ | JVM |
| Voice search | 🟡 if RecognizerIntent handler exists | 🟡 | 🟡 | 🟡 | — |
| Clock/date, network status | ✅ | ✅ | ✅ | ✅ | JVM |
| Notifications panel | 🟡 needs notification access | ✅ | ✅ | 🟡 adb grant | — |
| Connected devices with honest status | ✅ | ✅ | ✅ | 🟡 depends on input HAL | JVM (DeviceStatusTest) |
| Xbox Series X shortcut with real console photo + editor (input, photo, action, network check) | ✅ | ✅ | ✅ | ✅ | JVM |
| HDMI switching | 🟡 | 🟡 passthrough intent | 🟡 passthrough intent | 🟡 Amazon service (undocumented) | HW pending |
| Bluetooth headphones / controllers | ✅ | ✅ | ✅ | ✅ (runtime permission on Fire OS 14) | JVM logic; HW pending |
| Quick Controls (opens native screens) | ✅ | ✅ | ✅ | 🟡 some screens unavailable | — |
| Native settings shortcuts | ✅ | ✅ | ✅ | 🟡 stub detection | — |
| Continue Watching — Plex | ✅ | ✅ | ✅ | ✅ | — (needs a server) |
| Continue Watching — Jellyfin | ✅ | ✅ | ✅ | ✅ | JVM (mock server) |
| Continue Watching — Kodi | ✅ | ✅ | ✅ | ✅ | JVM (mock server) |
| Now Playing from media sessions | 🟡 needs notification access | ✅ | ✅ | 🟡 | — |
| Netflix/Disney+/Prime progress | ⛔ no API | ⛔ | ⛔ | ⛔ | — |
| Games page: local / streaming / console groups, game shortcuts | ✅ | ✅ | ✅ | ✅ | JVM |
| Appearance: wallpaper (builtin, URL, file, folder), theme, accent, glass, blur, dim, radius, border, icons, type, motion, glow, zoom | ✅ | ✅ | ✅ | ✅ | JVM (settings tests, screenshots) |
| Home layout: section order/visibility, tabs order/visibility, default tab, counts, tile size, status bar | ✅ | ✅ | ✅ | ✅ | JVM |
| Behavior: tab activation, focus memory, back, long-press, menu key, search scope, UI scale, overscan | ✅ | ✅ | ✅ | ✅ | JVM |
| Profiles with separate favorites/layout/theme + PIN | ✅ | ✅ | ✅ | ✅ | JVM (RepositoryTest) |
| Backup / restore / export / import | ✅ | ✅ | ✅ | ✅ | JVM (round-trip test) |
| Diagnostics, permission status, recent-events log | ✅ | ✅ | ✅ | ✅ | — |
| Default Home app | 🟡 | ✅ user-selectable | 🟡 adb only | ⛔ | — |
| Open after boot | 🟡 overlay permission | 🟡 | 🟡 | 🟡 adb | — |
| Animated wallpaper (optional) | ✅ | ✅ | ✅ | ✅ | — |
| Icon packs | ⛔ not yet | | | | — |
| Settings Overview dashboard, remote-reachable | ✅ | ✅ | ✅ | ✅ | JVM (SettingsOverviewTest) |
| Hideable Settings tab (⚙ always opens Settings) | ✅ | ✅ | ✅ | ✅ | JVM (HiddenSettingsTabTest) |
| Continue Watching live refresh (observers, late writes, ranking) | ✅ | ✅ | ✅ | 🟡 needs TV listings permission | JVM (ContinueWatchingTest, fake TvProvider); HW pending |
| Wallpaper preview before applying | ✅ | ✅ | ✅ | ✅ | JVM |
| Typography editor, 6 bundled typefaces, 90–150 % | ✅ | ✅ | ✅ | ✅ | JVM, screenshots |
| Storage screen (Aether-native) | ✅ | ✅ | ✅ | ✅ works without Amazon's launcher | JVM |
| CleanFrame symbols app style | ✅ | ✅ | ✅ | ✅ | JVM, screenshots |
| Apps ▸ Store (launchable stores, Aurora install) | ✅ | ✅ | ✅ | 🟡 Google Play usually absent | JVM; HW pending |
| Updates from GitHub Releases | ✅ | ✅ | ✅ | 🟡 installer confirmation unverified on Fire OS 14 | JVM (mock GitHub); HW pending |
| TV quick panel from apps/HDMI (⚙ / hold Home; optional hold ☰) | 🟡 | — | — | 🟡 documented gesture, unverified on M450 | JVM (policy); HW pending |
| Backups with pictures, checked + recoverable restore | ✅ | ✅ | ✅ | ✅ | JVM (BackupTest) |
