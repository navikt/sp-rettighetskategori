package no.nav.helse.sykepenger.rettighetskategori

import no.nav.sykepenger.libs.logging.loggInfo

internal class Backfill(
    private val spleisClient: SpleisClient,
    private val repository: RettighetskategoriRepository,
) {
    fun backfill(fødselsnummer: String): List<Rettighetskategori> {
        val fraSpleis = spleisClient.rettighetskategorier(fødselsnummer)
        val rettighetskategorier =
            fraSpleis
                .filter { it.harAktiveYrkesaktiviteter }
                .map { it.tilRettighetskategori(fødselsnummer) }
        val antallNye = repository.lagreBackfill(fødselsnummer, rettighetskategorier, fraSpleis.size)
        loggInfo(
            "Backfill av rettighetskategorier fra Spleis er ferdig",
            "antallFraSpleis" to fraSpleis.size.toString(),
            "antallUtenAktiveYrkesaktiviteter" to (fraSpleis.size - rettighetskategorier.size).toString(),
            "antallNye" to antallNye.toString(),
        )
        return rettighetskategorier
    }
}
