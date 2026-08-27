"""Bind the carried channels that the guide match left with no listings at all.

166 of the 432 channels on visible shelves had no `channel_map` entry — 38%,
and Sports worst of all — so those rows read "TV Guide unavailable" whatever
the feeds carried. This matches them by name against the curated packs and
writes epg_extra.json, which build_manifest merges under its own map and under
EPG_PIN, so a hand correction still wins.

Refusing is the point. A wrong guide is worse than none — this catalogue has
already shipped Sky News showing Sky News AUSTRALIA and a US network showing
ABC Sydney — so a candidate is taken only when it is unambiguous, unused,
country-compatible and actually carries a schedule. Everything else is left
unbound and reported.

    python3 epg_fill.py manifest.json epg6.xml.gz epg2.xml.gz
"""
import collections, gzip, json, os, re, sys, unicodedata

CHAN = re.compile(rb'<channel\s+id="([^"]+)"(.*?)</channel>', re.S)
DNAME = re.compile(rb'<display-name[^>]*>([^<]{1,80})')
PROG = re.compile(rb'<programme[^>]*\schannel="([^"]+)"')
QUAL = re.compile(r'(?i)\b(HD|FHD|UHD|SD|4K|8K|HEVC|H265|RAW|FPS|\d{2,3}FPS)\b')
PREFIX = re.compile(r'^[A-Za-z0-9]{2,6}\s*:+\s*')

# A territory's ids are only usable by a channel of that territory. The pairs
# that ARE allowed are the ones this catalogue treats as one market.
COMPATIBLE = {'UK': {'uk', 'ie', 'gb'}, 'US': {'us', 'ca'},
              'AFR': {'za', 'ng', 'gh', 'ke', 'ug'}}

# Below this an id has a few stray entries rather than a schedule, and binding
# to it trades "no guide" for "a guide that is blank in a different way".
MIN_PROGRAMMES = 3


def keys(name):
    """Every form worth trying, most specific first.

    The plural fold runs on the COLLAPSED string as well as the spaced one: an
    xmltv id has no spaces, so a word-boundary rule folds "SKY SPORTS FOOTBALL"
    and leaves "skysportsfootball" alone, and the two never meet. That one
    detail was the difference between binding Sky Sports Football and not.
    """
    s = ''.join(c for c in unicodedata.normalize('NFKD', name) if ord(c) < 128)
    s = QUAL.sub(' ', PREFIX.sub('', s))
    flat = re.sub(r'[^a-z0-9]', '', s.lower())
    return {k for k in (flat, flat.replace('sports', 'sport'), flat.replace('channel', ''))
            if len(k) >= 4}


def build_index(paths):
    """id -> feed and schedule size, for ids worth binding to."""
    index = {}
    for path in paths:
        feed = os.path.basename(path).split('.')[0]
        with gzip.open(path, 'rb') as fh:
            data = fh.read()
        counts = collections.Counter(m.group(1).decode() for m in PROG.finditer(data))
        names = {m.group(1).decode(): [d.decode('utf-8', 'ignore') for d in DNAME.findall(m.group(2))]
                 for m in CHAN.finditer(data)}
        for cid, n in counts.items():
            # dummy-* is the packs' filler and is 83% of what they declare; a
            # bare number is a provider's internal id, and two of those turned
            # out to name the same row twice. The manifest already rejects
            # "dummy" downstream; rejecting both here keeps them out of reach.
            if cid.startswith('dummy-') or cid.isdigit() or n < MIN_PROGRAMMES:
                continue
            index.setdefault(cid, {'feed': feed, 'names': names.get(cid, []), 'programmes': n})
    return index


def main():
    args = [a for a in sys.argv[1:]]
    manifest_path, feeds = args[0], args[1:]
    with open(manifest_path, encoding='utf-8') as fh:
        manifest = json.load(fh)
    here = os.path.dirname(os.path.abspath(manifest_path)) or '.'
    with open(os.path.join(here, 'kept_live.json'), encoding='utf-8') as fh:
        kept = json.load(fh)

    index = build_index(feeds)
    print(f"index: {len(index)} ids carrying a schedule")

    cmap = manifest['epg']['channel_map']
    display = manifest.get('display_name', {})
    hidden = {s['key'] for s in manifest['sections']['live'] if s['hidden_by_default']}
    regions = manifest.get('region_fix', {})
    tiers = {'4K', '8K', 'UHD', 'FHD', 'HD', 'SD', 'OTHER'}

    by_key = collections.defaultdict(set)
    for cid, meta in index.items():
        for source in [cid.rsplit('.', 1)[0]] + meta['names']:
            for k in keys(source):
                by_key[k].add(cid)

    # Ids already spoken for BY A CARRIED CHANNEL. Not by any binding in the
    # map: 624 of the 919 are for channels this build drops, and counting those
    # blocked Sky Sports Football on behalf of a 4K variant that is not shipped.
    line_up = {c['id'] for c in kept}
    # Lower-cased on both sides. The packs publish "SkySportsNews.uk" and
    # "skysportsnews.uk" and an exact-string test treats them as two ids, so a
    # channel could be handed one while another already reads the other.
    taken = {v['id'].lower() for k, v in cmap.items()
             if isinstance(v, dict) and int(k) in line_up and v.get('id')}

    out, refused = {}, collections.Counter()
    for c in kept:
        if c.get('section') in hidden or str(c['id']) in cmap:
            continue
        name = display.get(str(c['id'])) or c['name']
        cands = set()
        for k in keys(name):
            cands |= by_key.get(k, set())
        if not cands:
            refused['no candidate'] += 1
            continue
        if len(cands) > 1:
            refused['two or more ids answer to the name'] += 1
            continue
        cid = next(iter(cands))
        if cid.lower() in taken:
            refused['id already serves a carried channel'] += 1
            continue
        suffix = re.search(r'\.([a-z]{2})$', cid)
        if not suffix:
            refused['id names no territory'] += 1
            continue
        # The channel's OWN region, off kept_live, with region_fix winning.
        # This read c['category_id'] and kept_live has no such key — it carries
        # `region` directly — so the territory test silently never ran, which is
        # the one thing this script exists to get right. It is how the guide
        # match put Sky News AUSTRALIA on UK Sky News in the first place.
        region = regions.get(str(c['id'])) or c.get('region')
        if region in tiers or not region:
            refused['channel territory unknown'] += 1
            continue
        # Refuse, not skip, when the territory is not one this map knows.
        if region not in COMPATIBLE or suffix.group(1) not in COMPATIBLE[region]:
            refused['territory clash'] += 1
            continue
        out[str(c['id'])] = {'src': 'repo', 'id': cid, 'feed': index[cid]['feed']}
        taken.add(cid.lower())

    dest = os.path.join(here, 'epg_extra.json')
    with open(dest, 'w', encoding='utf-8') as fh:
        json.dump(out, fh, indent=1)
    print(f"bound {len(out)} previously unbound channels -> {dest}")
    for why, n in refused.most_common():
        print(f"   left alone, {why}: {n}")


if __name__ == '__main__':
    main()
