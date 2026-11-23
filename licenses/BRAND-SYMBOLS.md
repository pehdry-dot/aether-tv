# Brand symbols ("CleanFrame symbols" app style)

Appearance ▸ App logos ▸ **CleanFrame symbols** shows compact monochrome marks, modelled on CleanFrame's dock
(github.com/nthnclrk/CleanFrame, MIT, `CleanFrame-MIT.txt`). It uses the same balancing: each mark's visible ink is
measured, and its size is weighted so dense and sparse marks look equally large.

Brand marks identify their services. Their owners keep their trademark rights, and no endorsement or affiliation is
implied.

| Marks | Source | Licence |
|---|---|---|
| YouTube, YouTube Kids, YouTube TV, YouTube Music, Netflix, Spotify, Stremio, Max, Apple TV, Apple Music, Tubi, Plex, Kodi, Jellyfin, Twitch, Crunchyroll, Paramount+, Steam, VLC, Google Play, F-Droid, TIDAL, Deezer, DAZN, MUBI | Simple Icons 16.38.1 (npm package `simple-icons`, `icons/<slug>.svg`) | CC0 1.0, `SimpleIcons-CC0.txt` |
| Prime Video (the Amazon smile only, no wordmark) | Simple Icons 11.15.0 `icons/primevideo.svg`, last two subpaths, as extracted in CleanFrame `app/assets/icons/primevideo.svg` | CC0 1.0 |
| Disney+ | Custom Brand Icons (github.com/elax46/custom-brand-icons @ a53cabf, `icon-svg/disney-plus.svg`), recoloured in CleanFrame `app/assets/icons/disneyplus.svg` | MIT, `CustomBrandIcons-MIT.txt` |

The marks are generated into `core/designsystem/BrandSymbolData.kt` by `tools/gen_brand_symbols.py`.

**Apps without a bundled mark (Nuvio, for example):**
- Aether cuts a symbol out of the app's own icon on the TV. Nothing is bundled or downloaded.
- If the icon is Android's generic one, or no clean symbol can be cut out, Aether shows a monogram instead, made from
  the app's initials.
