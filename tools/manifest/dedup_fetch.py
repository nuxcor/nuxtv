#!/usr/bin/env python3
"""Fetch episode ids for the series entries that sit in duplicate groups."""
import json, urllib.request, urllib.parse, time
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
ids=[l.strip() for l in open('dup_ids.txt') if l.strip()]

def iter_eps(n):
    if isinstance(n,dict):
        if "id" in n and ("episode_num" in n or "container_extension" in n): yield n
        else:
            for v in n.values(): yield from iter_eps(v)
    elif isinstance(n,list):
        for v in n: yield from iter_eps(v)

def fetch(sid):
    q=urllib.parse.urlencode({"username":U,"password":P,"action":"get_series_info","series_id":sid})
    r=urllib.request.Request(f"http://{H}/player_api.php?{q}",headers={"User-Agent":UA})
    for a in range(4):
        try:
            with urllib.request.urlopen(r,timeout=45) as resp:
                d=json.loads(resp.read().decode('utf-8','replace'))
                return sid,[str(e['id']) for e in iter_eps(d.get('episodes') or {}) if e.get('id')]
        except Exception:
            if a==3: return sid,None
            time.sleep(1.5*(a+1))

t=time.time(); out={}
with ThreadPoolExecutor(max_workers=6) as pool:
    for i,(sid,eps) in enumerate(pool.map(fetch,ids),1):
        out[sid]=eps
        if i%500==0: print(f"  {i}/{len(ids)}  {time.time()-t:.0f}s",flush=True)
json.dump(out,open('dup_episodes.json','w'))
ok=sum(1 for v in out.values() if v is not None)
print(f"done {time.time()-t:.0f}s | ok {ok}/{len(ids)} | episodes {sum(len(v) for v in out.values() if v)}")
