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
import io.ktor.server.plugins.BadRequestException
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
import no.nav.helse.sykepenger.rettighetskategori.Tilleggskategori
import no.nav.sykepenger.libs.ktor.auth.AZURE_AD_APP_AUTHENTICATION_NAME
import no.nav.sykepenger.libs.ktor.auth.AZURE_AD_AUTHENTICATION_NAME
import no.nav.sykepenger.libs.ktor.auth.SaksbehandlerPrincipal
import no.nav.sykepenger.libs.ktor.auth.Tilgang
import no.nav.sykepenger.libs.logging.navngittLogger
import tools.jackson.core.JacksonException
import tools.jackson.databind.cfg.CoercionAction
import tools.jackson.databind.cfg.CoercionInputShape
import tools.jackson.databind.cfg.EnumFeature
import tools.jackson.databind.type.LogicalType
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

internal enum class ApiTilleggskategori { JORDBRUKER }

internal enum class ApiKilde { AUTOMATISK, SAKSBEHANDLER, SPLEIS }

internal data class RettighetskategoriResponse(
    val id: UUID,
    val skjæringstidspunkt: LocalDate,
    val hovedkategori: ApiHovedkategori,
    val tilleggskategorier: List<ApiTilleggskategori>,
    val kilde: ApiKilde,
    val vurderingstidspunkt: Instant,
) {
    companion object {
        fun fraDomene(avgjørelse: Rettighetskategori) =
            RettighetskategoriResponse(
                avgjørelse.id,
                avgjørelse.skjæringstidspunkt,
                when (avgjørelse.konklusjon.hovedkategori) {
                    Hovedkategori.ARBEIDSTAKER -> ApiHovedkategori.ARBEIDSTAKER
                    Hovedkategori.SELVSTENDIG_NÆRINGSDRIVENDE -> ApiHovedkategori.SELVSTENDIG_NAERINGSDRIVENDE
                    Hovedkategori.ARBEIDSTAKER_OG_SELVSTENDIG_NÆRINGSDRIVENDE -> ApiHovedkategori.ARBEIDSTAKER_OG_SELVSTENDIG_NAERINGSDRIVENDE
                },
                avgjørelse.konklusjon.tilleggskategorier.sorted().map {
                    when (it) {
                        Tilleggskategori.JORDBRUKER -> ApiTilleggskategori.JORDBRUKER
                    }
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
    manuellVurderingAktivert: Boolean,
) {
    install(ContentNegotiation) {
        jackson {
            enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            withCoercionConfig(LogicalType.Textual) { tekst ->
                listOf(CoercionInputShape.Integer, CoercionInputShape.Float, CoercionInputShape.Boolean)
                    .forEach { tekst.setCoercion(it, CoercionAction.Fail) }
            }
        }
    }
    install(StatusPages) {
        exception<BadRequestException> { call, cause ->
            logger.debug("400: ugyldig forespørsel", cause)
            call.respondProblem(HttpStatusCode.BadRequest, "Ugyldig forespørsel")
        }
        exception<Throwable> { call, cause ->
            logger.error("Uventet feil ved API-kall", cause)
            call.respondProblem(HttpStatusCode.InternalServerError, "Intern serverfeil")
        }
    }
    routing {
        authenticate(AZURE_AD_AUTHENTICATION_NAME) {
            rettighetskategoriApi(repository, backfill, personProvider, tilgangskontroll)
            manuellVurderingApi(manuellVurderingAktivert, repository, backfill, personProvider, tilgangskontroll)
        }
        authenticate(AZURE_AD_APP_AUTHENTICATION_NAME) {
            rettighetskategoriTjenesteApi(repository, backfill)
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
        val fødselsnummer = call.fødselsnummerMedTilgang(Tilgang.Les, personProvider, tilgangskontroll) ?: return@get
        val id =
            rettighetskategoriId(call.parameters["rettighetskategoriId"])
                ?: return@get call.respondProblem(HttpStatusCode.BadRequest, "Ugyldig forespørsel")
        call.respondRettighetskategori(repository, backfill, id, fødselsnummer)
    }
}

/** Svarer med feil og gir `null` når saksbehandleren mangler [tilgang] eller tilgang til personen. */
internal suspend fun ApplicationCall.fødselsnummerMedTilgang(
    tilgang: Tilgang,
    personProvider: PersonPseudoIdProvider,
    tilgangskontroll: PopulasjonstilgangskontrollProvider,
): String? {
    val principal = principal<SaksbehandlerPrincipal>() ?: return null.also { respond(HttpStatusCode.Unauthorized) }
    if (!principal.harTilgang(tilgang)) {
        logger.debug("403: saksbehandler mangler tilgang", "tilgang" to tilgang.name)
        return null.also { respondProblem(HttpStatusCode.Forbidden, "Mangler tilgang") }
    }
    val pseudoId =
        parameters["personPseudoId"]?.let(PersonPseudoId::fraString)
            ?: return null.also { respondProblem(HttpStatusCode.NotFound, "Ikke funnet") }
    val ident =
        personProvider.finnIdentitetsnummer(pseudoId)
            ?: return null.also { respondProblem(HttpStatusCode.NotFound, "Ikke funnet") }
    val resultat =
        try {
            tilgangskontroll.kontrollerKjerneTilgang(principal.accessToken.value, ident.value)
        } catch (ex: IOException) {
            return null.also { respondTilgangsfeil(ex) }
        } catch (ex: AccessTokenException) {
            return null.also { respondTilgangsfeil(ex) }
        } catch (ex: JacksonException) {
            return null.also { respondTilgangsfeil(ex) }
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
            return null.also { respondTilgangsfeil(ex) }
        }
    return when (resultat) {
        TilgangskontrollResultat.Ok -> ident.value
        TilgangskontrollResultat.IdentIkkeFunnet -> null.also { respondProblem(HttpStatusCode.NotFound, "Ikke funnet") }
        is TilgangskontrollResultat.ManglerTilgang -> {
            logger.debug("403: populasjonstilgangskontrollen ga avslag", "tilgangSomMangler" to resultat.tilgangSomMangler.name)
            null.also { respondProblem(HttpStatusCode.Forbidden, "Mangler tilgang") }
        }
        is TilgangskontrollResultat.UventetFeil -> {
            logger.debug("403: tilgangskontrollen feilet uventet", "forklaring" to resultat.menneskeligLesbarForklaring)
            null.also { respondProblem(HttpStatusCode.Forbidden, "Mangler tilgang") }
        }
    }
}

internal fun rettighetskategoriId(rawId: String?): UUID? =
    try {
        UUID.fromString(rawId).takeIf { it.toString().equals(rawId, ignoreCase = true) }
    } catch (_: IllegalArgumentException) {
        null
    }

internal suspend fun ApplicationCall.respondRettighetskategori(
    repository: RettighetskategoriRepository,
    backfill: Backfill,
    id: UUID,
    fødselsnummer: String,
) {
    val avgjørelse = finnAvgjørelse(repository, backfill, id, fødselsnummer) ?: return
    respond(RettighetskategoriResponse.fraDomene(avgjørelse))
}

/**
 * Er ID-en ukjent, kjøres backfill fra Spleis for fødselsnummeret før et nytt oppslag. En avgjørelse for en annen person gir 404.
 * Svarer med feil og gir `null` når avgjørelsen ikke finnes.
 */
internal suspend fun ApplicationCall.finnAvgjørelse(
    repository: RettighetskategoriRepository,
    backfill: Backfill,
    id: UUID,
    fødselsnummer: String,
): Rettighetskategori? {
    val avgjørelse =
        repository.finn(id) ?: run {
            try {
                backfill.backfill(fødselsnummer)
            } catch (ex: SpleisClientException) {
                logger.warn("Feil ved backfill fra Spleis", ex)
                return null.also { respondProblem(HttpStatusCode.ServiceUnavailable, "Spleis er utilgjengelig") }
            }
            repository.finn(id)
        }
    if (avgjørelse?.fødselsnummer != fødselsnummer) return null.also { respondProblem(HttpStatusCode.NotFound, "Ikke funnet") }
    return avgjørelse
}

private suspend fun ApplicationCall.respondTilgangsfeil(ex: Exception) {
    logger.debug("403: tilgangskontrollen kunne ikke fullføres", ex)
    respondProblem(HttpStatusCode.Forbidden, "Mangler tilgang")
}
