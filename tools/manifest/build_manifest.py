#!/usr/bin/env python3
"""Build the nuxtv catalogue manifest for one provider.

Encodes the agreed decisions:
  - single provider, manifest keyed by host
  - PPV hidden from the default surface
  - series dedup keeps most-episodes, tie-break toward 4K
  - movie genre from the panel's own get_vod_info metadata
  - hand-mapped categories the classifier could not place
"""
import json, re, collections, datetime, os, sys, unicodedata

HOST = "pro.dzidzi.online"
# Allow-list AND shelf order: the app renders territories in this sequence, so
# the order here is a decision, not incidental. It used to be emitted through
# sorted(), which is alphabetical by CODE — that put AFR/DSTV first, ahead of
# the markets this package is mostly made of, for no reason a viewer could see.
KEEP_REGIONS = ("US", "UK", "CA", "AFR")   # the 4K/8K bundles hide ~25 other markets
DROP_REGIONS = set()                        # superseded by KEEP_REGIONS
ALWAYS_ON    = {"24/7", "STREAMING"} # single-show loops - their own destination, not Live TV
TIMESHIFT    = __import__("re").compile(r"\+\d\s*$")   # "ITV 1 +1" - demote, never delete
OUT  = sys.argv[1] if len(sys.argv) > 1 else "manifest.json"

GENRE_SYNONYM = {
    "Avventura":"Adventure", "Azione":"Action", "Commedia":"Comedy",
    "Documentario":"Documentary", "Dramma":"Drama", "Drame":"Drama",
    "Famiglia":"Family", "Fantascienza":"Science Fiction", "Guerra":"War",
    "Mistero":"Mystery", "Musica":"Music", "Storia":"History",
    "televisione film":"TV Movie", "Sci-Fi":"Science Fiction",
}
def canon_genre(g):
    g = g.strip()
    return GENRE_SYNONYM.get(g, g)

def asc(s): return ''.join(c for c in s if ord(c) < 128).strip()

# ---------------------------------------------------------------- hand-mapping
# categories the automatic classifier could not place, resolved by inspection
LIVE_MANUAL = {
    "2036": ("ENTERTAINMENT", "US"),   # DirecTV bundle - mixed lineup
    "2049": ("ENTERTAINMENT", "US"),   # DirecTV bundle, second split (no overlap)
    "62":   ("ENTERTAINMENT", "CA"),   # general Canadian lineup
    "902":  ("ENTERTAINMENT", "AFR"),  # Azam Network (Tanzania)
    "656":  ("STREAMING",     "AR"),   # Watch It (Egypt)
    "662":  ("SPORTS",        "4K"),   # unnamed 4K tier - V Sport, Eleven, TNT
    "2223": ("ENTERTAINMENT", "AFR"),  # Somali TV
    "672":  ("STREAMING",     "AR"),   # Starzplay
    "1732": ("MUSIC",         "UK"),   # MC Video - music video channels
    "2224": ("ENTERTAINMENT", "AFR"),  # Somali IPTV
    "238":  ("ENTERTAINMENT", "IE"),   # general Irish
    "594":  ("ENTERTAINMENT", "AFR"),  # Somalia
    "1062": ("MOVIES",        "UK"),   # Cine Pro
    "595":  ("ENTERTAINMENT", "AFR"),  # Ethiopia & Eritrea
    "2212": ("ENTERTAINMENT", "AFR"),  # Mauritius
}
# movies misfiled by the provider - these are not films
MOVIE_MANUAL = {
    "310":  "SPORTS_EVENTS",    # WWE
    "387":  "SPORTS_EVENTS",    # UFC
    "388":  "SPORTS_EVENTS",    # Boxing
    "272":  "MUSIC_CONCERTS",   # Concerts
    "439":  "FITNESS",          # Workout
    "1784": "AFRICAN_CINEMA",   # Iroko TV
    "1803": "AFRICAN_CINEMA",   # Ibaka TV
    "1819": "AFRICAN_CINEMA",   # Cinaf TV
    "1939": "INTERNATIONAL",    # Italian sub eng
    "119":  "CATALOGUE",        # 2020 & older - recency handled by releasedate
    "217":  "CATALOGUE",        # generic "MOVIES"
}

SECTIONS_LIVE = [
    ("NEWS",          "News",                False),
    ("SPORTS",        "Sports",              False),
    # "Locals", not "Locals & Networks": the shelf is metro affiliate stations
    # and nothing else, so the second half named a thing that is not there and
    # cost width in a strip where width is the whole budget.
    ("LOCALS",        "Locals",              False),
    ("ENTERTAINMENT", "Entertainment",       False),
    ("MOVIES",        "Movies 24/7",         False),
    ("KIDS",          "Kids",                False),
    ("DOCUMENTARY",   "Documentary",         False),
    ("MUSIC",         "Music",               False),
    ("STREAMING",     "Streaming Networks",  False),
    ("24/7",          "24/7 Channels",       False),
    ("PPV",           "PPV & Events",        True),   # hidden from default surface
]
LIVE_DESTINATION = {k: ("always_on" if k in ALWAYS_ON else "live") for k,_,_ in SECTIONS_LIVE}
SECTIONS_VOD = [
    ("NEW_RELEASES","New Releases",False),("BY_GENRE","By Genre",False),
    ("TOP_RATED","Top Rated",False),("STUDIO","Studio & Streaming",False),
    ("COLLECTIONS","Collections",False),("AFRICAN_CINEMA","African Cinema",False),
    ("INTERNATIONAL","International",False),("MUSIC_CONCERTS","Concerts",False),
    ("SPORTS_EVENTS","Sports & Events",False),("FITNESS","Fitness",False),
    ("CATALOGUE","All Movies",False),
]

REGION = re.compile(r'^(US|UK|CA|AR|AFR|IE|4K|8K)\|\s*(.*)$')
SPORT_KW = ('SPORT','SOCCER','NFL','NBA','NHL','MLB','MLS','UFC','GAA','RUGBY','GOLF','TENNIS',
            'F1','FORMULA','RACING','BOXING','LEAGUE','LIGA','SERIE A','EPL','WNBA','NCAA','CFL',
            'WHL','MXGP','VOLLEY','POOL','PDC','MASTERS','SUPERCROSS','TT RACES','FOOTBALL','CUP')
NET_KW   = ('NETFLIX','PRIME','ROKU','PEACOCK','HULU','DISNEY','HBO','MAX','APPLE','PARAMOUNT',
            'TUBI','DISCOVERY','NOW TV','STAN','SKY STORE','ITV X','IPLAYER','VIAPLAY')
LOCAL_KW = ('ABC','CBS','NBC','FOX','CW','TELEMUNDO','SPECTRUM','BBC','ITV','MIAMI')
ENT_KW   = ('ENTERTAINMENT','GENERAL','REALITY','COMEDY','SERIES','SHOWS','RELAX','VIP','MIX')

def classify_live(cid, name):
    if cid in LIVE_MANUAL: return LIVE_MANUAL[cid] + (True,)
    m = REGION.match(name)
    region, rest = (m.group(1), m.group(2).upper()) if m else ("OTHER", name.upper())
    if 'PPV' in rest:                                    s='PPV'
    elif '24/7' in rest:                                 s='24/7'
    elif any(k in rest for k in SPORT_KW):               s='SPORTS'
    elif 'NEWS' in rest:                                 s='NEWS'
    elif 'KIDS' in rest or 'CARTOON' in rest:            s='KIDS'
    elif 'DOC' in rest:                                  s='DOCUMENTARY'
    elif 'MUSIC' in rest:                                s='MUSIC'
    elif any(k in rest for k in ('CINEMA','MOVIE','FILM')): s='MOVIES'
    elif any(k in rest for k in LOCAL_KW):               s='LOCALS'
    elif any(k in rest for k in NET_KW):                 s='STREAMING'
    elif any(k in rest for k in ENT_KW):                 s='ENTERTAINMENT'
    else:                                                s=None
    return s, region, False

GEN = {'DRAMA','COMEDY','ACTION','THRILLER','HORROR','ROMANCE','ADVENTURE','FAMILY','WESTERNS',
       'SCIENCE FICTION','DOCUMENTARIES','KIDS','MUSICAL','MANGA/ANIME','CHRISTMAS','BIBLICAL'}
def classify_vod(cid, name):
    if cid in MOVIE_MANUAL: return MOVIE_MANUAL[cid], True
    u = name.upper().replace('EN - ','')
    if 'NEW RELEASE' in u:                                        return 'NEW_RELEASES', False
    if 'IMDB TOP' in u or u.startswith('TOP MOVIES') or 'TOP KIDS' in u: return 'TOP_RATED', False
    if 'COLLECTION' in u or 'JAMES BOND' in u or 'MARVEL' in u:   return 'COLLECTIONS', False
    if any(b in u for b in ('NETFLIX','DISNEY','AMAZON','APPLE','VIAPLAY','UNIVERSAL',
                            'PARAMOUNT','DISCOVERY','DREAMWORKS')):return 'STUDIO', False
    if u in GEN:                                                  return 'BY_GENRE', False
    return 'CATALOGUE', False

# ---------------------------------------------------------------- build
live_cats = json.load(open('get_live_categories.json'))
vod_cats  = json.load(open('get_vod_categories.json'))
ser_cats  = json.load(open('series_cats.json'))
ls = json.load(open('get_live_streams.json'))
ls_by_id = {s['stream_id']: s for s in ls}
vs = json.load(open('get_vod_streams.json'))
ser = json.load(open('get_series.json'))
_vod_name = {s['stream_id']: s['name'] for s in vs}

lcount = collections.Counter(str(s.get('category_id')) for s in ls)
vcount = collections.Counter(str(s.get('category_id')) for s in vs)

cat_live, unresolved = {}, []
for c in live_cats:
    cid = str(c['category_id'])
    if not lcount.get(cid): continue
    s, region, manual = classify_live(cid, asc(c['category_name']))
    if s is None:
        unresolved.append((cid, asc(c['category_name']), lcount[cid])); s='ENTERTAINMENT'
    cat_live[cid] = {"section": s, "region": region, "manual": manual}

# ------------------------------------------------------- VOD section trimming
# Genre is already a filter, so "By Genre" is not a section - it is the default
# browse. Studio shelves and the catch-alls collapse into it; the tiny shelves
# fold too; sports events go where the other event content lives.
VOD_SECTION_MERGE = {
    'BY_GENRE': 'ALL_MOVIES', 'STUDIO': 'ALL_MOVIES', 'CATALOGUE': 'ALL_MOVIES',
    'INTERNATIONAL': 'ALL_MOVIES', 'FITNESS': 'ALL_MOVIES', 'MUSIC_CONCERTS': 'ALL_MOVIES',
    'SPORTS_EVENTS': 'PPV',
}
VOD_SECTIONS_FINAL = [
    ("NEW_RELEASES",   "New Releases",    False),
    ("TOP_RATED",      "Top Rated",       False),
    ("COLLECTIONS",    "Collections",     False),
    ("AFRICAN_CINEMA", "African Cinema",  False),
    ("ALL_MOVIES",     "All Movies",      False),
    ("PPV",            "Events",          True),
]
SERIES_TOP_RATING = 4.5      # 4.0 put a third of the catalogue in "Top Rated"

cat_vod = {}
for c in vod_cats:
    cid = str(c['category_id'])
    if not vcount.get(cid): continue
    s, manual = classify_vod(cid, asc(c['category_name']))
    cat_vod[cid] = {"section": s, "manual": manual}

for _cid, _v in cat_vod.items():
    _v['section'] = VOD_SECTION_MERGE.get(_v['section'], _v['section'])

cat_ser = {str(c['category_id']): {"label": asc(c['category_name'])} for c in ser_cats}

# junk separator streams
SEP = re.compile(r'^[\s#=\-*_~<>|.]+$')
junk = [s['stream_id'] for s in ls
        if SEP.match(asc(s.get('name',''))) or asc(s.get('name','')).count('#') >= 4]
def _eff_region(s):
    r = (cat_live.get(str(s.get('category_id'))) or {}).get('region')
    if r in {"4K","8K","OTHER"}:
        alias = {"GO":"US","RK":"US","SS":"AR","NOW":"UK","AF":"AFR"}
        mm = re.match(r'^([A-Z]{2,3})\s*:', asc(s.get('name','')))
        if mm:
            c = mm.group(1).upper()
            r = alias.get(c, c)
    return r
dropped_region = [s['stream_id'] for s in ls if _eff_region(s) not in KEEP_REGIONS]
timeshift = [s['stream_id'] for s in ls if TIMESHIFT.search(asc(s.get('name','')))]

# series dedup
PREFIX  = re.compile(r'^[A-Za-z0-9+]{1,4}(?:-[A-Za-z0-9+]{1,4})?\s+-\s+')
COUNTRY = re.compile(r'\s*\((?:[A-Z]{2})\)\s*$')
YEAR    = re.compile(r'\s*\((?:19|20)\d{2}\)\s*')
YEARFIND= re.compile(r'\((19|20)\d{2}\)')
def title_key(n):
    n = PREFIX.sub('', asc(n)).strip(); n = COUNTRY.sub('', n); n = YEAR.sub(' ', n)
    return re.sub(r'[^a-z0-9]', '', n.lower())

groups = collections.defaultdict(list)
for s in ser: groups[title_key(s['name'])].append(s)
epmap = json.load(open('dup_episodes.json')) if os.path.exists('dup_episodes.json') else {}
scat  = {c['category_id']: asc(c['category_name']) for c in ser_cats}
def is4k(s):
    n = scat.get(s['category_id'], '')
    return '3840' in n or n != asc(n)      # decorated name == a quality-tier variant

dedup, review = {}, []
for k, v in groups.items():
    if len(v) < 2: continue
    yrs = {m.group(0) for m in (YEARFIND.search(s['name']) for s in v) if m}
    ranked = sorted(v, key=lambda s: (len(epmap.get(str(s['series_id'])) or []),
                                      is4k(s), int(s['series_id'])), reverse=True)
    entry = {"keep": ranked[0]['series_id'], "hide": [s['series_id'] for s in ranked[1:]]}
    if len(yrs) > 1:
        entry["review"] = "members disagree on year - possible distinct remakes"
        review.append(k)
    dedup[k] = entry

# ---------------------------------------------------------------- live collapse
# Same channel at several quality tiers is ONE tile with many sources.
# Verified: merging across real territories is wrong (US MTV != UK MTV), so the
# region must match. 4K/8K are tiers, not territories, and attach to their
# channel's home region when that is unambiguous.
QUAL = re.compile(r'\b(4K|8K|UHD|FHD|HD|SD|3840P|1080P|720P|HEVC|H265|\d{0,3}FPS)\b', re.I)
SPFX = re.compile(r'^[A-Za-z0-9]{2,5}(?:\s+[A-Za-z0-9]{2,3})?\s*:\s*')
TIER = {"4K", "8K"}
GENRE_SECTIONS  = ['NEWS','SPORTS','KIDS','DOCUMENTARY','MUSIC','MOVIES']
BUNDLE_SECTIONS = ['LOCALS','ENTERTAINMENT','STREAMING','24/7']
# upstream miscategorisations no rule recovers from
SECTION_OVERRIDE = {"qvc": "ENTERTAINMENT", "abcnewslive": "NEWS", "foxweather": "NEWS",
                    "hsn": "ENTERTAINMENT"}

PLURALISE = [(r'\bSPORTS?\b', 'SPORT'), (r'\bNETWORKS?\b', 'NETWORK'),
             (r'\bCHANNELS?\b', 'CHANNEL')]
# Channels the provider ships under two names. The collapse key is built from
# the name, so a rename or a house style leaves one channel as two tiles —
# each with its own sources, so each also picks its own best feed and neither
# gets the other's. Written in POST-PLURALISE form ("skysport", not
# "skysports"), which is what channel_key actually produces.
#
# Hand-curated on purpose. A general rule here folds channels that merely read
# alike, and a wrong fold hides a channel behind another one for good.
CHANNEL_ALIAS = {
    # Sky renamed these; the panel still carries both names.
    'skysportpl': 'skysportpremierleague',
    'skysportprimelige': 'skysportpremierleague',
    'skysportnewshq': 'skysportnews',          # "HQ" was dropped in 2019
    'skysportmainevents': 'skysportmainevent',  # stray plural
    'skysportckreckt': 'skysportcricket',       # provider typo
    # One channel, two house styles.
    'viaplaysport1': 'viaplay1',
    'viaplaysport2': 'viaplay2',
    # Canada carries each of these twice, under the short name and the full
    # one. Folding points at the name the broadcaster actually uses, so the
    # tile is labelled correctly and the better feed wins on measurement.
    'cbcnews': 'cbcnewsnetwork',
    'ctvnewsnetwork': 'ctvnewschannel',   # renamed in 2011; panel kept both
    'globalnews': 'globalnewsnational',
    'theweathernetwork': 'weathernetwork',
    'rogerssportsnetone': 'sportsnetone',
    'golf': 'golfchannel',
    # The same national feed under two house styles, which left two ABCs
    # sitting beside each other on the US news shelf.
    'abcnewslive': 'abcnews',
    'foxnewschannel': 'foxnews',
}

