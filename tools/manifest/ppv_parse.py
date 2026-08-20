"""PPV feeds carry their schedule in the channel name - no EPG source has them.

Six formats observed on this provider:
  A  Live | Crossfire 2026 - Day 2 | all | 8K EXCLUSIVE
  B  (FLSP 001) | flolive: 2026 Sri Lanka vs India _ Cricket (2026-08-19)
  C  US (MiLB 001) | Hudson Valley Renegades @ Jersey Shore BlueClaws (2026-08-19 13:05:00)
  D  UFC 01 : DWCS S10 | W2: POST-FIGHT PRESS CONFERENCE start:2026-08-19 03:00
  D  NO EVENT STREAMING NOW - | 8K EXCLUSIVE | US: SOCCER PPV 65
  E  US: 24/7 MET GALA 2023                      (static loop, not an event)
"""
import re

IDLE   = re.compile(r'\bNO EVENT STREAMING NOW\b|\bNO EVENT\b|\bOFF ?AIR\b', re.I)
STATUS = re.compile(r'^\s*(LIVE|NEXT|ENDED|END|REPLAY|UPCOMING|FINISHED)\s*\|', re.I)
NORM   = {'LIVE':'live','NEXT':'upcoming','UPCOMING':'upcoming',
          'ENDED':'ended','END':'ended','FINISHED':'ended','REPLAY':'replay'}
ISO    = re.compile(r'(\d{4})-(\d{2})-(\d{2})[ T](\d{1,2}):(\d{2})(?::(\d{2}))?')
ISODAY = re.compile(r'\((\d{4})-(\d{2})-(\d{2})\)')
DMY    = re.compile(r'\b(\d{1,2})-(\d{1,2})-(\d{4})\b.*?\b(\d{1,2}):(\d{2})\b')
DMON   = re.compile(r'\b(\d{1,2})\s+([A-Z][a-z]{2})\s+(\d{1,2}):(\d{2})\s*([A-Z]{2,4})?')
TEAMS  = re.compile(r'^(.+?)\s+(?:@|vs?\.?|v)\s+(.+?)$', re.I)
PREFIX = re.compile(r'^(?:[A-Z]{2}\s*)?\((?:[A-Z]{2,6}\s*\d+)\)\s*\|\s*|^\([A-Z]{2,6}\s*\d+\)\s*\|\s*')
TAIL   = re.compile(r'\s*\|\s*(?:all|8K EXCLUSIVE|[A-Z]{2}:\s*.*)$', re.I)
FIELDS = re.compile(r'\b(?:start|stop|begin|end)\s*:\s*', re.I)   # "start: stop:" residue
BRACKET= re.compile(r'^\[|\]$')
# the provider files esports under SOCCER PPV; the title is the better signal
ESPORT = re.compile(r'\b(fortnite|counter-?strike|cs ?2|valorant|dota|league of legends|lol'
                    r'|rocket league|trackmania|apex|overwatch|starcraft|crossfire|ewc|esports'
                    r'|games of the future)\b', re.I)
SPORT_HINT = [
    ('Esports',    ESPORT),
    ('Fighting',   re.compile(r'\b(ufc|boxing|bare ?knuckle|mma|bellator|wwe)\b', re.I)),
    ('Soccer',     re.compile(r'\b(fc|united|city|soccer|premier league|la ?liga|serie a|uefa)\b', re.I)),
    ('Hockey',     re.compile(r'\bhockey\b|\bnhl\b', re.I)),
    ('Cricket',    re.compile(r'\bcricket\b|\btest\b.*\bv\b', re.I)),
]

def parse(name):
    """Return an event dict, or None when the name is not an event feed."""
    n = name.strip()
    if IDLE.search(n):
        return {'status': 'idle'}
    out = {}
    m = STATUS.match(n)
    if m:
        out['status'] = NORM.get(m.group(1).upper(), 'unknown')
        n = n[m.end():]
    n = PREFIX.sub('', n)
    body = n.split('|')
    title = (body[1] if len(body) > 1 and not out.get('status') else body[0]).strip()
    rest = n

    t = ISO.search(rest)
    if t:
        out['starts'] = f"{t.group(1)}-{t.group(2)}-{t.group(3)}T{int(t.group(4)):02d}:{t.group(5)}"
    else:
        t = ISODAY.search(rest)
        if t: out['starts'] = f"{t.group(1)}-{t.group(2)}-{t.group(3)}"
        else:
            t = DMY.search(rest)
            if t:
                out['starts'] = (f"{t.group(3)}-{int(t.group(2)):02d}-{int(t.group(1)):02d}"
                                 f"T{int(t.group(4)):02d}:{t.group(5)}")
            else:
                t = DMON.search(rest)
                if t: out['starts_text'] = t.group(0)
    title = TAIL.sub('', title)
    title = ISO.sub('', ISODAY.sub('', title)).strip(' -_()|')
    title = re.sub(r'^\w+:\s*', '', title)          # "flolive: ..." -> "..."
    title = FIELDS.sub('', title)
    title = BRACKET.sub('', title).strip(' -_|[]')
    if title:
        out['title'] = title
        for label, rx in SPORT_HINT:
            if rx.search(title): out['sport'] = label; break
    v = TEAMS.match(title) if title else None
    if v:
        out['home'] = v.group(1).strip(' .')
        out['away'] = re.split(r'\s*[\(_]', v.group(2))[0].strip(' .')
    # only a real event if it carries a time or an explicit status word;
    # a bare title is just a static channel parked in the PPV section
    if 'status' not in out:
        if 'starts' in out: out['status'] = 'scheduled'
        elif 'starts_text' in out: out['status'] = 'scheduled'
        else: return None
    return out
