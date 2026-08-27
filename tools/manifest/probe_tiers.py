"""Measure what the channels a viewer can actually reach decode at.

The build ranks a tile's sources by the tier token in the stream name, and
providers lie — "FHD" on a 720p feed is routine. This probes the real video
height with ffprobe and writes probed_tiers.json (stream_id -> height), which
build_manifest.py prefers over the advertised token wherever both exist; a
height of 0 records "couldn't decode" so the id isn't probed again and the
advertised token stands.

Resumable: already-probed ids are skipped. Iterate probe -> rebuild until the
set of primaries stops changing — a demoted liar promotes a source that may
itself be unprobed.

Credentials come from the environment (AGORO_HOST/USER/PASS), never a file —
this repository is public. One connection at a time, deliberately: Xtream
lines meter concurrent connections, and a parallel probe looks like account
sharing.

    AGORO_HOST=... AGORO_USER=... AGORO_PASS=... \
        python3 probe_tiers.py manifest-new.json [--all] [--limit N]
        python3 probe_tiers.py --ids 1577208,1562727

--all also probes a tile's non-primary sources — the backups behind whatever is
currently on top — rather than only the line-up as it stands.

--ids measures a named list instead of the line-up, and is the only way to
reach a channel the build DROPS. That is the question it answers: "is this
one worth carrying?", which cannot be asked of a queue derived from what is
already carried. It skips the manifest and kept_live.json entirely, so it
works before a build and against ids no shelf has ever held. Results land in
the same probed_tiers.json, so a keep decided this way arrives already
measured.
"""
import json, os, subprocess, sys, time

# Read before the manifest is opened: --ids answers a question about channels
# that are not in it, so it must not require one to exist.
explicit_ids = []
if '--ids' in sys.argv:
    explicit_ids = [s.strip() for s in sys.argv[sys.argv.index('--ids') + 1].split(',')
                    if s.strip()]

_positional = [a for a in sys.argv[1:] if not a.startswith('--')]
# The value of --ids is a positional-looking argument; it is not the manifest.
if explicit_ids and _positional and _positional[0] == sys.argv[sys.argv.index('--ids') + 1]:
    _positional = _positional[1:]
manifest_path = next(iter(_positional), 'manifest-new.json')
probe_all = '--all' in sys.argv
# Re-measure ids already recorded. Resolution varies over the day, and the
# ranking keeps the lowest sample seen, so a second pass can only improve it.
reprobe = '--reprobe' in sys.argv
limit = int(sys.argv[sys.argv.index('--limit') + 1]) if '--limit' in sys.argv else 0

HOST, USER, PASS = (os.environ[k] for k in ('AGORO_HOST', 'AGORO_USER', 'AGORO_PASS'))
OUT = 'probed_tiers.json'
done = json.load(open(OUT)) if os.path.exists(OUT) else {}

if explicit_ids:
    # --reprobe is implied: a candidate is being asked about NOW, and a stale
    # recording of it is the thing the question is trying to get past.
    queue = list(dict.fromkeys(explicit_ids))
    if limit:
        queue = queue[:limit]
    print(f"{len(queue)} named streams to probe", flush=True)
else:
    queue = None      # built from the manifest below

m = json.load(open(manifest_path)) if queue is None else None
tiles = (list(m['collapse']['live'].values()) + list(m.get('metro_locals', {}).values())
         if queue is None else [])

# The queue is every channel that survives the manifest — tile primaries AND
# the loose channels that belong to no tile.
#
# It used to be tile primaries only, and that quietly excluded most of the
# catalogue: a region could report "fully measured" while none of its loose
# channels had ever been opened. It hid 97 of DSTV's 146 and 83 of Canada's
# 107, each found by hand long after the region was called done. Deriving the
# queue the way the app derives its own line-up is the only version of this
# that cannot drift out of agreement with what a viewer sees.
here = os.path.dirname(os.path.abspath(__file__))
kept_path = os.path.join(here, 'kept_live.json')
if queue is None and not os.path.exists(kept_path):
    sys.exit(f"{kept_path} not found — run build_manifest.py first; it writes the line-up")
kept = json.load(open(kept_path)) if queue is None else []
# PPV event slots are 6,286 of the 6,941 survivors and sit on a shelf hidden
# by default. Probing them in queue order buried the real channels: a sweep
# reported ~6,300 unmeasured when only 154 browsable channels had never been
# opened. --ppv includes them; by default the queue is what a viewer browses.
skip_ppv = '--ppv' not in sys.argv
candidates = [str(c['id']) for c in kept
              if not (skip_ppv and c.get('section') == 'PPV')]
# --all reaches past the line-up into the backups a tile is holding in reserve.
if probe_all and queue is None:
    candidates += [str(s) for t in tiles for s in t['sources']]

if queue is None:
    queue, seen = [], set()
    for s in candidates:
        if (reprobe or s not in done) and s not in seen:
            seen.add(s); queue.append(s)
    if limit: queue = queue[:limit]
    print(f"{len(queue)} streams to probe ({len(done)} already recorded)", flush=True)


def probe(sid):
    url = f"http://{HOST}/live/{USER}/{PASS}/{sid}.ts"
    try:
        r = subprocess.run(
            ['ffprobe', '-v', 'error', '-select_streams', 'v:0',
             '-show_entries', 'stream=height', '-of', 'csv=p=0',
             '-probesize', '3000000', '-analyzeduration', '3000000',
             '-rw_timeout', '8000000', url],
            capture_output=True, text=True, timeout=25)
        return int(r.stdout.strip().splitlines()[0])
    except Exception:
        return 0


for i, sid in enumerate(queue, 1):
    height = probe(sid)
    # The LOWEST sample wins, never the latest. A live feed's resolution is
    # not a constant: BBC News measured 1080 one morning and 576 that
    # afternoon, and ranking on the optimistic sample put an SD feed at the
    # front of its tile — which is exactly the complaint the measuring was
    # meant to end. A feed that ever drops to SD is not an HD source.
    previous = done.get(sid) or 0
    done[sid] = min(previous, height) if (previous and height) else (height or previous)
    if i % 10 == 0 or i == len(queue):
        json.dump(done, open(OUT, 'w'))
        print(f"{i}/{len(queue)}  last {sid} -> {done[sid]}", flush=True)
    time.sleep(0.2)

json.dump(done, open(OUT, 'w'))
ok = sum(1 for v in done.values() if v)
print(f"done: {len(done)} recorded, {ok} decoded", flush=True)
