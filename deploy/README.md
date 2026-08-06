# Deploying the BFF to a Raspberry Pi

The BFF serves both the API and the wasmJS web app (Task 13), so one service on one host is the
whole deployment. Target used here: Raspberry Pi 5, Raspberry Pi OS / Debian 13 (trixie), arm64.

## Layout on the host

| Path | Owner | Holds |
|---|---|---|
| `/opt/listingforge/app` | `listingforge` | the `installDist` tree (`bin/`, `lib/`) |
| `/opt/listingforge/webapp` | `listingforge` | the wasmJS bundle, served at `/` |
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

## Etsy registration

`ETSY_REDIRECT_URI` must match the Etsy app's registered callback **byte-for-byte**, including
scheme and port. Changing the host means updating both. Note that Etsy may reject a plain-`http`
private-IP callback — if it does, this deployment cannot run in `prod` mode until a real HTTPS
hostname exists (see the remote-access decision in `BUILD_BRIEF.md` Task 14).

## Known gaps at this stage

- **`COOKIE_SECURE=false`** — plain http on the LAN. The session cookie is unencrypted in transit.
  Flip to `true` as soon as TLS is in front, and not later.
- **No TLS and no remote access** — LAN only. Both land with the tunnel decision.
- **Android's `bff.base.url`** is compile-time (`local.properties` → `BuildConfig`), so pointing the
  app at this host needs a rebuild, per checkout. That is also what retires
  `adb reverse tcp:8080 tcp:8080`.
- **The host IP is a DHCP lease.** Pin it on the router before the redirect URI is registered.
