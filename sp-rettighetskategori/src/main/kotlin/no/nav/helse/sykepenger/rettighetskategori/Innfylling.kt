package no.nav.helse.sykepenger.rettighetskategori

import no.nav.sykepenger.libs.logging.loggInfo

internal class Innfylling(
    private val spleisClient: SpleisClient,
    private val repository: RettighetskategoriRepository,
) {
    fun fyllInn(fødselsnummer: String): List<Rettighetskategori> {
        val rettighetskategorier = spleisClient.rettighetskategorier(fødselsnummer).map { it.tilRettighetskategori(fødselsnummer) }
        val antallNye = repository.fyllInn(fødselsnummer, rettighetskategorier)
        loggInfo(
            "Fylte inn rettighetskategorier fra Spleis",
            "antallFraSpleis" to rettighetskategorier.size.toString(),
            "antallNye" to antallNye.toString(),
        )
        return rettighetskategorier
    }
}
