package no.nav.helse.sykepenger.rettighetskategori.rest

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import no.nav.helse.sykepenger.rettighetskategori.Backfill
import no.nav.helse.sykepenger.rettighetskategori.RettighetskategoriRepository
import tools.jackson.core.JacksonException
import tools.jackson.module.kotlin.jacksonObjectMapper

private val fødselsnummerformat = Regex("\\d{11}")
private val forespørselMapper = jacksonObjectMapper()

/** Oppslag for andre tjenester. Ruten skal ligge under en provider som bare godtar maskin-til-maskin-token. */
internal fun Route.rettighetskategoriTjenesteApi(
    repository: RettighetskategoriRepository,
    backfill: Backfill,
) {
    post("/api/rettighetskategorier/{rettighetskategoriId}") {
        val id =
            rettighetskategoriId(call.parameters["rettighetskategoriId"])
                ?: return@post call.respondProblem(HttpStatusCode.BadRequest, "Ugyldig forespørsel")
        val fødselsnummer =
            fødselsnummerFraBody(call.receiveText())
                ?: return@post call.respondProblem(HttpStatusCode.BadRequest, "Ugyldig forespørsel")
        call.respondRettighetskategori(repository, backfill, id, fødselsnummer)
    }
}

private fun fødselsnummerFraBody(body: String): String? =
    try {
        forespørselMapper
            .readTree(body)
            ?.get("fødselsnummer")
            ?.takeIf { it.isString }
            ?.asString()
            ?.takeIf(fødselsnummerformat::matches)
    } catch (_: JacksonException) {
        null
    }
