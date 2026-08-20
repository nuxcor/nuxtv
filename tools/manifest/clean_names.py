import re
ACRONYM = {
 'ABC','CBS','NBC','FOX','CW','PBS','BBC','ITV','CNN','MSNBC','CNBC','ESPN','TNT','TBS','AMC',
 'HBO','MTV','VH1','BET','A&E','TLC','USA','TV','UFC','NFL','NBA','NHL','MLB','MLS','PGA','WWE',
 'NCAA','CBC','CTV','TSN','RDS','LCN','SEC','ACC','BTN','FS1','FS2','NBCSN','MSG','TVA','ION',
 'PSL','GTV','SABC','CGTN','NDTV','RTP','TBN','EWTN','AFN','BFBS','DAZN','WWE','CFL','WNBA',
 'AXS','FX','FXX','FXM','E!','QVC','HSN','SYFY','IFC','WGN','WPIX','KTLA','OWN','TCM','TNT',
 'AT&T','NASA','PBS','NPR','II','III','IV','1','2','3','4','5','6','7','8','9','10','HD2',
}
LOWER = {'and','of','the','in','on','for','to','a','an','de','du','la','le','les','y'}
PREFIX = re.compile(r'^(?:[A-Za-z0-9]{2,5}(?:\s+[A-Za-z0-9]{2,3})?)\s*[:;,]\s*')
QUALITY = re.compile(r'\s*\b(FHD|UHD|HEVC|H265|RAW|4K|8K|HD|SD|3840P|1080P|720P|\d{0,3}FPS)\b', re.I)
NOISE   = re.compile(r'\s*\((?:[ADH]|PC|R|D2|D3|DT|LD)\)\s*', re.I)
SPACES  = re.compile(r'\s+')
# brands whose own styling beats title case
BRAND = [
 (r'\bSUPER ?SPORTS?\b', 'SuperSport'), (r'\bBEIN\b', 'beIN'), (r'\bE\.?TV\b', 'e.tv'),
 (r'\bITVX\b', 'ITVX'), (r'\bDSTV\b', 'DStv'), (r'\bDAZN\b', 'DAZN'),
 (r'\bSPORTSNET\b', 'Sportsnet'), (r'\bNEWZROOM\b', 'Newzroom'),
 (r'\bTELLYTRACK\b', 'TellyTrack'), (r'\bMNET\b|\bM-NET\b', 'M-Net'),
 (r'\bTVA\b', 'TVA'), (r'\bNOOVO\b', 'Noovo'), (r'\bCRUNCHYROLL\b', 'Crunchyroll'), (r'\bOTT\b', 'OTT'),
 (r'\bCBEEBIES\b', 'CBeebies'), (r'\bCBBC\b', 'CBBC'), (r'\bPSL\b', 'PSL'),
 (r'\bWWE\b', 'WWE'), (r'\bUFC\b', 'UFC'), (r'\bIPTV\b', 'IPTV'),
]

CALLSIGN = re.compile(r'^[WK][A-Z]{2,3}(-[A-Z]{2})?$')
# [WK]xxx also matches ordinary English words - these are never call signs
NOT_CALLSIGN = {
 'WEST','WITH','WILL','WHAT','WHEN','WHO','WHY','WIDE','WILD','WIND','WINE','WING','WISE',
 'WOLF','WOOD','WORD','WORK','WORLD','WAVE','WALK','WALL','WANT','WARM','WARS','WASH','WEEK',
 'WIFE','WINS','WOMAN','WOW','KIDS','KING','KIND','KEEP','KEY','KICK','KILL','KISS','KIWI',
 'KNOW','WATCH','WHITE','WHOLE','WOMEN','WORTH','WRAP','WEATHER',
}
VOWELS = set('AEIOUY')

def smart_case(word):
    bare = word.strip('()[]')
    if not bare: return word
    up = bare.upper()
    # keep as-is: known acronyms, vowel-less initialisms (BBC, CNN, TNT), call signs
    if up in ACRONYM:                        return word.replace(bare, up)
    if CALLSIGN.match(up) and up not in NOT_CALLSIGN:
                                             return word.replace(bare, up)
    if (bare.isupper() and len(bare) <= 5
            and not (set(bare) & VOWELS)):   return word.replace(bare, up)
    if any(ch.isdigit() for ch in bare):     return word
    if bare.lower() in LOWER:                return word.replace(bare, bare.lower())
    return word.replace(bare, bare[0].upper() + bare[1:].lower())

def clean(name):
    n = ''.join(c for c in name if ord(c) < 128).strip()
    n = PREFIX.sub('', n)
    n = re.sub(r'^(ZA|UK|US|CA|GH|NG|KE)\s+(?=[A-Z])', '', n)
    n = NOISE.sub(' ', n)
    n = QUALITY.sub('', n)
    n = re.sub(r'[\s&+\-|:]+$', '', SPACES.sub(' ', n)).strip(' -|:')
    parts = n.split(' ')
    out = [parts[0] and smart_case(parts[0])] + [smart_case(w) for w in parts[1:]]
    out = [w for w in out if w]
    if out: out[0] = out[0][0].upper() + out[0][1:] if out[0][0].islower() else out[0]
    result = ' '.join(out)
    for pat, styled in BRAND:
        result = re.sub(pat, styled, result, flags=re.I)
    return result
