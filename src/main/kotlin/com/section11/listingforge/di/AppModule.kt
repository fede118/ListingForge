package com.section11.listingforge.di

import com.section11.listingforge.auth.EtsyOAuthClient
import com.section11.listingforge.auth.InMemoryPendingAuthStore
import com.section11.listingforge.auth.MockUserResolver
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
 * different TokenStore is a one-line change confined to this file â€” that's the
 * payoff of wiring through interfaces.
 */
fun appModule(config: AppConfig) = module {
    single { config }

    single<DataSource> { Database.dataSource(get<AppConfig>().db.path) }

    single<TokenStore> { SqliteTokenStore(get()) }
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

    single { EtsyOAuthClient(get(), get()) }

    // Mode-selected bindings. In MOCK the upstream and the auth check are both
    // faked, so the app gets a success with no Etsy call and no sign-in. Mock
    // implementations are only ever constructed in a mock process.
    single<EtsyApi> {
        if (get<AppConfig>().appMode == AppMode.MOCK) FakeEtsyApi()
        else EtsyApiClient(get(), get(), get(), get())
    }
    single<UserResolver> {
        if (get<AppConfig>().appMode == AppMode.MOCK) MockUserResolver()
        else RealUserResolver(get())
    }
}
