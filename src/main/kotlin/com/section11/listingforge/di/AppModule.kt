package com.section11.listingforge.di

import com.section11.listingforge.auth.EtsyOAuthClient
import com.section11.listingforge.auth.InMemoryPendingAuthStore
import com.section11.listingforge.auth.PendingAuthStore
import com.section11.listingforge.auth.SessionTokenService
import com.section11.listingforge.config.AppConfig
import com.section11.listingforge.db.Database
import com.section11.listingforge.etsy.EtsyApiClient
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

    single<DataSource> { Database.dataSource(get<AppConfig>().dbPath) }

    single<TokenStore> { SqliteTokenStore(get()) }
    single<PendingAuthStore> { InMemoryPendingAuthStore() }

    single {
        val cfg = get<AppConfig>()
        SessionTokenService(cfg.sessionSignKey.toByteArray(), cfg.sessionTokenTtlSeconds)
    }

    single {
        HttpClient(CIO) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
    }

    single { EtsyOAuthClient(get(), get()) }
    single { EtsyApiClient(get(), get(), get(), get()) }
}
