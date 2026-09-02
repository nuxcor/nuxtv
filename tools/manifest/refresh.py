#!/usr/bin/env python3
"""Re-fetch the panel, rebuild the manifest, and report what drifted.

The catalogue grows on its own and the curation does not. New content reaches
the app the moment the provider adds it — the app fetches the line-up at
runtime — but every per-title decision in the manifest is keyed by stream or
series id, so none of it covers anything new:

  * duplicate folding      a new show on four provider shelves arrives four times
  * drops                  new titles on a dropped shelf come back
  * series shelving        a new show has no New/Top/All and lands on no shelf

Rebuilding re-applies all of it. The app reads the manifest from the
repository's main branch and prefers whichever copy has the newer `generated`
stamp, so merging a rebuild reaches the box within a day WITHOUT an app
release — see ManifestRepository.DEFAULT_REMOTE.

Credentials come from the environment, never a file — this repository is
public:

    AGORO_HOST=panel.example.com AGORO_USER=... AGORO_PASS=... python3 refresh.py

Use --no-fetch to rebuild and diff against the dumps already on disk, which is
how to test everything downstream of the panel without touching it.
"""
import argparse
import json
import os
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
SHIPPED = HERE.parent.parent / "app" / "src" / "main" / "assets" / "catalogue-manifest.json"

# The six dumps build_manifest.py reads, and the panel action that produces
# each. Named on the left exactly as the build expects to find them.
DUMPS = {
    "get_live_categories.json": "get_live_categories",
    "get_live_streams.json": "get_live_streams",
    "get_vod_categories.json": "get_vod_categories",
    "get_vod_streams.json": "get_vod_streams",
    "get_series.json": "get_series",
    "series_cats.json": "get_series_categories",
}

UA = "Agoro/2.9"


def fetch(host, user, password, timeout=180):
    """Pull each dump to a temp file, then move it into place.

    Written whole or not at all: a truncated dump is worse than a stale one,
    because the build cannot tell the difference and would happily emit a
    manifest that drops half the catalogue.

    These are player_api calls, not streams, so they do not consume the line's
    single connection — the same reason the app can poll active_cons while a
    channel is playing.
    """
    for name, action in DUMPS.items():
        q = urllib.parse.urlencode(
            {"username": user, "password": password, "action": action}
        )
        url = f"http://{host}/player_api.php?{q}"
        req = urllib.request.Request(url, headers={"User-Agent": UA})
        # One line, not a traceback. This runs unattended and its log is read
        # weeks later by someone deciding whether the panel was down or the
        # credentials went stale; a stack of urllib frames answers neither.
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                body = r.read()
        except urllib.error.HTTPError as e:
            hint = " (513 from this panel means bad credentials or anti-flood)" \
                if e.code == 513 else ""
            raise SystemExit(f"{action}: panel answered HTTP {e.code}{hint}") from None
        except urllib.error.URLError as e:
            raise SystemExit(f"{action}: cannot reach {host} — {e.reason}") from None
        # A panel that refuses returns a short JSON object or an HTML error
        # page with a 200, so parse before believing it.
        try:
            parsed = json.loads(body)
        except json.JSONDecodeError:
            raise SystemExit(
                f"{action}: {host} returned {len(body)} bytes that are not JSON "
                f"— usually a captive portal or an error page served with a 200"
            ) from None
        if not isinstance(parsed, list) or not parsed:
            raise SystemExit(
                f"{action}: expected a non-empty list, got "
                f"{type(parsed).__name__} — check the credentials "
                f"(HTTP 513 from this panel means bad credentials or anti-flood)"
            )
        tmp = HERE / (name + ".part")
        tmp.write_bytes(body)
        tmp.replace(HERE / name)
        print(f"  fetched {name:26} {len(parsed):>6} entries")


def counts(m):
    """The handful of numbers that say whether curation still fits the line-up."""
    return {
        "live kept": m["stats"]["live_channels"] - len(m["drop_stream_ids"]),
        "live dropped": len(m["drop_stream_ids"]),
        "collapse tiles": len(m["collapse"]["live"]),
        "movies": m["stats"]["movies"],
        "movies dropped": len(m["vod_drop"]),
        "series entries": m["stats"]["series_entries"],
        "series dropped": len(m["series_drop"]),
        "series deduped": m["stats"]["dedup_groups"],
        "guide bound": m["stats"]["epg_bound"],
        "logos bound": m["stats"]["logo_bound"],
    }


def report(old, new):
    """Print the drift, and answer whether it is worth a pull request.

    Worth merging means the LINE-UP moved, not merely that the build ran: a
    rebuild always rewrites `generated`, and a manifest whose only change is
    its own timestamp is churn. Guide and artwork bindings count too — they
    are the other half of what goes stale.
    """
    a, b = counts(old), counts(new)
    width = max(len(k) for k in a)
    moved = False
    for k in a:
        flag = ""
        if a[k] != b[k]:
            moved = True
            flag = f"   ({b[k] - a[k]:+d})"
        print(f"  {k:<{width}}  {a[k]:>7} -> {b[k]:>7}{flag}")

    # The fields a rebuild is expected to touch even when nothing drifted.
    ignored = {"generated", "stats"}
    changed = sorted(
        k for k in set(old) | set(new)
        if k not in ignored and old.get(k) != new.get(k)
    )
    if changed:
        moved = True
        print(f"\n  fields changed: {', '.join(changed)}")
    return moved


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--no-fetch", action="store_true",
                    help="rebuild from the dumps already on disk")
    ap.add_argument("--write", action="store_true",
                    help="install the rebuilt manifest over the shipped asset")
    args = ap.parse_args()

    if not SHIPPED.exists():
        raise SystemExit(f"shipped manifest not found at {SHIPPED}")
    old = json.loads(SHIPPED.read_text())

    if args.no_fetch:
        print("Skipping the panel; rebuilding from the dumps on disk.")
    else:
        missing = [k for k in ("AGORO_HOST", "AGORO_USER", "AGORO_PASS")
                   if not os.environ.get(k)]
        if missing:
            raise SystemExit(
                "missing credentials: " + ", ".join(missing) +
                "\nThey are read from the environment and never stored in this "
                "repository, which is public. Use --no-fetch to rebuild from "
                "the dumps already on disk."
            )
        print(f"Fetching from {os.environ['AGORO_HOST']}…")
        fetch(os.environ["AGORO_HOST"], os.environ["AGORO_USER"],
              os.environ["AGORO_PASS"])

    # The build reads manifest.json as its PREVIOUS state and falls back to it
    # for guide, artwork and crest bindings when the match files are absent —
    # which they usually are. Seeding it from the SHIPPED asset is what stops a
    # rebuild silently losing those; a stale manifest.json here has cost a
    # round of exactly that before.
    (HERE / "manifest.json").write_text(SHIPPED.read_text())

    print("\nBuilding…")
    out = HERE / "manifest.json"
    r = subprocess.run([sys.executable, "build_manifest.py", str(out)],
                       cwd=HERE, capture_output=True, text=True)
    if r.returncode != 0:
        sys.stderr.write(r.stdout + r.stderr)
        raise SystemExit("build_manifest.py failed; nothing was installed")
    new = json.loads(out.read_text())

    print("\nDrift against the shipped manifest:")
    moved = report(old, new)

    if not moved:
        print("\nNothing but the timestamp moved. No pull request is warranted.")
        return 0

    if args.write:
        SHIPPED.write_text(out.read_text())
        print(f"\nInstalled over {SHIPPED}.")
    else:
        print("\nThe line-up drifted. Re-run with --write to install it.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
