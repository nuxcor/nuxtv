"""Close logo gaps in a built manifest, from the manifest alone.

The full rebuild path (logo_match.py over get_live_streams.json) writes
logo_map.json, but the shipped map never covered the whole catalogue: any
tile it missed falls back to the provider's watermarked artwork in the app.
This pass needs no panel dumps — the manifest's own collapse keys and
metro-local labels carry enough of each channel's name to match against
the tv-logo/tv-logos file tree:

  1. A tile whose effective primary is unbound inherits the binding any of
     its other sources already has (same channel, already resolved).
  2. Collapse keys ("abcnewslive|US") are the same normalized form
     logo_match.py derives from the repo's file names — exact match only,
     gated to the tile's own territory so "Sky News" never picks up a
     namesake from another market.
  3. Metro locals match by call sign (KTRK, WABC) against us-local, and
     fall back to the network's brand mark — network_fallback exists for
     exactly this, and nothing else reads it.

A binding is written for the effective primary AND every surviving source,
so a later change to the drop list cannot orphan it.

Run after every rebuild, on the shipped asset:

    python3 bind_logos.py ../../app/src/main/assets/catalogue-manifest.json
"""
import json, os, re, sys, urllib.request
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from logo_match import RAW, slug_keys

TREE_URL = "https://api.github.com/repos/tv-logo/tv-logos/git/trees/main?recursive=1"
CALL = re.compile(r'\b([WK][A-Z]{2,3})\b')

# Territory -> the repo folders it may draw from. Exact-key matching still
# collides across markets ("bbc-news" exists nowhere else, but "comedy
# central" is in six countries), so a tile only looks inside its own.
REGION_DIRS = {
    'US':  ['united-states'],
    'UK':  ['united-kingdom'],
    'CA':  ['canada'],
    'AFR': ['south-africa', 'world-africa'],
}
FALLBACK_DIRS = ['international']

# The generic tail a channel name carries and a logo filename usually
# doesn't: "foxnewschannel" is filed as fox-news-us.png. Same folding
# name_keys() does, but collapse keys arrive pre-concatenated.
GENERIC_TAILS = ('channel', 'network', 'tv')

# Brands whose on-screen name is an acronym the repo spells out, plus
# marks the exact key can never derive. cbs-us.png never existed — the
# fallback map pointed at it from day one — the eye mark is this file.
ALIAS = {
    'cbs': 'countries/united-states/cbs-logo-white-us.png',
    'gsn': 'countries/united-states/game-show-network-us.png',
    'own': 'countries/united-states/oprah-winfrey-network-us.png',
}


def load_tree(cache='tvlogos_tree.json'):
    try:
        with open(cache) as fh: tree = json.load(fh)
    except Exception:
        req = urllib.request.Request(TREE_URL, headers={
            'Accept': 'application/vnd.github+json', 'User-Agent': 'agoro-manifest'})
        with urllib.request.urlopen(req) as resp: tree = json.load(resp)
        with open(cache, 'w') as fh: json.dump(tree, fh)
    return [e['path'] for e in tree['tree']
            if e['path'].startswith('countries/') and e['path'].endswith('.png')]


def build_index(paths):
    """key -> [path, ...]; every path answers to all its slug_keys."""
    idx = {}
    for p in paths:
        for k in slug_keys(p.rsplit('/', 1)[1]):
            idx.setdefault(k, []).append(p)
    return idx


def key_variants(key):
    """The key as-is, then with generic tails peeled off one at a time."""
    out = [key]
    k = key
    for _ in range(2):
        tail = next((t for t in GENERIC_TAILS if k.endswith(t) and len(k) > len(t) + 2), None)
        if not tail: break
        k = k[:-len(tail)]
        out.append(k)
    return out


def pick(cands, dirs):
    """Shortest basename inside the allowed folders — the plain mark, not
    the -light/-hz variant slug_keys folded onto the same key."""
    for tier in (dirs, FALLBACK_DIRS):
        pool = [p for p in cands if p.split('/')[1] in tier]
        if pool:
            return min(pool, key=lambda p: (len(p.rsplit('/', 1)[1]), p))
    return None


def main(manifest_path):
    with open(manifest_path) as fh:
        m = json.load(fh)

    paths = load_tree()
    idx = build_index(paths)
    alive = set(paths)
    logo = m['logo']['channel_logo']
    fallback = m['logo'].get('network_fallback', {})
    dropped = set(m['drop_stream_ids'])
    healed = matched = called = branded = repaired = 0

    # Bindings can rot: the repo renames files on main, and one mark
    # (cbs-us.png) was never there at all. Re-point what the tree still
    # answers for; anything else is reported, never silently dropped.
    for table in (logo, fallback):
        for k, url in list(table.items()):
            if not url.startswith(RAW) or url[len(RAW):] in alive:
                continue
            keys = {v for key in slug_keys(url.rsplit('/', 1)[1])
                    for v in key_variants(key)}
            hit = next((ALIAS[v] for v in sorted(keys) if v in ALIAS), None)
            if hit:
                table[k] = RAW + hit; repaired += 1
            else:
                print(f"DEAD, no repair: {k} -> {url[len(RAW):]}")

    def bind(tile, url):
        for s in {effective(tile), *(s for s in tile['sources'] if s not in dropped)}:
            logo.setdefault(str(s), url)

    def effective(t):
        if t['primary'] not in dropped: return t['primary']
        return next((s for s in t['sources'] if s not in dropped), None)

    tiles = list(m['collapse']['live'].items())
    metros = list(m['metro_locals'].items())

    for key, t in tiles + metros:
        ep = effective(t)
        if ep is None or str(ep) in logo: continue
        url = next((logo[str(s)] for s in t['sources'] if str(s) in logo), None)
        if url: bind(t, url); healed += 1

    for key, t in tiles:
        ep = effective(t)
        if ep is None or str(ep) in logo: continue
        name_key, _, region = key.partition('|')
        hit = None
        for k in key_variants(name_key):
            if k in ALIAS and region == 'US':
                hit = ALIAS[k]; break
            hit = pick(idx.get(k, []), REGION_DIRS.get(region, []))
            if hit: break
        if hit: bind(t, RAW + hit); matched += 1

    for key, t in metros:
        ep = effective(t)
        if ep is None or str(ep) in logo: continue
        hit = None
        for call in CALL.findall(t.get('label', '')):
            hit = pick(idx.get('call:' + call, []), REGION_DIRS['US'])
            if hit: break
        if hit:
            bind(t, RAW + hit); called += 1
        elif t.get('network') in fallback:
            bind(t, fallback[t['network']]); branded += 1

    with open(manifest_path, 'w') as fh:
        json.dump(m, fh, separators=(',', ':'))
    print(f"healed from sibling: {healed}  name-matched: {matched}  "
          f"call-sign: {called}  network mark: {branded}  repaired: {repaired}  "
          f"channel_logo now: {len(logo)}")


if __name__ == '__main__':
    main(sys.argv[1] if len(sys.argv) > 1 else
         '../../app/src/main/assets/catalogue-manifest.json')
