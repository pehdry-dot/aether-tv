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

## Focus and navigation (1.2.0)
- **Focus layers** (`core/designsystem/FocusScopes.kt`): the launcher, each page kept alive in the background, and each
  open overlay are `FocusLayer`s. `GlassTile` can take focus only while its layer is active. A hidden page or anything
  under a dialog can never hold or receive focus, whoever asks.
- **Overlays are a stack** (`LauncherViewModel.overlays`). All entries stay composed, so a dialog keeps its edits under
  a picker; only the top is placed. `OverlayHost` returns focus to the tile or row that opened each overlay; an
  overlay that replaced another inherits that one's opener.
- **`dpadEntry` instead of `focusRestorer`** for groups that are also targets of programmatic focus (tab row, Settings
  page). Compose's restorer redirects *every* entry, including `requestFocus()` on a specific child.
- **Settings** owns Back only while its panel has focus. Deep links (`vm.openSettings`) wait until Settings is on screen.

## Persistence
| Data | Store | Notes |
|---|---|---|
| Settings (per profile) | Preferences DataStore, JSON per key | Unknown fields ignored, corrupt → defaults |
| Profiles, app metadata, favorites, categories, devices, game shortcuts | Room (`aether.db`) | Schema exported to `app/schemas` |
| Media-server credentials | `SecureStore` — AES-256-GCM, key in Android Keystore | Refuses to store if Keystore unavailable; never in backups |
| Wallpapers, icon overrides, device photos, artwork | App-private files (`AssetStore`) | Imported via SAF, URL or the TV folder (copied); unused copies removed; originals never touched |
| Backups | ZIP (format 2: JSON + pictures) in `Android/data/<pkg>/files/backups` or SAF | Checked before restore, journaled, undo; excludes credentials |
| Update downloads | `files/updates` | Verified before install; removed once installed |

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
`MediaProvider` declares `capabilities`. `MediaRepository` refreshes each source independently and keeps a
`SourceState` for each: the last good items, the last read, and the last error.
- **While Aether is in front**, the TV's listings (Watch Next, channels, preview programs) and media sessions are
  observed. Bursts are coalesced, and follow-up reads run 2 and 4 s after coming to the front.
- **Servers** are read at most once a minute unless asked.
- **Nothing runs in the background.**
- `ContinueWatchingRanker` merges, deduplicates (per episode) and orders all sources; Home applies the limit.
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

## Updates and signing
`data/update/UpdateRules` (pure) selects and verifies; `UpdateManager` checks GitHub Releases, downloads and resumes;
`platform/ApkInstaller` reads APKs and runs a PackageInstaller session. See SIGNING.md.