# Provider source tags — "NBC NEWS NOW (A)", "(D)", "(H)", "(PC)" — which name
# where the feed comes from, not what channel it is. Left in the key they made
# a separate tile each, so NBC News Now stood on the US news shelf five times.
#
# Single letters and "PC", and nothing else. Two letters generally would also
# strip "(NY)", "(PA)", "(TX)" and the rest of the state codes, which name a
# genuinely different station — two locals differing only by state would have
# folded into one tile. Nothing folds that way today; the narrower pattern is
# so nothing starts to.
VARIANT_TAG = re.compile(r'\(\s*(?:[A-Za-z]|PC)\s*\)')

# The provider prefixes its NBC-family feeds with the parent network: "NBC
# CNBC", "NBC MSNBC". The prefix is not part of the channel's name, and left in
# place it kept CNBC's only 1080p US feed out of both the CNBC tile and the
# news allowlist — so the US shelf carried a 720p copy while the good feed sat
# dropped. Applied to both keys, because the two disagreeing is what hid it.
NBC_FAMILY = re.compile(r'^\s*NBC\s+(?=(?:C|MS)NBC\b)', re.I)

def channel_key(n):
    n = NBC_FAMILY.sub('', VARIANT_TAG.sub('', QUAL.sub('', SPFX.sub('', asc(n)))))
    for pat, base in PLURALISE:          # "Sky Sport 1" == "Sky Sports 1"
        n = re.sub(pat, base, n, flags=re.I)
    k = re.sub(r'[^a-z0-9]', '', n.lower())
    return CHANNEL_ALIAS.get(k, k)

NAMEREG = re.compile(r'^([A-Z]{2,3})\s*:')
NAME_REGION_ALIAS = {"GO":"US","RK":"US","SS":"AR","NOW":"UK","AF":"AFR","PPV":None}
def region_from_name(n):
    mm = NAMEREG.match(asc(n))
    if not mm: return None
    code = mm.group(1).upper()
    if code in NAME_REGION_ALIAS: return NAME_REGION_ALIAS[code]
    return code if len(code) in (2,3) else None

def tier_of(n):
    # asc() deletes the superscript markers (ᴿᴬᵂ, ᴴᴰ) outright; NFKD folds
    # them back to letters, so the tier survives the provider's styling. RAW
    # rips are typically 1080p60 — ranked above a bare "HD" claim, below FHD.
    # Resolution tokens beat codec/style tokens: "TNT SPORT 1 SD hevc" is an
    # SD picture in an HEVC wrapper, not an HEVC-tier feed.
    u = unicodedata.normalize('NFKD', n).upper()
    for t in ("8K","4K","UHD","FHD","SD","HEVC","H265","RAW","HD"):
        if re.search(rf'\b{t}\b', u): return t
    return None

# Measured heights from probe_tiers.py, when a probe run has landed. The
# name's advertised tier is a claim; a decoded height is a fact, and where
# both exist the fact ranks the tile. 0 means the probe couldn't decode the
# stream — no information, so the advertised token stands.
_probed = {}
if os.path.exists('probed_tiers.json'):
    _probed = {str(k): v for k, v in json.load(open('probed_tiers.json')).items() if v}

def measured_tier(sid):
    h = _probed.get(str(sid))
    # A zero is a FAILED probe, not a dead channel: re-probing the fourteen
    # streams recorded as zero brought ten of them back at 720p-1080p. So zero
    # stays "no information" and the advertised token stands — dropping on it
    # would have deleted ten working channels.
    if not h: return None
    return "4K" if h >= 2000 else "FHD" if h >= 1000 else "HD" if h >= 700 else "SD"

# Religious broadcasting, dropped at ingest like the DGO re-streams above.
# There is no religious CATEGORY to filter on — this provider files these
# under Entertainment and Kids alongside everything else — so the rule reads
# names. Two halves, kept apart because they fail differently:
#
#  - Named broadcasters, which are unambiguous and are most of what lands.
#  - Generic words, which are only safe word-bounded: "CHURCH" must not take
#    Churchill, and bare "GOD" would take Godzilla, so the loose ones that
#    would need that judgement ("GOD", "ANGEL", "HOLY", "MIRACLE") are left
#    out entirely. A missed channel is a nuisance; a wrongly dropped one is a
#    channel the viewer can never get back.
TELEMUNDO = re.compile(r'\bTELEMUNDO\d*\b', re.I)

# Brands that exist ONLY as regional sports networks — no national feed shares
# the name, so the brand alone is enough to condemn them.
RSN_BRAND = re.compile(r"""\b(
    BALLY\s+SPORTS | AT&?T\s+SPORTSNET | ROOT\s+SPORTS | MARQUEE\s+SPORTS |
    NESN | YES\s+NETWORK | ALTITUDE\s+SPORTS | SPECTRUM\s+SPORTSNET |
    MSG\s*(SPORTSNET|2|\+)? | SNY | MASN | MIDCO\s+SPORTS | SWX
)\b""", re.I | re.X)

# Brands with a national feed AND regional ones. Condemned only when a market
# name follows: "FOX SPORTS 1" is national and stays, "FOX SPORTS OHIO" does
# not. The market list is deliberately explicit rather than "any word" —
# guessing here deletes national sport.
RSN_MARKET = re.compile(r"""\b(FOX\s+SPORTS|NBC\s+SPORTS|SPORTSNET)\b[^|]*?\b(
    SOUTHEAST | SOUTHWEST | MIDWEST | NORTHWEST | NORTH | SOUTH | WEST | EAST |
    OHIO | DETROIT | FLORIDA | ARIZONA | SUN | INDIANA | WISCONSIN | TENNESSEE |
    KANSAS\s+CITY | OKLAHOMA | NEW\s+ORLEANS | SAN\s+DIEGO | CAROLINAS? |
    CINCINNATI | GREAT\s+LAKES | PITTSBURGH | BOSTON | CHICAGO | PHILADELPHIA |
    WASHINGTON | CALIFORNIA | BAY\s+AREA | NEW\s+ENGLAND | ROCKY\s+MOUNTAIN |
    PRIME\s+TICKET | SOCAL | UTAH | NEW\s+YORK | MINNESOTA | MISSOURI | TEXAS
)\b""", re.I | re.X)

# Canada ships the same three kinds of clutter the other territories did:
# a news channel per city, numbered event feeds, and regional sports networks.
CA_REGIONAL_NEWS = re.compile(
    r'\bGLOBAL NEWS\s+(?!NATIONAL\b)\w'      # Global News BC, Halifax, Regina...
    r'|\b(CBC|CTV)\s+\w+\s+NEWS\b(?!\s*(NETWORK|CHANNEL))'
    r'|\b(CBC|CTV)\s+NEWS\s+(?!NETWORK\b|CHANNEL\b)[A-Za-z]'
    r'|\bCITY ?NEWS\s+\w+', re.I)
CA_EVENT_FEED = re.compile(
    r'\bCTV NEWS LIVE EVENTS?\s*\d+'
    r'|\bROGERS SUPER SPORTS PACK\s*\d+', re.I)
# Sportsnet ONE and 360 are national and stay; the compass points are regional.
# Sportsnet's regional feeds carry the same schedule with different local
# rights. "SPORTSN ET" is not a typo here — the panel really does split the
# word on this one, and the unspaced pattern walked straight past it.
CA_REGIONAL_SPORT = re.compile(
    r'\bSPORTSN\s?ET\s+(ONTARIO|EAST|WEST|PACIFIC)\b', re.I)
# Rogers TV is per-city community programming; Global's named stations are
# regional affiliates of the national feed that survives above.
CA_LOCAL_STATION = re.compile(
    r'\bROGERS TV\s+\w'
    r'|\bGLOBAL\s+(MARITIMES|BC|OKANAGAN|LETHBRIDGE|SASKATOON|REGINA'
    r'|WINNIPEG|DURHAM|PETERBOROUGH|KINGSTON|BARRIE|HALIFAX)\b', re.I)

def _ca_clutter(name):
    """Canadian regional variants and numbered event feeds.

    Gated on the CA prefix so nothing outside Canada can match: "CITY NEWS" and
    "EAST"/"WEST" are common enough words that an unanchored rule would reach
    into the other territories.
    """
    n = asc(name)
    if not re.match(r'^\s*CA(\s+(EN|FR))?\s*:', n, re.I):
        return False
    return bool(CA_REGIONAL_NEWS.search(n) or CA_EVENT_FEED.search(n)
                or CA_REGIONAL_SPORT.search(n) or CA_LOCAL_STATION.search(n))

def _regional_sport(name):
    """A US regional sports network, as opposed to a national sports channel.

    Two tests, because the brands split two ways. Bally, NESN, YES and the
    rest have no national feed, so the brand condemns them outright. FOX
    SPORTS and NBC SPORTS do have one, so those are condemned only when a
    named market follows — FOX SPORTS 1 stays, FOX SPORTS OHIO goes.
    """
    n = asc(name)
    return bool(RSN_BRAND.search(n) or RSN_MARKET.search(n))

RELIGIOUS_NETWORK = re.compile(r"""\b(
    EWTN | TBN | TRINITY\s+BROADCASTING | DAYSTAR | GOD\s?TV | CBN | INSP |
    3ABN | HOPE\s+CHANNEL | JCTV | GAITHER | HILLSONG | LOVEWORLD |
    EMMANUEL\s+TV | SAT-?7 | PEACE\s+TV | IQRAA | HUDA\s+TV | AL\s?RESALAH |
    ISLAM\s+CHANNEL | SHALOM\s+TV | K-?LOVE | REVELATION\s+TV |
    WORD\s+NETWORK | IMPACT\s+NETWORK | SONLIFE | NRB\s+TV
)\b""", re.I | re.X)
# Bare CHRISTIAN and CATHOLIC are deliberately absent. In this panel they are
# overwhelmingly school names on college sports slots — Lubbock Christian,
# Bergen Catholic, Christian Brothers University — and one of them is Christian
# Bale. They are only religious when paired, so they are spelled out that way.
RELIGIOUS_WORD = re.compile(r"""\b(
    GOSPEL | CHURCH | ISLAMIC | QURAN | KORAN | BIBLE |
    WORSHIP | PREACHING | MINISTRIES | MINISTRY | EVANGEL \w* | PENTECOSTAL |
    ADVENTIST | RELIGIOUS | RELIGION | JESUS | TORAH | RABBI | SERMON |
    CATHOLIC \s+ (?:TV|CHANNEL|NETWORK) |
    CHRISTIAN \s+ (?:TV|CHANNEL|NETWORK|HITS|MUSIC|RADIO)
)\b""", re.I | re.X)
def _religious(name):
    n = asc(name)
    return bool(RELIGIOUS_NETWORK.search(n) or RELIGIOUS_WORD.search(n))

live_rows = []
junkset = set(junk)
go_drop, sd_all_drop, religion_drop = [], [], []
# Regional US news: one-metro cable news that belongs nowhere near a national
# news shelf. NY1 is matched with its Spectrum prefix only — bare "NY1" would
# also catch sports and entertainment feeds that carry the market in their name.
US_REGIONAL_NEWS = re.compile(
    r'\bNECN\b|\bNEW ENGLAND CABLE NEWS\b'
    r'|\bNEWS ?12\b'
    r'|\bSPECTRUM NEWS\b|\bSPECTRUM BAY NEWS\b|\bBAY NEWS ?9\b', re.I)

telemundo_drop, rsn_drop, ca_drop, us_news_drop = [], [], [], []
for s in ls:
    if s['stream_id'] in junkset: continue
    c = cat_live.get(str(s.get('category_id')))
    if not c: continue
    # DGO re-streams carry a burned-in watermark and duplicate native feeds;
    # dropped outright, not demoted — the catalogue serves the originals.
    if re.match(r'^GO\s*:', asc(s['name']), re.I):
        go_drop.append(s['stream_id']); continue
    # Telemundo's stations are Spanish-language and were filling eight of the
    # metro shelves' tiles. Dropped at ingest so they never form a tile at
    # all, rather than being hidden later — a tile whose every member is gone
    # is a tile that renders nothing.
    if TELEMUNDO.search(asc(s['name'])):
        telemundo_drop.append(s['stream_id']); continue
    # Regional sports networks. Sixty of them survived into US Sports, one per
    # metro, and a viewer in one market can watch none of the other
    # fifty-nine — they are blackout-locked to a territory this package does
    # not sell.
    if _regional_sport(s['name']):
        rsn_drop.append(s['stream_id']); continue
    if _ca_clutter(s['name']):
        ca_drop.append(s['stream_id']); continue
    # The US equivalent of the regional sports networks: cable news that only
    # covers one metro. Most Spectrum feeds name their city and were already
    # going out with the locals, but the ones that don't — plain "Spectrum
    # News 1", NY1, Bay News 9, NECN, and the five News 12 boroughs — landed on
    # the national news shelf beside CNN and the networks.
    if US_REGIONAL_NEWS.search(asc(s['name'])):
        us_news_drop.append(s['stream_id']); continue
    # PPV event slots are not channels. "NCAAF 06: FOX" reduces to the key
    # "fox" once the prefix comes off, which put a college-football slot into
    # the FOX tile — and, once the real feeds were trimmed, at the FRONT of
    # it: the News shelf's FOX played an event slot. Events have their own
    # destination and must never join a channel's fold.
    if c['section'] == 'PPV':
        continue
    # After PPV, and never on a 24/7 marathon: both are named for the fixture
    # or the star rather than the subject, and both have their own
    # destination — "24/7: CHRISTIAN BALE" is a movie channel.
    if c['section'] != '24/7' and _religious(s['name']):
        religion_drop.append(s['stream_id']); continue
    t = measured_tier(s['stream_id']) or tier_of(s['name'])
    # SD is below the floor everywhere, fallback slots included. ", SD" is
    # South Dakota, not a tier — "NBC (KDLT) SIOUX FALLS, SD" stays.
    if t == "SD" and not re.search(r',\s*SD\b', asc(s['name']), re.I):
        sd_all_drop.append(s['stream_id']); continue
    reg = c['region']
    if reg in TIER:                       # 4K/8K are tiers, not territories
        reg = region_from_name(s['name']) or reg
    live_rows.append((channel_key(s['name']), s['stream_id'], reg,
                      c['section'], t))

home = collections.defaultdict(set)
for k, sid, reg, sec, t in live_rows:
    if reg not in TIER: home[k].add(reg)

TIER_RANK = {"8K":0,"4K":1,"UHD":2,"FHD":3,"HEVC":4,"H265":5,"RAW":6,"HD":7,None:8,"SD":9}
# Hand-pinned territories where the fold's majority lands wrong: NBC News Now
# grouped under UK because its surviving sources sit in UK categories.
#
# The same pin doubles as the fix for a genuinely global channel. A tile only
# collapses within one territory, which is right for a "Comedy Central" that
# differs per market — and wrong for a single worldwide broadcast: BBC World
# News is one feed, and the provider's US copy of it is 576p while its CA copy
# is 1080p. Pinning the key sends every territory's copy into one tile, where
# the measured ranking picks the best of them and the SD copy is dropped like
# any other. Only for channels that really are one broadcast everywhere.
REGION_PIN = {'nbcnewsnow': 'US', 'bbcworldnews': 'US'}

# The territories that share one shelf per genre. DSTV (AFR) keeps its own.
MERGED_REGIONS = ('US', 'UK', 'CA')
tiles = collections.defaultdict(list)
for k, sid, reg, sec, t in live_rows:
    if k in REGION_PIN:
        reg = REGION_PIN[k]
    elif reg in TIER:
        h = home.get(k)
        if h and len(h) == 1: reg = next(iter(h))
    if reg not in KEEP_REGIONS: continue
    # The shelves are genre-only now — one News, one Sports — so US, UK and CA
    # copies of a channel land on the same row, and a fold that stopped at the
    # territory line left CNN sitting beside CNN. They share a bucket, which
    # also catches the case a later pass cannot see: two territories holding
    # ONE copy each never formed a tile at all, so nothing ever compared them.
    #
    # DSTV keeps its own shelf and is bucketed separately, because what it
    # keeps is decided below on a different rule: uniqueness, not quality.
    tiles[(k, 'MERGED' if reg in MERGED_REGIONS else reg)].append(
        {"id": sid, "section": sec, "tier": t, "region": reg})

def pick_section(sources):
    c = collections.Counter(x["section"] for x in sources)
    gen = {s:n for s,n in c.items() if s in GENRE_SECTIONS}
    pool, order = (gen, GENRE_SECTIONS) if gen else (c, BUNDLE_SECTIONS)
    best = max(pool.values())
    tied = [s for s,n in pool.items() if n == best]
    win = min(tied, key=lambda s: order.index(s) if s in order else 99)
    return win, (len(gen) > 1 and len(tied) == 1 and len(gen) > 1)

# Sections the clean-surface pass sweeps from Live TV. Defined here because
# the collapse below must know them too: donor feeds (streaming rips of real
# channels) join tiles but must not vote the tile onto a swept shelf.
DROP_LIVE_SECTIONS = {'MOVIES', '24/7', 'STREAMING'}

collapse, needs_review = {}, []
_cnm = {s['stream_id']: asc(s['name']) for s in ls}
# Swept-section members that are not PRIME: donors get dropped later, so they
# must never be declared primary — the app would only have to promote past
# the corpse. They still trail the list as recorded fallbacks.
def _swept_source(x):
    return (x["section"] in DROP_LIVE_SECTIONS
            and not re.match(r'^PRIME\s*:', _cnm.get(x["id"], ''), re.I))
