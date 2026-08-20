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

With a channel list the pass also covers single-source channels — the ones
no collapse key names. Both shapes work: the panel's get_live_streams.json
(stream_id/name/category_id) and the app's cached bundle pulled off a
device (xtreamId/name/categoryId), whose categoryId is already the
"US|SPORTS" shelf and carries the territory on its front.

Run after every rebuild, on the shipped asset:

    python3 bind_logos.py ../../app/src/main/assets/catalogue-manifest.json \
        [get_live_streams.json | bundle.json]
"""
import json, os, re, sys, urllib.request
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from clean_names import clean
from logo_match import RAW, full_key, name_keys, slug_keys

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
# An alias only applies inside its own folder's territory: itv is the
# regional feeds' floor, and it must never leave the UK.
ALIAS = {
    'cbs': 'countries/united-states/cbs-logo-white-us.png',
    'gsn': 'countries/united-states/game-show-network-us.png',
    'own': 'countries/united-states/oprah-winfrey-network-us.png',
    'itv': 'countries/united-kingdom/itv-1-uk.png',
    # the provider abbreviates or misspells these past any derivation
    'skysportpl': 'countries/united-kingdom/sky-sports-premier-league-hd-uk.png',
    'bbcparlament': 'countries/united-kingdom/bbc-parliament-uk.png',
    # locals named without their call sign: the market knowledge lives here
    'abc7newyork': 'countries/united-states/us-local/abc-7-wabc-us.png',
    'fox2sanfrancisco': 'countries/united-states/us-local/fox-2-ktvu-us.png',
    'bouncexl': 'countries/united-states/bounce-us.png',
}

# Umbrella brands a peel must never land on: "BBC Parliament" stripped to
# "bbc" matched bbc-uhd-uk.png — there is no channel just called BBC.
# (itv is absent by design: the regional feeds ARE ITV1.)
PEEL_STOP = {'bbc', 'sky'}


# Hand-verified marks whose only artwork lives outside the channel's own
# territory folder — the region gate would veto them, so they bypass it.
# i24 News broadcasts from Israel; the UK/US feeds are the same channel.
CROSS_TERRITORY = {
    'i24news': 'countries/israel/i24-news-il.png',
    'i24newstv': 'countries/israel/i24-news-il.png',
}


def alias_for(key, region):
    hand = CROSS_TERRITORY.get(key)
    if hand:
        return hand
    path = ALIAS.get(key)
    if path and path.split('/')[1] in REGION_DIRS.get(region, []):
        return path
    return None


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


def name_variants(name):
    """Two tiers, most-specific first. Safe: the keys as derived and with
    generic tails peeled — these may match a filename's word-dropped key
    too. Risky: the words this provider injects that the repo's filenames
    don't carry ("GLOBAL NEWS EDMONTON" is filed as global-edmonton, "TSN
    SPORT 2" as tsn-2), trailing words peeled so a regional feed falls
    back to its national mark ("BBC One London" -> bbc-one), and last the
    brand head of a regional news feed ("CTV News Edmonton" -> ctv-news,
    the one mark CTV has). A risky key must equal a filename's WHOLE name:
    matching its word-dropped form let "talksport" minus sport reach
    talk-tv-uk.png — a different brand. Returns (safe, risky)."""
    keys = name_keys(name)
    safe = [v for key in keys for v in key_variants(key)]
    # a doubled letter where two words meet, which the repo fuses:
    # "ESPN NEWS" -> espnnews, filed as espnews. First among the risky —
    # it is the only variant that still carries every word of the name.
    risky = []
    for key in safe:
        fused = re.sub(r'([a-z0-9])\1', r'\1', key)
        if fused != key: risky.append(fused)
    # spellings the repo prefers: singular ("...MAIN EVENTS" is filed as
    # sky-sports-main-event) and jr ("NICK JUNIOR" as nick-jr)
    for key in keys:
        if key.endswith('s') and len(key) >= 5: risky.append(key[:-1])
        if 'junior' in key: risky.append(key.replace('junior', 'jr'))
    for key in keys:
        for tok in ('sports', 'sport', 'news'):
            if tok in key:
                v = key.replace(tok, '', 1)
                if len(v) >= 4: risky.append(v)
    words = clean(name).split(' ')
    for cut in (1, 2):
        if len(words) - cut < 1: break
        peeled = name_keys(' '.join(words[:-cut]))
        if peeled and len(peeled[0]) >= 3 and peeled[0] not in PEEL_STOP:
            risky.append(peeled[0])
    for key in keys:
        i = key.find('news')
        if i > 2: risky.append(key[:i + 4])
    seen = set(safe)
    return safe, [k for k in risky if not (k in seen or seen.add(k))]


def pick(cands, dirs):
    """Shortest basename inside the allowed folders — the plain mark, not
    the -light/-hz variant slug_keys folded onto the same key."""
    for tier in (dirs, FALLBACK_DIRS):
        pool = [p for p in cands if p.split('/')[1] in tier]
        if pool:
            return min(pool, key=lambda p: (len(p.rsplit('/', 1)[1]), p))
    return None


def load_channels(path, m):
    """(stream id, name, region) from a panel dump or a device bundle."""
    with open(path) as fh:
        data = json.load(fh)
    rows = data['channels'] if isinstance(data, dict) else data
    out = []
    for c in rows:
        sid = c.get('xtreamId') or c.get('stream_id')
        name = c.get('name', '')
        if not sid or not name: continue
        cat = str(c.get('categoryId') or c.get('category_id') or '')
        region = (cat.split('|')[0] if '|' in cat
                  else (m['categories']['live'].get(cat) or {}).get('region', ''))
        out.append((sid, name, region))
    return out


def main(manifest_path, channels_path=None):
    with open(manifest_path) as fh:
        m = json.load(fh)

    paths = load_tree()
    idx = build_index(paths)
    strict = {}
    for p in paths:
        strict.setdefault(full_key(p.rsplit('/', 1)[1]), []).append(p)
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
            hit = alias_for(k, region) or pick(idx.get(k, []), REGION_DIRS.get(region, []))
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

    # Single-source channels: only a channel list knows their names. Same
    # exact-key, own-territory rules as the tiles; name_keys() also yields
    # the call sign, which is how a "CITY: CW KRON SAN FRANCISCO" resolves.
    named = 0
    if channels_path:
        for sid, name, region in load_channels(channels_path, m):
            if str(sid) in logo: continue
            safe, risky = name_variants(name)
            hit = None
            for table, keys in ((idx, safe), (strict, risky)):
                for k in keys:
                    hit = alias_for(k, region) or pick(table.get(k, []), REGION_DIRS.get(region, []))
                    if hit: break
                if hit: break
            if hit:
                logo[str(sid)] = RAW + hit; named += 1
                print(f"  {sid} {name!r} -> {hit.rsplit('/', 1)[1]}")

    with open(manifest_path, 'w') as fh:
        json.dump(m, fh, separators=(',', ':'))
    print(f"healed from sibling: {healed}  name-matched: {matched}  "
          f"call-sign: {called}  network mark: {branded}  repaired: {repaired}  "
          f"from channel list: {named}  channel_logo now: {len(logo)}")


if __name__ == '__main__':
    main(sys.argv[1] if len(sys.argv) > 1 else
         '../../app/src/main/assets/catalogue-manifest.json',
         sys.argv[2] if len(sys.argv) > 2 else None)
