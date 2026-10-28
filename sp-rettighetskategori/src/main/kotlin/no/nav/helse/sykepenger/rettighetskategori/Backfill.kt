package no.nav.helse.sykepenger.rettighetskategori

import no.nav.sykepenger.libs.logging.loggInfo

internal class Backfill(
    private val spleisClient: SpleisClient,
    private val repository: RettighetskategoriRepository,
) {
    fun backfill(fødselsnummer: String): List<Rettighetskategori> {
        val rettighetskategorier = spleisClient.rettighetskategorier(fødselsnummer).map { it.tilRettighetskategori(fødselsnummer) }
        val antallNye = repository.lagreBackfill(fødselsnummer, rettighetskategorier)
        loggInfo(
            "Backfill av rettighetskategorier fra Spleis er ferdig",
            "antallFraSpleis" to rettighetskategorier.size.toString(),
            "antallNye" to antallNye.toString(),
        )
        return rettighetskategorier
    }
}
