# ListingForge BFF

A Backend-For-Frontend for the Etsy Open API v3. It owns the OAuth 2.0 + PKCE
login, holds tokens server-side (SQLite), and proxies authenticated calls to
Etsy so that browser/wasm clients never touch a token or the shared secret.

This is the **Phase 2 walking skeleton**: full login flow end-to-end plus one
proxied call (`/api/me`). Right-sized on purpose — single process, SQLite, no
distributed anything (Etsy caps personal access at 5 shops, so there is no
scale to engineer for).

## Request flow

```
Browser ──GET /auth/login──▶ BFF ──302──▶ Etsy consent screen
                                              │  (user approves)
Browser ◀──302 to /auth/callback?code&state──┘
        ──GET /auth/callback──▶ BFF ──exchange code+verifier──▶ Etsy /oauth/token
                                BFF: validate state, save tokens (SQLite),
                                     set signed HttpOnly session cookie (userId only)
Browser ──GET /api/me (cookie)──▶ BFF ──Bearer token + x-api-key──▶ Etsy /users/me
        ◀──────── JSON ───────────┘   (token loaded from SQLite, refreshed if stale)
```

The cookie carries **only the userId**. Tokens live in SQLite, keyed by userId.
The browser holds an identity, never a credential — the entire reason this tier
exists.

## Prerequisites

