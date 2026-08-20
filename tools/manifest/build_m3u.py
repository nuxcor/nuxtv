#!/usr/bin/env python3
"""Build an m3u_plus playlist of TV series episodes from an Xtream panel
whose get.php is blocked. Walks get_series_info per series and emits one
#EXTINF per episode."""
import json, sys, time, urllib.request, urllib.parse
from concurrent.futures import ThreadPoolExecutor

HOST, USER, PASS = sys.argv[1], sys.argv[2], sys.argv[3]
LIMIT   = int(sys.argv[4]) if len(sys.argv) > 4 else 0      # 0 = all series
WORKERS = int(sys.argv[5]) if len(sys.argv) > 5 else 6
OUT     = sys.argv[6] if len(sys.argv) > 6 else "series.m3u"
UA      = "VLC/3.0.20 LibVLC/3.0.20"
BASE    = f"http://{HOST}/player_api.php"

def api(**params):
    q = urllib.parse.urlencode({"username": USER, "password": PASS, **params})
    req = urllib.request.Request(f"{BASE}?{q}", headers={"User-Agent": UA})
    for attempt in range(4):
        try:
            with urllib.request.urlopen(req, timeout=45) as r:
                return json.loads(r.read().decode("utf-8", "replace"))
        except Exception as e:
            if attempt == 3:
                raise
            time.sleep(1.5 * (attempt + 1))          # back off on 429 / hiccup

cats   = {c["category_id"]: c["category_name"] for c in api(action="get_series_categories")}
series = api(action="get_series")
if LIMIT:
    series = series[:LIMIT]
print(f"series to crawl: {len(series)}  workers: {WORKERS}", flush=True)

def fetch(s):
    try:
        info = api(action="get_series_info", series_id=s["series_id"])
    except Exception as e:
        return s, None, str(e)
    return s, info, None

def iter_eps(node):
    """Yield episode dicts from whatever shape the panel returns.
    Observed: {season: [ep,...]}, [[ep,...]], [ep,...] — so recurse."""
    if isinstance(node, dict):
        if "id" in node and ("episode_num" in node or "container_extension" in node):
            yield node
        else:
            for v in node.values():
                yield from iter_eps(v)
    elif isinstance(node, list):
        for v in node:
            yield from iter_eps(v)

lines   = ["#EXTM3U"]
n_eps = n_ok = n_fail = n_skip = 0
started = time.time()

with ThreadPoolExecutor(max_workers=WORKERS) as pool:
    for i, (s_, info, err) in enumerate(pool.map(fetch, series), 1):
        if err or not isinstance(info, dict):
            n_fail += 1
            continue
        n_ok += 1
        name  = (s_.get("name") or "").strip()
        cover = s_.get("cover") or ""
        group = cats.get(str(s_.get("category_id")), "SERIES")
        try:
            for ep in iter_eps(info.get("episodes") or {}):
                epid = ep.get("id")
                if not epid:
                    continue
                ext = ep.get("container_extension") or "mkv"
                try:
                    se  = int(ep.get("season") or 0)
                    num = int(ep.get("episode_num") or 0)
                except (TypeError, ValueError):
                    se = num = 0
                title = (ep.get("title") or "").strip() or f"{name} S{se:02d}E{num:02d}"
                title = title.replace('"', "'").replace("\n", " ")
                url   = f"http://{HOST}/series/{USER}/{PASS}/{epid}.{ext}"
                lines.append(
                    f'#EXTINF:-1 tvg-id="" tvg-name="{title}" tvg-logo="{cover}" '
                    f'group-title="{group}",{title}'
                )
                lines.append(url)
                n_eps += 1
        except Exception:
            n_skip += 1
        if i % 500 == 0:
            rate = i / max(time.time() - started, 1e-9)
            eta  = (len(series) - i) / max(rate, 1e-9)
            print(f"  {i}/{len(series)} series | {n_eps} eps | {rate:.1f}/s | "
                  f"eta {eta/60:.1f}m | fail {n_fail} skip {n_skip}", flush=True)

with open(OUT, "w", encoding="utf-8") as f:
    f.write("\n".join(lines) + "\n")

print(f"\ndone in {time.time()-started:.0f}s")
print(f"  series ok/fail : {n_ok}/{n_fail}  (malformed skipped: {n_skip})")
print(f"  episodes       : {n_eps}")
print(f"  wrote          : {OUT}")
