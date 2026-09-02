#!/bin/bash
# Weekly manifest rebuild. Driven by a launchd agent; see INSTALL below.
#
# Works on a FRESH CLONE in a temp directory, never on a working tree. The
# job runs unattended on a machine someone else is using, and a script that
# checks out branches and commits under a person's feet is a script that will
# one day eat their afternoon's work. Nothing here touches the repository you
# are editing.
#
# Credentials are read from a file OUTSIDE the repository, which is public.
# The file is yours alone (chmod 600) and its contents never reach the clone.
#
# INSTALL
#   mkdir -p ~/.config/nuxtv && chmod 700 ~/.config/nuxtv
#   cat > ~/.config/nuxtv/panel.env <<'EOF'
#   AGORO_HOST=your.panel.host
#   AGORO_USER=your-username
#   AGORO_PASS=your-password
#   EOF
#   chmod 600 ~/.config/nuxtv/panel.env
#   launchctl load ~/Library/LaunchAgents/com.agoro.manifest-refresh.plist
#
# UNINSTALL
#   launchctl unload ~/Library/LaunchAgents/com.agoro.manifest-refresh.plist
#   rm ~/Library/LaunchAgents/com.agoro.manifest-refresh.plist
set -euo pipefail

# launchd hands a job almost no PATH, so git, gh and python are named the way
# a login shell would find them rather than assumed.
export PATH="/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"

REPO="${MANIFEST_REPO_URL:-git@github.com:nuxcor/nuxtv.git}"
ENV_FILE="${MANIFEST_ENV_FILE:-$HOME/.config/nuxtv/panel.env}"
LOG="${MANIFEST_LOG:-$HOME/.config/nuxtv/refresh.log}"

log() { printf '%s  %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" | tee -a "$LOG"; }

if [ ! -r "$ENV_FILE" ]; then
    log "no credentials at $ENV_FILE — see INSTALL in this script; nothing done"
    exit 0    # not a failure: an uninstalled job should be quiet, not noisy
fi
# shellcheck disable=SC1090
set -a; . "$ENV_FILE"; set +a

# The file is installed with the keys present and the values blank, so it can
# be filled in without looking anything up. Blank means NOT CONFIGURED YET,
# and that must exit quietly too — running on empty credentials would fail
# against the panel every week and fill the log with an error that is really
# just "you have not finished installing this".
if [ -z "${AGORO_HOST:-}" ] || [ -z "${AGORO_USER:-}" ] || [ -z "${AGORO_PASS:-}" ]; then
    log "credentials at $ENV_FILE are blank; fill them in to start the weekly rebuild"
    exit 0
fi

WORK="$(mktemp -d -t nuxtv-refresh)"
# Runs on every exit including the failures, so a panel that is down for a
# week does not leave a week of half-built clones in the temp directory.
trap 'rm -rf "$WORK"' EXIT

log "cloning into $WORK"
git clone --depth 1 --quiet "$REPO" "$WORK/nuxtv"
cd "$WORK/nuxtv"

log "fetching and rebuilding"
if ! python3 tools/manifest/refresh.py --write >>"$LOG" 2>&1; then
    log "refresh failed — see $LOG; the working tree is untouched"
    exit 1
fi

if git diff --quiet -- app/src/main/assets/catalogue-manifest.json; then
    log "no drift; nothing to open"
    exit 0
fi

# The manifest lives under app/, so CI's version-guard wants a bump even
# though the app does not need a release to pick this up — it reads the
# manifest off main and prefers the newer `generated` stamp. Patch only.
VERSION=$(grep -o 'versionName = "[^"]*"' app/build.gradle.kts | head -1 | cut -d'"' -f2)
CODE=$(grep -o 'versionCode = [0-9]*' app/build.gradle.kts | head -1 | awk '{print $3}')
NEXT="${VERSION%.*}.$(( ${VERSION##*.} + 1 ))"
sed -i '' "s/versionName = \"$VERSION\"/versionName = \"$NEXT\"/" app/build.gradle.kts
sed -i '' "s/versionCode = $CODE/versionCode = $((CODE + 1))/" app/build.gradle.kts

BRANCH="manifest-refresh-$(date +%Y%m%d)"
git checkout -q -b "$BRANCH"
git add app/build.gradle.kts app/src/main/assets/catalogue-manifest.json
git commit -q -m "Catalogue refresh $(date +%Y-%m-%d)

Scheduled rebuild: the provider's line-up moved, so the curation keyed to it
was re-applied. Duplicate folding, drop lists and series shelving are all
keyed by stream or series id and cover nothing the provider added since the
last build; this is what re-applies them.

Version bumped only to satisfy the version guard — the app reads the manifest
off main and prefers the newer generated stamp, so it does not need a release
to pick this up.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
git push -q -u origin "$BRANCH"

gh pr create --base main --head "$BRANCH" \
    --title "Catalogue refresh $(date +%Y-%m-%d)" \
    --body "Scheduled weekly rebuild — the provider's line-up drifted.

The drift table is in the run log at \`$LOG\`. Worth a look before merging:
a large swing in dropped counts usually means the provider renamed or
re-shelved something, which is a curation question rather than a rebuild.

$NEXT bumped for the version guard only; the app picks the manifest up off
main without a release." >>"$LOG" 2>&1

log "opened a pull request for $BRANCH ($VERSION -> $NEXT)"
