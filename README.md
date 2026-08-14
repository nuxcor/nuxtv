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
- **Dual playback engines** — ExoPlayer and libVLC behind one abstraction. If a stream fails on ExoPlayer it automatically retries on VLC (which handles odd TS muxing and exotic codecs); you can also swap engines from the player or set a default in Settings
- **Catch-up TV** — Xtream channels with `tv_archive` expose their EPG in the player; pick any archived programme and it plays via timeshift
- **Recording** — record raw TS live streams to local storage from the player (foreground service with a stop notification); recordings get their own library section with playback and delete
- **TV-first UI** — collapsible navigation rail, category rows with focus-scaling poster cards, immersive hero header, detail pages for movies and series, global search, D-pad friendly throughout
- **Pro player** — custom TV controls (seek bar with ±10s D-pad seeking, transport, engine badge), live channel zapping with DPAD/CHANNEL up/down, episode binge queueing, and automatic resume for movies and episodes
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

- Full EPG grid guide (XMLTV for M3U sources)
- Favorites and channel reordering
- Scheduled recordings
- Subtitle & audio track selection UI
