# NuxTV

A modern IPTV player for **Android TV**, built with Jetpack Compose for TV and Media3/ExoPlayer.

Point it at an **Xtream Codes** login or a plain **M3U playlist link** and NuxTV organizes everything into a real TV experience — Live channels, a Movies library, and Series with seasons and episodes.

## Features

- **Two playlist types**
  - **Xtream Codes** — server URL + username + password (full live/VOD/series catalogs, posters, plots, ratings, lazily loaded episode lists)
  - **M3U URL** — any standard playlist link
- **Intelligent playlist mapping** — flat M3U playlists are classified automatically:
  - Xtream-style URL paths (`/live/`, `/movie/`, `/series/`) are used as authoritative signals
  - Series are detected from `S01E02`, `1x02`, and `Season 1 Episode 2` title patterns, then grouped into shows → seasons → episodes
  - Movies are detected from group keywords (VOD/Movies/Film…) and file containers (`.mkv`, `.mp4`, …), with year extraction (`Title (2023)`)
  - Everything else becomes a live channel, grouped by `group-title` categories
  - Titles are cleaned of quality noise (`1080p`, `HEVC`, `[4K]`, language prefixes)
- **TV-first UI** — collapsible navigation rail, category rows with focus-scaling poster cards, immersive hero header, detail pages for movies and series, D-pad friendly throughout
- **Playback** — Media3/ExoPlayer with HLS + progressive support, episode binge queueing, and live channel zapping with DPAD/CHANNEL up/down
- **Multiple playlists** — add several sources and switch between them in Settings

## Building

```bash
./gradlew assembleDebug        # APK at app/build/outputs/apk/debug/
./gradlew testDebugUnitTest    # classifier + parser unit tests
```

Requires JDK 17+ and the Android SDK (compileSdk 36). Install on an Android TV device or emulator:

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Architecture

| Layer | What's there |
|---|---|
| `data/` | `XtreamClient` (defensive JSON parsing of the wildly inconsistent Xtream API), `M3uParser`, `ContentClassifier` (the mapping brain), `ContentRepository`, DataStore-backed `SourceStore` |
| `ui/screens/` | Onboarding, Home (Live/Movies/Series/Settings tabs), detail screens, ExoPlayer screen |
| `ui/components/` | Focus-aware TV cards, hero header, artwork with generated fallbacks |

No content is bundled — NuxTV is a player; you bring your own playlist.

## Roadmap

- Search across channels, movies and series
- EPG (XMLTV) support for live guide data
- Continue-watching / resume positions
- Favorites
