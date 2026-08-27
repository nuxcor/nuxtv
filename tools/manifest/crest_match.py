"""Match the clubs in the sport roster to their crests.

The fixture rows are text — "Arsenal v Crystal Palace" — and a crest beside
each club is what turns a list of fixtures into something a viewer reads at a
glance from the sofa. The roster is 239 clubs across nine competitions; this
resolves each one to an image URL and writes crest_map.json, which
build_manifest.py folds into the manifest as `sport.club_crest`.

Nothing is downloaded or redistributed. The manifest carries URLs and the app
fetches them at render time, which is exactly what already happens for channel
artwork via logo_match.py.

Two sources, because no single one covers the roster:

  luukhopman/football-logos   the 25 top European leagues, PNG. No licence
                              stated, so it is linked and never vendored.
  klunn91/team-logos          NFL, NBA, MLB, NCAA. MIT.

MLS has no equivalent: the closest is a Laravel Blade icon pack, which is SVG
and not addressable by club name. So 32 MLS clubs resolve to nothing and their
rows render without a crest. That is a deliberate gap, not a bug — see the
crest-less path in SportTab.

    python3 crest_match.py [manifest.json]      # writes crest_map.json

Refreshing the source indexes needs the two repo trees, one call each:

    gh api "repos/luukhopman/football-logos/git/trees/master?recursive=1" \
      --jq '.tree[]|select(.path|endswith(".png"))|.path' > crest_tree_euro.txt
    gh api "repos/klunn91/team-logos/git/trees/master?recursive=1" \
      --jq '.tree[]|select(.path|test("\\.(png|svg)$"))|.path' > crest_tree_us.txt
"""
import json, os, re, sys, unicodedata, difflib
from urllib.parse import quote

EURO_RAW = "https://raw.githubusercontent.com/luukhopman/football-logos/master/"
US_RAW = "https://raw.githubusercontent.com/klunn91/team-logos/master/"

HERE = os.path.dirname(os.path.abspath(__file__))
MANIFEST = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, 'manifest.json')
OUT = os.path.join(HERE, 'crest_map.json')

# Words that are decoration on a club name rather than part of it. Dropped from
# BOTH sides before comparing, so "Arsenal" reaches "Arsenal FC.png" and
# "Atletico Madrid" reaches "Atlético de Madrid.png".
NOISE = re.compile(
    r"(?i)\b(fc|cf|ac|as|ss|ssc|sc|cd|rc|ca|us|sv|vfl|vfb|tsg|fsv|bsc|afc|ogc"
    r"|rcd|ud|sd|cp|club|deportivo|calcio|de|del)\b"
)


def key(name):
    """The form two spellings of one club share.

    Accents folded, digits dropped — the German clubs carry founding years
    ("1. FC Köln", "Schalke 04") and the roster never does — then the decoration
    words above, then everything that is not a letter or a digit.
    """
    s = unicodedata.normalize('NFKD', name).encode('ascii', 'ignore').decode()
    s = re.sub(r'\d', '', s)
    s = NOISE.sub(' ', s)
    return re.sub(r'[^a-z0-9]', '', s.lower())


# The clubs the roster and the sources call different things. Hand-verified,
# every one of them, because the alternative is a fuzzy match confident enough
# to be wrong: "Inter Miami" scores 0.85 against "Inter Milan", and a row
# wearing another club's badge is worse than a row wearing none.
#
# Roster spelling -> the source's own spelling.
ALIAS = {
    # England: the roster uses what a viewer says, the source uses the
    # registered name.
    'manutd': 'Manchester United', 'manunited': 'Manchester United',
    'wolves': 'Wolverhampton Wanderers',
    # Spain, Italy, France, Germany: the short form in common use.
    'internazionale': 'Inter Milan',
    'pisa': 'Pisa Sporting Club',
    'cologne': '1. FC Köln',
    'psg': 'Paris Saint-Germain',
    'lyon': 'Olympique Lyonnais',
    'rennes': 'Stade Rennais FC',
    # Champions League entrants from leagues the source covers under another
    # spelling, or not at all.
    'psv': 'PSV Eindhoven',
    'bodoglimt': 'FK Bodø/Glimt',
    # NFL/NBA: klunn91 files by the nickname alone.
    'sixers': '76ers',
    'commanders': 'Washington Commanders',
}