for (k, reg), srcs in tiles.items():
    if len(srcs) < 2: continue
    # Measured height breaks the tie inside a tier. Two feeds both calling
    # themselves HD are indistinguishable to TIER_RANK, so the winner was
    # whichever landed first — and a feed that failed to decode (recorded 0)
    # beat one measured at 720p that way. A number we took beats a word the
    # provider chose; 0 sorts last because it means "we could not tell".
    srcs.sort(key=lambda x: (_swept_source(x), TIER_RANK.get(x["tier"], 8),
                             -_probed.get(str(x["id"]), 0)))
    vote = [x for x in srcs if x["section"] not in DROP_LIVE_SECTIONS] or srcs
    sec, ambiguous = pick_section(vote)
    if k in SECTION_OVERRIDE: sec = SECTION_OVERRIDE[k]
    elif ambiguous and len({x["section"] for x in srcs}) > 1:
        needs_review.append(k)
    # The bucket is not a territory. Record the primary's own, so the app's
    # kept-region gate still recognises the tile.
    out_reg = srcs[0].get("region", reg) if reg == 'MERGED' else reg
    collapse[f"{k}|{out_reg}"] = {
        "section": sec, "region": out_reg,
        "sources": [x["id"] for x in srcs],      # best tier first
        "primary": srcs[0]["id"],
    }

# ------------------------------------------------- unique-only on the DSTV shelf
# US, UK and CA already share one tile per channel — they were bucketed
# together above, so the measured ranking picked one primary for all three.
# DSTV is bucketed apart because what it keeps is decided on a different rule:
# it keeps its own shelf, and keeps only the channels that exist nowhere else.
# A South African feed of something already on a merged row is the same
# duplication seen from the other side.
cross_region_dupe = []
_by_key = collections.defaultdict(set)
for _tkey, _t in collapse.items():
    _by_key[_tkey.rsplit('|', 1)[0]].add(_t['region'])
for _tkey in [k for k in collapse if collapse[k]['region'] == 'AFR']:
    if _by_key[_tkey.rsplit('|', 1)[0]] - {'AFR'}:
        cross_region_dupe += collapse.pop(_tkey)['sources']

# A tile member's territory was just resolved by the fold itself (home
# regions, name prefixes). dropped_region was computed from the raw category
# before that knowledge existed — left in place it killed the very 4K/8K
# primaries the collapse promoted, and the app had to promote past the
# corpse: 115 tiles shipped that way, playing their second-best source.
# Members of tiles deleted by later passes are re-added to the drop there.
_tile_member_ids = {x for t in collapse.values() for x in t['sources']}
dropped_region = [sid for sid in dropped_region if sid not in _tile_member_ids]


# ---------------------------------------------------------------- US locals
# 1,394 affiliate feeds is noise. Keep the major markets only, resolving the
# market from the city in the name, a suburb alias, or the call sign.
TOP_METROS = ['NEW YORK','LOS ANGELES','CHICAGO','PHILADELPHIA','DALLAS',
              'SAN FRANCISCO','WASHINGTON','HOUSTON','BOSTON','ATLANTA']
MAJOR_MARKETS = {
 'NEW YORK','LOS ANGELES','CHICAGO','PHILADELPHIA','DALLAS','SAN FRANCISCO','WASHINGTON',
 'HOUSTON','BOSTON','ATLANTA','PHOENIX','SEATTLE','TAMPA','DETROIT','MINNEAPOLIS','DENVER',
 'MIAMI','ORLANDO','CLEVELAND','SACRAMENTO','CHARLOTTE','PORTLAND','RALEIGH','ST. LOUIS',
 'PITTSBURGH','BALTIMORE','SAN DIEGO','NASHVILLE','SAN ANTONIO','LAS VEGAS','AUSTIN',
 'KANSAS CITY','COLUMBUS','INDIANAPOLIS','MILWAUKEE','SALT LAKE CITY','CINCINNATI',
 'NEW ORLEANS','OKLAHOMA CITY','MEMPHIS','BUFFALO','HARTFORD','JACKSONVILLE',
 'WEST PALM BEACH','BIRMINGHAM','FRESNO',
}
SUBURB_ALIAS = {
 'FORT WORTH':'DALLAS','THE COLONY':'DALLAS','ARLINGTON':'DALLAS','IRVING':'DALLAS',
 'LINDEN':'NEW YORK','SECAUCUS':'NEW YORK','NEWARK':'NEW YORK','PATERSON':'NEW YORK',
 'ST PETERSBURG':'TAMPA','ST. PETERSBURG':'TAMPA','CLEARWATER':'TAMPA',
 'OAKLAND':'SAN FRANCISCO','SAN JOSE':'SAN FRANCISCO','CONCORD':'SAN FRANCISCO',
 'PEARLAND':'HOUSTON','GARDEN GROVE':'LOS ANGELES','CORONA':'LOS ANGELES',
 'PARKER':'DENVER','CHULA VISTA':'SAN DIEGO','MARANA':'PHOENIX','WORCESTER':'BOSTON',
 'FORT LAUDERDALE':'MIAMI','HOLLYWOOD':'MIAMI','ARLINGTON HEIGHTS':'CHICAGO',
 'GARY':'CHICAGO','SILVER SPRING':'WASHINGTON','ARLINGTON VA':'WASHINGTON',
}
# flagships whose entry carries no city at all
CALLSIGN_MARKET = {
 'WABC':'NEW YORK','WCBS':'NEW YORK','WNBC':'NEW YORK','WNYW':'NEW YORK','WPIX':'NEW YORK','WNJU':'NEW YORK',
 'KABC':'LOS ANGELES','KCBS':'LOS ANGELES','KNBC':'LOS ANGELES','KTTV':'LOS ANGELES','KTLA':'LOS ANGELES',
 'WLS':'CHICAGO','WBBM':'CHICAGO','WMAQ':'CHICAGO','WFLD':'CHICAGO','WGN':'CHICAGO',
 'WPVI':'PHILADELPHIA','KYW':'PHILADELPHIA','WCAU':'PHILADELPHIA','WTXF':'PHILADELPHIA',
 'WFAA':'DALLAS','KTVT':'DALLAS','KXAS':'DALLAS','KDFW':'DALLAS','KXTX':'DALLAS',
 'KGO':'SAN FRANCISCO','KPIX':'SAN FRANCISCO','KNTV':'SAN FRANCISCO','KTVU':'SAN FRANCISCO',
 'WJLA':'WASHINGTON','WUSA':'WASHINGTON','WRC':'WASHINGTON','WTTG':'WASHINGTON',
 'KTRK':'HOUSTON','KHOU':'HOUSTON','KPRC':'HOUSTON','KRIV':'HOUSTON',
 'WCVB':'BOSTON','WBZ':'BOSTON','WHDH':'BOSTON','WFXT':'BOSTON',
 'WSB':'ATLANTA','WGCL':'ATLANTA','WXIA':'ATLANTA','WAGA':'ATLANTA',
 'KOMO':'SEATTLE','KIRO':'SEATTLE','KING':'SEATTLE','KCPQ':'SEATTLE',
 'WXYZ':'DETROIT','WWJ':'DETROIT','WDIV':'DETROIT','WJBK':'DETROIT',
 'WPLG':'MIAMI','WFOR':'MIAMI','WTVJ':'MIAMI','WSVN':'MIAMI',
 'KNXV':'PHOENIX','KPHO':'PHOENIX','KPNX':'PHOENIX','KSAZ':'PHOENIX',
}
LOCAL_CITY = re.compile(r'\(([WK][A-Z0-9\-]{2,6})\)\s*(.*)$')
CALL_ONLY  = re.compile(r'\(([WK][A-Z0-9\-]{2,6})\)')

def _clean_city(c):
    c = c.upper()
    c = re.sub(r'\((?:[A-Z]|DT|LD|HD)\)', '', c)
    c = re.sub(r'\b(HD|SD|FHD|UHD|4K|DT|LD|IP)\b', '', c)
    c = re.sub(r'[^A-Z\.\s]', '', c)
    return re.sub(r'\s+', ' ', c).strip(' .')

def local_market(name):
    """Return the major market this affiliate serves, or None to drop it."""
    n = asc(name)
    mm = LOCAL_CITY.search(n)
    if mm and mm.group(2).strip():
        city = _clean_city(mm.group(2))
        city = SUBURB_ALIAS.get(city, city)
        if city in MAJOR_MARKETS: return city
    cm = CALL_ONLY.search(n)
    if cm:
        call = cm.group(1).split('-')[0].upper()
        if call in CALLSIGN_MARKET: return CALLSIGN_MARKET[call]
    for city in MAJOR_MARKETS:                     # bare city mention
        if re.search(rf'\b{re.escape(city)}\b', n.upper()): return city
    return None

# The provider files the national news networks in a LOCALS category, and they
# carry neither a city nor a callsign — so the market lookup found nothing and
# they went out as unplaceable local junk. ABC News is not an ABC affiliate,
# and dropping it cost the US news shelf its 1080p feed while a 720p copy
# filed elsewhere survived to stand in for it.
# The flagship feed only. "ABC News International" and "ABC News 2" are
# separate services, and rescuing them too just puts a second ABC back on the
# shelf beside the first — which is the clutter this is meant to remove.
NATIONAL_NEWS = re.compile(
    r'\b(?:ABC|CBS|NBC|FOX)\s+NEWS\b(?!\s*(?:INTERNATIONAL\b|\d))'
    # CNBC and MSNBC are filed the same way and were dropped the same way.
    # CNBC World is excluded deliberately: it measures 480p.
    r'|\b(?:C|MS)NBC\b(?!\s*WORLD\b)', re.I)

locals_market, locals_dropped = {}, []
for s in ls:
    c = cat_live.get(str(s.get('category_id')))
    if not c or c['section'] != 'LOCALS' or c['region'] != 'US': continue
    mk = local_market(s['name'])
    if mk: locals_market[str(s['stream_id'])] = mk
    # Left alone rather than placed: NAME_SECTION's \bNEWS\b rule files it
    # under NEWS further down, which is where it belonged all along.
    elif NATIONAL_NEWS.search(asc(s['name'])): continue
    else:  locals_dropped.append(s['stream_id'])


# --------------------------------------------------- one tile per network per metro
# Telemundo/Univision first: "TELEMUNDO NBC (WNJU)" must not register as NBC.
# Trailing digits are part of how affiliates name themselves — "ABC7 NEW
# YORK", "FOX2", "CBS11" — and \bABC\b does not match "ABC7", so a New York
# ABC affiliate resolved to no network at all and was dropped instead of
# folded into the NEW YORK|ABC tile it belongs to.
NETWORK_ORDER = [
    ("TELEMUNDO", r"\bTELEMUNDO\d*\b"), ("UNIVISION", r"\bUNIVISION\d*\b"),
    ("ABC", r"\bABC\d*\b"), ("CBS", r"\bCBS\d*\b"), ("NBC", r"\bNBC\d*\b"),
    ("FOX", r"\bFOX\d*\b"), ("CW", r"\bCW\d*\b"), ("PBS", r"\bPBS\d*\b"),
]
FLAGSHIP = {
 ("NEW YORK","ABC"):"WABC",("NEW YORK","CBS"):"WCBS",("NEW YORK","NBC"):"WNBC",
 ("NEW YORK","FOX"):"WNYW",("NEW YORK","TELEMUNDO"):"WNJU",
 ("LOS ANGELES","ABC"):"KABC",("LOS ANGELES","CBS"):"KCBS",("LOS ANGELES","NBC"):"KNBC",("LOS ANGELES","FOX"):"KTTV",
 ("CHICAGO","ABC"):"WLS",("CHICAGO","CBS"):"WBBM",("CHICAGO","NBC"):"WMAQ",("CHICAGO","FOX"):"WFLD",
 ("PHILADELPHIA","ABC"):"WPVI",("PHILADELPHIA","CBS"):"KYW",("PHILADELPHIA","NBC"):"WCAU",("PHILADELPHIA","FOX"):"WTXF",
 ("DALLAS","ABC"):"WFAA",("DALLAS","CBS"):"KTVT",("DALLAS","NBC"):"KXAS",("DALLAS","FOX"):"KDFW",("DALLAS","TELEMUNDO"):"KXTX",
 ("SAN FRANCISCO","ABC"):"KGO",("SAN FRANCISCO","CBS"):"KPIX",("SAN FRANCISCO","NBC"):"KNTV",("SAN FRANCISCO","FOX"):"KTVU",
 ("WASHINGTON","ABC"):"WJLA",("WASHINGTON","CBS"):"WUSA",("WASHINGTON","NBC"):"WRC",("WASHINGTON","FOX"):"WTTG",
 ("HOUSTON","ABC"):"KTRK",("HOUSTON","CBS"):"KHOU",("HOUSTON","NBC"):"KPRC",("HOUSTON","FOX"):"KRIV",("HOUSTON","TELEMUNDO"):"KTMD",
 ("BOSTON","ABC"):"WCVB",("BOSTON","CBS"):"WBZ",("BOSTON","NBC"):"WHDH",("BOSTON","FOX"):"WFXT",
 ("ATLANTA","ABC"):"WSB",("ATLANTA","CBS"):"WGCL",("ATLANTA","NBC"):"WXIA",("ATLANTA","FOX"):"WAGA",
}
def network_of(n):
    u = asc(n).upper()
    for key, pat in NETWORK_ORDER:
        if re.search(pat, u): return key
    return None

def _quality_rank(n, metro, net):
    """Lower is better. Flagship call sign wins, then HD, then least decorated."""
    u = asc(n).upper()
    flag = FLAGSHIP.get((metro, net))
    r = 0 if (flag and flag in u) else 10
    if re.search(r'\bSD\b', u):      r += 4
    elif re.search(r'\bHD\b|\(H\)', u): r += 0
    else:                             r += 2
    if re.search(r'\bNEWS\b|\(PC\)', u): r += 3     # sub-feeds, not the main affiliate
    return (r, len(u))

metro_tiles, locals_extra_drop = {}, []
_by = collections.defaultdict(list)
for s in ls:
    sid = str(s['stream_id'])
    mk = locals_market.get(sid)
    if not mk: continue
    if mk not in TOP_METROS:
        locals_extra_drop.append(s['stream_id']); continue
    net = network_of(s['name'])
    if not net:
        locals_extra_drop.append(s['stream_id']); continue
    _by[(mk, net)].append((s['stream_id'], asc(s['name'])))
for (mk, net), items in _by.items():
    items.sort(key=lambda x: _quality_rank(x[1], mk, net))
    metro_tiles[f"{mk}|{net}"] = {
        "metro": mk, "network": net,
        "primary": items[0][0], "label": items[0][1],
        "sources": [i[0] for i in items],
    }


# ------------------------------------------------------ UK has no "locals"
# That section is BBC/ITV national channels plus radio, Red Button slots and
# single-show loops. Drop the noise, collapse the regional variants, and file
# the real channels under the section their content belongs to.
UK_DROP = re.compile(
    r'\bRADIO\b|\bASIAN NETWORK\b|\b1XTRA\b|\b6 MUSIC\b'      # radio, not TV
    r'|\bRED BUTTON\b'                                            # interactive event slots
    r'|\bEVENTS \d', re.I)
UK_ALWAYS_ON = re.compile(
    r'\bIPLAYER SERIES\b|\b24/7\b|\bMIDSOMER MURDERS\b|\bVERA\b|\bTHE CHASE\b'
    r'|\bTOWIE TV\b|\bSATURDAY NIGHT EVERY NIGHT\b|\bALL ACTION MOVIES\b', re.I)
UK_SECTION = [
    (r'\bBBC NEWS\b|\bBBC PARLIAMENT\b',            'NEWS'),
    (r'\bCBBC\b|\bCBEEBIES\b|\bITV ?X KIDS\b',      'KIDS'),
]
# ITV 2/3/4/Be/X are distinct channels; "ITV Granada" is ITV1 for a region.
# Match the base name only when a REGION word follows it.
UK_REGION_WORD = (r'LONDON|MIDLANDS?|NORTH(?:\s+(?:EAST|WEST))?|SOUTH(?:\s+(?:EAST|WEST))?'
                  r'|EAST(?:\s+(?:MIDLANDS?|YORKSHIRE))?|WEST(?:\s+(?:MIDLANDS?|COUNTRY))?'
                  r'|WALES|CYMRU|SCOT(?:LAND)?|NI|N\.?\s?IRELAND|ULSTER|YORKS(?:HIRE)?'
                  r'|GRANADA|MERIDIAN|TYNE\s*TEES|BORDER|ANGLIA|CENTRAL|CHANNEL\s*ISLANDS?'
                  r'|CAMBRIDGE|OXFORD|LINCOLNSHIRE|CUMBRIA|ENGLAND|UK|YK\s*LI|ROTX')
UK_REGIONAL = re.compile(
    rf'^(BBC ONE|BBC TWO|CHANNEL 4|CHANNEL 5|C4|C5|ITV ?1|ITV)\b\s*((?:{UK_REGION_WORD})\b.*)$',
    re.I)
