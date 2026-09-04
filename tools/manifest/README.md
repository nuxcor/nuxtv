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
- `collapse[].direct` — the broadcaster's own public feeds for a tile, best
  first, played BEFORE the provider's copies (`DIRECT_FEED` in the build).
  Only for free channels the network streams itself; each url is opened and
  checked the day it is added, and the provider sources stay behind it as
  the recorded fallbacks.

`kept_regions` is an **ordered** list, not a set: it decides the order among
territories. It was once emitted through `sorted()`, which is alphabetical by
code, and that silently put AFR/DSTV at the head of the strip ahead of the
markets this package is mostly made of.

It is the tie-break *within* a genre, not the top-level grouping — the strip
sorts by section order first, so a territory that keeps its own shelf renders
beside the genre it holds rather than after every merged shelf. DStv sits
directly after the merged Entertainment, not past Streaming Networks.

## Credentials

Never in a file — this repository is public.

The scripts talk to the panel over **HTTPS**. Xtream puts the username and
password in the URL — in the query string for `player_api.php` and in the PATH
for every stream (`/live/user/pass/id.ts`) — so over plain HTTP they are
readable by everyone between here and the panel. Checked 2026-09-02: this
panel presents a valid certificate (Google Trust Services, SAN
`*.dzidzi.online`) and answers identically over TLS, marginally faster.

`refresh.py` falls back to http if the TLS handshake or the certificate
fails, and says so loudly each time — a maintenance job should lose its
privacy rather than its existence when a certificate lapses. It does NOT fall
back on a refused connection or an unknown host: that is a panel that is down
or a typo, and re-asking in clear would leak the password to learn nothing.

```sh
export AGORO_HOST=pro.example.online
export AGORO_USER=...
export AGORO_PASS=...
```

`build_manifest.py` needs none: it reads the panel dumps listed below, already
on disk.

## Order

`refresh.py` drives the whole loop — fetch, build, and a drift report against
the shipped asset — and is what a scheduled rebuild should run:

```sh
AGORO_HOST=... AGORO_USER=... AGORO_PASS=... python3 refresh.py        # report only
AGORO_HOST=... AGORO_USER=... AGORO_PASS=... python3 refresh.py --write # install it
python3 refresh.py --no-fetch                # rebuild from the dumps on disk
```

## Running it on a schedule

`scheduled-refresh.sh` is the unattended form: clone fresh into a temp
directory, fetch, rebuild, and open a pull request only when the line-up
moved. It works on a throwaway clone and never on a working tree, so it
cannot disturb whatever you have checked out.

Credentials live outside this repository, which is public:

```sh
mkdir -p ~/.config/nuxtv && chmod 700 ~/.config/nuxtv
printf 'AGORO_HOST=\nAGORO_USER=\nAGORO_PASS=\n' > ~/.config/nuxtv/panel.env
chmod 600 ~/.config/nuxtv/panel.env   # then fill in the three values
```

Blank values are treated as "not installed yet" and the job exits quietly, so
an unfinished install is silent rather than a weekly error. Check what it
would use, without touching the panel or the repository:

```sh
tools/manifest/scheduled-refresh.sh --check
```

### Getting the password out of a plain file

A `chmod 600` file is readable by anything running as you — a rogue
dependency, a helper process — and it travels into every backup, sync client
and dotfiles repo that touches `~/.config`. Those are the two realistic ways
a home-server credential leaks. Neither is fixed by full-disk encryption,
which protects a powered-off machine and nothing else.

What it does NOT fix: someone who already has your session. An unattended job
must decrypt without a human, so whatever unlocks the secret is reachable
from that session too. This is about not leaving plaintext lying around, not
about resisting an attacker who is already you.

The script looks for credentials in this order and uses the first it finds,
so either upgrade is a drop-in and the file keeps working if you do neither.

**macOS — the login keychain:**

```sh
tools/manifest/scheduled-refresh.sh --import-keychain
tools/manifest/scheduled-refresh.sh --check     # confirm it reads back
rm ~/.config/nuxtv/panel.env                    # only after that passes
```

Needs the login keychain unlocked, which it is once you have logged in after
a boot. A Mac sitting at the login screen cannot run the job at all.

**Linux — `systemd-creds`, which is the best of the three:**

```sh
systemd-creds encrypt --name=panel ~/.config/nuxtv/panel.env \
    ~/.config/nuxtv/panel.cred
rm ~/.config/nuxtv/panel.env
```

