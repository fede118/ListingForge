package com.section11.listingforge.di

import com.section11.listingforge.auth.ConsentScreen
import com.section11.listingforge.auth.EtsyConsentScreen
import com.section11.listingforge.auth.EtsyOAuthClient
import com.section11.listingforge.auth.FakeConsentScreen
import com.section11.listingforge.auth.FakeOAuthClient
import com.section11.listingforge.auth.InMemoryPendingAuthStore
import com.section11.listingforge.auth.OAuthClient
import com.section11.listingforge.auth.PendingAuthStore
import com.section11.listingforge.auth.RealUserResolver
import com.section11.listingforge.auth.SessionTokenService
import com.section11.listingforge.auth.UserResolver
import com.section11.listingforge.config.AppConfig
import com.section11.listingforge.config.AppMode
import com.section11.listingforge.db.Database
import com.section11.listingforge.etsy.EtsyApi
import com.section11.listingforge.etsy.EtsyApiClient
import com.section11.listingforge.etsy.FakeEtsyApi
import com.section11.listingforge.template.SqliteTemplateStore
import com.section11.listingforge.template.TemplateStore
import com.section11.listingforge.token.SqliteTokenStore
import com.section11.listingforge.token.TokenStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.koin.dsl.module
import javax.sql.DataSource

/**
 * The single composition root. Every dependency is built here and bound by
 * interface wherever one exists (TokenStore, PendingAuthStore), so no other
 * class news-up its own collaborators. Swapping SqliteTokenStore for a
 * different TokenStore is a one-line change confined to this file — that's the
 * payoff of wiring through interfaces.
 *
 * Composition is split into a [baseModule] (mode-independent bindings) plus
 * exactly one of [mockModule] / [liveModule], picked by a **single** read of
 * `config.appMode` here in [appModule]. Koin modules are plain values, so this
 * is the idiomatic Koin shape for "mock vs. live implementation of the same
 * app" — combine modules with `+` rather than branching inside each binding.
 * Reading the flag once (instead of once per dependency) also makes it
 * structurally impossible to end up with a half-configured process, e.g. a
 * FakeEtsyApi paired with a live EtsyOAuthClient: mock implementations are
 * only ever constructed by [mockModule], and that module is only ever mixed
 * in as a whole.
 */
fun appModule(config: AppConfig) = baseModule(config) +
    if (config.appMode == AppMode.MOCK) mockModule else liveModule

/** Bindings shared by both MOCK and LIVE processes. */
fun baseModule(config: AppConfig) = module {
    single { config }

    single<DataSource> { Database.dataSource(get<AppConfig>().db.path) }

    single<TokenStore> { SqliteTokenStore(get()) }
    single<TemplateStore> { SqliteTemplateStore(get()) }
    single<PendingAuthStore> { InMemoryPendingAuthStore() }

    single {
        val cfg = get<AppConfig>()
        SessionTokenService(cfg.session.signKey.toByteArray(), cfg.session.tokenTtlSeconds)
    }

    single {
        HttpClient(CIO) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
    }

    // UserResolver is NOT mode-selected: both MOCK and LIVE use
    // RealUserResolver, so /api/* still 401s until a session actually exists.
    // MOCK exercises the real sign-in flow (login -> consent -> callback ->
    // session), it just fakes what's on the other end of the wire (see
    // mockModule/liveModule below).
    single<UserResolver> { RealUserResolver(get()) }
}

/**
 * MOCK-mode bindings: the Etsy upstream (EtsyApi) AND the OAuth token
 * exchange (OAuthClient) are faked - no network call ever leaves this
 * process, and the human consent step is a BFF-rendered stub page
 * (ConsentScreen) instead of Etsy's real one.
 */
val mockModule = module {
    single<EtsyApi> { FakeEtsyApi() }
    single<OAuthClient> { FakeOAuthClient() }
    single<ConsentScreen> { FakeConsentScreen() }
}

/** LIVE-mode bindings: real OAuth + real Etsy calls. */
val liveModule = module {
    single<EtsyApi> { EtsyApiClient(get(), get(), get(), get()) }
    single<OAuthClient> { EtsyOAuthClient(get(), get()) }
    single<ConsentScreen> { EtsyConsentScreen(get()) }
}
