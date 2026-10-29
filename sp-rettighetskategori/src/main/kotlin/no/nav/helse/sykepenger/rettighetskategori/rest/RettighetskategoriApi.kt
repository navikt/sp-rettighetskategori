package no.nav.helse.sykepenger.rettighetskategori.rest

import com.github.navikt.tbd_libs.access_token.AccessTokenException
import com.github.navikt.tbd_libs.personpseudoid.PersonPseudoId
import com.github.navikt.tbd_libs.personpseudoid.PersonPseudoIdProvider
import com.github.navikt.tbd_libs.populasjonstilgang.api.PopulasjonstilgangskontrollProvider
import com.github.navikt.tbd_libs.populasjonstilgang.api.TilgangskontrollResultat
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.jackson3.jackson
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import no.nav.helse.sykepenger.rettighetskategori.Backfill
import no.nav.helse.sykepenger.rettighetskategori.Hovedkategori
import no.nav.helse.sykepenger.rettighetskategori.Kilde
import no.nav.helse.sykepenger.rettighetskategori.Rettighetskategori
import no.nav.helse.sykepenger.rettighetskategori.RettighetskategoriRepository
import no.nav.helse.sykepenger.rettighetskategori.SpleisClientException
import no.nav.sykepenger.libs.logging.navngittLogger
import tools.jackson.core.JacksonException
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

private val logger = navngittLogger("no.nav.helse.sykepenger.rettighetskategori.rest")

internal enum class ApiHovedkategori {
    ARBEIDSTAKER,
    SELVSTENDIG_NAERINGSDRIVENDE,
    ARBEIDSTAKER_OG_SELVSTENDIG_NAERINGSDRIVENDE,
}

internal enum class ApiKilde { AUTOMATISK, SAKSBEHANDLER, SPLEIS }

internal data class RettighetskategoriResponse(
    val id: UUID,
    val skjæringstidspunkt: LocalDate,
    val hovedkategori: ApiHovedkategori,
    val kilde: ApiKilde,
    val vurderingstidspunkt: Instant,
) {
    companion object {
        fun fraDomene(avgjørelse: Rettighetskategori) =
            RettighetskategoriResponse(
                avgjørelse.id,
                avgjørelse.skjæringstidspunkt,
                when (avgjørelse.hovedkategori) {
                    Hovedkategori.ARBEIDSTAKER -> ApiHovedkategori.ARBEIDSTAKER
                    Hovedkategori.SELVSTENDIG_NÆRINGSDRIVENDE -> ApiHovedkategori.SELVSTENDIG_NAERINGSDRIVENDE
                    Hovedkategori.ARBEIDSTAKER_OG_SELVSTENDIG_NÆRINGSDRIVENDE -> ApiHovedkategori.ARBEIDSTAKER_OG_SELVSTENDIG_NAERINGSDRIVENDE
                },
                when (avgjørelse.kilde) {
                    Kilde.AUTOMATISK -> ApiKilde.AUTOMATISK
                    Kilde.SAKSBEHANDLER -> ApiKilde.SAKSBEHANDLER
                    Kilde.SPLEIS -> ApiKilde.SPLEIS
                },
                avgjørelse.vurderingstidspunkt,
            )
    }
}

internal fun Application.configureRestApi(
    repository: RettighetskategoriRepository,
    backfill: Backfill,
    personProvider: PersonPseudoIdProvider,
    tilgangskontroll: PopulasjonstilgangskontrollProvider,
) {
    install(ContentNegotiation) { jackson() }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            logger.error("Uventet feil ved API-kall", cause)
            call.respondProblem(HttpStatusCode.InternalServerError, "Intern serverfeil")
        }
    }
    routing {
        authenticate(AZURE_AD_AUTHENTICATION_NAME) {
            rettighetskategoriApi(repository, backfill, personProvider, tilgangskontroll)
        }
    }
}

private fun Route.rettighetskategoriApi(
    repository: RettighetskategoriRepository,
    backfill: Backfill,
    personProvider: PersonPseudoIdProvider,
    tilgangskontroll: PopulasjonstilgangskontrollProvider,
) {
    get("/api/personer/{personPseudoId}/rettighetskategorier/{rettighetskategoriId}") {
        val principal = call.principal<SaksbehandlerPrincipal>() ?: return@get call.respond(HttpStatusCode.Unauthorized)
        if (!principal.harLesetilgang) {
            logger.debug("403: saksbehandler mangler lesetilgang")
            return@get call.respondProblem(HttpStatusCode.Forbidden, "Mangler tilgang")
        }
        val pseudoId =
            call.parameters["personPseudoId"]?.let(PersonPseudoId::fraString)
                ?: return@get call.respondProblem(HttpStatusCode.NotFound, "Ikke funnet")
        val ident =
            personProvider.finnIdentitetsnummer(pseudoId)
                ?: return@get call.respondProblem(HttpStatusCode.NotFound, "Ikke funnet")
        val tilgang =
            try {
                tilgangskontroll.kontrollerKjerneTilgang(principal.accessToken, ident.value)
            } catch (ex: IOException) {
                return@get call.respondTilgangsfeil(ex)
            } catch (ex: AccessTokenException) {
                return@get call.respondTilgangsfeil(ex)
            } catch (ex: JacksonException) {
                return@get call.respondTilgangsfeil(ex)
            } catch (ex: InterruptedException) {
                Thread.currentThread().interrupt()
                return@get call.respondTilgangsfeil(ex)
            }
        when (tilgang) {
            TilgangskontrollResultat.Ok -> Unit
            TilgangskontrollResultat.IdentIkkeFunnet -> return@get call.respondProblem(HttpStatusCode.NotFound, "Ikke funnet")
            is TilgangskontrollResultat.ManglerTilgang -> {
                logger.debug("403: populasjonstilgangskontrollen ga avslag", "tilgangSomMangler" to tilgang.tilgangSomMangler.name)
                return@get call.respondProblem(HttpStatusCode.Forbidden, "Mangler tilgang")
            }
            is TilgangskontrollResultat.UventetFeil -> {
                logger.debug("403: tilgangskontrollen feilet uventet", "forklaring" to tilgang.menneskeligLesbarForklaring)
                return@get call.respondProblem(HttpStatusCode.Forbidden, "Mangler tilgang")
            }
        }
        val rawId = call.parameters["rettighetskategoriId"]
        val id =
            try {
                UUID.fromString(rawId).also { require(it.toString().equals(rawId, ignoreCase = true)) }
            } catch (_: IllegalArgumentException) {
                return@get call.respondProblem(HttpStatusCode.BadRequest, "Ugyldig forespørsel")
            }
        val avgjørelse =
            repository.finn(id) ?: run {
                try {
                    backfill.backfill(ident.value)
                } catch (ex: SpleisClientException) {
                    logger.warn("Feil ved backfill fra Spleis", ex)
                    return@get call.respondProblem(HttpStatusCode.ServiceUnavailable, "Spleis er utilgjengelig")
                }
                repository.finn(id)
            } ?: return@get call.respondProblem(HttpStatusCode.NotFound, "Ikke funnet")
        if (avgjørelse.fødselsnummer != ident.value) return@get call.respondProblem(HttpStatusCode.NotFound, "Ikke funnet")
        call.respond(RettighetskategoriResponse.fraDomene(avgjørelse))
    }
}

private suspend fun ApplicationCall.respondTilgangsfeil(ex: Exception) {
    logger.debug("403: tilgangskontrollen kunne ikke fullføres", ex)
    respondProblem(HttpStatusCode.Forbidden, "Mangler tilgang")
}
