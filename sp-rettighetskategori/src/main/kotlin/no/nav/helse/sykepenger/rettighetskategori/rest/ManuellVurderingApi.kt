package no.nav.helse.sykepenger.rettighetskategori.rest

import com.github.navikt.tbd_libs.personpseudoid.PersonPseudoIdProvider
import com.github.navikt.tbd_libs.populasjonstilgang.api.PopulasjonstilgangskontrollProvider
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.principal
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import no.nav.helse.sykepenger.rettighetskategori.Backfill
import no.nav.helse.sykepenger.rettighetskategori.ManuellVurdering
import no.nav.helse.sykepenger.rettighetskategori.RettighetskategoriRepository
import no.nav.helse.sykepenger.rettighetskategori.Tilleggskategori
import no.nav.sykepenger.libs.ktor.auth.SaksbehandlerPrincipal
import no.nav.sykepenger.libs.ktor.auth.Tilgang
import no.nav.sykepenger.libs.logging.loggInfo
import tools.jackson.core.JacksonException
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant

private val forespørselMapper = jacksonObjectMapper()

private data class ManuellVurderingRequest(
    val tilleggskategorier: Set<Tilleggskategori>,
    val begrunnelse: String?,
)

/** Saksbehandler legger til eller fjerner tilleggskategorier. Gir en ny avgjørelse som erstatter den gjeldende. */
internal fun Route.manuellVurderingApi(
    repository: RettighetskategoriRepository,
    backfill: Backfill,
    personProvider: PersonPseudoIdProvider,
    tilgangskontroll: PopulasjonstilgangskontrollProvider,
) {
    post("/api/personer/{personPseudoId}/rettighetskategorier/{rettighetskategoriId}/manuell-vurdering") {
        val fødselsnummer = call.fødselsnummerMedTilgang(Tilgang.Skriv, personProvider, tilgangskontroll) ?: return@post
        val saksbehandler = checkNotNull(call.principal<SaksbehandlerPrincipal>()).saksbehandler
        val id =
            rettighetskategoriId(call.parameters["rettighetskategoriId"])
                ?: return@post call.respondProblem(HttpStatusCode.BadRequest, "Ugyldig forespørsel")
        val forespørsel =
            manuellVurderingRequest(call.receiveText())
                ?: return@post call.respondProblem(HttpStatusCode.BadRequest, "Ugyldig forespørsel")
        val erstattes = call.finnAvgjørelse(repository, backfill, id, fødselsnummer) ?: return@post
        if (repository.gjeldende(fødselsnummer, erstattes.skjæringstidspunkt)?.id != erstattes.id) {
            return@post call.respondProblem(HttpStatusCode.Conflict, "Rettighetskategorien er endret")
        }
        if (!erstattes.kanHaTilleggskategorier) {
            return@post call.respondProblem(HttpStatusCode.BadRequest, "Hovedkategorien kan ikke ha tilleggskategorier")
        }
        if (erstattes.konklusjon.tilleggskategorier == forespørsel.tilleggskategorier) {
            return@post call.respond(RettighetskategoriResponse.fraDomene(erstattes))
        }

        val ny = erstattes.manueltVurdert(forespørsel.tilleggskategorier, Instant.now())
        val manuellVurdering = ManuellVurdering(saksbehandler.navIdent.value, forespørsel.begrunnelse, erstattes.id)
        if (!repository.lagreManuellVurdering(ny, manuellVurdering)) {
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

private fun manuellVurderingRequest(body: String): ManuellVurderingRequest? {
    val json =
        try {
            forespørselMapper.readTree(body)
        } catch (_: JacksonException) {
            null
        }?.takeIf { it.isObject } ?: return null
    val tilleggskategorier =
        json.get("tilleggskategorier")?.takeIf { it.isArray }?.toList()?.map { kategori ->
            ApiTilleggskategori.entries.singleOrNull { kategori.isString && it.name == kategori.asString() } ?: return null
        } ?: return null
    val begrunnelseNode = json.get("begrunnelse")
    val begrunnelse =
        when {
            begrunnelseNode == null || begrunnelseNode.isNull -> null
            begrunnelseNode.isString -> begrunnelseNode.asString().takeIf(String::isNotBlank)
            else -> return null
        }
    return ManuellVurderingRequest(
        tilleggskategorier.map { it.tilDomene() }.toSet(),
        begrunnelse,
    )
}

private fun ApiTilleggskategori.tilDomene() =
    when (this) {
        ApiTilleggskategori.JORDBRUKER -> Tilleggskategori.JORDBRUKER
    }
