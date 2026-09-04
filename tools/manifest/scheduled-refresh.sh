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
# Runs on macOS under launchd and on Linux under systemd; see INSTALL and
# INSTALL (LINUX) below. Nothing in it is specific to either.
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
#
# INSTALL (LINUX, systemd --user) — see tools/manifest/systemd/ for the units
#   the machine needs git, python3 and gh, a gh login that can open a pull
#   request, and a key that can push. `loginctl enable-linger $USER` is what
#   lets a --user timer fire while nobody is logged in, which on a home server
#   is the whole point.
set -euo pipefail

# launchd and systemd both hand a job almost no PATH, so the places git, gh
# and python actually live are named rather than assumed. Homebrew's two
# prefixes are harmless on a machine that has neither.
export PATH="/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"

REPO="${MANIFEST_REPO_URL:-git@github.com:nuxcor/agoro.git}"
# The config directory keeps the OLD name on purpose. The repository was
# renamed to agoro on 2026-09-04; this path names a file that already exists on
# the machine running the job, holding the panel credentials, and a rename here
# would leave the job looking for a file nobody moved — silently, weekly, until
# someone noticed the manifest had stopped. Point MANIFEST_ENV_FILE somewhere
# else if you would rather move it yourself.
ENV_FILE="${MANIFEST_ENV_FILE:-$HOME/.config/nuxtv/panel.env}"
LOG="${MANIFEST_LOG:-$HOME/.config/nuxtv/refresh.log}"

