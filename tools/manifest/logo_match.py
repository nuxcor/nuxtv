"""Match channels to tv-logo/tv-logos artwork."""
import re, sys
sys.path.insert(0,'.')
from clean_names import clean

RAW = "https://raw.githubusercontent.com/tv-logo/tv-logos/main/"
CC   = re.compile(r'-(us|uk|ca|za|ie|ng|gh|ke|int|ae|au|nz|cz|ph|ar|de|fr|es|it|afr|lat|eur|asia|me)$')
QUAL = re.compile(r'-(hd|uhd|fhd|sd|4k|8k|hdr|ultra-hdr|hz|icon|alt|dark|light|white|black|old|new)(?=-|$)')
PLUR = [(r'\bsports\b','sport'), (r'\bnetworks\b','network'), (r'\bchannels\b','channel')]
DROP = re.compile(r'\b(tv|television|channel|network|the)\b')
CALL = re.compile(r'\b([wk][a-z]{2,3})\b')

def _plural(s):
    for pat, base in PLUR: s = re.sub(pat, base, s, flags=re.I)
    return s

def slug_keys(filename):
    """Every lookup key a logo filename should answer to."""
    s = filename.rsplit('.',1)[0].lower()
    s = CC.sub('', s)
    prev = None
    while prev != s:                      # strip repeated quality/variant tokens
        prev = s; s = QUAL.sub('', s)
    s = s.replace('-and-', 'and').replace('&', 'and')
    base = _plural(s.replace('-',' '))
    k1 = re.sub(r'[^a-z0-9]','', base)
    k2 = re.sub(r'[^a-z0-9]','', DROP.sub('', base))
    out = {k for k in (k1,k2) if len(k) >= 2}
    for c in CALL.findall(s):             # abc-7-kgo-us -> KGO
        out.add('call:'+c.upper())
    return out

def name_keys(name):
    c = _plural(clean(name).lower().replace('&', 'and'))
    k1 = re.sub(r'[^a-z0-9]','', c)
    k2 = re.sub(r'[^a-z0-9]','', DROP.sub('', c))
    out = [k for k in (k1,k2) if len(k) >= 2]
    for m in re.finditer(r'\b([WK][A-Z]{2,3})\b', clean(name)):
        out.append('call:'+m.group(1).upper())
    return out
