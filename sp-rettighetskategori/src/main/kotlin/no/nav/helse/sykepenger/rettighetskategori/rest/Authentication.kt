package no.nav.helse.sykepenger.rettighetskategori.rest

import com.auth0.jwk.JwkProviderBuilder
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.response.respond
import java.net.URI
import java.util.concurrent.TimeUnit

internal const val AZURE_AD_AUTHENTICATION_NAME = "azure-ad-jwt"

internal data class SaksbehandlerPrincipal(
    val navIdent: String,
    val oid: String,
    val harLesetilgang: Boolean,
    val accessToken: String,
)

internal data class AzureAdConfig(
    val clientId: String,
    val issuerUrl: String,
    val jwkProviderUri: String,
) {
    companion object {
        fun fraEnv(env: Map<String, String>) =
            AzureAdConfig(
                env.getValue("AZURE_APP_CLIENT_ID"),
                env.getValue("AZURE_OPENID_CONFIG_ISSUER"),
                env.getValue("AZURE_OPENID_CONFIG_JWKS_URI"),
            )
    }
}

internal class Tilgangsgrupper(
    private val lesegrupper: Set<String>,
    private val skrivegrupper: Set<String>,
) {
    fun harLesetilgang(grupper: Set<String>): Boolean = lesegrupper.any { it in grupper } || skrivegrupper.any { it in grupper }

    companion object {
        fun fraEnv(env: Map<String, String>) = Tilgangsgrupper(grupper(env.getValue("TILGANG_LES")), grupper(env.getValue("TILGANG_SKRIV")))

        private fun grupper(verdi: String) =
            verdi
                .split(",")
                .map(String::trim)
                .filter(String::isNotEmpty)
                .toSet()
    }
}

internal fun Application.configureJwtAuthentication(
    config: AzureAdConfig,
    tilgangsgrupper: Tilgangsgrupper,
) {
    val jwks =
        JwkProviderBuilder(URI(config.jwkProviderUri).toURL())
            .cached(10, 24, TimeUnit.HOURS)
            .rateLimited(10, 1, TimeUnit.MINUTES)
            .build()
    install(Authentication) {
        jwt(AZURE_AD_AUTHENTICATION_NAME) {
            verifier(jwks, config.issuerUrl) { withAudience(config.clientId) }
            validate { credential ->
                val payload = credential.payload
                val navIdent = payload.getClaim("NAVident").asString()?.takeIf(String::isNotBlank) ?: return@validate null
                val oid = payload.getClaim("oid").asString()?.takeIf(String::isNotBlank) ?: return@validate null
                val gruppeclaim = payload.getClaim("groups")
                val grupper =
                    if (gruppeclaim.isMissing || gruppeclaim.isNull) {
                        emptySet()
                    } else {
                        gruppeclaim.asList(String::class.java)?.toSet() ?: return@validate null
                    }
                val token = request.headers["Authorization"]?.substringAfter(' ') ?: return@validate null
                SaksbehandlerPrincipal(navIdent, oid, tilgangsgrupper.harLesetilgang(grupper), token)
            }
            challenge { _, _ -> call.respond(HttpStatusCode.Unauthorized) }
        }
    }
}
