package no.nav.helse.sykepenger.rettighetskategori

import com.github.navikt.tbd_libs.result_object.getOrThrow
import com.github.navikt.tbd_libs.spedisjon.HentMeldingResponse
import com.github.navikt.tbd_libs.spedisjon.SpedisjonClient
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

internal data class Søknad(
    val yrkesaktivitet: Yrkesaktivitet,
    val tilleggskategorier: Set<Tilleggskategori>,
)

internal class Søknader(
    private val spedisjonClient: SpedisjonClient,
) {
    fun hent(søknader: List<UUID>): List<Søknad> {
        val meldinger = spedisjonClient.hentMeldinger(søknader).getOrThrow().meldinger
        val mangler = søknader - meldinger.map { it.internDokumentId }.toSet()
        check(mangler.isEmpty()) { "Spedisjon mangler søknader: $mangler" }
        return meldinger.map { it.søknad() }
    }

    private fun HentMeldingResponse.søknad(): Søknad {
        val arbeidssituasjon = objectMapper.readTree(jsonBody).path("arbeidssituasjon").asString()
        return when {
            type in søknadstyperForArbeidstaker -> Søknad(Yrkesaktivitet.Arbeidstaker, emptySet())
            type == SØKNADSTYPE_FOR_SELVSTENDIG && arbeidssituasjon in arbeidssituasjonerForSelvstendig ->
                Søknad(Yrkesaktivitet.SelvstendigNæringsdrivende, tilleggskategorierForSelvstendig[arbeidssituasjon].orEmpty())
            else -> error("Søknad $internDokumentId av type $type med arbeidssituasjon $arbeidssituasjon gir ingen støttet yrkesaktivitet")
        }
    }

    private companion object {
        val objectMapper = jacksonObjectMapper()
        val søknadstyperForArbeidstaker = setOf("sendt_søknad_arbeidsgiver", "sendt_søknad_nav")
        const val SØKNADSTYPE_FOR_SELVSTENDIG = "sendt_søknad_selvstendig"
        val arbeidssituasjonerForSelvstendig = setOf("SELVSTENDIG_NARINGSDRIVENDE", "FISKER", "BARNEPASSER", "ANNET", "JORDBRUKER")
        val tilleggskategorierForSelvstendig = mapOf("JORDBRUKER" to setOf(Tilleggskategori.JORDBRUKER))
    }
}
