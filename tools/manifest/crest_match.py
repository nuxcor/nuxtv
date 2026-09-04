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


def tokens(name):
    """A club name as the set of words that identify it.

    Accents folded, then the decoration words, then STANDALONE numbers — the
    German clubs carry founding years the roster never does ("1. FC Köln",
    "Schalke 04"). Standalone is the whole point: an earlier version stripped
    every digit anywhere, which collapsed "49ers" and "76ers" both to "ers",
    and the San Francisco 49ers shipped wearing the Philadelphia 76ers' badge.
    """
    s = unicodedata.normalize('NFKD', name).encode('ascii', 'ignore').decode()
    # camelCase is a word boundary. klunn91 files "trailBlazers.png",
    # "airForce.png", "washingtonState.png" — one token to any splitter that
    # only knows about spaces, so "Trail Blazers" could never be a subset of
    # it. Split before the case is folded, which is the only point it is
    # still visible.
    s = re.sub(r'(?<=[a-z0-9])(?=[A-Z])', ' ', s)
    s = NOISE.sub(' ', s)
    out = set()
    for t in re.split(r'[^A-Za-z0-9]+', s.lower()):
        if t and not t.isdigit():
            out.add(t)
    return out


def key(name):
    """The tokens joined, for the exact-match index."""
    return ''.join(sorted(tokens(name)))


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
    'lyon': 'Olympique Lyon',
    'rennes': 'Stade Rennais FC',
    'brest': 'Stade Brestois',
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
# Washington's NFL team is in the source only under the name it dropped in
# 2022, and matching the roster's "Commanders" to "redskins.png" would mean
# writing that name into this file to do it. Left without a crest; the row
# falls back to a monogram like any MLS club.
KNOWN_ABSENT = {'pafos', 'kairat', 'qarabag', 'commanders'}


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


def build_index(tree_path, base_url, folders=None):
    """path list -> ({club key: URL}, {club key: token set}), newest first.

    `folders` restricts the tree to the top-level directories named, which is
    how the US index is kept honest. klunn91 files every league it carries in
    one repository — MLB/, NBA/, NCAA/, NFL/ — and a single flat index over all
    of them resolves on the club NICKNAME alone: "Cardinals" and "Giants" exist
    in both MLB and the NFL, MLB sorts first, and setdefault handed the NFL
    roster two baseball badges. The pool a league is resolved against is now the
    league's own folder.

    encoding='utf-8' explicitly, on every read and write in this module. The
    tree listings carry "Atlético de Madrid", "1.FC Köln", "FK BodøGlimt", and
    the default is the locale's — so under LC_ALL=C, which is an ordinary CI
    container, this raises UnicodeDecodeError or silently mis-decodes into keys
    that match nothing and URLs that 404.
    """
    if not os.path.exists(tree_path):
        sys.exit(f"{tree_path} not found — see the module docstring for the gh call")
    with open(tree_path, encoding='utf-8') as fh:
        paths = sorted((l.strip() for l in fh if l.strip()), key=_season_rank)
    idx, tok = {}, {}
    for p in paths:
        if folders and p.split('/')[0] not in folders:
            continue
        base = os.path.splitext(os.path.basename(p))[0]
        if base.startswith('_'):          # _NFL_logo.png and friends: the league, not a club
            continue
        # setdefault, so the first (newest) spelling of a club wins and the
        # older seasons only fill in what the newer ones do not have.
        # quote(), not a hand-rolled escape. The first version of this used
        # ord() per character, which emits Latin-1 — "Atlético" became %E9
        # where raw.githubusercontent wants the UTF-8 %C3%A9, and every one of
        # the 40 accented clubs 404'd while the ASCII ones looked fine.
        k = key(base)
        if k not in idx:
            idx[k] = base_url + quote(p, safe='/')
            tok[k] = tokens(base)
    return idx, tok


# The near-miss floor. Chosen against the roster: it admits the accent and
# punctuation variants and rejects Inter Miami -> Inter Milan (0.85) and
# Wolfsburg -> Wolfsberger AC. Loosening it does not add coverage, it adds
# wrong badges.
NEAR = 0.88


def resolve(club, pool, pool_tokens):
    """One club against one index. Exact, then token subset, then near-miss."""
    alias = ALIAS.get(key(club))
    want = tokens(alias) if alias else tokens(club)
    k = ''.join(sorted(want))
    if k in pool:
        return pool[k]
    # SUBSET OF WORDS, not substring of letters. Substring matching in either
    # direction is what put five more wrong badges in the manifest: "angers"
    # sits inside "rangers", "rapid" inside "coloradorapids", "sporting"
    # inside "sportingkansascity" — and the tie-break took the SHORTEST hit,
    # which deliberately picks the least specific candidate. Words cannot do
    # that: Angers and Rangers share no word at all.
    #
    # Fewest surplus words wins, which is the same instinct the old shortest
    # rule had and the reason it was there: "Barcelona" is a subset of both
    # "FC Barcelona" (nothing left over once FC is noise) and "Barcelona B"
    # (one word left over), and the senior side is the one with nothing left.
    if want:
        hits = [(len(toks - want), x) for x, toks in pool_tokens.items() if want <= toks]
        if hits:
            return pool[min(hits)[1]]
    close = difflib.get_close_matches(k, list(pool), 1, NEAR)
    if close and difflib.SequenceMatcher(None, k, close[0]).ratio() >= NEAR:
        return pool[close[0]]
    return None


