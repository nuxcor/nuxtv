#!/usr/bin/env python3
"""Pull genre + sort keys for every movie from the panel's own metadata."""
import json, time, urllib.request, urllib.parse
from concurrent.futures import ThreadPoolExecutor
import os
# Credentials come from the environment, never the file. This script lives in a
# public repository; a panel login committed here is a panel login published.
#   export AGORO_HOST=pro.example.online AGORO_USER=... AGORO_PASS=...
H = os.environ.get("AGORO_HOST", "pro.dzidzi.online")
U, P = os.environ.get("AGORO_USER"), os.environ.get("AGORO_PASS")
if not (U and P):
    raise SystemExit("Set AGORO_USER and AGORO_PASS in the environment first.")
UA="VLC/3.0.20 LibVLC/3.0.20"
KEEP=("genre","releasedate","rating","tmdb_id","duration_secs","country","age")
vs=json.load(open('get_vod_streams.json'))
ids=[s['stream_id'] for s in vs]
print(f"movies to enrich: {len(ids)}", flush=True)

def fetch(vid):
    q=urllib.parse.urlencode({"username":U,"password":P,"action":"get_vod_info","vod_id":vid})
    r=urllib.request.Request(f"http://{H}/player_api.php?{q}",headers={"User-Agent":UA})
    for a in range(4):
        try:
            with urllib.request.urlopen(r,timeout=45) as resp:
                i=(json.loads(resp.read().decode('utf-8','replace')) or {}).get('info') or {}
                return vid,{k:i[k] for k in KEEP if i.get(k)}
        except Exception:
            if a==3: return vid,None
            time.sleep(1.5*(a+1))

out={}; t=time.time(); fail=0
with ThreadPoolExecutor(max_workers=6) as pool:
    for n,(vid,rec) in enumerate(pool.map(fetch,ids),1):
        if rec is None: fail+=1
        else: out[str(vid)]=rec
        if n%2500==0:
            rate=n/(time.time()-t)
            print(f"  {n}/{len(ids)} | {rate:.1f}/s | eta {(len(ids)-n)/rate/60:.1f}m | fail {fail}",flush=True)
json.dump(out,open('vod_meta.json','w'))
g=sum(1 for v in out.values() if v.get('genre'))
print(f"\ndone {time.time()-t:.0f}s | ok {len(out)} | fail {fail}")
print(f"  with genre      : {g} ({g/len(ids)*100:.0f}%)")
print(f"  with releasedate: {sum(1 for v in out.values() if v.get('releasedate'))}")
