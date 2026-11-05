package no.nav.helse.sykepenger.rettighetskategori.rest

import com.github.navikt.tbd_libs.personpseudoid.PersonPseudoIdProvider
import com.github.navikt.tbd_libs.populasjonstilgang.api.PopulasjonstilgangskontrollProvider
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import no.nav.helse.sykepenger.rettighetskategori.Backfill
import no.nav.helse.sykepenger.rettighetskategori.LagreManuellVurdering
import no.nav.helse.sykepenger.rettighetskategori.ManuellVurdering
import no.nav.helse.sykepenger.rettighetskategori.RettighetskategoriRepository
import no.nav.helse.sykepenger.rettighetskategori.Tilleggskategori
import no.nav.sykepenger.libs.ktor.auth.SaksbehandlerPrincipal
import no.nav.sykepenger.libs.ktor.auth.Tilgang
import no.nav.sykepenger.libs.logging.loggInfo
import java.time.Instant

internal data class ManuellVurderingRequest(
    val tilleggskategorier: Set<ApiTilleggskategori>,
    val begrunnelse: String? = null,
)

internal fun Route.manuellVurderingApi(
    aktivert: Boolean,
    repository: RettighetskategoriRepository,
    backfill: Backfill,
    personProvider: PersonPseudoIdProvider,
    tilgangskontroll: PopulasjonstilgangskontrollProvider,
    lagreManuellVurdering: LagreManuellVurdering,
) {
    post("/api/personer/{personPseudoId}/rettighetskategorier/{rettighetskategoriId}/manuell-vurdering") {
        if (!aktivert) return@post call.respondProblem(HttpStatusCode.NotFound, "Ikke funnet")
        val fødselsnummer = call.fødselsnummerMedTilgang(Tilgang.Skriv, personProvider, tilgangskontroll) ?: return@post
        val saksbehandler = checkNotNull(call.principal<SaksbehandlerPrincipal>()).saksbehandler
        val id =
            rettighetskategoriId(call.parameters["rettighetskategoriId"])
                ?: return@post call.respondProblem(HttpStatusCode.BadRequest, "Ugyldig forespørsel")
        val forespørsel = call.receive<ManuellVurderingRequest>()
        val tilleggskategorier = forespørsel.tilleggskategorier.map { it.tilDomene() }.toSet()
        val erstattes = call.finnAvgjørelse(repository, backfill, id, fødselsnummer) ?: return@post
        if (repository.gjeldende(fødselsnummer, erstattes.skjæringstidspunkt)?.id != erstattes.id) {
            return@post call.respondProblem(HttpStatusCode.Conflict, "Rettighetskategorien er endret")
        }
        if (!erstattes.kanHaTilleggskategorier) {
            return@post call.respondProblem(HttpStatusCode.BadRequest, "Hovedkategorien kan ikke ha tilleggskategorier")
        }
        if (erstattes.konklusjon.tilleggskategorier == tilleggskategorier) {
            return@post call.respond(RettighetskategoriResponse.fraDomene(erstattes))
        }

        val ny = erstattes.manueltVurdert(tilleggskategorier, Instant.now())
        val manuellVurdering = ManuellVurdering(saksbehandler.navIdent.value, forespørsel.begrunnelse?.takeIf(String::isNotBlank), erstattes.id)
        if (!lagreManuellVurdering.lagre(ny, manuellVurdering)) {
            return@post call.respondProblem(HttpStatusCode.Conflict, "Rettighetskategorien er endret")
        }
        loggInfo(
            "Lagret manuell vurdering av rettighetskategori",
            "rettighetskategoriId" to ny.id.toString(),
            "erstatter" to erstattes.id.toString(),
        )
        call.respond(RettighetskategoriResponse.fraDomene(ny))
    }
}

private fun ApiTilleggskategori.tilDomene() =
    when (this) {
        ApiTilleggskategori.JORDBRUKER -> Tilleggskategori.JORDBRUKER
    }
