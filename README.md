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

## Known production gaps (intentionally out of scope for the skeleton)

- **Cross-site cookies.** `SameSite=Lax` + `secure=false` is correct for
  same-origin http-localhost testing. A separate-origin web client (the wasm app
  on another port/domain) will need `SameSite=None` + `Secure` + HTTPS, and the
  CORS `allowHost` set to that origin.
- **Pending-auth cleanup.** Expired entries are skipped but not actively swept.
  Fine in-memory; revisit if it ever moves to a store.
- **Refresh races.** Concurrent requests for one user could refresh in parallel.
  Single-user dev never hits this; a per-user lock is the fix if it matters.