- JDK 21
- Your Etsy app's **keystring** and **shared secret**
- The redirect URI `http://localhost:8080/auth/callback` registered on the app,
  **byte-for-byte** (this is the #1 source of "redirect URL is not permitted")
- The app status must be **active**, not "Pending Personal Approval", before the
  live flow will succeed

## Configure

```bash
cp .env.example .env       # then edit .env with your real keystring/secret
set -a; source .env; set +a   # export the vars into the shell that runs the server
```

`.env` is not auto-loaded by the JVM — the `set -a; source` line is what puts the
values into the environment `AppConfig.fromEnv()` reads. (IntelliJ: put them in
the run configuration's Environment Variables field instead.)

## Run

```bash
./gradlew run
```

## Test the flow

1. Health check (no auth): `curl localhost:8080/health` → `ok`
2. **In a browser**, open `http://localhost:8080/auth/login`. It redirects to
   Etsy; approve the consent screen. Etsy redirects back to `/auth/callback` and
   you should see `Signed in as Etsy user <id>`. The session cookie is now set.
3. In the **same browser**, open `http://localhost:8080/api/me`. You should see
   the JSON from Etsy's `/users/me` — proof the proxied, token-backed call works
   end to end, with the token never leaving the server.

> The browser is required for step 2: PKCE has a human-in-the-loop consent
> screen that curl can't click. curl is fine for `/health`, and for `/api/me` if
> you copy the `SESSION` cookie out of the browser.

This is also where the deferred question gets answered: `EtsyApiClient` currently
sends `x-api-key: keystring:shared_secret`. If `/api/me` 401/403s, switch
`apiKeyHeader` to send the keystring alone and retry.

## Mock mode — testing the sign-in flow with no Etsy app

Set `APP_MODE=mock` (in `.env`, since `./gradlew run` auto-loads it — a shell-exported
`APP_MODE=mock` alone won't win, because the run task's `environment(...)` call for
whatever `.env` sets takes precedence) and run the exact same flow above:
`/auth/login` → approve → `/api/me`. Nothing is bypassed — `/api/me` still 401s
until you complete it — but two things are faked so no Etsy app or network call
is involved:

- `/auth/login` serves a minimal BFF-rendered **stub consent page**
  ("Approve as mock user") instead of redirecting to Etsy.
- The code-for-token exchange returns a **canned token** for a fixed demo user
  (`mock-user`) instead of calling Etsy's `/oauth/token`.

Everything else — PKCE state validation, session cookie / Android bearer
issuance, `/api/*` auth enforcement — is the real code path. This is what lets
the client's sign-in UI be built and tested before the Etsy app is approved,
without the BFF secretly skipping auth.

## Serving the web app (Task 13)

The BFF can serve the built wasmJS bundle itself, same-origin with the API, so
a browser hitting the BFF's origin gets the whole app with no other process
running. This matters beyond convenience: the session cookie is
`SameSite=Lax`, which browsers withhold on cross-origin requests, so
same-origin is what makes the web client first-party and retires CORS for it
entirely (CORS/`FRONTEND_ORIGIN` still exist and still matter for the webpack
dev server, which remains the day-to-day dev loop - see below).

The BFF **never builds** this bundle - that happens in the **client** repo:

```bash
# in the CLIENT repo (ListingForge-fe/ListingForge)
./gradlew :webApp:wasmJsBrowserDistribution
# output: webApp/build/dist/wasmJs/productionExecutable/
#   (index.html, webApp.js, the .wasm binaries incl. skiko's, styles.css,
#    favicon.svg, Compose resources)
```

Point the BFF at that folder with `WEBAPP_DIR` (unset by default - an
API-only run registers no static route at all):

```bash
WEBAPP_DIR=/path/to/webApp/build/dist/wasmJs/productionExecutable ./gradlew run
```

Then `http://localhost:8080` serves `index.html`, while `/health`, `/auth/*`
and `/api/*` keep working exactly as before - the static handler is a
catch-all registered *after* those routes specifically so it can't shadow
them (see `WebAppRoutesTest`).

**Redeploying the web app needs no BFF rebuild or restart.** The webpack
output filenames are **not content-hashed** (`webApp.js`, `skiko.wasm` keep
the same name every build), so copying a new bundle into the same `WEBAPP_DIR`
and hard-refreshing the browser is enough - the BFF reads the directory live.
What makes that safe rather than serving a stale cached app: `ConditionalHeaders`
gives every file a `Last-Modified`/`If-Modified-Since` round trip (304 on an
unchanged file), and `CachingHeaders` sends `Cache-Control: no-cache` (always
revalidate, not blind-cache and not `no-store`) on everything under this
route.

`.wasm` files are served as `application/wasm` with no extra configuration -
verified against Ktor 3.1.0's built-in file-extension MIME table, which
already maps `wasm` to `application/wasm` with no charset appended. This
matters because `WebAssembly.instantiateStreaming` hard-refuses any other
content type; it's the single most common silent wasm-deploy failure, which is
why it's locked in by a test (`WebAppRoutesTest`) rather than left to trust.

**Signing in works from either origin the web app can be served from.** Since
Task 13 that's two: this BFF's own origin (`BFF_ORIGIN`, once `WEBAPP_DIR` is
set) and the webpack dev server (`FRONTEND_ORIGIN`). `GET /auth/login`
captures the initiating origin (`Origin` header, falling back to `Referer`'s
origin) and carries it through `PendingAuthStore` alongside the PKCE state, so
`/auth/callback` redirects back to wherever the flow actually started rather
than always landing on `FRONTEND_ORIGIN` - see the KDoc on
`resolveReturnOrigin` in `AuthRoutes.kt`. Only `FRONTEND_ORIGIN`/`BFF_ORIGIN`
are ever used as a redirect target; anything else falls back to
`FRONTEND_ORIGIN`, so a forged header can't turn this into an open redirect.

## OAuth scopes

`OAUTH_SCOPES` (space-separated, Etsy's scope-list format) defaults to
`shops_r listings_w listings_r`: `shops_r` covers the read calls (`/api/me`,
`/api/shop`), `listings_w` is required for the Task 9 submit pipeline (create
draft listing, upload image, upload file), and `listings_r` is required for
Task 12's browse-drafts read (`GET /api/listings`) — without the right scope
Etsy answers the corresponding calls with a 403.

**A token already stored keeps whatever scopes it was issued with.** Widening
this list doesn't retroactively grant an already-signed-in seller the new
scope — after changing it (or pulling this change for the first time), sign
out (which drops the stored token) and go through `/auth/login` again so the
new consent screen requests the new scope and a fresh token is stored with it.
This applies to `listings_r`/Task 12 too: a token stored before this change
won't return any drafts until you sign out and re-consent.

Any Etsy status this BFF doesn't have a specific mapping for (a 403 from a
missing scope is the most likely case) is returned to the client as a clean
**502 Bad Gateway** with Etsy's own error message in the JSON body, rather
than an opaque 500 — see `EtsyUpstreamException` / `configureStatusPages`.

## Known production gaps (intentionally out of scope for the skeleton)

- **Cross-site cookies.** `SameSite=Lax` + `secure=false` is correct for
  same-origin http-localhost testing. A separate-origin web client (the wasm app
  on another port/domain) will need `SameSite=None` + `Secure` + HTTPS, and the
  CORS `allowHost` set to that origin. **Closed for the web client once `WEBAPP_DIR`
  is set** (Task 13): serving the app from this BFF's own origin makes it
  first-party, so `SameSite=Lax` is correct as-is and no CORS applies to it at
  all. The webpack dev server is still a separate origin during day-to-day
  development, so `FRONTEND_ORIGIN`/CORS stay in place for that loop.
- **Pending-auth cleanup.** Expired entries are skipped but not actively swept.
  Fine in-memory; revisit if it ever moves to a store.
- **Refresh races.** Concurrent requests for one user could refresh in parallel.
  Single-user dev never hits this; a per-user lock is the fix if it matters.
