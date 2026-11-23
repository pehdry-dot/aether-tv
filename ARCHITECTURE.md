# Architecture

Single Gradle module (`:app`) organised by layer. The boundaries below are enforced by package and by the rule that
only `platform/` touches system services.

```
ui (Compose)  ──reads──▶  LauncherViewModel.state (StateFlow<LauncherUiState>)
     │                             ▲
     └──actions──▶ LauncherViewModel ──▶ repositories (data/*) ──▶ Room / DataStore / SecretStorage
                                    └──▶ platform/* ──▶ PackageManager, TvInputManager, Bluetooth, Connectivity…
```

## State management
- `LauncherViewModel` combines profile-scoped flows (`settings`, `apps`, `favorites`, `categories`, `games`) with
  system flows (`devices`, `media`, `network`, `notifications`) into one immutable `LauncherUiState`.
- Switching profile re-subscribes everything via `flatMapLatest` on the active profile id.
- Transient UI state lives in the VM too: an overlay stack (`Overlay`), reorder `MoveMode`, notices.
- Pages are stateless functions of `LauncherUiState` plus local UI state (focus, query text).

## Persistence
| Data | Store | Notes |
|---|---|---|
| Settings (per profile) | Preferences DataStore, JSON per key | Unknown fields ignored, corrupt → defaults |
| Profiles, app metadata, favorites, categories, devices, game shortcuts | Room (`aether.db`) | Schema exported to `app/schemas` |
| Media-server credentials | `SecureStore` — AES-256-GCM, key in Android Keystore | Refuses to store if Keystore unavailable; never in backups |
| Wallpapers / icon overrides | App-private files | Imported via SAF, URL download, or the app's external folder |
| Backups | JSON in `Android/data/<pkg>/files/backups` or SAF | Excludes credentials |

## Platform integration boundaries (`platform/`)
- `PlatformInfo` — platform family (Fire TV / Google TV / Android TV), diagnostics; inferred values are flagged.
- `SystemShortcuts` — native settings screens with ordered intent candidates; filters Fire OS "CTSDummy" stubs.
- `TvInputController` — HDMI inputs from `TvInputManager` (with live state callbacks) and switching:
  Amazon `KaminoInputSwitchService` → passthrough `ACTION_VIEW` → open Live TV/Settings. Every attempt is logged.
- `DeviceScanner` — bonded Bluetooth devices (no discovery scans), A2DP/headset connection via profile proxies,
  game controllers via `InputDevice` (gamepad + joystick sources, so remotes aren't counted).
- `NetworkMonitor` — `ConnectivityManager` default-network callback.
- `NotificationHub` + `AetherNotificationListener` — notifications and media sessions, only with user-granted access.
- `AppLauncher` — launch / app info / uninstall via standard intents.

## Media adapters (`data/media`)
`MediaProvider` declares `capabilities`; `MediaRepository` aggregates enabled providers with timeouts, on demand,
throttled to once a minute (no background polling).
- `MediaSessionProvider` — what any app is playing (requires notification access); not a history.
- `PlexProvider` — plex.tv PIN link, server discovery, `/hubs/continueWatching`, search, deep link into Plex.
- `JellyfinProvider` — Quick Connect or password; `/Users/{id}/Items/Resume`, latest, search.
- `KodiProvider` — JSON-RPC in-progress movies/episodes, `Player.Open` resume.

## Design system
- `AetherTheme` builds immutable tokens (colors, shapes, type, motion, glass) from `AppearanceSettings`; UI scale is
  applied by overriding `LocalDensity`.
- **Glass**: `WallpaperHost` renders the wallpaper + legibility gradients once into a screen-size bitmap and a
  1/8-size box-blurred copy (off the main thread). `Modifier.glass` clips to its shape and draws the blurred copy at
  its own root position (read in the draw phase → no recomposition while scrolling), then tint, sheen and border.
- **Focus**: `GlassTile` = explicit `focusable` + D-pad/long-press/Menu handling (`tvClickable`) + subtle scale and a
  concentric-hairline glow. `FocusMemory` restores focus after overlays close.

## Data models
See `core/model/Models.kt` (`AppInfo`, `Favorite`, `ConnectedDevice`, `DeviceStatus`, `TvInput`, `MediaItem`) and
`data/db/Entities.kt`.
