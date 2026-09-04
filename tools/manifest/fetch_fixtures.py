#!/usr/bin/env python3
"""The schedule, from something that knows it.

The panel's PPV slots are marketing strings, not a timetable. One match is
routinely carried by four packs writing four formats, disagreeing about the
kick-off by hours, filling the competition field with the word "all", and
listing a women's fixture under the men's club names. Every rule the parser
has for reading them is a rule about how a pack happens to write, and the
packs keep inventing new ways to write.

This takes the fixtures from ESPN's public scoreboards instead — no key, no
account — and publishes them beside the manifest. The app matches a slot to a
fixture on the club names, which the packs DO get right, and takes the
kick-off and the competition from here.

    python3 fetch_fixtures.py            # -> ../../app/src/main/assets/fixtures.json

Eight days ahead, which covers the app's cue window many times over and keeps
the file small (a few hundred fixtures).
"""
import json, os, sys, time, urllib.request
from datetime import datetime, timedelta, timezone

# Manifest league name -> ESPN's path. The names on the left are the ones the
# manifest's sport.leagues uses, because that is what the app bills a row as.
LEAGUES = {
    "NFL": "football/nfl",
    "NBA": "basketball/nba",
    "MLS": "soccer/usa.1",
    "Premier League": "soccer/eng.1",
    "La Liga": "soccer/esp.1",
    "Serie A": "soccer/ita.1",
    "Bundesliga": "soccer/ger.1",
    "Ligue 1": "soccer/fra.1",
    "Champions League": "soccer/uefa.champions",
    "Europa League": "soccer/uefa.europa",
    "Conference League": "soccer/uefa.europa.conf",
    "Carabao Cup": "soccer/eng.league_cup",
    "FA Cup": "soccer/eng.fa",
}

BASE = "https://site.api.espn.com/apis/site/v2/sports"
DAYS = 8
UA = {"User-Agent": "agoro-fixtures/1.0 (+https://github.com/nuxcor/agoro)"}


def fetch(league, path, start, end):
    """One request per league for the whole window; ESPN takes a date range."""
    url = f"{BASE}/{path}/scoreboard?dates={start}-{end}&limit=200"
    for attempt in range(3):
        try:
            req = urllib.request.Request(url, headers=UA)
            with urllib.request.urlopen(req, timeout=30) as resp:
                return json.load(resp)
        except Exception as exc:
            if attempt == 2:
                print(f"  {league}: FAILED ({exc})", file=sys.stderr)
                return None
            time.sleep(1.5 * (attempt + 1))


def sides(event):
    """Home and away, as ESPN spells them. The app normalises before matching."""
    comps = (event.get("competitions") or [{}])[0].get("competitors") or []
    home = away = None
    for c in comps:
        name = (c.get("team") or {}).get("displayName")
        if not name:
            continue
        if c.get("homeAway") == "home":
            home = name
        elif c.get("homeAway") == "away":
            away = name
    return home, away


def main():
    today = datetime.now(timezone.utc).date()
    start = today.strftime("%Y%m%d")
    end = (today + timedelta(days=DAYS)).strftime("%Y%m%d")
    dest = os.path.normpath(os.path.join(
        os.path.dirname(os.path.abspath(__file__)),
        "..", "..", "app", "src", "main", "assets", "fixtures.json"))
    prev = {}
    if os.path.exists(dest):
        try:
            prev = json.load(open(dest))
        except Exception:
            prev = {}
    out, counts, failed = [], {}, []
    for league, path in LEAGUES.items():
        data = fetch(league, path, start, end)
        if data is None:
            # A request that FAILED, which is not the same as a league with no
            # fixtures this week. Counted, because a run where most of them
            # fail must not publish over a good file.
            failed.append(league)
            continue
        n = 0
        for event in data.get("events") or []:
            home, away = sides(event)
            date = event.get("date")
            if not (home and away and date):
                continue
            out.append({
                "league": league,
                "home": home,
                "away": away,
                # ESPN writes Zulu; the app parses it as such and does its own
                # local arithmetic. No zone is ever inferred from a name here,
                # which is the entire point of this file.
                "start": date,
            })
            n += 1
        counts[league] = n
        print(f"  {league:<18} {n:>3} fixtures")
    out.sort(key=lambda f: (f["start"], f["league"], f["home"]))

    # Refuse to publish a partial outage. The old guard only asked for five
    # fixtures in total, and MLS alone carries forty — so a run that lost every
    # European league and the NFL would have committed happily and taken those
    # rows back to reading kick-offs out of slot names, which is the one thing
    # the guard exists to prevent. A league with no fixtures is ordinary (a
    # winter break, an international window); a league whose REQUEST failed is
    # not, and neither is a collapse in the total.
    if len(failed) > 2:
        raise SystemExit(f"{len(failed)} leagues failed to answer ({', '.join(failed)}) "
                         "— refusing to publish over the last good file.")
    was = len(prev.get("fixtures") or [])
    if was >= 20 and len(out) < was * 0.4:
        raise SystemExit(f"{len(out)} fixtures against {was} last time — that is an "
                         "outage, not a quiet week. Refusing to publish.")

    doc = {
        # Held steady when nothing moved. Regenerated every run it made the file
        # byte-different every six hours forever: four commits a day to main,
        # each one re-triggering the release workflow and expiring every box's
        # cache, to say the same thing.
        "generated": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "source": "site.api.espn.com",
        "days": DAYS,
        "counts": counts,
        "fixtures": out,
    }
    if (prev.get("fixtures") or []) == out and prev.get("counts") == counts:
        doc["generated"] = prev.get("generated", doc["generated"])
    with open(dest, "w") as fh:
        json.dump(doc, fh, indent=1)
    print(f"\n{len(out)} fixtures over {DAYS} days -> {dest}")


if __name__ == "__main__":
    main()