log() { printf '%s  %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" | tee -a "$LOG"; }

KEYCHAIN_SERVICE="${MANIFEST_KEYCHAIN_SERVICE:-nuxtv-panel}"

# Where the panel password comes from, best first.
#
# What encryption buys here, honestly: not much against someone who already
# has your session, because an unattended job must be able to decrypt without
# a human, so whatever unlocks the secret is reachable from that session too.
# What it does buy is that the password stops being a plaintext file in your
# home directory — which is readable by ANYTHING running as you (a rogue
# dependency, a helper process) and which travels into every backup, sync
# client and dotfiles repo that ever touches ~/.config. Those are the two
# realistic ways a home-server credential leaks, and both are closed by
# keeping it out of a file.
#
# The file is still supported and still the fallback. An existing install
# keeps working untouched.
load_credentials() {
    # 1. systemd, which hands the unit a decrypted copy in a private tmpfs
    #    that dies with the process. With LoadCredentialEncrypted the blob on
    #    disk is sealed to the machine (its TPM where there is one), so a copy
    #    of it taken elsewhere is useless. The best of the three.
    if [ -n "${CREDENTIALS_DIRECTORY:-}" ] && [ -r "$CREDENTIALS_DIRECTORY/panel" ]; then
        # shellcheck disable=SC1091
        set -a; . "$CREDENTIALS_DIRECTORY/panel"; set +a
        CRED_SOURCE="systemd credential"
        return 0
    fi

    # 2. The macOS login keychain. Gated by the OS rather than by file
    #    permissions, and Time Machine backs it up encrypted. It needs the
    #    login keychain UNLOCKED, which it is once you have logged in after a
    #    boot — a Mac sitting at the login screen cannot run this, which is
    #    one more reason the server is the better host.
    #
    #    One item per field, not one item holding all three: `security -w`
    #    hex-encodes any password containing a newline and hands back a bare
    #    hex string, so a single multi-line item comes out unreadable.
    if command -v security >/dev/null 2>&1; then
        local host user pass
        host="$(security find-generic-password -s "$KEYCHAIN_SERVICE" -a AGORO_HOST -w 2>/dev/null)" || host=""
        user="$(security find-generic-password -s "$KEYCHAIN_SERVICE" -a AGORO_USER -w 2>/dev/null)" || user=""
        pass="$(security find-generic-password -s "$KEYCHAIN_SERVICE" -a AGORO_PASS -w 2>/dev/null)" || pass=""
        if [ -n "$host" ] || [ -n "$user" ] || [ -n "$pass" ]; then
            export AGORO_HOST="$host" AGORO_USER="$user" AGORO_PASS="$pass"
            CRED_SOURCE="keychain ($KEYCHAIN_SERVICE)"
            return 0
        fi
    fi

    # 3. The plain file. Works everywhere, protects least.
    if [ -r "$ENV_FILE" ]; then
        # shellcheck disable=SC1090
        set -a; . "$ENV_FILE"; set +a
        CRED_SOURCE="$ENV_FILE"
        return 0
    fi
    return 1
}

# Move the file's contents into the login keychain, then say what to delete.
# Deliberately does NOT delete it: removing the only copy of a password on
# someone's behalf is not a thing a script should decide, and if the keychain
# read then fails they have nothing.
if [ "${1:-}" = "--import-keychain" ]; then
    if ! command -v security >/dev/null 2>&1; then
        echo "no keychain on this machine; on Linux use systemd-creds — see the README" >&2
        exit 1
    fi
    [ -r "$ENV_FILE" ] || { echo "nothing to import: $ENV_FILE is not readable" >&2; exit 1; }
    # shellcheck disable=SC1090
    set -a; . "$ENV_FILE"; set +a
    for field in AGORO_HOST AGORO_USER AGORO_PASS; do
        security add-generic-password -U -s "$KEYCHAIN_SERVICE" -a "$field" \
            -w "${!field}" -T /usr/bin/security
    done
    echo "stored in the login keychain as '$KEYCHAIN_SERVICE'."
    echo "verify:  $0 --check"
    echo "then remove the plaintext copy:  rm $ENV_FILE"
    exit 0
fi

# Say where the credentials would come from and whether they are usable,
# without touching the panel or the repository. This is what to run after
# changing anything, rather than waiting a week to find out.
if [ "${1:-}" = "--check" ]; then
    if load_credentials; then
        if [ -z "${AGORO_HOST:-}" ] || [ -z "${AGORO_USER:-}" ] || [ -z "${AGORO_PASS:-}" ]; then
            echo "found $CRED_SOURCE, but one or more values are blank"
            exit 1
        fi
        echo "credentials from $CRED_SOURCE — host ${AGORO_HOST}, user ${AGORO_USER}, password set"
        exit 0
    fi
    echo "no credentials found (looked at systemd, the keychain, then $ENV_FILE)"
    exit 1
fi

if ! load_credentials; then
    log "no credentials found — see INSTALL in this script; nothing done"
    exit 0    # not a failure: an uninstalled job should be quiet, not noisy
fi

# The file is installed with the keys present and the values blank, so it can
# be filled in without looking anything up. Blank means NOT CONFIGURED YET,
# and that must exit quietly too — running on empty credentials would fail
# against the panel every week and fill the log with an error that is really
# just "you have not finished installing this".
if [ -z "${AGORO_HOST:-}" ] || [ -z "${AGORO_USER:-}" ] || [ -z "${AGORO_PASS:-}" ]; then
    log "credentials from $CRED_SOURCE are blank; fill them in to start the weekly rebuild"
    exit 0
fi
log "credentials from $CRED_SOURCE"

# The template carries its own X's: BSD mktemp appends them to a -t name and
# GNU mktemp refuses a template without them, so spelling them out is the one
# form both accept. This script runs on the Mac under launchd and on a Linux
# home server under systemd.
WORK="$(mktemp -d "${TMPDIR:-/tmp}/agoro-refresh.XXXXXXXX")"
# Runs on every exit including the failures, so a panel that is down for a
# week does not leave a week of half-built clones in the temp directory.
trap 'rm -rf "$WORK"' EXIT

log "cloning into $WORK"
git clone --depth 1 --quiet "$REPO" "$WORK/agoro"
cd "$WORK/agoro"

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
# In place, portably. GNU sed reads `-i ''` as a filename and BSD sed demands
# the argument, so neither spelling works on both; a temp file and a move does.
bump() {  # bump <sed-expression> <file>
    sed "$1" "$2" > "$2.bump" && mv "$2.bump" "$2"
}
bump "s/versionName = \"$VERSION\"/versionName = \"$NEXT\"/" app/build.gradle.kts
bump "s/versionCode = $CODE/versionCode = $((CODE + 1))/" app/build.gradle.kts

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
