package no.nav.helse.sykepenger.rettighetskategori

import com.github.navikt.tbd_libs.result_object.getOrThrow
import com.github.navikt.tbd_libs.spedisjon.HentMeldingResponse
import com.github.navikt.tbd_libs.spedisjon.SpedisjonClient
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

internal class Søknader(
    private val spedisjonClient: SpedisjonClient,
) {
    fun yrkesaktiviteter(søknader: List<UUID>): Set<Yrkesaktivitet> {
        val meldinger = spedisjonClient.hentMeldinger(søknader).getOrThrow().meldinger
        val mangler = søknader - meldinger.map { it.internDokumentId }.toSet()
        check(mangler.isEmpty()) { "Spedisjon mangler søknader: $mangler" }
        return meldinger.map { it.yrkesaktivitet() }.toSet()
    }

    private fun HentMeldingResponse.yrkesaktivitet(): Yrkesaktivitet {
        val arbeidssituasjon = objectMapper.readTree(jsonBody).path("arbeidssituasjon").asString()
        return when {
            type in søknadstyperForArbeidstaker -> Yrkesaktivitet.Arbeidstaker
            type == "sendt_søknad_selvstendig" && arbeidssituasjon in arbeidssituasjonerForSelvstendig -> Yrkesaktivitet.SelvstendigNæringsdrivende
            else -> error("Søknad $internDokumentId av type $type med arbeidssituasjon $arbeidssituasjon gir ingen støttet yrkesaktivitet")
        }
    }

    private companion object {
        val objectMapper = jacksonObjectMapper()
        val søknadstyperForArbeidstaker = setOf("sendt_søknad_arbeidsgiver", "sendt_søknad_nav")
        val arbeidssituasjonerForSelvstendig = setOf("SELVSTENDIG_NARINGSDRIVENDE", "FISKER", "JORDBRUKER", "BARNEPASSER", "ANNET")
    }
}
