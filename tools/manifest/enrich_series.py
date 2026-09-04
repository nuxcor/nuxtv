#!/usr/bin/env python3
"""Genres the panel cannot express, taken from TMDB keywords.

The panel's series genres ARE TMDB's television genres — all sixteen of them,
verbatim — and TMDB has no Romance genre for television. Romance (10749) is a
MOVIE genre there. So the ten series in this catalogue tagged "Romance" are
hand-typed strays, and no amount of re-pulling genres from TMDB adds an
eleventh: Crash Landing on You, Bridgerton, Queen of Tears, Normal People and
every other romance in the library are filed, correctly by TMDB's own scheme,
as Drama.

What TMDB does carry for television is KEYWORDS, and romances are keyworded as
such. This reads them and writes the genres they imply.

No searching and no name cleaning: the panel ships a `tmdb` id on 8,402 of its
8,598 series, so every lookup is exact. 6,324 distinct ids, one call each.

    export TMDB_API_KEY=...        # never read from a file: this repo is public
    python3 enrich_series.py       # -> series_meta.json
"""
import json, os, re, sys, time, urllib.request
from concurrent.futures import ThreadPoolExecutor

KEY = os.environ.get("TMDB_API_KEY")
if not KEY:
    raise SystemExit("Set TMDB_API_KEY in the environment first.")

# "bromance" is not romance, and it is what a buddy thriller gets keyworded
# with — Bloodhounds and Great Pretender both came back as false positives
# before the negative lookbehind was there.
ROMANCE = re.compile(r'(?<!b)romance|romantic', re.I)
# A multi-word love phrase is a romance; the bare keyword "love" is not. Bare
# "love" is what a dating-format reality show carries ("Sexy Beasts"), and
# also what Emily in Paris carries, so the line is drawn on the phrase rather
# than on the show: precision over the two or three titles it costs.
LOVE = re.compile(r'^(?=.*\blove\b|.*lovers).+\s.+$', re.I)
# A film about a love story is a documentary, and belongs on that shelf.
EXCLUDE_GENRES = {"Documentary", "News"}


def genres_for(keywords, tmdb_genres):
    if set(tmdb_genres) & EXCLUDE_GENRES:
        return []
    for k in keywords:
        if ROMANCE.search(k) or LOVE.match(k):
            return ["Romance"]
    return []


def fetch(tid):
    url = f"https://api.themoviedb.org/3/tv/{tid}?api_key={KEY}&append_to_response=keywords"
    for attempt in range(4):
        try:
            with urllib.request.urlopen(url, timeout=30) as resp:
                d = json.load(resp)
            kws = [k["name"] for k in (d.get("keywords", {}).get("results") or [])]
            gs = [g["name"] for g in (d.get("genres") or [])]
            return tid, {"keywords": kws, "tmdb_genres": gs, "add": genres_for(kws, gs)}
        except Exception:
            if attempt == 3:
                return tid, None
            time.sleep(1.5 * (attempt + 1))


def main():
    ser = json.load(open("get_series.json"))
    ids = sorted({str(s["tmdb"]).strip() for s in ser
                  if str(s.get("tmdb") or "").strip() not in ("", "0")})
    print(f"series: {len(ser)} | distinct tmdb ids: {len(ids)}", flush=True)
    out, fail, t0 = {}, 0, time.time()
    with ThreadPoolExecutor(max_workers=8) as pool:
        for n, (tid, rec) in enumerate(pool.map(fetch, ids), 1):
            if rec is None:
                fail += 1
            elif rec["add"]:
                out[tid] = rec["add"]
            if n % 500 == 0:
                rate = n / (time.time() - t0)
                print(f"  {n}/{len(ids)} | {rate:.0f}/s | eta {(len(ids)-n)/rate/60:.1f}m "
                      f"| romance {len(out)} | fail {fail}", flush=True)
    # Keyed by SERIES id, which is what the manifest and the app speak.
    by_series = {}
    for s in ser:
        add = out.get(str(s.get("tmdb") or "").strip())
        if add:
            by_series[str(s["series_id"])] = add
    json.dump(by_series, open("series_meta.json", "w"))
    print(f"\ntmdb ids tagged Romance: {len(out)} / {len(ids)} ({len(out)*100//len(ids)}%)")
    print(f"series entries tagged:   {len(by_series)} / {len(ser)}")
    print(f"failed lookups:          {fail}")


if __name__ == "__main__":
    main()
