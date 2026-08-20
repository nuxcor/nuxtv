#!/usr/bin/env python3
"""Clean provider cruft out of a series m3u.

  python3 clean_m3u.py IN.m3u OUT.m3u [fold|single|keep]

    fold   - strip 4K/Dolby decorations from category names, so
             "AMAZON SERIES" and "AMAZON SERIES 4K" become one group  (default)
    single - put every episode in one "TV SERIES" group
    keep   - leave category names exactly as the provider sent them
"""
import re, sys, collections

IN   = sys.argv[1]
OUT  = sys.argv[2]
MODE = sys.argv[3] if len(sys.argv) > 3 else "fold"

# provider tag glued to the front: "EN - ", "NF - ", "4K-NF - ", "D+ - "
PREFIX  = re.compile(r'^[A-Za-z0-9+]{1,4}(?:-[A-Za-z0-9+]{1,4})?\s+-\s+')
COUNTRY = re.compile(r'\s*\((?:[A-Z]{2})\)\s*$')          # trailing (US) (GB) (JP)
SXXEYY  = re.compile(r'\bS(\d{1,3})\s*E(\d{1,4})\b', re.I)

def ascii_only(s):
    """Drop the superscript decorations; keep ordinary punctuation."""
    return ''.join(ch for ch in s if ord(ch) < 128)

def clean_category(c):
    if MODE == "single":
        return "TV SERIES"
    if MODE == "keep":
        return c
    c = ascii_only(c)
    c = re.sub(r'\b3840P\b', '', c)                       # resolution noise
    c = re.sub(r'\s+', ' ', c).strip(' -')
    return c or "TV SERIES"

def clean_title(t):
    t = ascii_only(t).strip()
    t = PREFIX.sub('', t)                                  # kill "NF - "
    parts = [p.strip() for p in t.split(' - ')]
    # rebuild: <show> - SxxEyy - <episode>, dropping the country tag on the show
    if parts:
        parts[0] = COUNTRY.sub('', parts[0]).strip()
    t = ' - '.join(p for p in parts if p)
    t = SXXEYY.sub(lambda m: f"S{int(m.group(1)):02d}E{int(m.group(2)):02d}", t)
    t = re.sub(r'\s+', ' ', t).strip(' -')
    return t

n = 0
cats_before, cats_after = set(), collections.Counter()
out = ["#EXTM3U"]
with open(IN, encoding='utf-8') as f:
    for line in f:
        line = line.rstrip('\n')
        if line.startswith('#EXTINF'):
            g = re.search(r'group-title="([^"]*)"', line)
            v = re.search(r'tvg-name="([^"]*)"', line)
            raw_cat   = g.group(1) if g else 'SERIES'
            raw_title = v.group(1) if v else line.split(',', 1)[-1]
            cats_before.add(raw_cat)
            cat, title = clean_category(raw_cat), clean_title(raw_title)
            cats_after[cat] += 1
            logo = re.search(r'tvg-logo="([^"]*)"', line)
            out.append(f'#EXTINF:-1 tvg-id="" tvg-name="{title}" '
                       f'tvg-logo="{logo.group(1) if logo else ""}" '
                       f'group-title="{cat}",{title}')
            n += 1
        elif line.startswith('http'):
            out.append(line)

open(OUT, 'w', encoding='utf-8').write('\n'.join(out) + '\n')
print(f"mode={MODE}  episodes={n}")
print(f"categories: {len(cats_before)} -> {len(cats_after)}")
print(f"wrote {OUT}")