# Clubs with no crest in either source, recorded so a rebuild does not report
# them as a regression every time. Pafos and Kairat are Champions League
# entrants from leagues neither repo carries; Qarabag is in neither tree
# under any spelling.
KNOWN_ABSENT = {'pafos', 'kairat', 'qarabag'}


def _season_rank(path):
    """Newest first: logos/ is the current season, history/ goes back to 2021.

    History is indexed and not skipped, and it is load-bearing. The current
    logos/England - Premier League holds Coventry, Hull and Ipswich and holds
    no West Ham, Wolves or Burnley — so the current season alone cannot dress
    the league the roster actually carries. Any club that has been in a covered
    league since 2021 keeps a crest through relegation.
    """
    if path.startswith('logos/'):
        return (0, 0)
    m = re.match(r'history/(\d{4})-', path)
    return (1, -int(m.group(1))) if m else (2, 0)


def build_index(tree_path, base_url, strip_ext='.png'):
    """path list -> {club key: full URL}, newest season winning each key."""
    if not os.path.exists(tree_path):
        sys.exit(f"{tree_path} not found — see the module docstring for the gh call")
    paths = sorted((l.strip() for l in open(tree_path) if l.strip()), key=_season_rank)
    idx = {}
    for p in paths:
        base = os.path.splitext(os.path.basename(p))[0]
        if base.startswith('_'):          # _NFL_logo.png and friends: the league, not a club
            continue
        # setdefault, so the first (newest) spelling of a club wins and the
        # older seasons only fill in what the newer ones do not have.
        # quote(), not a hand-rolled escape. The first version of this used
        # ord() per character, which emits Latin-1 — "Atlético" became %E9
        # where raw.githubusercontent wants the UTF-8 %C3%A9, and every one of
        # the 40 accented clubs 404'd while the ASCII ones looked fine.
        idx.setdefault(key(base), base_url + quote(p, safe='/'))
    return idx


def resolve(club, pool):
    """One club against one index. Exact, then containment, then near-miss."""
    k = ALIAS.get(key(club))
    k = key(k) if k else key(club)
    if k in pool:
        return pool[k]
    # Containment, shortest wins: "Barcelona" is inside "Barcelona B", and the
    # senior side is the shorter of the two.
    if len(k) > 4:
        hits = [x for x in pool if k in x or x in k]
        if hits:
            return pool[min(hits, key=len)]
    # A near miss has to be very near. 0.88 was chosen against the roster: it
    # admits the accent and punctuation variants and rejects Inter Miami ->
    # Inter Milan (0.85) and Wolfsburg -> Wolfsberger AC. Loosening it does not
    # add coverage, it adds wrong badges.
    close = difflib.get_close_matches(k, list(pool), 1, 0.80)
    if close and difflib.SequenceMatcher(None, k, close[0]).ratio() >= 0.88:
        return pool[close[0]]
    return None


def main():
    manifest = json.load(open(MANIFEST))
    leagues = (manifest.get('sport') or {}).get('leagues') or {}
    if not leagues:
        sys.exit(f"{MANIFEST} carries no sport.leagues — run build_manifest.py first")

    euro = build_index(os.path.join(HERE, 'crest_tree_euro.txt'), EURO_RAW)
    us = build_index(os.path.join(HERE, 'crest_tree_us.txt'), US_RAW)
    print(f"index: {len(euro)} European clubs, {len(us)} US")

    crest, missing = {}, []
    for league, clubs in leagues.items():
        pool = us if league in ('NFL', 'NBA') else euro
        for club in clubs:
            url = resolve(club, pool)
            if url:
                crest[club] = url
            elif key(club) not in KNOWN_ABSENT:
                missing.append((league, club))

    json.dump(crest, open(OUT, 'w'), indent=1, ensure_ascii=False)
    total = sum(len(v) for v in leagues.values())
    print(f"{len(crest)}/{total} clubs matched -> {OUT}")
    if missing:
        by = {}
        for lg, c in missing:
            by.setdefault(lg, []).append(c)
        print("no crest:")
        for lg, v in sorted(by.items(), key=lambda x: -len(x[1])):
            print(f"  {lg:18s} {len(v):3d}  {', '.join(v[:6])}{' …' if len(v) > 6 else ''}")


if __name__ == '__main__':
    main()