def main():
    with open(MANIFEST, encoding='utf-8') as fh:
        manifest = json.load(fh)
    leagues = (manifest.get('sport') or {}).get('leagues') or {}
    if not leagues:
        sys.exit(f"{MANIFEST} carries no sport.leagues — run build_manifest.py first")

    # Named per competition, never a default. An `else euro` sent every
    # league without an entry into the European index; MLB or NHL added to the
    # roster tomorrow would silently do the same thing MLS did.
    global POOL
    euro, euro_tok = build_index(os.path.join(HERE, 'crest_tree_euro.txt'), EURO_RAW)
    us_tree = os.path.join(HERE, 'crest_tree_us.txt')
    nfl, nfl_tok = build_index(us_tree, US_RAW, folders={'NFL'})
    nba, nba_tok = build_index(us_tree, US_RAW, folders={'NBA'})
    print(f"index: {len(euro)} European clubs, {len(nfl)} NFL, {len(nba)} NBA")
    POOL = {
        'Premier League': (euro, euro_tok), 'La Liga': (euro, euro_tok),
        'Serie A': (euro, euro_tok), 'Bundesliga': (euro, euro_tok),
        'Ligue 1': (euro, euro_tok), 'Champions League': (euro, euro_tok),
        'NFL': (nfl, nfl_tok), 'NBA': (nba, nba_tok),
        # MLS: no entry, on purpose. See the module docstring.
    }

    # The sport a competition is played in, which is what scopes a crest key.
    # The bare club name is not unique across sports — "Spurs" is San Antonio
    # and Tottenham, "Patriots" and "Falcons" and "Giants" are each two clubs —
    # so a flat name->URL map has one of every pair silently overwriting the
    # other, and whichever lost wore the wrong badge.
    SPORT = {
        'NFL': 'gridiron', 'NBA': 'basketball',
        'MLS': 'soccer', 'Premier League': 'soccer', 'La Liga': 'soccer',
        'Serie A': 'soccer', 'Bundesliga': 'soccer', 'Ligue 1': 'soccer',
        'Champions League': 'soccer', 'Europa League': 'soccer',
        'Conference League': 'soccer', 'UEFA': 'soccer',
        'Carabao Cup': 'soccer', 'FA Cup': 'soccer',
    }

    crest, missing = {}, []
    for league, clubs in leagues.items():
        pool, pool_tok = POOL.get(league, (None, None))
        if pool is None:
            # No source for this competition, and that is the whole answer.
            # This used to fall through to the European index on `else`, which
            # is how MLS — documented right here as resolving to nothing — put
            # Portugal's Sporting CP on Sporting Kansas City and Romania's FC
            # Rapid on the Colorado Rapids.
            missing.extend((league, c) for c in clubs)
            continue
        for club in clubs:
            url = resolve(club, pool, pool_tok)
            if url:
                # Both keys. The scoped one is what the app prefers and is the
                # only one that can be right for a shared nickname; the bare one
                # is what every build already shipped reads, and dropping it
                # would take the badges off any box that has not updated yet.
                sport = SPORT.get(league)
                if sport:
                    crest[f"{sport}|{club}"] = url
                # Last league wins the bare key, which is what this always did.
                # It is the wrong answer for a shared nickname — that is what
                # the scoped key above is for — but the manifest reaches a box
                # within a day while an app update does not, so a build that
                # only knows the bare key must keep reading exactly what it read
                # before. Roster order puts football after the US leagues, so
                # "Spurs" stays Tottenham there.
                crest[club] = url
            elif key(club) not in KNOWN_ABSENT:
                missing.append((league, club))

    with open(OUT, 'w', encoding='utf-8') as fh:
        json.dump(crest, fh, indent=1, ensure_ascii=False)
    total = sum(len(v) for v in leagues.values())
    scoped = sum(1 for k in crest if '|' in k)
    print(f"{scoped}/{total} clubs matched -> {OUT} ({len(crest)} keys with the bare aliases)")
    if missing:
        by = {}
        for lg, c in missing:
            by.setdefault(lg, []).append(c)
        print("no crest:")
        for lg, v in sorted(by.items(), key=lambda x: -len(x[1])):
            print(f"  {lg:18s} {len(v):3d}  {', '.join(v[:6])}{' …' if len(v) > 6 else ''}")


if __name__ == '__main__':
    main()
