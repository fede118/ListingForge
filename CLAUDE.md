# ListingForge BFF — agent guide

A **Backend-for-frontend** for the Etsy Open API v3, written in **Kotlin / Ktor**. It owns the
OAuth 2.0 + PKCE login, holds Etsy tokens **server-side** (SQLite), and proxies authenticated
calls to Etsy so that browser/wasm clients **never touch a token or the shared secret**. This is
the server half of ListingForge; the CMP client lives in a sibling project and talks **only** to
this BFF.

> The cross-project task plan and architecture rationale are at the **orchestration root**
> (`../BUILD_BRIEF.md`). This file holds the durable conventions for the BFF specifically. When
> given a task, do the **BFF slice** here; the client slice is the client agent's job.

## Scope discipline

- **Work only inside this project** (`listingforge-bff`). Do not edit the client project.
- The **contract** between client and BFF (the Postman collection) lives at the root in
  `../contract/`. If you add or change an endpoint's shape, say so clearly so the contract and the
  client can be updated to match — treat it as a breaking-change signal.

## Stack

- **Kotlin 2.1**, JVM toolchain **21**, Gradle (`application` plugin; `./gradlew run`).
- **Ktor server 3.1** on **Netty**. Plugins in use: ContentNegotiation (kotlinx-json), Sessions,
  CORS, StatusPages, CallLogging.
- **Ktor client (CIO)** for the outbound calls to Etsy.
- **Koin 4** for DI (`koin-ktor`).
- **SQLite** via `sqlite-jdbc` behind **HikariCP**.
- **Logback** for logging. **kotlin.test** + `ktor-server-test-host` + `kotlinx-coroutines-test`
  for tests.

## Package layout (`com.section11.listingforge`)

- `Application.kt` — entrypoint / server wiring.
- `plugins/` — Ktor plugin install (negotiation, sessions, CORS, status pages, logging).
- `config/` — `AppConfig` (read from env; **no hardcoded identifiers**) and `AppMode` (LIVE/MOCK).
- `di/AppModule.kt` — **the single composition root.** Every dependency is built here and bound
  **by interface** where one exists. Adding a collaborator means wiring it here, not `new`-ing it
  inside a class. Mode-selected bindings (MOCK ↔ LIVE) live here too.
- `auth/` — the OAuth/PKCE flow: `AuthRoutes`, `EtsyOAuthClient`, `Pkce`, `PendingAuthStore`
  (in-memory, keyed by `state`), `SessionTokenService` (signs the HttpOnly cookie),
  `UserResolver`/`UserSession`. `UserResolver` collapses **either** a session cookie (web) **or**
  an `Authorization: Bearer` token (Android) to a single `userId`.
- `api/ApiRoutes.kt` — the **proxied** Etsy endpoints (`/api/me`, `/api/shop`, …). Each resolves a
  userId, loads that user's token, calls Etsy, returns JSON. **The token never crosses back to the
  client.**
- `etsy/` — `EtsyApi` (interface), `EtsyApiClient` (real, Ktor client + `x-api-key`),
  `FakeEtsyApi` (MOCK), `EtsyModels`. DTOs/serialization live in `dto/Dto.kt`.
- `token/` — `TokenStore` (interface) + `SqliteTokenStore`. Tokens keyed by userId; refreshed
  transparently when stale.
- `db/Database.kt` — DataSource / SQLite setup.
- `error/ApiExceptions.kt` — typed exceptions (e.g. `NotAuthenticatedException`) surfaced via
  StatusPages.

## Conventions

- **Bind by interface, wire in `AppModule`.** Swapping an implementation should be a one-line
  change confined to the composition root (that's why `TokenStore`, `PendingAuthStore`, `EtsyApi`,
  `UserResolver` are all interfaces).
- **DTOs stay in `dto`/`etsy`.** Use `kotlinx.serialization` + Ktor ContentNegotiation; don't leak
  raw Etsy wire shapes into unrelated layers. `Json { ignoreUnknownKeys = true }`.
- **Throw typed exceptions, map them in StatusPages** — don't hand-roll error responses per route.
- **No hardcoded identifiers or secrets** in source. Everything (keystring, secret, redirect URI,
  session sign key, DB path) comes from env via `AppConfig.fromEnv()`. The redirect URI must match
  what's registered on the Etsy app **byte-for-byte**.
- **Tokens are server-only.** The cookie carries **only the userId**; the token lives in SQLite.
  Never return a token, keystring, or secret to a client.
- **Right-size it.** Single process, SQLite, no distributed anything (Etsy caps personal access at
  5 shops). Clean boundaries over speculative abstraction. The owner reads this code to learn —
  clarity over cleverness, and surface non-obvious decisions.
- **Tests:** `kotlin.test`; use `ktor-server-test-host` for route tests and `FakeEtsyApi` /
  `MockUserResolver` to test without hitting Etsy. Prefer fakes over mocks.

## Hard rules

- **Draft-only, enforced here.** The listing-creation path (Task 9, `POST /api/listings`) runs
  `createDraftListing → uploadListingImage×N → uploadListingFile` and **must never call publish**.
  This is the server-side belt to the client's suspenders.
- **Every Etsy call goes through this BFF.** The client never calls Etsy directly and never holds a
  token — that invariant is the reason this tier exists.
- **Single shop now, modeled for many.** The token store is already keyed by userId (multi-tenant
  by construction); key shop-scoped data by `shop_id`. Don't build a shop switcher.

## App modes

`AppConfig.appMode` selects bindings in `AppModule`:
- **`MOCK`** → `FakeEtsyApi` + `MockUserResolver`: returns success with **no Etsy call and no
  sign-in**. Lets the client be built before the Etsy app is approved.
- **`LIVE`** → `EtsyApiClient` + `RealUserResolver`: real OAuth + real Etsy calls.

## Known open item — `x-api-key`

Etsy's docs contradict each other on whether `x-api-key` is the **keystring alone** or
`keystring:shared_secret`. It's isolated to the `useSharedSecretInApiKey` flag in `EtsyApiClient`.
Resolve **empirically** on the first live authenticated call (`/api/shop`, Task 4). If it
401/403s, flip the flag and retry. Also: the Etsy app may be in **"Pending Personal Approval"** —
live calls fail until it's active; that's not a code bug.

## Build & run

```bash
cp .env.example .env            # then edit with your real keystring/secret
./gradlew run                   # the run task auto-loads .env into the process env
./gradlew test                  # unit + route tests
curl localhost:8080/health      # -> ok  (no auth)
```

Live login needs a **browser** (PKCE has a human consent step): open
`http://localhost:8080/auth/login`, approve, then `http://localhost:8080/api/me`. See `README.md`
for the full walkthrough and the intentional production gaps (cross-site cookies, pending-auth
sweep, refresh races).
