"""Match our channels to XMLTV guide ids across three sources."""
import re, sys
sys.path.insert(0, '.')
from clean_names import clean

QW = re.compile(r'(HD|FHD|UHD|SD|4K|8K|HEVC|H265|RAW)', re.I)
# open-epg appends the country to the display name: "Sky Sports News UK"
CC = re.compile(r'(uk|us|usa|ca|za|ng|ke|gh|ie|au|nz|fr|de|es|it)$')

def nid(i):
    """An XMLTV id, normalised — country code included in what is dropped.

    That last part is a hazard, not a feature, and it is why "Sky News" bound
    skynews.au on the UK channel: strip the suffix and skynews.au and
    SkyNews.uk are the same key, so whichever the source listed first won and
    the UK channel got Australia's listings. Two of the three .au bindings in
    the map were wrong this way.

    Left as it is because the caller that consumes it is not in this
    repository, so a change here cannot be tested. Anyone re-running the match
    should compare the id's country against the channel's own region before
    accepting a hit; until then build_manifest's EPG_PIN carries the
    corrections.
    """
    i = re.sub(r'\.(uk|us|ca|za|ng|ke|ie|au|nz|gh)$', '', i, flags=re.I)
    return re.sub(r'[^a-z0-9]', '', QW.sub('', i).lower())

def nname(n, strip_cc=True):
    k = re.sub(r'[^a-z0-9]', '', clean(n).lower())
    if strip_cc:
        k2 = CC.sub('', k)
        if len(k2) >= 4: return k2
    return k

def keys(n):
    """Every form worth trying, most specific first."""
    a = nname(n, strip_cc=False)
    b = nname(n, strip_cc=True)
    out = [a]
    if b != a: out.append(b)
    return out
