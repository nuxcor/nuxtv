# Agoro

A premium streaming player for **Android TV**, built with Jetpack Compose for TV, Media3/ExoPlayer and libVLC.

Point it at an **Xtream Codes** login or a plain **M3U playlist link** and Agoro organizes everything into a real TV experience — Live channels, a Movies library, and Series with seasons and episodes.

## Features

- **Xtream Codes** — server URL + username + password (full live/VOD/series catalogs, posters, plots, ratings, lazily loaded episode lists). This is how playlists are added
- **M3U URL** — still supported and still classified as below, but no longer offered when adding a playlist. Sources added before are loaded, played and edited exactly as they were
- **Intelligent playlist mapping** — flat M3U playlists are classified automatically:
  - Xtream-style URL paths (`/live/`, `/movie/`, `/series/`) are used as authoritative signals
  - Series are detected from `S01E02`, `1x02`, and `Season 1 Episode 2` title patterns, then grouped into shows → seasons → episodes
  - Movies are detected from group keywords (VOD/Movies/Film…) and file containers (`.mkv`, `.mp4`, …), with year extraction (`Title (2023)`)
  - Everything else becomes a live channel, grouped by `group-title` categories
  - Titles are cleaned of quality noise (`1080p`, `HEVC`, `[4K]`, language prefixes)
- **Dual playback engines** — ExoPlayer and libVLC behind one abstraction. If a stream fails on ExoPlayer it automatically retries on VLC (which handles odd TS muxing and exotic codecs); you can also swap engines from the player or set a default in Settings
- **Every stream format** — progressive TS/MP4/MKV, HLS, DASH, SmoothStreaming, RTSP and RTMP. Extensionless stream URLs get a MIME hint so the right demuxer is picked up front instead of falling back to progressive
- **Full-quality by default** — hardware decoders first with software fallback, no viewport cap on adaptive bitrate selection (TV boxes routinely under-report their display size and silently pin an HLS ladder to a low rung), and a deeper buffer so provider hiccups don't read as bad picture
- **Video quality picker** — adaptive streams expose their full bitrate ladder in the player's options; leave it on Auto or pin a rung. The banner shows the resolution actually being decoded, not what the stream name advertises
- **Instant start** — the parsed playlist is cached on disk, so the library appears immediately on launch and refreshes silently in the background
- **Full EPG grid guide** — XMLTV support for both source types (Xtream `xmltv.php`, M3U `url-tvg` header or explicit EPG URL, gzip supported). A 48-hour channel-by-time grid with a live "now" highlight; click a current programme to watch, a past one for catch-up, a future one to schedule a recording — or a reminder when the stream can't be recorded
- **In-player channel list** — press OK (or LEFT) while watching live TV for a TiviMate-style channel list with now/next and per-channel progress, without interrupting playback; browse without tuning, and type a channel number on the remote to jump straight to it
- **Now/next everywhere** — channel rows in Live TV show what's airing right now
- **Remote-first navigation** — rail tabs and Live categories switch once focus rests (no OK needed), BACK returns focus to the rail before exiting, and every list takes channel numbers straight off the keypad. In the player OK opens the channel options (favorite and more), LEFT opens the channel list, RIGHT opens the controls — every long-press action also has a plain-key route for remotes without key repeat
- **Continue Watching** — partially-watched movies and series surface at the top of their tabs
- **Parental control** — set a PIN and restricted categories are hidden across Live, Movies and Series until you unlock them
- **Player options** — aspect ratio (fit/stretch/zoom, remembered per channel), playback speed for VOD, preferred audio/subtitle language remembered across sessions, a sleep timer that counts down on screen, and automatic reconnection with a per-channel engine fallback when a live stream drops
- **Catch-up TV** — Xtream channels with `tv_archive` expose their EPG in the player; pick any archived programme and it plays via timeshift
- **Recording & DVR** — record raw TS live streams from the player, or schedule future recordings from the guide (AlarmManager, survives reboots, auto-stops after the programme); recordings get their own library section with playback and delete
- **Favorites & hidden channels** — star channels from the player (★ Favorites category in Live TV); hide unwanted channels everywhere via the channel manager
- **Audio & subtitle tracks** — switch audio languages and subtitles on either engine from the player
- **Auto channel logos** — channels without artwork get logos automatically from the community [tv-logo/tv-logos](https://github.com/tv-logo/tv-logos) repo (index cached for a week)
- **EPG sources & auto-update** — one-tap [epgshare01](https://epgshare01.online/epgshare01/) country packs or any custom XMLTV URL in Settings; guides auto-refresh every 6 hours
- **Quality badges** — advertised quality (4K/FHD/HD/SD) parsed from stream names shows on channel rows, and the player displays the real decoded resolution (e.g. "1080p FHD") live
- **Ratings & reviews** — rating bars on detail pages, with ratings, vote counts, posters and review excerpts from TMDB. The API key is bundled at build time, so there is nothing to configure
- **Picture-in-picture** — pop live TV into PiP from the player
- **Backup & restore** — export playlists, favorites, hidden channels, schedules and settings to a JSON file and restore them on any install
- **TV-first UI** — collapsible navigation rail, category rows with focus-scaling poster cards, immersive hero header, detail pages for movies and series, global search, D-pad friendly throughout
- **Pro player** — custom TV controls (seek bar with ±10s D-pad seeking, transport, engine badge), live channel zapping with DPAD/CHANNEL up/down, episode binge queueing, and automatic resume for movies and episodes
- **Multiple playlists** — add several sources and switch between them in Settings; select the one you're on (or hold OK on any of them) to edit its details or remove it. Edits are validated before they're saved, so a mistyped password can't break a playlist that was working

## Installing

**On a TV** — open the [Downloader](https://www.aftvnews.com/downloader/) app and
enter the code:

```
5526044
```

Digits only; Downloader expands it. `nuxcor.github.io/nuxtv/` works too if you'd
rather type the address. Both land on the newest signed APK.

The pre-rebrand code `7141087` still works. It was issued against the old
`dzidzi.apk` filename, which the rebrand stopped producing — a shortener entry
can't be repointed, so each release publishes that filename too, as a byte
identical copy. Anyone holding the old number has no way to tell it went stale,
which is the whole reason to keep it alive.

**Anywhere else** — the download link is stable across releases, so it always
points at the current version:

```bash
curl -L -o agoro.apk https://github.com/nuxcor/nuxtv/releases/latest/download/agoro.apk
adb install agoro.apk
```

One APK covers Android TV, Google TV, Fire TV and phones (arm64 + arm32).
Installing over an older copy keeps playlists and settings.

## Building

```bash
./gradlew assembleDebug        # APK at app/build/outputs/apk/debug/
./gradlew testDebugUnitTest    # classifier + parser unit tests
```

To bundle the TMDB key, add it to `local.properties` (gitignored) or set it in the
environment. Without it the app builds and runs fine — movies and series simply
aren't enriched with ratings, posters and reviews:

```properties
TMDB_API_KEY=your_key_here
```

CI reads the same value from the `TMDB_API_KEY` repository secret.

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

No content is bundled — Agoro is a player; you bring your own playlist.

## Roadmap

- Channel reordering
- Series recording rules ("record every episode")
- Multi-view (side-by-side streams)
- Android TV home-screen (Watch Next) integration
- Localization