def _uk_base(g):
    g = g.upper().replace(' ', '')
    return {'C4': 'CHANNEL 4', 'CHANNEL4': 'CHANNEL 4', 'C5': 'CHANNEL 5',
            'CHANNEL5': 'CHANNEL 5', 'ITV1': 'ITV', 'ITV': 'ITV',
            'BBCONE': 'BBC ONE', 'BBCTWO': 'BBC TWO'}.get(g, g)

UK_PREFERRED_REGION = ('LONDON', 'ENGLAND', '')     # which variant becomes the tile

uk_locals_drop, uk_reassign, uk_collapse = [], {}, {}
_uk_reg = collections.defaultdict(list)
for s in ls:
    c = cat_live.get(str(s.get('category_id')))
    if not c or c['region'] != 'UK': continue
    nm = asc(s['name'])
    body = re.sub(r'^[A-Z0-9]{2,5}\s*[:;,]\s*', '', nm)
    # the regional-variant fold applies to every UK section, not just LOCALS:
    # "Channel 4 London" and "ITV Granada" sit in Entertainment categories.
    if c['section'] != 'LOCALS' and not UK_REGIONAL.match(body): continue
    if UK_DROP.search(body):
        uk_locals_drop.append(s['stream_id']); continue
    if UK_ALWAYS_ON.search(body):
        uk_reassign[str(s['stream_id'])] = "ALWAYS_ON"; continue
    mm = UK_REGIONAL.match(body)
    if mm:
        _uk_reg[_uk_base(mm.group(1))].append((s['stream_id'], mm.group(2).strip().upper()))
        continue
    sec = 'ENTERTAINMENT'
    for pat, target in UK_SECTION:
        if re.search(pat, body, re.I): sec = target; break
    uk_reassign[str(s['stream_id'])] = sec

# quality-collapse ran first, so sweep the tiles it made for the same treatment
_nm = {st['stream_id']: asc(st['name']) for st in ls}
for _key in [k for k, t in collapse.items()
             if t['region'] == 'UK' and (t['section'] == 'LOCALS'
                or UK_REGIONAL.match(re.sub(r'^[A-Z0-9]{2,5}\s*[:;,]\s*', '',
                                            asc(_nm.get(t['primary'], '')))))]:
    _t   = collapse[_key]
    _body = re.sub(r'^[A-Z0-9]{2,4}:\s*', '', _nm.get(_t['primary'], ''))
    if UK_DROP.search(_body):
        uk_locals_drop.extend(_t['sources']); del collapse[_key]; continue
    if UK_ALWAYS_ON.search(_body):
        _t['section'] = '24/7'; continue
    _mm = UK_REGIONAL.match(_body)
    if _mm:
        _uk_reg[_uk_base(_mm.group(1))].append((_t['primary'], _mm.group(2).strip().upper()))
        uk_locals_drop.extend(x for x in _t['sources'] if x != _t['primary'])
        del collapse[_key]; continue
    _sec = 'ENTERTAINMENT'
    for _pat, _target in UK_SECTION:
        if re.search(_pat, _body, re.I): _sec = _target; break
    _t['section'] = _sec

for chan, items in _uk_reg.items():                 # BBC ONE x16 -> one tile
    def rank(it):
        reg = it[1]
        for i, pref in enumerate(UK_PREFERRED_REGION):
            if reg == pref: return i
        return len(UK_PREFERRED_REGION)
    items.sort(key=rank)
    uk_collapse[chan] = {"section": "ENTERTAINMENT", "region": "UK",
                         "primary": items[0][0],
                         "sources": [i[0] for i in items],
                         "variants": [i[1] for i in items]}

# The chosen primary must SURVIVE. Folding the regional variants puts every
# member on a drop list, and the app does not read uk_collapse — so the fold
# left BBC One represented by nothing at all, and the channel appeared only
# when some variant happened to escape the drop list by accident. It stopped
# escaping, and the most-watched channel in the country vanished from the
# catalogue. The tile names one feed to represent the channel; that feed is
# not a duplicate of anything.
_uk_primaries = {t['primary'] for t in uk_collapse.values()}
uk_locals_drop = [x for x in uk_locals_drop if x not in _uk_primaries]


# ------------------------------------------------------------ AFR, shown as DSTV
# Keep the DStv bundle plus the Ghanaian channels out of Africa VIP; drop the
# rest (francophone West African locals, Somali, Ethiopian, Azam, Canal+,
# Mauritius). Then drop anything already carried under US or UK.
AFR_LABEL = "DSTV"
AFR_KEEP_CATEGORY = re.compile(r'DSTV', re.I)
AFR_KEEP_PREFIX   = re.compile(r'^GHA\s*:', re.I)      # Ghana, from Africa VIP
AFR_KEEP_NAME     = re.compile(r'SUPER\s?SPORT', re.I)  # SuperSport, wherever it sits
AFR_GENRE = [
    ('NEWS',        r'\bNEWS\b|\bAL ?JAZEERA\b|\bBLOOMBERG\b|\bCGTN\b|\bCNBC\b'
                    r'|\bSABC NEWS\b|\bNEWZROOM\b|\bRUSSIA TODAY\b|\bNDTV\b|\bPARLIAMENT'),
    ('KIDS',        r'\bKIDS\b|\bCARTOON\b|\bJUNIOR\b|\bNICK|\bBOOMERANG\b'),
    ('SPORTS',      r'SUPER\s?SPORT|\bSPORT|\bTELLYTRACK\b|\bRUGBY\b|\bCRICKET\b|\bPSL\b'),
    ('MOVIES',      r'\bMOVIE|\bCINEMA|\bFILM|\bEMOVIES\b|\bEPIC\b|\bM-?NET\b|\bSTAR LIFE\b'),
    ('MUSIC',       r'\bMTV\b|\bMUSIC\b|\bTRACE\b|\bDUMISA\b|\bSPICE\b'),
    ('DOCUMENTARY', r'\bDOCU|\bNAT ?GEO|\bDISCOVERY\b|\bHISTORY\b|\bREAL TIME\b'
                    r'|\bTRAVEL\b|\bHOME CHANNEL\b|\bMINDSET\b'),
]
_catname = {c['category_id']: asc(c['category_name'])
            for c in json.load(open('get_live_categories.json'))}

def _afr_section(n):
    u = asc(n).upper()
    for k, p in AFR_GENRE:
        if re.search(p, u): return k
    return 'ENTERTAINMENT'

_usuk_keys = set()
for st in ls:
    c = cat_live.get(str(st.get('category_id')))
    if c and c['region'] in ('US', 'UK'):
        _usuk_keys.add(channel_key(st['name']))

afr_drop, afr_assign, afr_dupes = [], {}, []
for st in ls:
    c = cat_live.get(str(st.get('category_id')))
    if not c or c['region'] != 'AFR': continue
    cat = _catname.get(str(st.get('category_id')), '')
    keep = (bool(AFR_KEEP_CATEGORY.search(cat))
            or bool(AFR_KEEP_PREFIX.match(asc(st['name'])))
            or bool(AFR_KEEP_NAME.search(asc(st['name']))))
    if not keep:
        afr_drop.append(st['stream_id']); continue
    if channel_key(st['name']) in _usuk_keys:
        afr_dupes.append(st['stream_id']); afr_drop.append(st['stream_id']); continue
    afr_assign[str(st['stream_id'])] = {"section": _afr_section(st['name'])}

# collapse ran before this filter, so sweep the tiles it made
for _key in [k for k, t in collapse.items() if t['region'] == 'AFR']:
    _t = collapse[_key]
    _keep = [sid for sid in _t['sources'] if str(sid) in afr_assign]
    if not _keep:
        afr_drop.extend(_t['sources']); del collapse[_key]; continue
    _t['sources'] = _keep
    _t['primary'] = _keep[0]
    _t['section'] = afr_assign[str(_keep[0])]['section']
    afr_drop.extend(sid for sid in _t['sources'] if str(sid) not in afr_assign)

# anything that resolves to AFR but is not DStv/Ghana leaks in via the AF: alias
# on the worldwide sport bundles - cut it, the region is DStv-only now.
for st in ls:
    sid = st['stream_id']
    if str(sid) in afr_assign or sid in afr_drop: continue
    if _eff_region(st) == 'AFR':
        afr_drop.append(sid)
for _key in [k for k, t in collapse.items()
             if t['region'] == 'AFR' and str(t['primary']) not in afr_assign]:
    afr_drop.extend(collapse[_key]['sources']); del collapse[_key]

# ------------------------------------------------- per-channel section override
# A channel inside a mixed bundle (DirecTV, Roku, a locals pack) inherits that
# bundle's section, so FOX SPORTS 1 lands in Entertainment. The channel's own
# name is more reliable than the category it was shipped in.
# Note the glued forms - \bSPORT never matches SUPERSPORT or EUROSPORT.
NAME_SECTION = [
    # Sports: brands and league feeds, not just the word "sport". \bESPN\b
    # never matched "Espnews", and MSG / ACC / Big Ten carry no sport word.
    ('SPORTS', r'(?:EURO|MOTOR|SUPER|MOTO)\s?SPORTS?\b|\bSPORTS?\b|\bESPN'
               r'|\bMSG\b|\bMSGSN\b|\bACC NETWORK\b|\bBIG ?(?:TEN|12)\b|\bSEC NETWORK\b'
               r'|\bBALLY\b|\bALTITUDE\b|\bMARQUEE\b|\bTUDN\b|\bGOLAZO\b|\bWILLOW\b'
               r'|\bBEIN\b|\bDAZN\b|\bSTADIUM\b|\bDRAFTKINGS\b|\bFANDUEL\b|\bTVG\b'
               r'|\bPOKERGO\b|\bFUEL ?TV\b|\bMAV ?TV\b|\bRACER\b|\bRACING\b|\bTNA\b'
               r'|\bNBA\b|\bNFL\b|\bNHL\b|\bMLB\b|\bWNBA\b|\bNCAA\b|\bGOLF\b'
               r'|\bTENNIS\b|\bRUGBY\b|\bCRICKET\b|\bUFC\b|\bWWE\b|\bBOXING\b'),
    # News: "Newsmax" and "Espnews" have no word boundary after the stem, so
    # \bNEWS\b misses them; business and weather channels are news too.
    ('NEWS',   r'\bNEWS\b|\bNEWSMAX\b|\bNEWSNATION\b|\bCNN\b|\bMSNBC\b|\bHLN\b'
               r'|\bBLOOMBERG\b|\bAL ?JAZEERA\b|\bCGTN\b|\bEURONEWS\b|\bC-?SPAN\b'
               r'|\bCNBC\b|\bFOX BUSINESS\b|\bYAHOO FINANCE\b|\bCHEDDAR\b'
               r'|\bACCUWEATHER\b|\bWEATHER (?:CHANNEL|NETWORK)\b|\bSCRIPPS\b'
               r"|\bREAL AMERICA'?S VOICE\b|\bONE AMERICA\b|\bINFOWARS\b"),
    ('MUSIC',  r'\bMUSIC CHOICE\b|\bMTV\b|\bVH1\b|\bCMT\b|\bREVOLT\b|\bTRACE\b'
               r'|\bBET (?:JAMS|SOUL)\b|\bSOUNDCITY\b|\bCLUBLAND\b|\bKISS ?TV\b'),
    ('KIDS',   r'\bKIDS\b|\bCARTOON\b|\bNICKELODEON\b|\bNICK ?JR\b|\bTEENNICK\b'
               r'|\bBOOMERANG\b|\bCBEEBIES\b|\bCBBC\b|\bDISNEY (?:JR|JUNIOR)\b'),
    ('DOCUMENTARY', r'\bDISCOVERY\b|\bNAT ?GEO\b|\bNATIONAL GEOGRAPHIC\b|\bHISTORY\b'
               r'|\bSMITHSONIAN\b|\bANIMAL PLANET\b|\bCURIOSITY\b|\bMILITARY HISTORY\b'
               r'|\bSCIENCE\b|\bDOCUMENTARY\b'),
]
# a local affiliate that never reached the metro fold is not entertainment
STRAY_AFFILIATE = re.compile(
    r'\b[WK][A-Z]{2,3}\b.*\b(SAN FRANCISCO|NEW YORK|CHICAGO|BOSTON|DALLAS|HOUSTON|ATLANTA'
    r'|PHOENIX|SEATTLE|PHILADELPHIA|WASHINGTON|LOS ANGELES|MIAMI|DENVER|DETROIT|TAMPA'
    r'|MINNEAPOLIS|ORLANDO|CLEVELAND|SACRAMENTO|LITTLE ROCK|WHEELING|WATERBURY)\b'
    # The trailing token must be call-sign shaped ([WK]xxx), not any four
    # capitals: [A-Z]{4} matched "FOX NEWS" / "CBS NEWS" and filed the
    # national news networks under LOCALS before the NEWS rule could run.
    r'|^(?:ABC|CBS|NBC|FOX|CW|PBS|MYTV|MNT|TMO|UNIVISION|TELEMUNDO)[- ][WK][A-Z]{2,3}$', re.I)
# residue the provider leaves behind
NAME_JUNK = re.compile(r'^[A-Z]{2}\|', re.I)          # "It| 20THCENTURYFOX"
NOT_CALL = {'WEST','WITH','WILL','WHAT','WHEN','WIDE','WILD','WIND','WING','WOLF','WOOD',
            'WORD','WORK','WAVE','WALK','WALL','WANT','WARM','WARS','WASH','WEEK','WIFE',
            'KIDS','KING','KIND','KEEP','KICK','KILL','KISS','KIWI','KNOW','WATCH','WHITE'}
CALLSIGN_ANY = re.compile(r'\b([WK][A-Z]{2,3})\b')
NETWORK_WORD = re.compile(r'\b(ABC|CBS|NBC|FOX|CW|PBS|MYTV|MNT|TMO|IND|UNIVISION|TELEMUNDO)\b')
TRAIL_WORD   = re.compile(r'\s+(TV|CHANNEL|NETWORK|ENTERTAINMENT)$', re.I)

OVERRIDABLE = {'ENTERTAINMENT', 'LOCALS'}
_entertainment_fixes = collections.Counter()
name_section = {}
name_junk_drop = []
for st in ls:
    c = cat_live.get(str(st.get('category_id')))
    if not c or c['section'] not in OVERRIDABLE: continue
    n = asc(st['name'])
    body = re.sub(r'^[A-Za-z0-9]{2,5}\s*[:;,]\s*', '', n)
    if NAME_JUNK.match(body):
        name_junk_drop.append(st['stream_id']); _entertainment_fixes['junk'] += 1; continue
    if c['section'] == 'ENTERTAINMENT':
        calls = [x for x in CALLSIGN_ANY.findall(body.upper()) if x not in NOT_CALL]
        if STRAY_AFFILIATE.search(body) or (calls and NETWORK_WORD.search(body.upper())):
            name_section[str(st['stream_id'])] = 'LOCALS'
            _entertainment_fixes['-> LOCALS'] += 1; continue
    up = n.upper()
    for target, pat in NAME_SECTION:
        if re.search(pat, up):
            if target != c['section']:
                name_section[str(st['stream_id'])] = target
                _entertainment_fixes['-> ' + target] += 1
            break

# SD feeds are dropped wholesale at ingest now (sd_all_drop, beside the DGO
# drop) — fallback slots included, and measured-SD liars with them when a
# probe run has landed. Only the group index survives from the old pass here.
_in_group = {sid for t in collapse.values() for sid in t['sources']}

# ------------------------------------------------- exact duplicates left over
_seen, exact_dupe_drop = {}, []
_QN = re.compile(r'\b(HD|SD|FHD|UHD|4K|8K|3840P|1080P|720P|HEVC|H265|VIP|RAW)\b', re.I)
_PN = re.compile(r'^[A-Z0-9]{2,5}\s*:\s*')
# A stream already condemned must never be the keeper: with "GO: A&E" first
# in panel order, first-wins kept it, the DGO drop then removed it, and the
# good "US: A&E HD" stayed condemned as its duplicate.
_gone = junkset | set(dropped_region) | set(go_drop) | set(sd_all_drop) \
        | set(religion_drop) | set(telemundo_drop) | set(rsn_drop) | set(ca_drop) \
           | set(us_news_drop) \
        | set(locals_dropped) | set(locals_extra_drop)
for st in ls:
    sid = st['stream_id']
    # collapse sources are deliberate fallbacks, not duplicate tiles
    if sid in _in_group: continue
    if sid in _gone: continue
    c = cat_live.get(str(st.get('category_id')))
    if not c: continue
    k = (re.sub(r'[^a-z0-9+]', '', _QN.sub('', _PN.sub('', asc(st['name']))).lower()), c['region'])
    if not k[0]: continue
    if k in _seen: exact_dupe_drop.append(sid)
    else: _seen[k] = sid

# ------------------------------------------------- show loops filed as Movies
# "US| CINEMA TV SHOWS" holds 18 real channels (the CM * feeds) and ~290 channels
# named after a single programme - FRIENDS 4K, THE OFFICE 4K, BREAKING BAD 4K.
# The latter are Always-On, not a movie channel.
CINEMA_CAT = re.compile(r'CINEMA TV SHOWS', re.I)
REAL_CM    = re.compile(r'^[A-Z]{2}:\s*CM\s', re.I)
show_loop = []
for st in ls:
    cat = _catname.get(str(st.get('category_id')), '')
    if not CINEMA_CAT.search(cat): continue
    if REAL_CM.match(asc(st['name'])): continue
    show_loop.append(str(st['stream_id']))
