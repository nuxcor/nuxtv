# Catalogue manifest pipeline

Builds `app/src/main/assets/catalogue-manifest.json` — the provider-specific
curation the app ships and reads. A raw playlist is not a catalogue: this
provider lists ~18,800 live streams that reduce to ~790 real channels once
regional duplicates, quality tiers, dead event slots and territories we don't
serve come out. None of that is logic the app can derive, so it lives here and
ships as data.

## The rule these scripts exist to serve

**Whatever this pipeline resolves, it must RECORD.** The app cannot redo the
inference — it has no channel names to parse prefixes from, no view of where a
channel's other sources live — so anything worked out here and thrown away is
simply lost, and the usual symptom is a channel with no shelf to appear on.
Two fields exist for exactly this and are easy to forget:

- `region_fix` — a channel's real territory when its category says something
  else, including when the category says a quality tier (`4K`, `8K`) rather
  than a place.
- `collapse[].section` / `.region` — the shelf a folded tile resolved for
  itself, which outranks the primary's own provider category.

`kept_regions` is an **ordered** list, not a set: the app renders territories
in this sequence. It was once emitted through `sorted()`, which is alphabetical
by code, and that silently put AFR/DSTV at the head of the strip ahead of the
markets this package is mostly made of.

## Credentials

Never in a file — this repository is public.

```sh
export AGORO_HOST=pro.example.online
export AGORO_USER=...
export AGORO_PASS=...
```

`build_manifest.py` needs none: it reads the panel dumps listed below, already
on disk.

## Order

| Step | Script | Reads | Writes |
| --- | --- | --- | --- |
| Fetch | *(panel API by hand)* | — | `get_live_categories.json`, `get_live_streams.json`, `get_vod_categories.json`, `get_vod_streams.json`, `get_series.json`, `series_cats.json` |
| Episodes | `dedup_fetch.py` | `dup_ids.txt` | `dup_episodes.json` |
| VOD metadata | `enrich_vod.py` | `get_vod_streams.json` | genre/rating/tmdb keys |
| Guide match | `epg_match.py` | `repo_epg_index.json`, `openepg_index_built.json` | `epg_map_final.json` |
| Artwork match | `logo_match.py` | tv-logos index | `logo_map.json` |
| Events | `ppv_parse.py` | panel PPV categories | `ppv_events.json` |
| **Build** | `build_manifest.py` | all of the above | `manifest.json` |
| Measure | `probe_tiers.py` | `manifest.json`, panel streams | `probed_tiers.json` |
| Artwork gaps | `bind_logos.py` | `manifest.json`, tv-logos tree | `manifest.json` (in place) |

Providers lie about tiers, so `probe_tiers.py` ffprobes what each tile source
actually decodes and the build prefers the measurement. Iterate
probe → rebuild until no primary is unprobed (a demoted liar promotes a
source that may itself be unmeasured); `--all` extends the truth to the
fallback ladders. One connection at a time — panels meter them.

Then copy the result over the shipped asset:

```sh
cp manifest.json /path/to/agoro/app/src/main/assets/catalogue-manifest.json
```

The intermediates are large, machine-generated and deliberately untracked — see
`.gitignore` beside this file. Only the scripts are versioned.

## After a rebuild, check

- `kept_regions` is the authored order, not alphabetical.
- Every surviving stream resolves to a section AND a territory. One that
  resolves to a section but no territory has no shelf: the app keeps it rather
  than deleting it, but only search will find it.
- No per-channel table names a section absent from `sections.live` — an
  undeclared key has no label and no place in the order, and surfaces raw.
