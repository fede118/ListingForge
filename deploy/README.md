# Deploying the BFF to a Raspberry Pi

The BFF serves both the API and the wasmJS web app (Task 13), so one service on one host is the
whole deployment. Target used here: Raspberry Pi 5, Raspberry Pi OS / Debian 13 (trixie), arm64.

## Layout on the host

| Path | Owner | Holds |
|---|---|---|
| `/opt/listingforge/app` | `listingforge` | the `installDist` tree (`bin/`, `lib/`) |
| `/opt/listingforge/webapp` | `listingforge` | the wasmJS bundle, served at `/` |
| `/opt/listingforge/downloads` | `listingforge` | the Android debug APK + `app-metadata.json`, served at `/downloads` (Task 15, `WITH_APK=1` only) |
| `/etc/listingforge/listingforge.env` | `root:listingforge` 0640 | secrets + config |
| `/var/lib/listingforge/db` | `listingforge` | SQLite — **the thing to back up** |

The service runs as a system user with no login password and no sudo beyond `systemctl` on its
own unit. Config lives outside the payload so a redeploy never overwrites secrets.

## First-time setup

Java 21 is required — the Gradle toolchain is 21, so a 17 JRE will not run the artifact.
On trixie it is in main; **refresh the package index first**, or the availability check reports a
false negative on a freshly imaged host:

```bash
sudo apt-get update && sudo apt-get install -y openjdk-21-jre-headless
```

Create the service user, directories and sudo rule (script at the orchestration root):

```bash
sudo bash pi-bootstrap.sh
```

Then the config and the unit, both as root:

```bash
sudo install -m 0640 -o root -g listingforge listingforge.env.example /etc/listingforge/listingforge.env
```

Fill in `ETSY_KEYSTRING`, `ETSY_SHARED_SECRET` and `SESSION_SIGN_KEY` (`openssl rand -base64 48`),
then install and enable the unit:

```bash
sudo install -m 0644 listingforge.service /etc/systemd/system/listingforge.service && sudo systemctl daemon-reload && sudo systemctl enable listingforge.service
```

## Deploying

From the repo root on the workstation:

```bash
bash deploy/deploy.sh
```

`SKIP_WEB=1` skips the wasm build when only the server changed. `PI_HOST` and `CLIENT_DIR` override
the SSH alias and the client checkout path.

### Shipping the Android APK (Task 15)

Off by default — `assembleDebug` on every deploy is a tax paid mostly for nothing, since the
day-to-day loop is the web app. Opt in with `WITH_APK=1`, and pass `BFF_BASE_URL` explicitly (the
script fails fast if it's unset, rather than silently baking in a dev default that ships an APK
dead on arrival on the tablet):

```bash
WITH_APK=1 BFF_BASE_URL=http://fedesrasp.lan:8080 bash deploy/deploy.sh
```

This builds the **debug** variant only (`:androidApp:assembleDebug`) — deliberate, not a shortcut:
release builds deny cleartext http, and this deployment is plain `http://fedesrasp.lan:8080` until
TLS is in front of it, so a release APK would install and then fail every call. `-Papp.versionCode`
is set from the client repo's commit count (`git rev-list --count HEAD`), so the About screen's
download card can tell whether the tablet already has the current build. The script renames the
built APK to the stable `listingforge.apk`, generates `app-metadata.json` alongside it (version,
size, sha256, build time — computed here on the workstation, not on the Pi, which has neither
`aapt` nor cycles to spare hashing a ~30MB file per request), and ships both to
`/opt/listingforge/downloads`, a directory the script creates on demand (`mkdir -p`) if
`pi-bootstrap.sh` hasn't been re-run since this landed.

## Etsy registration

`ETSY_REDIRECT_URI` must match the Etsy app's registered callback **byte-for-byte**, including
scheme and port. Changing the host means updating both.

**Etsy rejects IP-address callbacks.** The registration form requires the host to be a domain name;
`http://192.168.1.144:8080/auth/callback` is refused as invalid. It does *not* require HTTPS —
`http://localhost:8080` is accepted — so the constraint is the host being a **name**, not TLS.

This deployment satisfies that with `fedesrasp.lan`, which exists because the gateway registers
DHCP client hostnames under `.lan`. Two consequences worth knowing: the name follows the **host's
hostname**, so renaming the machine changes it and breaks the registered callback; and it resolves
only for devices using the gateway as their DNS server, i.e. on the LAN. Off-network access needs a
publicly resolvable hostname — see the remote-access decision in `BUILD_BRIEF.md` Task 14.

## Known gaps at this stage

- **`COOKIE_SECURE=false`** — plain http on the LAN. The session cookie is unencrypted in transit.
  Flip to `true` as soon as TLS is in front, and not later.
- **No TLS and no remote access** — LAN only. Both land with the tunnel decision.
- **Android's `bff.base.url`** is compile-time (`local.properties` → `BuildConfig`), so pointing the
  app at this host needs a rebuild, per checkout. That is also what retires
  `adb reverse tcp:8080 tcp:8080`.
- **The host IP is a DHCP lease.** Pin it on the router before the redirect URI is registered.