for _sid in show_loop:
    name_section[_sid] = '24/7'          # the Always-On destination

# --------------------------------------------------------------- junk sweep
# Verified against the surviving tiles. Deliberately narrow: religious channels,
# weather and French-Canadian content are real programming, not junk, and
# "ADULT SWIM" is a cartoon block - none of those are cut here.
JUNK = [
    ('timeshift', r'\+\s?\d\s*$'),                                   # "ITV 1 +1"
    ('radio',     r'\bRADIO\b|\b\d{2,3}\.\d\s*FM\b|^[A-Z]{2,4}:\s*FM$'),
    ('dead',      r'\bBACKUP\b|\bOFFLINE\b|\bNOT WORKING\b|\bDUMMY\b'),
    ('shopping',  r'\bQVC\b|\bHSN\b|\bJEWELRY TV\b|\bSHOP LC\b|\bTV MALL\b|\bSHOPPING\b'),
]
junk_sweep, junk_kind = [], collections.Counter()
for st in ls:
    n = asc(st['name'])
    for kind, pat in JUNK:
        if re.search(pat, n, re.I):
            junk_sweep.append(st['stream_id']); junk_kind[kind] += 1
            break

# misfiled, not junk - fix rather than cut
FIXES = [(r'\bADULT SWIM\b', 'ENTERTAINMENT'),      # a cartoon block, not Kids
         (r'\bWEATHER (CHANNEL|NETWORK)\b', 'NEWS')]
for st in ls:
    n = asc(st['name'])
    for pat, target in FIXES:
        if re.search(pat, n, re.I):
            name_section[str(st['stream_id'])] = target
            break

# ------------------------------------------------- per-region section allow-list
# Canada is carried for the sports and news that US feeds do not cover (TSN,
# Sportsnet, CBC/CTV news). Its entertainment, kids, movies, docs and music
# duplicate what the US region already provides.
REGION_SECTIONS = {"CA": {"SPORTS", "NEWS"}}

def _final_section(sid, default):
    sid = str(sid)
    if sid in afr_assign:  return afr_assign[sid]['section']
    if sid in uk_reassign: return uk_reassign[sid]
    if sid in name_section: return name_section[sid]
    return default

region_section_drop = []
for st in ls:
    c = cat_live.get(str(st.get('category_id')))
    if not c: continue
    reg = _eff_region(st)
    allowed = REGION_SECTIONS.get(reg)
    if not allowed: continue
    if _final_section(st['stream_id'], c['section']) not in allowed:
        region_section_drop.append(st['stream_id'])
# Only when the tile is that territory's alone. A tile records the territory
# of its primary, and territories share tiles now — so a channel whose best
# copy happened to be the Canadian one was recorded as Canadian, failed this
# restriction (Canada contributes sports and news only), and was deleted for
# every territory at once. National Geographic went that way. The per-stream
# loop above already drops the disallowed Canadian streams; a tile that keeps
# copies from elsewhere is not a Canadian tile.
def _sole_region(t, reg):
    return all(_eff_region(ls_by_id[s]) == reg for s in t['sources'] if s in ls_by_id)

for _key in [k for k, t in collapse.items()
             if REGION_SECTIONS.get(t['region'])
             and _final_section(t['primary'], t['section']) not in REGION_SECTIONS[t['region']]
             and _sole_region(t, t['region'])]:
    region_section_drop.extend(collapse[_key]['sources']); del collapse[_key]

# ============================================================ clean-surface pass
# Live TV carries only what is genuinely live. Movie channels and 24/7 loops
# duplicate the VOD and series catalogues, which are on demand and complete.
# (DROP_LIVE_SECTIONS is defined beside the collapse, which shares it.)

# PRIME: rips of channels the catalogue already carries are donor feeds —
# often the best picture the panel has (RAW 60fps) — and join their channel's
# tile instead of being swept with the FAST loops. Only PRIME donates: the
# Roku and Tubi bundles stay swept with the rest of the streaming section.
# A group with no real-section member stays sweepable.
_donor_nm = {s['stream_id']: s['name'] for s in ls}
_donor_ok = set()
for _srcs in tiles.values():
    if len(_srcs) < 2: continue
    if all(_final_section(x['id'], x['section']) in DROP_LIVE_SECTIONS for x in _srcs):
        continue
    _donor_ok.update(x['id'] for x in _srcs
                     if _final_section(x['id'], x['section']) == 'STREAMING'
                     and re.match(r'^PRIME\s*:', asc(_donor_nm.get(x['id'], '')), re.I))

# 1. a second locals bundle hides under a CITY: prefix inside Entertainment
LOCAL_PREFIX = re.compile(r'^\s*(?:CITY|PRIME)\s*:', re.I)

def _is_local_affiliate(n):
    """A local station, as opposed to a national channel under the same prefix.

    The old test was `^CITY:` followed by a network word, which saw a third of
    these at best: the same stations also arrive under PRIME:, lead with a
    bare call sign ("CITY: KTXA MIAMI"), or carry a network this list never
    had ("CITY: UNIVISION WXTV NEW YORK"). What escaped did not just clutter
    the shelf — it never joined its metro tile, so it never inherited the
    tile's call-sign logo and wore the provider's platform badge instead.

    Identification is deliberately by MARKET or a parenthesised call sign,
    never by a bare [WK]xxx token: the branch below DROPS what it does not
    place, and a bare token would read "PRIME: WWE NETWORK" as a call sign
    and delete it. A network word alone is no good either — "PRIME: ABC NEWS
    LIVE" carries one and is not a local station.
    """
    if not LOCAL_PREFIX.match(n):
        return False
    return bool(local_market(n) or CALL_ONLY.search(asc(n)))
# 2. the DirecTV bundle carries single-show FAST channels alongside real ones
GO_LOOP = re.compile(r'^GO\s*:', re.I)
# 3. BBC ONE regional variants my earlier sweep spelled too narrowly
BBC_REGIONAL = re.compile(r'^(?:UK\s*:\s*)?BBC (ONE|TWO)\b\s*\S', re.I)

# ITV ships one channel per English region — Granada, Meridian, Tyne Tees,
# Yorkshire, Border, Wales — and they carry the same schedule apart from the
# local news bulletin. Seven copies of ITV on one shelf is six too many.
#
# The English regions go and London stays, because London IS the network feed
# for everything except that bulletin. ITV2/3/4/Be are NOT regional and must
# not match: the test requires a region WORD, not merely something after
# "ITV".
ITV_REGIONAL = re.compile(
    r'^(?:UK\s*:\s*)?ITV\s*1?\s+'
    r'(GRANADA|MERIDIAN|TYNE\s*TEES|YORKSHIRE|BORDER|ANGLIA|CENTRAL|WESTCOUNTRY'
    r'|WEST|CALENDAR|UTV|ULSTER|WALES|CYMRU|SCOTLAND|STV|NORTH\s*EAST|NORTH\s*WEST'
    r'|SOUTH|EAST|MIDLANDS|BORDER\s+SCOTLAND|CHANNEL\s+ISLANDS?)\b', re.I)

clean_drop, clean_kind = [], collections.Counter()
_go_real = set()          # GO: channels that are real networks, kept
for st in ls:
    c = cat_live.get(str(st.get('category_id')))
    if not c: continue
    n = asc(st['name'])
    sid = st['stream_id']
    sec = _final_section(sid, c['section'])

    if _is_local_affiliate(n):
        mk = local_market(n)                       # reuse the metro matcher
        if mk and mk in TOP_METROS:
            net = network_of(n)
            if net:
                _by[(mk, net)].append((sid, n))    # fold into the metro tile
                clean_kind['city_locals_folded'] += 1
                continue
        clean_drop.append(sid); clean_kind['city_locals_dropped'] += 1; continue

    if BBC_REGIONAL.match(n) and 'LONDON' not in n.upper() and 'ENGLAND' not in n.upper():
        clean_drop.append(sid); clean_kind['bbc_regional'] += 1; continue

    if ITV_REGIONAL.match(n) and 'LONDON' not in n.upper():
        clean_drop.append(sid); clean_kind['itv_regional'] += 1; continue

    if sec in DROP_LIVE_SECTIONS:
        if sid in _donor_ok:
            clean_kind['streaming_donor_kept'] += 1; continue
        clean_drop.append(sid); clean_kind['redundant_section'] += 1; continue

# rebuild the metro tiles now that CITY: affiliates joined them
metro_tiles.clear()
for (mk, net), items in _by.items():
    items.sort(key=lambda x: _quality_rank(x[1], mk, net))
    metro_tiles[f"{mk}|{net}"] = {
        "metro": mk, "network": net, "primary": items[0][0],
        "label": items[0][1], "sources": [i[0] for i in items],
    }

def _tile_disposable(key, t):
    """True when every member sits in a swept section — a donor feed fronting
    a real channel's tile must not take the whole tile down with it."""
    members = tiles.get(tuple(key.rsplit('|', 1))) \
        or [{'id': t['primary'], 'section': t['section']}]
    return all(_final_section(x['id'], x['section']) in DROP_LIVE_SECTIONS
               for x in members)

for _key in [k for k, t in collapse.items()
             if _tile_disposable(k, t)
             or (BBC_REGIONAL.match(asc(_nm.get(t['primary'], '')))
                 and 'LONDON' not in asc(_nm.get(t['primary'], '')).upper())]:
    clean_drop.extend(collapse[_key]['sources']); del collapse[_key]

# ================================================== second-pass surface cleanup
# Found by listing the surviving tiles: timezone feeds, overflow feeds, channels
# named after one programme, and locals that never reached the metro fold.
TIMEZONE_FEED = re.compile(r'\b(EAST|WEST|PACIFIC|MOUNTAIN|CENTRAL|ATLANTIC)\b\s*(HD|SD|4K|FHD)?\s*$', re.I)
OVERFLOW_FEED = re.compile(r'\bOVERFLOW\b|\bQUAD ?VIEW\b|\bMULTI ?VIEW\b|\bEXTRA \d+\b'
                           r'|\bVAULT\b|\bALT(ERNATE)? ?\d*\b|\bMIX \d+\b', re.I)
SHOW_CHANNEL  = re.compile(
    r"AMERICA'?S GOT TALENT|BIG BROTHER|BIZARRE FOODS|ALWAYS FUNNY|AMERICAN CRIMES"
    r"|BOB ROSS|FAMILY HANDYMAN|50 CENT|DANCE MOMS|KEEPING UP|DECLASSIFIED|PRANKS"
    r"|COSMIC FRONTIERS|EARTH TOUCH|DROOL|E! KEEPING", re.I)
_NET_THEN_CALL = re.compile(
    r'^(?:CITY\s*:\s*)?(?:ABC|CBS|NBC|FOX|CW|PBS|IND|MNT|TMO)\d*\s+([WK][A-Z]{2,3})\b', re.I)
_LEADING_CALL  = re.compile(r'^([WK][A-Z]{2,3})\b')

def stray_local_call(body):
    """The call sign of a US local affiliate that reached here without a metro.

    A CALL SIGN is the evidence, never a network word on its own: this branch
    DROPS what it cannot place, and "CBS SPORTS NETWORK" carries a network
    word while being national. Three forms, all seen on this panel:

        CBS 11 DALLAS TX (KTVT) HD     parenthesised — the common one
        ABC WSB ATLANTA                network then call sign
        KTLA LOS ANGELES HD            leads with the call sign, no network

    The old test only recognised the middle form, so the affiliates that
    define a market — KTVT Dallas, WMAQ Chicago, KTLA Los Angeles — never
    reached the fold and sat loose beside the very tiles they belong in.
    """
    mm = CALL_ONLY.search(body)
    if mm: return mm.group(1).split('-')[0].upper()
    mm = _NET_THEN_CALL.match(body)
    if mm: return mm.group(1).upper()
    mm = _LEADING_CALL.match(asc(body).upper())
    if mm and mm.group(1) in CALLSIGN_MARKET: return mm.group(1)
    return None

pass2_drop, pass2_kind = [], collections.Counter()
for st in ls:
    sid = st['stream_id']
    if sid in clean_drop: continue
    c = cat_live.get(str(st.get('category_id')))
    if not c: continue
    n = asc(st['name'])
    body = re.sub(r'^[A-Z0-9]{2,5}(?:\s+[A-Z0-9]{2,3})?\s*:\s*', '', n)
    call = stray_local_call(body)
    if call:
        mk = local_market(body) or CALLSIGN_MARKET.get(call)
        # A station with no network word still belongs to its market — KTLA
        # is Los Angeles' CW whether or not the name says so. Keyed by its
        # call sign, it gets a tile of its own in the right metro instead of
        # being thrown away for failing to name a network.
        net = network_of(body) or call
        if mk and mk in TOP_METROS:
            _by[(mk, net)].append((sid, n))
            pass2_kind['stray_local_folded'] += 1
        else:
            pass2_drop.append(sid); pass2_kind['stray_local_dropped'] += 1
        continue
    for kind, rx in (('timezone', TIMEZONE_FEED), ('overflow', OVERFLOW_FEED),
                     ('show_channel', SHOW_CHANNEL)):
        if rx.search(body):
            pass2_drop.append(sid); pass2_kind[kind] += 1
            break

metro_tiles.clear()
for (mk, net), items in _by.items():
    items.sort(key=lambda x: _quality_rank(x[1], mk, net))
    metro_tiles[f"{mk}|{net}"] = {"metro": mk, "network": net, "primary": items[0][0],
                                  "label": items[0][1], "sources": [i[0] for i in items]}
for _key in [k for k, t in collapse.items()
             if any(rx.search(re.sub(r'^[A-Z0-9]{2,5}(?:\s+[A-Z0-9]{2,3})?\s*:\s*', '',
                                     asc(_nm.get(t['primary'], ''))))
                    for rx in (TIMEZONE_FEED, OVERFLOW_FEED, SHOW_CHANNEL))]:
    pass2_drop.extend(collapse[_key]['sources']); del collapse[_key]

# ------------------------------------------------- match replay & event feeds
# "EFL-L1 Replay 7", "PL Replay Info", "TNT Sports Event 14" are per-match feeds
# spun up around fixtures - the same shape as PPV, not browsable channels.
REPLAY_FEED = re.compile(
    r'\bREPLAY\b|\bHIGHLIGHTS\b|\bREPLAY INFO\b|\bEVENT \d+\b|\bMOSAIC\b'
    r'|\bINFO\s*$|\bBOX OFFICE\b', re.I)
replay_drop = []
for st in ls:
    sid = st['stream_id']
    if sid in clean_drop or sid in pass2_drop: continue
    body = re.sub(r'^[A-Z0-9]{2,5}(?:\s+[A-Z0-9]{2,3})?\s*:\s*', '', asc(st['name']))
    if REPLAY_FEED.search(body):
        replay_drop.append(sid)
for _key in [k for k, t in collapse.items()
             if REPLAY_FEED.search(re.sub(r'^[A-Z0-9]{2,5}(?:\s+[A-Z0-9]{2,3})?\s*:\s*',
                                          '', asc(_nm.get(t['primary'], ''))))]:
    replay_drop.extend(collapse[_key]['sources']); del collapse[_key]

# ------------------------------------------ one channel, one section everywhere
# History is Entertainment in the US feed and Documentary in the UK feed. Same
# channel, so pick one: a genre section always beats the ENTERTAINMENT catch-all.
GENRE_RANK = ['NEWS', 'SPORTS', 'KIDS', 'DOCUMENTARY', 'MUSIC', 'MOVIES']
# Same name, genuinely different channel per territory - keep each region's own
# section. US Pop is an entertainment channel; UK Pop is a kids channel.
CANON_EXEMPT = {'pop'}
_by_name = collections.defaultdict(list)
for st in ls:
    sid = st['stream_id']
    c = cat_live.get(str(st.get('category_id')))
    if not c or sid in clean_drop or sid in pass2_drop or sid in replay_drop: continue
    sec = _final_section(sid, c['section'])
    if sec in {'PPV', '24/7', 'STREAMING', 'MOVIES'}: continue
    _by_name[channel_key(st['name'])].append((sid, sec))

section_canon = {}
for k, items in _by_name.items():
    if k in CANON_EXEMPT: continue
    secs = {s for _, s in items}
    if len(secs) < 2: continue
    genre = [s for s in GENRE_RANK if s in secs]
    if not genre: continue
    win = genre[0]
    for sid, sec in items:
        if sec != win:
            section_canon[str(sid)] = win
for sid, win in section_canon.items():
    name_section[sid] = win
# explicit corrections win over every rule above
FINAL_OVERRIDE = {'fuse': 'MUSIC', 'fusemusic': 'MUSIC', 'qvc': 'ENTERTAINMENT', 'qvc2': 'ENTERTAINMENT', 'lovenature': 'DOCUMENTARY',
                  'abcnewslive': 'NEWS', 'foxweather': 'NEWS', 'shoplc': 'ENTERTAINMENT',
                  'hsn': 'ENTERTAINMENT'}
