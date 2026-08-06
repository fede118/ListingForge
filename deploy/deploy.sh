#!/usr/bin/env bash
# Build on the workstation, ship to the server, restart. Run from the repo root:
#   bash deploy/deploy.sh              # BFF + web app
#   SKIP_WEB=1 bash deploy/deploy.sh   # BFF only (web bundle unchanged)
#   WITH_APK=1 BFF_BASE_URL=http://fedesrasp.lan:8080 bash deploy/deploy.sh
#                                      # also builds + ships the Android debug APK
#
# Kotlin/wasm compilation on a Raspberry Pi is slow enough to be impractical, so
# both artifacts are built here and only their outputs cross the wire.
#
# Transport is a tarball streamed over SSH rather than rsync: rsync is absent
# from Git Bash on Windows, which is where this is run from, and streaming tar
# needs nothing installed on either end.
set -euo pipefail

PI_HOST=${PI_HOST:-listingforge-pi}
CLIENT_DIR=${CLIENT_DIR:-../ListingForge-fe/ListingForge}
SKIP_WEB=${SKIP_WEB:-0}
WITH_APK=${WITH_APK:-0}

BFF_DIST=build/install/listingforge-bff
WEB_DIST="$CLIENT_DIR/webApp/build/dist/wasmJs/productionExecutable"
APK_OUT_DIR="$CLIENT_DIR/androidApp/build/outputs/apk/debug"

say() { printf '\n=== %s ===\n' "$1"; }

# assembleDebug bakes bff.base.url into BuildConfig at build time (Task 14's open
# item): a missing value would silently fall back to the emulator loopback alias
# and ship an APK that's dead on arrival on the tablet, so this fails fast
# instead. Only required when WITH_APK=1 - the day-to-day web-only deploy never
# touches Android at all.
if [ "$WITH_APK" = "1" ]; then
  : "${BFF_BASE_URL:?BFF_BASE_URL must be set when WITH_APK=1, e.g. BFF_BASE_URL=http://fedesrasp.lan:8080}"
fi

say "building BFF"
./gradlew --quiet installDist
[ -x "$BFF_DIST/bin/listingforge-bff" ] || { echo "missing $BFF_DIST/bin/listingforge-bff"; exit 1; }

if [ "$SKIP_WEB" != "1" ]; then
  say "building web app"
  (cd "$CLIENT_DIR" && ./gradlew --quiet :webApp:wasmJsBrowserDistribution)
  [ -f "$WEB_DIST/index.html" ] || { echo "missing $WEB_DIST/index.html"; exit 1; }
fi

if [ "$WITH_APK" = "1" ]; then
  # DEBUG, not release, on purpose: release builds deny cleartext http, and this
  # deployment is plain http://fedesrasp.lan:8080 until TLS lands (BUILD_BRIEF
  # Task 15) - a release APK would install and then fail every call.
  say "building android debug apk"
  APK_VERSION_CODE=$(cd "$CLIENT_DIR" && git rev-list --count HEAD)
  APK_VERSION_NAME=$(
    grep -m1 'versionName = "' "$CLIENT_DIR/androidApp/build.gradle.kts" |
      sed -E 's/.*versionName = "([^"]+)".*/\1/'
  )
  (
    cd "$CLIENT_DIR" &&
      ./gradlew --quiet :androidApp:assembleDebug \
        "-Pbff.base.url=$BFF_BASE_URL" \
        "-Papp.versionCode=$APK_VERSION_CODE"
  )
  APK_SRC=$(find "$APK_OUT_DIR" -maxdepth 1 -name '*.apk' | head -1)
  [ -n "$APK_SRC" ] || { echo "missing built APK under $APK_OUT_DIR"; exit 1; }

  say "staging apk + metadata"
  APK_STAGE=$(mktemp -d)
  trap 'rm -rf "$APK_STAGE"' EXIT
  cp "$APK_SRC" "$APK_STAGE/listingforge.apk"
  APK_SHA256=$(sha256sum "$APK_STAGE/listingforge.apk" | awk '{print $1}')
  APK_SIZE_BYTES=$(stat -c%s "$APK_STAGE/listingforge.apk" 2>/dev/null || wc -c <"$APK_STAGE/listingforge.apk")
  APK_BUILD_TIME=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  cat >"$APK_STAGE/app-metadata.json" <<JSON
{"versionName":"$APK_VERSION_NAME","versionCode":$APK_VERSION_CODE,"sizeBytes":$APK_SIZE_BYTES,"buildTime":"$APK_BUILD_TIME","sha256":"$APK_SHA256","downloadPath":"/downloads/listingforge.apk"}
JSON
fi

# Stopped before the payload is replaced: the start script and its lib/ tree are
# read live, so swapping them under a running JVM is what produces the
# NoClassDefFoundError-after-deploy class of failure.
say "stopping service"
ssh "$PI_HOST" 'sudo -n systemctl stop listingforge.service || true'

say "shipping BFF"
tar -cz -C "$BFF_DIST" . |
  ssh "$PI_HOST" 'rm -rf /opt/listingforge/app/* && tar -xz -C /opt/listingforge/app'
ssh "$PI_HOST" 'chmod +x /opt/listingforge/app/bin/listingforge-bff'

if [ "$SKIP_WEB" != "1" ]; then
  say "shipping web app"
  tar -cz -C "$WEB_DIST" . |
    ssh "$PI_HOST" 'rm -rf /opt/listingforge/webapp/* && tar -xz -C /opt/listingforge/webapp'
fi

if [ "$WITH_APK" = "1" ]; then
  # Its own directory, deliberately not /opt/listingforge/webapp: the web step
  # above does `rm -rf webapp/*`, which would silently delete the APK on the
  # next web-only deploy if it lived there.
  say "shipping android apk"
  tar -cz -C "$APK_STAGE" . |
    ssh "$PI_HOST" 'mkdir -p /opt/listingforge/downloads && rm -rf /opt/listingforge/downloads/* && tar -xz -C /opt/listingforge/downloads'
fi

say "starting service"
ssh "$PI_HOST" 'sudo -n systemctl start listingforge.service'

say "health"
ssh "$PI_HOST" '
  for i in $(seq 1 20); do
    if curl -fsS --max-time 2 http://localhost:8080/health >/dev/null 2>&1; then
      echo "health: ok after ${i}s"; exit 0
    fi
    sleep 1
  done
  echo "health: FAILED - last 30 log lines:"
  journalctl -u listingforge.service -n 30 --no-pager
  exit 1
'
