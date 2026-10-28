package no.nav.helse.sykepenger.rettighetskategori

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

internal data class SpleisRettighetskategori(
    val id: UUID,
    val skjæringstidspunkt: LocalDate,
    val opprettet: Instant,
    val kilde: Kilde,
    val harSelvstendigInntekt: Boolean,
    val aktive: List<String>,
    val forkastede: List<String>,
) {
    enum class Kilde { SPLEIS, INFOTRYGD }

    val hovedkategori: Hovedkategori
        get() =
            when {
                kilde == Kilde.INFOTRYGD -> Hovedkategori.ARBEIDSTAKER
                aktive.isNotEmpty() -> hovedkategori(aktive)
                forkastede.isNotEmpty() -> hovedkategori(forkastede)
                harSelvstendigInntekt -> Hovedkategori.SELVSTENDIG_NÆRINGSDRIVENDE
                else -> Hovedkategori.ARBEIDSTAKER
            }

    private fun hovedkategori(yrkesaktivitetstyper: List<String>) =
        checkNotNull(Hovedkategori.avgjør(yrkesaktivitetstyper.map(::yrkesaktivitet).toSet())) {
            "Rettighetskategori $id har yrkesaktivitetstypene $yrkesaktivitetstyper, som ikke gir noen hovedkategori"
        }

    fun tilRettighetskategori(fødselsnummer: String) =
        Rettighetskategori.fraSpleis(
            id = id,
            fødselsnummer = fødselsnummer,
            skjæringstidspunkt = skjæringstidspunkt,
            hovedkategori = hovedkategori,
            vurderingstidspunkt = opprettet,
        )

    private fun yrkesaktivitet(yrkesaktivitetstype: String) =
        when (yrkesaktivitetstype) {
            "ARBEIDSTAKER" -> Yrkesaktivitet.Arbeidstaker
            "SELVSTENDIG" -> Yrkesaktivitet.SelvstendigNæringsdrivende
            else -> error("Rettighetskategori $id har yrkesaktivitetstypen $yrkesaktivitetstype, som ikke gir noen støttet hovedkategori")
        }
}