for st in ls:
    k = channel_key(st['name'])
    if k in FINAL_OVERRIDE:
        name_section[str(st['stream_id'])] = FINAL_OVERRIDE[k]
        section_canon.pop(str(st['stream_id']), None)

# ---------------------------------------------------------------- live events
# PPV stays out of browse, but a single Home row can surface what is on right
# now. The manifest groups the feeds; the app resolves "live" at runtime from
# get_short_epg, showing only channels with a programme currently airing.
EVENT_GROUPS = [
    ('Soccer',     r'\bSOCCER\b|\bEPL\b|\bPREMIER LEAGUE\b|\bLA ?LIGA\b|\bSERIE A\b'
                   r'|\bUEFA\b|\bMLS\b|\bFIFA\b|\bLIGUE 1\b|\bCHAMPIONSHIP\b'),
    ('NFL',        r'\bNFL\b'), ('NBA', r'\bNBA\b'), ('NHL', r'\bNHL\b'),
    ('MLB',        r'\bMLB\b|\bMILB\b'), ('College', r'\bNCAA\b|\bBTN\b|\bBIG ?12\b|\bSEC\b'),
    ('Fighting',   r'\bUFC\b|\bBOXING\b|\bMATCHROOM\b|\bWWE\b|\bPPV EVENT\b'),
    ('Motorsport', r'\bF1\b|\bFORMULA\b|\bMOTOGP\b|\bNASCAR\b|\bRALLY\b|\bMXGP\b|\bSUPERCROSS\b'),
    ('Tennis',     r'\bTENNIS\b'), ('Golf', r'\bGOLF\b|\bMASTERS\b'),
    ('Rugby',      r'\bRUGBY\b|\bSUPER LEAGUE\b'), ('GAA', r'\bGAA\b|\bLOI\b|\bNIFL\b'),
    ('Cricket',    r'\bCRICKET\b'),
]
live_events = collections.defaultdict(list)
for st in ls:
    sid = st['stream_id']
    c = cat_live.get(str(st.get('category_id')))
    if not c or c['section'] != 'PPV': continue
    if SEP.match(asc(st['name'])) or asc(st['name']).count('#') >= 4: continue
    hay = (asc(st['name']) + ' ' + _catname.get(str(st.get('category_id')), '')).upper()
    for label, pat in EVENT_GROUPS:
        if re.search(pat, hay):
            live_events[label].append(sid)
            break
    else:
        live_events['Other'].append(sid)

# CSN <market> was renamed NBC Sports <market>; the provider ships both.
RSN_ALIAS = re.compile(r'^CSN\s+(BAY AREA|CALIFORNIA|CHICAGO|PHILADELPHIA|WASHINGTON|BOSTON)\b', re.I)
NBCS_ALIAS = re.compile(r'^NBC SPORTS\s+(BAY AREA|CALIFORNIA|CHICAGO|PHILADELPH\w+|WASHINGTON|BOSTON)\b', re.I)

def _rsn_market(n):
    for rx in (RSN_ALIAS, NBCS_ALIAS):
        mm = rx.match(n)
        if mm:
            mk = mm.group(1).upper()
            return 'PHILADELPHIA' if mk.startswith('PHILADELPH') else mk
    return None

# ------------------------------------------------------------- brand groups
# A sports brand ships a handful of real named channels plus a tail of numbered
# event feeds. Group them so the tail collapses behind one "more" entry instead
# of filling the row with near-identical tiles.
BRANDS = [
 {"name":"SuperSport", "region":"AFR", "section":"SPORTS",
  "match": r'\bSUPER ?SPORTS?\b',
  "alias": {"supersportpl":"supersportpremierleague", "supersportliga":"supersportlaliga"},
  "overflow": r'\b(OTT|PLAY)\s*\d+\b'},
 {"name":"Tennis Channel", "region":"US", "section":"SPORTS",
  "match": r'\bTENNIS(?: CHANNEL)?\b',
  "alias": {"tennis":"tennischannel"},
  "overflow": r'\bPLUS\s*\d+\b'},
 {"name":"MSG", "region":"US", "section":"SPORTS",
  "match": r'\bMSG(?:SN)?\b',
  "alias": {"msgsportsnet635":"msgsn", "msgsportsnet":"msgsn"},
  "overflow": r'\bZONE\s*\d+\b|\bSPORTSNET\b|\b635\b'},
 {"name":"Fubo Sports", "region":"US", "section":"SPORTS",
  "match": r'\bFUBO SPORTS?\b', "alias": {},
  "overflow": r'\bFUBO SPORTS? \d+\b'},
 {"name":"Stadium", "region":"US", "section":"SPORTS",
  "match": r'\bSTADIUM\b', "alias": {},
  "overflow": r'\bSTADIUM (?:\d+|STREAM)\b'},
 {"name":"Pac-12", "region":"US", "section":"SPORTS",
  "match": r'\bPAC ?12\b', "alias": {},
  "overflow": r'\bPAC ?12 (?!NATIONAL)\w'},
 {"name":"YES Network", "region":"US", "section":"SPORTS",
  "match": r'\bYES(?: NETWORK)?\b|\bYANKEES ENTERTAINMENT\b',
  "alias": {"yankeesentertainmentsport":"yesnetwork", "foxsportsyes":"yesnetwork",
            "yes":"yesnetwork"},
  "overflow": r'\bLATIN\b'},
 {"name":"NESN", "region":"US", "section":"SPORTS",
  "match": r'\bNESN\b', "alias": {"nesnboston":"nesn"},
  "overflow": r'\bPLUS\b'},
 {"name":"beIN Sports US", "region":"US", "section":"SPORTS",
  "match": r'\bBEIN SPORTS?\b', "alias": {},
  "overflow": r'\bBEIN SPORTS? [2-9]\b|\bXTRA\b'},
 {"name":"Sky & TNT Sports", "region":"UK", "section":"SPORTS",
  "match": r'\b(SKY|TNT) ?SPORTS?\b',
  "alias": {"skysportspl":"skysportspremierleague",
            "skysportsnewshq":"skysportsnews",
            "skysportsckreckt":"skysportscricket",
            "skysportsprimelige":"skysportspremierleague",
            "skysportsplus":"skysports"},
  # bare brand names, Sky's legacy 1/2 numbering, and TNT 6+ are event feeds
  "overflow": r'^(SKY|TNT) ?SPORTS?$|^SKY ?SPORTS? [12]$|^TNT ?SPORTS? (6|7|8|9|10)$'
              r'|\bULTIMATE\b|\bMOSAIC\b|\bEVENTS?\s+\d|\bPPV\b'},
]
TERR = re.compile(r'^(ZA|UK|US|CA|GH|NG|KE|UGA|NOW|VIP)\s+', re.I)
# must know the same tokens as QUAL above, or brand/alias keys disagree with
# the collapse keys and the same channel keys two different ways.
QUALW = re.compile(r'\b(HD|FHD|UHD|SD|4K|8K|HEVC|H265|RAW|3840P|2160P|1440P|1080P|720P|\d{0,3}FPS)\b', re.I)

def _brand_key(n, match_rx, canon, alias):
    n = re.sub(r'^[A-Za-z0-9]{2,5}\s*[:;,]\s*', '', asc(n))
    n = TERR.sub('', n)
    n = QUALW.sub('', n)
    n = re.sub(match_rx, lambda mm: mm.group(0).replace(' ', ''), n, flags=re.I)
    k = re.sub(r'[^a-z0-9]', '', n.lower())
    k = re.sub(r'sports(?=[a-z])', 'sport', k)      # supersportsrugby -> supersportrugby
    k = re.sub(r'sports$', 'sport', k)
    # aliases are written in the un-normalised form, so normalise them the same way
    alias_n = {re.sub(r'sports$', 'sport', re.sub(r'sports(?=[a-z])', 'sport', a)): b
               for a, b in alias.items()}
    k = alias_n.get(k, k)
    return re.sub(r'sports(?=[a-z])', 'sport', re.sub(r'sports$', 'sport', k))

brands_out, brand_dupe = {}, []
_dropped = set(junk) | set(dropped_region) | set(locals_dropped) | set(locals_extra_drop) \
           | set(uk_locals_drop) | set(afr_drop) | set(sd_all_drop) | set(go_drop) \
           | set(religion_drop) | set(telemundo_drop) | set(rsn_drop) | set(ca_drop) \
           | set(us_news_drop) \
           | set(exact_dupe_drop) \
           | set(junk_sweep) | set(region_section_drop) | set(clean_drop) | set(pass2_drop) \
           | set(replay_drop)
_SEPJ = re.compile(r'^[\s#=\-*_~<>|.]+$')

def _tile_ids():
    """Every surviving tile, as (primary_id, region, section)."""
    out = []
    for t in collapse.values():
        if t['primary'] in _dropped: continue
        out.append((t['primary'], t['region'], _final_section(t['primary'], t['section'])))
    inside = {s for t in collapse.values() for s in t['sources']}
    for st in ls:
        sid = st['stream_id']
        if sid in _dropped or sid in inside: continue
        c = cat_live.get(str(st.get('category_id')))
        if not c: continue
        out.append((sid, c['region'], _final_section(sid, c['section'])))
    return out

_tiles = _tile_ids()

# 3840P/2160P are 4K by another name; ranking them "unknown" made the 4K feed
# lose to an HD one and dropped the better stream. Resolution tokens beat
# codec tokens ("SD hevc" is an SD picture), NFKD folds the superscript
# markers asc() deletes, and a probed height beats every claim. Callers pass
# the RAW provider name, not asc(n).
TIER_ORDER = {'8K':0,'4320P':0,'4K':1,'3840P':1,'2160P':1,'UHD':2,'1440P':2,
              'FHD':3,'1080P':3,'HEVC':4,'H265':5,'RAW':6,'HD':7,'720P':7,None:8,'SD':9}
def _tier_rank(n, sid=None):
    m = measured_tier(sid) if sid is not None else None
    if m: return {'4K':1,'FHD':3,'HD':7,'SD':9}[m]
    u = unicodedata.normalize('NFKD', n).upper()
    for t in ('8K','4320P','4K','3840P','2160P','UHD','1440P','FHD','1080P',
              'SD','HEVC','H265','RAW','HD','720P'):
        if re.search(rf'\b{t}\b', u): return TIER_ORDER[t]
    return TIER_ORDER[None]

# CSN <market> was renamed NBC Sports <market>; the provider ships both as
# separate tiles. Fold on the tile, keeping the current name.
rsn_dupe, _rsn_seen = [], {}
for sid, reg, sec in _tiles:
    if reg != 'US': continue
    n = re.sub(r'^[A-Za-z0-9]{2,5}\s*[:;,]\s*', '', asc(ls_by_id[sid]['name']))
    mk = _rsn_market(n)
    if not mk: continue
    if mk in _rsn_seen:
        keep = _rsn_seen[mk]
        if n.upper().startswith('NBC SPORTS'):
            rsn_dupe.append(keep); _rsn_seen[mk] = sid
        else:
            rsn_dupe.append(sid)
    else:
        _rsn_seen[mk] = sid

for b in BRANDS:
    canon = b['name'].replace(' ', '')
    mrx, orx = re.compile(b['match'], re.I), re.compile(b['overflow'], re.I)
    seen, main, over, hidden_dupes = {}, [], [], []
    for sid, reg, sec in _tiles:
        if reg != b['region'] or sec != b['section']: continue
        n = asc(ls_by_id[sid]['name'])
        if not mrx.search(n) or _SEPJ.match(n) or n.count('#') >= 4: continue
        k = _brand_key(n, b['match'], canon, b['alias'])
        if k in seen:
            # was first-seen-wins: panel order chose which duplicate survived.
            # The better feed keeps the slot; the other hides behind the tile.
            keep = seen[k]
            if _tier_rank(ls_by_id[sid]['name'], sid) \
                    < _tier_rank(ls_by_id[keep]['name'], keep):
                hidden_dupes.append(keep); seen[k] = sid
            else:
                hidden_dupes.append(sid)
            continue
        seen[k] = sid
    # classify once the best of each duplicate pair is known
    for _sid in seen.values():
        n = asc(ls_by_id[_sid]['name'])
        stripped = QUALW.sub('', TERR.sub('', re.sub(r'^[A-Za-z0-9]{2,5}\s*[:;,]\s*', '', n))).strip()
        (over if orx.search(stripped) else main).append(_sid)
    # Overflow feeds are event content, not channels: send them to PPV so they
    # leave the browse row entirely and surface through Live Events instead.
    for _sid in over:
        name_section[str(_sid)] = 'PPV'
    brands_out[b['name']] = {"region": b['region'], "section": b['section'], "label": b['name'],
                             "main": main, "overflow": over, "hidden_duplicates": hidden_dupes,
                             "overflow_destination": "PPV"}
    brand_dupe.extend(hidden_dupes)


# ------------------------------------------------------- EPG + logo binding
# These were bolted on by separate scripts and every rebuild wiped them.
# They belong in the one pipeline: build the maps here or carry them forward.
def _load(path, default=None):
    try:
        with open(path) as fh: return json.load(fh)
    except Exception: return default

_epg_map  = _load('epg_map_final.json', {}) or {}
_logo_map = _load('logo_map.json', {}) or {}
# a prior manifest is the fallback so a missing index never blanks the binding
_prev = _load('manifest.json', {}) or {}
if not _epg_map:  _epg_map  = (_prev.get('epg')  or {}).get('channel_map', {})
if not _logo_map: _logo_map = (_prev.get('logo') or {}).get('channel_logo', {})

# ------------------------------------------------- "Cozi" == "Cozi TV"
# The provider ships both forms of the same channel. Fold only when the bare
# form also exists, so "Comedy TV" survives when there is no bare "Comedy".
def _bare(n):
    x = re.sub(r'^[A-Za-z0-9]{2,5}\\s*[:;,]\\s*', '', asc(n))
    x = QUALW.sub('', x).strip()
    prev = None
    while prev != x:
        prev = x; x = TRAIL_WORD.sub('', x).strip()
    return re.sub(r'[^a-z0-9]', '', x.lower())

_ent, suffix_dupe = {}, []
for st in ls:
    sid = st['stream_id']
    if sid in clean_drop or sid in pass2_drop or sid in replay_drop or sid in name_junk_drop:
        continue
    c = cat_live.get(str(st.get('category_id')))
    if not c: continue
    if _final_section(sid, c['section']) != 'ENTERTAINMENT': continue
    # Already condemned elsewhere, so it must not win this contest. Choosing
    # the shortest label made "MORE4" beat "MORE 4 4K" and "MORE 4 HEVC 4K",
    # and MORE4 was then killed on its own as SD — so a mainstream channel
    # lost every feed it had, to two rules neither of which knew about the
    # other.
    if sid in sd_all_drop or sid in go_drop or sid in religion_drop \
       or sid in telemundo_drop or sid in rsn_drop:
        continue
    k = (_bare(st['name']), c['region'])
    if not k[0]: continue
    if k in _ent:
        keep = _ent[k]
        # Shortest, plainest label wins — the original rule. Ranking on tier
        # here is a trap: a feed's advertised tier lies 87% of the time on this
        # panel, and preferring MEASURED tier instead reshuffles which sibling
        # survives into later passes, which quietly deleted BBC One, Channel 4
        # and Channel 5. Quality is already decided where it belongs, by the
        # collapse tiles' own ranking. This pass only picks a label.
        rank = len(asc(st['name']))
        rank_keep = len(asc(ls_by_id[keep]['name']))
        if rank < rank_keep:
            suffix_dupe.append(keep); _ent[k] = sid
        else:
            suffix_dupe.append(sid)
    else:
        _ent[k] = sid

# --------------------------------------------- entertainment: the main ones
# 153 real channels is still a wall. Keep the networks people name when asked
# what they watch; everything else stays reachable through search.
# Curation, not logic - this list is the judgement call, so it lives as data.
MAIN_ENTERTAINMENT = {
 'US': {
  'a&e','amc','adult swim','animal planet','bbc america','bet','bravo','cartoon network',
  'cmt','comedy central','cooking channel','discovery','discovery family','discovery life',
  'destination america','disney channel','e!','food network','freeform','fx','fxm',
  'fx movie channel','fyi','gsn','hallmark','hallmark drama','hgtv','history','ifc',
  'investigation discovery','lifetime','lifetime movie network','logo','mgm','motortrend',
  'national geographic','nickelodeon','outdoor channel','own','oxygen','paramount','pbs',
  'pop','reelz','smithsonian','sundance','syfy','tbs','tcm','tlc','tnt','travel channel',
  'trutv','tv land','tv one','usa network','viceland','we tv','wgn','oxygen true crime',
  'antenna tv','bounce tv','cozi','get','grit','laff','metv','comet','charge','dabl',
 },
 'UK': {
  'bbc one','bbc two','bbc three','bbc four','bbc scotland','bbc alba','bbc news',
  'itv','itv 1','itv 2','itv 3','itv 4','itv be','itvx',
  'channel 4','channel 5','4seven','e4','more4','film4','5 star','5 usa',
  '5 action','5 select','5select','5action',
  # Real broadcasters the list simply never named, each of which was being
  # deleted for the crime of not appearing on it.
  'bbc brit','bbc earth','pbs america','talking pictures','together tv',
  'sky atlantic','sky max',
  'sky showcase','sky witness','sky arts','sky comedy','sky crime','sky documentaries',
  'sky history','sky nature','sky replay','sky sci-fi','alibi','dave','drama','gold',
  'w','yesterday','eden','crime and investigation','discovery','comedy central','mtv',
  'paramount','syfy','universal tv','really','quest','quest red','blaze','legend','together',
 },
}
def _ent_key(n):
    x = re.sub(r'^[A-Za-z0-9]{2,5}\s*[:;,]\s*', '', asc(n))
    x = NBC_FAMILY.sub('', x)
    x = QUALW.sub('', x)
    x = re.sub(r'\s*\([^)]*\)', '', x)                 # "(Awe)", "(WZME) (SP)"
    x = re.sub(r'\s+(TV|CHANNEL|NETWORK|ENTERTAINMENT)$', '', x, flags=re.I).strip()
    # A digit glued to the name or spaced off it is the same channel: the
    # panel writes "FILM 4" and the list below writes "film4", and the two
    # never met — so Film4 was trimmed off the shelf as an unlisted channel.
    x = re.sub(r'(?<=[a-z])\s+(?=\d)', '', re.sub(r'\s+', ' ', x).strip().lower())
    return x

