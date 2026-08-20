"""Measure what collapse-tile sources actually decode at.

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

--all probes every source of every tile rather than just each tile's primary.
"""
import json, os, subprocess, sys, time

manifest_path = next((a for a in sys.argv[1:] if not a.startswith('--')), 'manifest-new.json')
probe_all = '--all' in sys.argv
# Re-measure ids already recorded. Resolution varies over the day, and the
# ranking keeps the lowest sample seen, so a second pass can only improve it.
reprobe = '--reprobe' in sys.argv
limit = int(sys.argv[sys.argv.index('--limit') + 1]) if '--limit' in sys.argv else 0

HOST, USER, PASS = (os.environ[k] for k in ('AGORO_HOST', 'AGORO_USER', 'AGORO_PASS'))
OUT = 'probed_tiers.json'
done = json.load(open(OUT)) if os.path.exists(OUT) else {}

m = json.load(open(manifest_path))
tiles = list(m['collapse']['live'].values()) + list(m.get('metro_locals', {}).values())
queue, seen = [], set()
for t in tiles:
    for sid in (t['sources'] if probe_all else [t['primary']]):
        s = str(sid)
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
