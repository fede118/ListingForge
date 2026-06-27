package com.section11.listingforge

import com.section11.listingforge.api.apiRoutes
import com.section11.listingforge.auth.EtsyOAuthClient
import com.section11.listingforge.auth.PendingAuthStore
import com.section11.listingforge.auth.SessionTokenService
import com.section11.listingforge.auth.authRoutes
import com.section11.listingforge.config.AppConfig
import com.section11.listingforge.di.appModule
import com.section11.listingforge.etsy.EtsyApiClient
import com.section11.listingforge.plugins.configureCallLogging
import com.section11.listingforge.plugins.configureCors
import com.section11.listingforge.plugins.configureSerialization
import com.section11.listingforge.plugins.configureSessions
import com.section11.listingforge.plugins.configureStatusPages
import com.section11.listingforge.token.TokenStore
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import org.koin.ktor.ext.inject
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger

fun main() {
    val config = AppConfig.fromEnv()   // fail fast on missing config, before binding the port
    embeddedServer(Netty, port = config.serverPort) {
        module(config)
    }.start(wait = true)
}

fun Application.module(config: AppConfig) {
    install(Koin) {
        slf4jLogger()
        modules(appModule(config))
    }

    configureCallLogging()
    configureSerialization()
    configureSessions(config)
    configureCors(config)
    configureStatusPages()

    // Resolve dependencies once at wiring time and pass them into the route
    // builders, rather than service-locating inside each handler.
    val pendingAuth by inject<PendingAuthStore>()
    val oauth by inject<EtsyOAuthClient>()
    val tokenStore by inject<TokenStore>()
    val etsyApi by inject<EtsyApiClient>()
    val sessionTokens by inject<SessionTokenService>()

    routing {
        get("/health") { call.respondText("ok") }
        authRoutes(config, pendingAuth, oauth, tokenStore, sessionTokens)
        apiRoutes(etsyApi, sessionTokens)
    }
}