def _glue(x):
    """"film 4" and "film4" are one channel. Applied to BOTH the key and the
    allowed names — normalising only the key turned "channel 4" into
    "channel4" while the list still said "channel 4", which deleted Channel 4
    and Channel 5 outright."""
    return re.sub(r'(?<=[a-z])\s+(?=\d)', '', x)

def _allowed(key, allow):
    """Exact, or the allowed name followed by a word boundary: "bbc one london"
    is BBC One with a regional suffix, "bbc one" is the channel we listed."""
    allow = {_glue(a) for a in allow} | set(allow)
    key = _glue(key)
    if key in allow: return True
    for a in allow:
        if key.startswith(a) and (len(key) == len(a) or not key[len(a)].isalnum()):
            return True
    return False

ent_trim, ent_kept = [], collections.Counter()
for st in ls:
    sid = st['stream_id']
    if sid in clean_drop or sid in pass2_drop or sid in replay_drop \
       or sid in name_junk_drop or sid in suffix_dupe: continue
    c = cat_live.get(str(st.get('category_id')))
    if not c: continue
    reg = 'AFR' if str(sid) in afr_assign else c['region']
    allow = MAIN_ENTERTAINMENT.get(reg)
    if not allow: continue                              # DSTV/CA untouched
    if _final_section(sid, c['section']) != 'ENTERTAINMENT': continue
    if _allowed(_ent_key(st['name']), allow): ent_kept[reg] += 1
    else: ent_trim.append(sid)

# ------------------------------------------------------- news: the main ones
# "\bNEWS\b" swept in every local station whose name contains the word, plus
# the CSN regional sports networks. Route those out, then keep the nationals.
LOCAL_NEWS = re.compile(
    r'\b(?:ABC|CBS|NBC|FOX|CW|MYTV|PBS)\s*\d{1,3}\b'        # "CBS 11 Dallas"
    # "7NEWS", "News 12" — but "ABC NEWS 2" is a numbered national feed,
    # not a local station, so a network name before NEWS opts out.
    r'|\b\d{1,2}\s?NEWS\b|(?<!ABC\s)(?<!CBS\s)(?<!NBC\s)(?<!FOX\s)\bNEWS\s?\d{1,2}\b'
    r'|\([WK][A-Z]{2,3}(?:-[A-Z]{2})?\)'                      # "(WHDH)"
    r'|\bSPECTRUM (?:NEWS|BAY NEWS)\b'
    r'|\b[WK][A-Z]{2,3}\s+\d{1,2}\s+NEWS\b', re.I)
REGIONAL_SPORT = re.compile(r'\bCSN\b|\bNBC ?SN\b|\bFANDUEL\b|\bSPECTRUM SPORTSNET\b'
                            r'|\bALTITUDE\b|\bMARQUEE\b|\bBALLY\b', re.I)
MAIN_NEWS = {
 'US': {'abc news','cbs news','nbc news','fox news','cnn','msnbc','hln','cnbc','cnbc world',
        'fox business network','bloomberg','newsmax','news nation','newsnation','one american news network',
        'c-span','c-span 1','c-span 2','c-span 3','al jazeera english','bbc world news',
        'cheddar news','the weather channel','accuweather','yahoo finance','trt world',
        'i24 news','rt news','real america\'s voice','the first','scripps news','necn',
        # both carry a SECTION_OVERRIDE to NEWS — trimming them here left
        # their tiles declaring dead primaries
        'fox weather','euronews','livenow from fox'},
}
news_trim, news_fix = [], collections.Counter()
for st in ls:
    sid = st['stream_id']
    if sid in clean_drop or sid in pass2_drop or sid in replay_drop \
       or sid in name_junk_drop or sid in suffix_dupe or sid in ent_trim: continue
    c = cat_live.get(str(st.get('category_id')))
    if not c: continue
    reg = 'AFR' if str(sid) in afr_assign else c['region']
    if _final_section(sid, c['section']) != 'NEWS': continue
    body = re.sub(r'^[A-Za-z0-9]{2,5}\s*[:;,]\s*', '', asc(st['name']))
    if REGIONAL_SPORT.search(body):
        name_section[str(sid)] = 'SPORTS'; news_fix['-> SPORTS'] += 1; continue
    if LOCAL_NEWS.search(body):
        name_section[str(sid)] = 'LOCALS'; news_fix['-> LOCALS'] += 1; continue
    allow = MAIN_NEWS.get(reg)
    if allow and not _allowed(_ent_key(st['name']), allow):
        news_trim.append(sid); news_fix['trimmed'] += 1

# ------------------------------------------- explicit channel aliases (curation)
# The pattern rules cannot tell "MSG 2" (a real second network) from
# "Nesn Boston" (the same RSN twice). These five are hand-verified.
CHANNEL_ALIAS = {
    'espnews': 'espnnews', 'espnnews': 'espnnews',
    'nesnboston': 'nesn', 'nesn': 'nesn',
    'yesnetwork': 'yesnetwork', 'yankeesentertainmentsports': 'yesnetwork',
    'yankeesentertainmentsport': 'yesnetwork', 'foxsportsyes': 'yesnetwork',
}
# region corrections: these are not US channels
REGION_FIX = [(re.compile(r'^SUPER ?SPORT', re.I), 'AFR'),
              (re.compile(r'^TSN\b', re.I), 'CA'),
              (re.compile(r'^NBC NEWS NOW\b', re.I), 'US')]
PAREN_PFX = re.compile(r'^\([A-Z0-9]{1,4}\)\s*')     # "(GT) Yes Network"

def _alias_key(n):
    x = re.sub(r'^[A-Za-z0-9]{2,5}\s*[:;,]\s*', '', asc(n))
    x = PAREN_PFX.sub('', x)
    x = QUALW.sub('', x)
    k = re.sub(r'[^a-z0-9]', '', x.lower())
    return CHANNEL_ALIAS.get(k)

alias_dupe, _alias_best, region_fix = [], {}, {}
for st in ls:
    sid = st['stream_id']
    if sid in clean_drop or sid in pass2_drop or sid in replay_drop or sid in rsn_dupe:
        continue
    c = cat_live.get(str(st.get('category_id')))
    if not c: continue
    n = asc(st['name'])
    for rx, reg in REGION_FIX:                       # wrong-region corrections
        if rx.match(re.sub(r'^[A-Za-z0-9]{2,5}\s*[:;,]\s*', '', n)):
            region_fix[str(sid)] = reg; break
    # A tier is not a territory. These categories are filed "4K"/"8K", and only
    # this pass can resolve them — from the name prefix, and from where the rest
    # of the channel lives. RECORD the answer: the app cannot redo it, and a
    # survivor left with a tier for a region has no shelf to appear on.
    if str(sid) not in region_fix and c['region'] in TIER:
        _eff = _eff_region(st)
        if _eff in KEEP_REGIONS:
            region_fix[str(sid)] = _eff
    k = _alias_key(n)
    if not k: continue
    if k in _alias_best:                             # keep the best tier
        keep = _alias_best[k]
        if _tier_rank(st['name'], sid) < _tier_rank(ls_by_id[keep]['name'], keep):
            alias_dupe.append(keep); _alias_best[k] = sid
        else:
            alias_dupe.append(sid)
    else:
        _alias_best[k] = sid

# ------------------------------------------------ fewer rows on a 10-foot UI
# Seven top-level sections is a lot of d-pad travel. Documentary, Music and
# Kids are all entertainment by any viewer's reckoning, and each was a thin row
# standing between the viewer and the next real one. Kids used to survive as
# its own row wherever a region had five or more of them, on the theory that
# people navigate to it deliberately — but a row that appears in some regions
# and not others is worse than one that never appears at all.
SECTION_MERGE = {
    'DOCUMENTARY': 'ENTERTAINMENT',
    'MUSIC': 'ENTERTAINMENT',
    'KIDS': 'ENTERTAINMENT',
}

_pre = collections.Counter()
for sid, reg, sec in _tile_ids():
    if reg in KEEP_REGIONS: _pre[(reg, sec)] += 1

section_merge_map = {}
for (reg, sec), n in _pre.items():
    if sec in SECTION_MERGE:
        section_merge_map[f"{reg}|{sec}"] = SECTION_MERGE[sec]

merged_section = {}
for sid, reg, sec in _tile_ids():
    # SECTION_MERGE applies everywhere, so fall back to it directly. Keying
    # only on the region|section pair meant a section that existed in one
    # territory and not another kept its own row for the stragglers — four
    # Canadian channels and one African one were enough to put Kids,
    # Documentary and Music back in the strip beside Entertainment.
    tgt = section_merge_map.get(f"{reg}|{sec}") or SECTION_MERGE.get(sec)
    if tgt: merged_section[str(sid)] = tgt

# --------------------------------------- US affiliates mislabelled as non-US
# "UK: LA KATC ABC 3" is the ABC affiliate in Lafayette, Louisiana wearing a UK
# prefix. A US call sign beside a US network is a US local wherever the provider
# filed it; outside the ten metros it goes the same way as every other one.
US_STATE = (r'AL|AK|AZ|AR|CA|CO|CT|DE|FL|GA|HI|ID|IL|IN|IA|KS|KY|LA|ME|MD|MA|MI|MN'
            r'|MS|MO|MT|NE|NV|NH|NJ|NM|NY|NC|ND|OH|OK|OR|PA|RI|SC|SD|TN|TX|UT|VT'
            r'|VA|WA|WV|WI|WY')
FOREIGN_US_LOCAL = re.compile(
    rf'(?:\b(?:{US_STATE})\b.*)?\b[WK][A-Z]{{2,3}}\b.*\b(ABC|CBS|NBC|FOX|CW|PBS|MYTV|MNT)\b'
    rf'|\b(ABC|CBS|NBC|FOX|CW|PBS|MYTV|MNT)\b.*\b[WK][A-Z]{{2,3}}\b')
misfiled_local = []
for st in ls:
    sid = st['stream_id']
    if sid in clean_drop or sid in pass2_drop or sid in replay_drop or sid in alias_dupe:
        continue
    c = cat_live.get(str(st.get('category_id')))
    if not c or c['region'] in ('US', 'OTHER'): continue
    body = re.sub(r'^[A-Za-z0-9]{2,5}\s*[:;,]\s*', '', asc(st['name']))
    calls = [x for x in CALLSIGN_ANY.findall(body.upper()) if x not in NOT_CALL]
    if calls and FOREIGN_US_LOCAL.search(body.upper()):
        # a US local outside the ten metros we serve
        if not (local_market(body) or '') in TOP_METROS:
            misfiled_local.append(sid)

# movie genre, when the enrichment crawl has landed
vod_meta = json.load(open('vod_meta.json')) if os.path.exists('vod_meta.json') else {}
genres = collections.Counter()
for mv in vod_meta.values():
    for g in re.split(r'[,/&]', mv.get('genre','')):
        g = canon_genre(g)
        if g: genres[g] += 1
GENRE_MIN = 8                                    # fold long-tail spellings away
vocab = sorted(g for g, n in genres.items() if n >= GENRE_MIN)
gidx  = {g: i for i, g in enumerate(vocab)}
movie_genres, movie_year = {}, {}
for sid, mv in vod_meta.items():
    ids = sorted({gidx[canon_genre(g)] for g in re.split(r'[,/&]', mv.get('genre',''))
                  if canon_genre(g) in gidx})
    if ids: movie_genres[sid] = ids
    rd = (mv.get('releasedate') or '')[:4]
    if rd.isdigit(): movie_year[sid] = int(rd)
# series genre vocabulary comes free off the series records
sgen = collections.Counter()
for sr in ser:
    for g in re.split(r'[,/&]', sr.get('genre') or ''):
        g = canon_genre(g)
        if g: sgen[g] += 1

# ============================================================== movies & series
# The live passes never touched VOD. Same three jobs: fold duplicates keeping
# the best source, clean the display names, give series real sections.
VOD_PFX   = re.compile(r'^[A-Z0-9+]{1,5}\s*-\s*')          # "NF - ", "4K-TOP - "
VOD_QUAL  = re.compile(r'\b(4K|8K|UHD|FHD|HD|SD|HEVC|H265|3840P|2160P|1080P|720P'
                       r'|MULTI|MULTISUB|SUBS?|VIP|DUAL|DUBBED)\b', re.I)
VOD_CC    = re.compile(r'\s*\((?:[A-Z]{2})\)\s*$')
VOD_YEAR  = re.compile(r'\((?:19|20)\d{2}\)')
EPISODIC  = {'SPORTS_EVENTS', 'MUSIC_CONCERTS', 'FITNESS'}

def _vod_key(n):
    x = asc(n)
    for _ in range(3):
        x2 = VOD_PFX.sub('', x)
        if x2 == x: break
        x = x2
    return re.sub(r'[^a-z0-9]', '', VOD_QUAL.sub('', x).lower())

def _vod_tier(n):
    u = asc(n).upper()
    for t, r in (('8K',0),('4K',1),('3840P',1),('2160P',1),('UHD',2),('FHD',3),
                 ('1080P',3),('HEVC',4),('HD',6),('720P',6),('SD',8)):
        if re.search(rf'\b{t}\b', u): return r
    return 7

def _vod_display(n):
    x = asc(n)
    for _ in range(3):                      # "4K-TOP - " is two prefixes deep
        x2 = VOD_PFX.sub('', x)
        if x2 == x: break
        x = x2
    x = VOD_QUAL.sub('', x)
    x = VOD_CC.sub('', x)
    return re.sub(r'\s{2,}', ' ', x).strip(' -_|')

# --- movies: fold duplicate films, keep the best source ---------------------
vod_drop, _vod_best = [], {}
for st in vs:
    sid = st['stream_id']
    sec = (cat_vod.get(str(st.get('category_id'))) or {}).get('section')
    if sec in EPISODIC: continue                      # weekly shows, not dupes
    k = _vod_key(st['name'])
    if not k: continue
    if k in _vod_best:
        keep = _vod_best[k]
        if _vod_tier(st['name']) < _vod_tier(_vod_name[keep]):
            vod_drop.append(keep); _vod_best[k] = sid
        else:
            vod_drop.append(sid)
    else:
        _vod_best[k] = sid

_vd = set(vod_drop)
vod_display = {}          # only exceptions; the app applies vod_name_rules

# --- series: display names + sections ---------------------------------------
_recent_cutoff = ''
_lm = sorted((s.get('last_modified') or '') for s in ser if s.get('last_modified'))
if _lm: _recent_cutoff = _lm[int(len(_lm) * 0.85)]     # newest ~15%

_hidden_series = {i for g in dedup.values() for i in g['hide']}
series_display, series_section = {}, {}
for s in ser:
    sid = s['series_id']
    if sid in _hidden_series: continue
    _c = _vod_display(s['name'])
    if _c != asc(s['name']) and VOD_PFX.match(asc(s['name'])) is None:
        series_display[str(sid)] = _c        # rule cannot derive it; ship it
    sec = 'ALL'
    if (s.get('last_modified') or '') >= _recent_cutoff and _recent_cutoff: sec = 'NEW'
    elif float(s.get('rating_5based') or 0) >= SERIES_TOP_RATING: sec = 'TOP'
    series_section[str(sid)] = sec

