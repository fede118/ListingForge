#!/usr/bin/env bash
# Build on the workstation, ship to the server, restart. Run from the repo root:
#   bash deploy/deploy.sh              # BFF + web app
#   SKIP_WEB=1 bash deploy/deploy.sh   # BFF only (web bundle unchanged)
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

BFF_DIST=build/install/listingforge-bff
WEB_DIST="$CLIENT_DIR/webApp/build/dist/wasmJs/productionExecutable"

say() { printf '\n=== %s ===\n' "$1"; }

say "building BFF"
./gradlew --quiet installDist
[ -x "$BFF_DIST/bin/listingforge-bff" ] || { echo "missing $BFF_DIST/bin/listingforge-bff"; exit 1; }

if [ "$SKIP_WEB" != "1" ]; then
  say "building web app"
  (cd "$CLIENT_DIR" && ./gradlew --quiet :webApp:wasmJsBrowserDistribution)
  [ -f "$WEB_DIST/index.html" ] || { echo "missing $WEB_DIST/index.html"; exit 1; }
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