The unit already carries the matching `LoadCredentialEncrypted=`. systemd
decrypts into a private tmpfs that dies with the process, so the plaintext
never reaches the disk, and the blob is sealed to the machine — bound to the
TPM where there is one — so a copy taken elsewhere is useless.

**On a Linux home server** — the better host, because a timer only helps if
the machine is awake. It needs `git`, `python3` and `gh`, a `gh` login that
can open a pull request, and a key that can push.

```sh
git clone git@github.com:nuxcor/nuxtv.git ~/nuxtv
mkdir -p ~/.config/systemd/user
cp ~/nuxtv/tools/manifest/systemd/manifest-refresh.* ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now manifest-refresh.timer
loginctl enable-linger "$USER"     # let the timer fire with nobody logged in
```

`enable-linger` is the part people miss: without it a `--user` timer only runs
while that user has a session, which on a headless box is never. Check it with
`systemctl --user list-timers` and read the log at `~/.config/nuxtv/refresh.log`.
Run it once by hand first — `systemctl --user start manifest-refresh.service` —
rather than waiting a week to find out `gh` was not logged in.

The unit reads `%h/nuxtv/...`, so a checkout anywhere else needs its
`ExecStart` path edited.

**On macOS** the same script runs under a launchd agent
(`~/Library/LaunchAgents/com.agoro.manifest-refresh.plist`, Sunday 04:00
local). A sleeping Mac runs a missed job when it next wakes, but a Mac that
stays shut skips the week — which is the reason to prefer the server.

**Why it is worth running on a schedule.** New provider content reaches the app
on its own — the app fetches the line-up at runtime — but every per-title
decision in the manifest is keyed by stream or series id, so none of it covers
anything new: duplicate folding misses new duplicates, a dropped shelf's new
titles come back, and a new series has no New/Top/All and lands on no shelf at
all. Rebuilding re-applies the lot. And because the app prefers whichever
manifest carries the newer `generated` stamp and reads it from `main`, merging
a rebuild reaches the box within a day **without an app release**.

| Step | Script | Reads | Writes |
| --- | --- | --- | --- |
| Fetch | `refresh.py` *(or the panel API by hand)* | — | `get_live_categories.json`, `get_live_streams.json`, `get_vod_categories.json`, `get_vod_streams.json`, `get_series.json`, `series_cats.json` |
| Episodes | `dedup_fetch.py` | `dup_ids.txt` | `dup_episodes.json` |
| VOD metadata | `enrich_vod.py` | `get_vod_streams.json` | genre/rating/tmdb keys |
| Series genres | `enrich_series.py` | `get_series.json` + TMDB keywords | `series_meta.json` |
| Guide match | `epg_match.py` | `repo_epg_index.json`, `openepg_index_built.json` | `epg_map_final.json` |
| Guide gaps | `epg_fill.py` | `manifest.json`, `kept_live.json`, guide packs | `epg_extra.json` |
| Artwork match | `logo_match.py` | tv-logos index | `logo_map.json` |
| Club crests | `crest_match.py` | `manifest.json`, `crest_tree_*.txt` | `crest_map.json` |
| Events | `ppv_parse.py` | panel PPV categories | `ppv_events.json` |
| **Build** | `build_manifest.py` | all of the above | `manifest.json` |

`epg_fill.py` also runs after a first build — it reads the channel map and the
line-up out of `manifest.json` and `kept_live.json` to find what is unbound.
Fetch the packs it reads first:

```
curl -sL -o epg6.xml.gz https://raw.githubusercontent.com/ferteque/Curated-M3U-Repository/main/epg6.xml.gz
curl -sL -o epg2.xml.gz https://raw.githubusercontent.com/ferteque/Curated-M3U-Repository/main/epg2.xml.gz
python3 epg_fill.py manifest.json epg6.xml.gz epg2.xml.gz
```

`crest_match.py` runs AFTER a first build: it reads `sport.leagues` out of
`manifest.json` and exits if it is missing. So the real order is build → crest
→ rebuild, and only the second build carries the crests.

Both its inputs and its output are gitignored, and a missing `crest_map.json`
does not fail the build — `build_manifest.py` falls back to the crests in the
previous manifest and reports `club_crests_bound` from that. On a fresh clone
that is a silent stale count, so refresh the trees before trusting it:

```
gh api "repos/luukhopman/football-logos/git/trees/master?recursive=1" \
  --jq '.tree[]|select(.path|endswith(".png"))|.path' > crest_tree_euro.txt
gh api "repos/klunn91/team-logos/git/trees/master?recursive=1" \
  --jq '.tree[]|select(.path|test("\\.(png|svg)$"))|.path' > crest_tree_us.txt
```
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