# -------------------------------------------------- reconcile tiles with drops
# Per-stream passes (allowlist trims, junk sweeps, locals folds) can kill a
# tile's declared primary — or every source it has — without the collapse
# hearing about it. The app promotes past a dead primary, but the manifest
# must record what it resolved: promote the best surviving source here, and
# delete tiles nothing survives of. 112 tiles shipped declaring dead primaries.
_drop_lists = [
    ('junk', junk), ('dropped_region', dropped_region),
    ('locals_dropped', locals_dropped), ('locals_extra_drop', locals_extra_drop),
    ('uk_locals_drop', uk_locals_drop), ('afr_drop', afr_drop),
    ('sd_all_drop', sd_all_drop), ('go_drop', go_drop),
    ('religion_drop', religion_drop), ('telemundo_drop', telemundo_drop),
    ('rsn_drop', rsn_drop), ('ca_drop', ca_drop), ('us_news_drop', us_news_drop),
    ('cross_region_dupe', cross_region_dupe),
    ('exact_dupe_drop', exact_dupe_drop), ('junk_sweep', junk_sweep),
    ('region_section_drop', region_section_drop), ('clean_drop', clean_drop),
    ('pass2_drop', pass2_drop), ('replay_drop', replay_drop),
    ('name_junk_drop', name_junk_drop), ('suffix_dupe', suffix_dupe),
    ('ent_trim', ent_trim), ('news_trim', news_trim),
    ('brand_dupe', brand_dupe), ('rsn_dupe', rsn_dupe),
    ('alias_dupe', alias_dupe), ('misfiled_local', misfiled_local),
]
_all_drops = [sid for _, lst in _drop_lists for sid in lst]
# A tile's chosen primary is never a drop, whichever list caught it. The UK
# regional fold puts every member of a group on a drop list and names one to
# represent the channel — and BBC One's representative was then caught by the
# exact-duplicate pass, so the fold left the country's most-watched channel
# with no feed at all. The same guard the region drop already applies to
# collapse members, applied to the tiles this pass builds.
_all_drops = [sid for sid in _all_drops
              if sid not in {t['primary'] for t in uk_collapse.values()}]
_dropset = set(_all_drops)
dead_tiles = 0
for _tset in (collapse, metro_tiles):
    for _key in list(_tset):
        t = _tset[_key]
        alive = [s for s in t['sources'] if s not in _dropset]
        if not alive:
            del _tset[_key]; dead_tiles += 1; continue
        if t['primary'] not in _dropset: continue
        t['primary'] = alive[0]
        if 'label' in t: t['label'] = _cnm.get(alive[0], t['label'])

if os.environ.get('WHY'):
    print("WHY", os.environ['WHY'], "->",
          [nm for nm, lst in _drop_lists if int(os.environ['WHY']) in set(lst)])

# The surviving line-up, written beside this script for probe_tiers.py.
#
# Nothing reads it at runtime — it exists because "which channels actually
# survived?" used to be answered by hand, once per region, and every
# hand-rolled version missed the loose channels that belong to no tile: 97 of
# DSTV's 146 and 83 of Canada's 107 went unmeasured behind a region that had
# already been called done. This is the one place that knows the answer, so
# this is the place that writes it down.
_folded = {s for _tset in (collapse, metro_tiles, uk_collapse)
           for t in _tset.values() for s in t['sources'] if s != t['primary']}
kept_live = [
    {"id": s['stream_id'], "name": _cnm.get(s['stream_id'], s['name']),
     "region": _eff_region(s),
     # So the probe can tell a channel from a PPV event slot. 6,300 of the
     # survivors are event slots on a shelf hidden by default, and probing
     # them buried the 154 real channels that had never been measured.
     "section": _final_section(
         s['stream_id'],
         (cat_live.get(str(s.get('category_id'))) or {}).get('section'))}
    for s in ls
    if s['stream_id'] not in _dropset and s['stream_id'] not in _folded
]
with open(os.path.join(os.path.dirname(os.path.abspath(__file__)), 'kept_live.json'), 'w') as _fh:
    json.dump(kept_live, _fh, indent=1)

# ------------------------------------------------------- live sport fixtures
# Which leagues the Sport destination carries, as team names.
#
# Team names, NOT competition names, and that is the whole trick: the packs
# write the competition inconsistently and the obvious rule over-matches
# badly — "Premier League" also catches the Caribbean Premier League (cricket)
# and the DFA Premier League (Dominica). A fixture is two teams, so if either
# side is on a list the league is known for certain and no competition string
# is needed.
#
# Aliases are listed beside the full name because the packs disagree with
# themselves: "Man United" and "Manchester United", "Red Bull New York" and
# "New York Red Bulls" all appear. Matching is substring on a normalised name,
# so the shorter alias must come with the longer one.
SPORT_LEAGUES = {
 "NFL": ["Cardinals","Falcons","Ravens","Bills","Panthers","Bears","Bengals","Browns",
   "Cowboys","Broncos","Lions","Packers","Texans","Colts","Jaguars","Chiefs","Raiders",
   "Chargers","Rams","Dolphins","Vikings","Patriots","Saints","Giants","Jets","Eagles",
   "Steelers","49ers","Seahawks","Buccaneers","Titans","Commanders"],
 "NBA": ["Hawks","Celtics","Nets","Hornets","Bulls","Cavaliers","Mavericks","Nuggets",
   "Pistons","Warriors","Rockets","Pacers","Clippers","Lakers","Grizzlies","Heat","Bucks",
   "Timberwolves","Pelicans","Knicks","Thunder","Magic","76ers","Sixers","Suns",
   "Trail Blazers","Blazers","Kings","Spurs","Raptors","Jazz","Wizards"],
 "MLS": ["Atlanta United","Austin FC","Charlotte FC","Chicago Fire","FC Cincinnati",
   "Colorado Rapids","Columbus Crew","DC United","D.C. United","FC Dallas","Houston Dynamo",
   "Sporting Kansas City","LA Galaxy","LAFC","Los Angeles FC","Inter Miami","Minnesota United",
   "CF Montreal","CF Montreal","Nashville SC","New England Revolution","New York City",
   "New York Red Bulls","Red Bull New York","Orlando City","Philadelphia Union",
   "Portland Timbers","Real Salt Lake","San Diego FC","San Jose Earthquakes",
   "Seattle Sounders","St. Louis City","Toronto FC","Vancouver Whitecaps"],
 "Premier League": ["Arsenal","Aston Villa","Bournemouth","Brentford","Brighton","Burnley",
   "Chelsea","Crystal Palace","Everton","Fulham","Leeds","Liverpool","Manchester City",
   "Man City","Manchester United","Man United","Man Utd","Newcastle","Nottingham Forest",
   "Sunderland","Tottenham","Spurs","West Ham","Wolves","Wolverhampton"],
 "La Liga": ["Alaves","Athletic Club","Athletic Bilbao","Atletico Madrid","Barcelona",
   "Celta Vigo","Elche","Espanyol","Getafe","Girona","Levante","Mallorca","Osasuna",
   "Rayo Vallecano","Real Betis","Real Madrid","Real Sociedad","Sevilla","Valencia",
   "Villarreal","Real Oviedo"],
 "Serie A": ["Atalanta","Bologna","Cagliari","Como","Cremonese","Fiorentina","Genoa",
   "Inter Milan","Internazionale","Juventus","Lazio","Lecce","AC Milan","Napoli","Parma",
   "Pisa","Roma","Sassuolo","Torino","Udinese","Verona"],
 "Bundesliga": ["Augsburg","Bayer Leverkusen","Leverkusen","Bayern Munich","Bayern Munchen",
   "Borussia Dortmund","Dortmund","Borussia Monchengladbach","Gladbach","Eintracht Frankfurt",
   "Freiburg","Hamburger SV","Heidenheim","Hoffenheim","Koln","Cologne","Mainz","RB Leipzig",
   "St. Pauli","Stuttgart","Union Berlin","Werder Bremen","Wolfsburg"],
 "Ligue 1": ["Angers","Auxerre","Brest","Le Havre","Lens","Lille","Lorient","Lyon",
   "Marseille","Metz","Monaco","Nantes","Nice","Paris FC","Paris Saint-Germain","PSG",
   "Rennes","Strasbourg","Toulouse"],
 # The big-five clubs above cover most of the draw; these are the regulars from
 # everywhere else, so a Champions League night is not half empty.
 "Champions League": ["Ajax","PSV","Feyenoord","Benfica","Porto","Sporting CP","Celtic",
   "Rangers","Galatasaray","Fenerbahce","Shakhtar","Red Bull Salzburg","Club Brugge",
   "Olympiacos","Copenhagen","Slavia Prague","Sparta Prague","Dinamo Zagreb","Young Boys",
   "Bodo/Glimt","Qarabag","Union Saint-Gilloise","Pafos","Kairat"],
}

# Nicknames more than one sport answers to. These, and only these, need both
# sides recognised before a fixture counts — "Cardinals at Reds" is baseball
# and read as NFL off its first word until this list existed.
#
# Everything else may stand alone, which is what lets a cup tie through: a
# DFB-Pokal or FA Cup draw pairs a Bundesliga or Premier League side with a
# club three divisions down that no roster will ever carry, and demanding both
# sides threw the whole competition away.
SPORT_AMBIGUOUS = ["Giants", "Cardinals", "Jets", "Panthers", "Kings", "Rangers"]

# How early a fixture may appear, in minutes. An hour ahead of kick-off, which
# also covers the catalogue refresh: slot names only change when the catalogue
# is re-fetched, so a shorter cue would let a match start before it is listed.
SPORT_CUE_MINUTES = 60

manifest = {
    "manifest_version": 1,
    "generated": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec='seconds'),
    "provider": {"host": HOST, "protocol": "xtream"},
    "sections": {
        "live":   [{"key":k,"label":l,"hidden_by_default":h} for k,l,h in SECTIONS_LIVE],
        "movies": [{"key":k,"label":l,"hidden_by_default":h} for k,l,h in VOD_SECTIONS_FINAL],
    },
    "categories": {"live": cat_live, "movies": cat_vod, "series": cat_ser},
    "rules": {
        "strip_name_prefix":  PREFIX.pattern,
        "strip_country_suffix": COUNTRY.pattern,
        "separator_stream":   SEP.pattern,
        "channel_quality_words": QUAL.pattern,
        "collapse_requires_same_region": True,
        "source_tier_order": ["8K","4K","UHD","FHD","HEVC","H265","RAW","HD",None,"SD"],
        "normalize_season_ep": r"\bS(\d{1,3})\s*E(\d{1,4})\b -> S%02dE%02d",
        "strip_non_ascii_in_category_names": True,
    },
    # WHY=<stream_id> names each drop list that claimed a stream — see the
    # _drop_lists table beside the tile reconciliation above.
    "drop_stream_ids": _all_drops,
    "afr_assign": afr_assign,
    "region_fix": region_fix,
    "section_merge": section_merge_map,
    "merged_section": merged_section,
    "brands": brands_out,
    "epg": {
        "sources": [
            {"key":"repo","label":"Curated M3U Repository","priority":1,
             "base":"https://raw.githubusercontent.com/ferteque/Curated-M3U-Repository/main/"},
            {"key":"openepg","label":"Open-EPG","priority":2,
             "index":"https://www.open-epg.com/app/epgfetch.php"},
            {"key":"provider","label":"provider xmltv.php","priority":3,
             "note":"unreliable - last resort, mark low confidence"},
        ],
        "match_order": ["id","name","call","alias","provider"],
        "reject": ["dummy"],
        "channel_map": _epg_map,
    },
    "logo": {
        "repo": "tv-logo/tv-logos",
        "base": "https://raw.githubusercontent.com/tv-logo/tv-logos/main/",
        "policy": "region-compatible countries only, except known global brands",
        "fallback": ["network_brand", "provider stream_icon", "generated initials"],
        "channel_logo": _logo_map,
    },
    "brand_note": ("render each brand as one row inside its section: the named channels "
                   "first, then a single 'more' entry opening the numbered event feeds"),
    "section_canon": section_canon,
    "live_events": {
        "surface": "home_row",
        "label": "Live Events",
        "resolve": "get_short_epg",
        "note": "show only channels with a programme airing now; hide the row when empty",
        "groups": {k: v for k, v in live_events.items()},
    },
    "name_section": name_section,
    "region_labels": {"US": "United States", "UK": "United Kingdom",
                      "CA": "Canada", "AFR": AFR_LABEL},
    "uk_reassign": uk_reassign,
    "uk_collapse": uk_collapse,
    "local_market": locals_market,
    "metro_locals": metro_tiles,
    "top_metros": TOP_METROS,
    "collapse": {"live": collapse},
    "dedup": {"series": dedup},
    "genre_vocabulary": {
        "movies": vocab,
        "series": sorted(g for g, n in sgen.items() if n >= GENRE_MIN),
    },
    "vod_drop": vod_drop,
    "vod_display_name": vod_display,
    "vod_name_rules": {
        "strip_prefix": VOD_PFX.pattern, "strip_prefix_repeat": 3,
        "strip_quality": VOD_QUAL.pattern,
        "strip_trailing_country": VOD_CC.pattern,
        "note": "apply in this order, then collapse whitespace and trim ' -_|'",
    },
    "series_display_name": series_display,
    "series_section": series_section,
    "movie_genres": movie_genres,
    "movie_year": movie_year,
    "kept_regions": list(KEEP_REGIONS),   # authored order — see KEEP_REGIONS
    # These share one shelf per genre; anything else keeps its own shelf.
    "merged_regions": list(MERGED_REGIONS),
    "sport": {"leagues": SPORT_LEAGUES, "cue_minutes": SPORT_CUE_MINUTES,
              "ambiguous": SPORT_AMBIGUOUS},
    # Section-level fold, applied to whatever section a channel resolves to.
    # The per-stream merged_section map cannot cover a channel that no pass
    # enumerated, and a handful of strays were enough to reopen a shelf.
    "section_fold": dict(SECTION_MERGE),
    "region_sections": {k: sorted(v) for k, v in REGION_SECTIONS.items()},
    "destinations": LIVE_DESTINATION,
    "demote_stream_ids": timeshift,
    "stats": {
        "live_channels": len(ls), "movies": len(vs), "series_entries": len(ser),
        "series_distinct": len(groups), "dedup_groups": len(dedup),
        "dedup_needs_review": len(review), "junk_streams": len(junk),
        "live_categories": len(cat_live), "movie_categories": len(cat_vod),
        "unresolved_live_categories": len(unresolved),
        "movies_with_genre": sum(1 for m in vod_meta.values() if m.get('genre')),
        "live_tiles_after_collapse": len(tiles),
        "live_tiles_collapsed": len(live_rows) - len(tiles),
        "collapse_groups": len(collapse),
        "collapse_needs_review": len(needs_review),
        "dropped_region_streams": len(dropped_region),
        "religion_streams": len(religion_drop),
        "telemundo_streams": len(telemundo_drop),
        "regional_sport_streams": len(rsn_drop),
        "us_regional_news_streams": len(us_news_drop),
        "dstv_duplicates_dropped": len(cross_region_dupe),
        "ca_clutter_streams": len(ca_drop),
        "timeshift_demoted": len(timeshift),
        "us_locals_kept": len(locals_market),
        "us_locals_dropped": len(locals_dropped),
        "us_markets": len(set(locals_market.values())),
        "metro_local_tiles": len(metro_tiles),
        "metro_locals_folded": sum(len(v["sources"])-1 for v in metro_tiles.values()),
        "uk_locals_dropped": len(uk_locals_drop),
        "uk_reassigned": len(uk_reassign),
        "uk_regional_collapsed": sum(len(v["sources"])-1 for v in uk_collapse.values()),
        "afr_kept": len(afr_assign),
        "afr_dropped": len(afr_drop),
        "afr_usuk_duplicates": len(afr_dupes),
        "name_section_overrides": len(name_section),
        "entertainment_fixes": dict(_entertainment_fixes),
        "entertainment_suffix_dupes": len(suffix_dupe),
        "entertainment_trimmed": len(ent_trim),
        "entertainment_kept": dict(ent_kept),
        "news_fixes": dict(news_fix),
        "show_loops_moved": len(show_loop),
        "junk_swept": len(junk_sweep),
        "junk_by_kind": dict(junk_kind),
        "region_section_dropped": len(region_section_drop),
        "clean_pass_dropped": len(clean_drop),
        "clean_pass_detail": dict(clean_kind),
        "pass2_dropped": len(pass2_drop),
        "pass2_detail": dict(pass2_kind),
        "replay_feeds_dropped": len(replay_drop),
        "section_canonicalised": len(section_canon),
        "brand_groups": len(brands_out),
        "brand_dupes_dropped": len(brand_dupe),
        "rsn_dupes_dropped": len(rsn_dupe),
        "alias_dupes_dropped": len(alias_dupe),
        "region_fixed": len(region_fix),
        "misfiled_us_locals": len(misfiled_local),
        "sections_merged": len(section_merge_map),
        "tiles_resectioned": len(merged_section),
        "epg_bound": len(_epg_map),
        "logo_bound": len(_logo_map),
        "live_event_channels": sum(len(v) for v in live_events.values()),
        "live_event_groups": len(live_events),
        "sd_dropped": len(sd_all_drop),
        "dgo_dropped": len(go_drop),
        "tiers_measured": len(_probed),
        "exact_duplicates_dropped": len(exact_dupe_drop),
        "movie_genre_vocab": len(vocab),
        "movies_with_genre_ids": len(movie_genres),
        "vod_dupes_dropped": len(vod_drop),
        "vod_display_names": len(vod_display),
        "series_display_names": len(series_display),
        "series_genre_vocab": len([g for g,n in sgen.items() if n>=GENRE_MIN]),
    },
}
json.dump(manifest, open(OUT,'w'), indent=1)
sz = os.path.getsize(OUT)
print(f"wrote {OUT}  ({sz/1024:.0f} KB)")
for k,v in manifest['stats'].items(): print(f"  {k:32} {v}")
if unresolved:
    print("\nstill unresolved (defaulted to ENTERTAINMENT):")
    for cid,n,c in unresolved: print(f"   {c:>5}  id={cid} {n}")
