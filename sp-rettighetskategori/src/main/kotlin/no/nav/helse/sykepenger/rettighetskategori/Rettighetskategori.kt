package no.nav.helse.sykepenger.rettighetskategori

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

internal enum class Yrkesaktivitet {
    Arbeidstaker,
    SelvstendigNæringsdrivende,
}

internal enum class Hovedkategori {
    Arbeidstaker,
    SelvstendigNæringsdrivende,
    ;

    companion object {
        fun avgjør(yrkesaktiviteter: Set<Yrkesaktivitet>): Hovedkategori? =
            when (yrkesaktiviteter) {
                setOf(Yrkesaktivitet.Arbeidstaker) -> Arbeidstaker
                setOf(Yrkesaktivitet.SelvstendigNæringsdrivende) -> SelvstendigNæringsdrivende
                else -> null
            }
    }
}

internal enum class Kilde {
    AUTOMATISK,
}

internal data class Rettighetskategori(
    val id: UUID,
    val fødselsnummer: String,
    val skjæringstidspunkt: LocalDate,
    val hovedkategori: Hovedkategori,
    val kilde: Kilde,
    val tidspunkt: Instant,
    val søknader: List<UUID>,
) {
    companion object {
        fun automatisk(
            fødselsnummer: String,
            skjæringstidspunkt: LocalDate,
            hovedkategori: Hovedkategori,
            søknader: List<UUID>,
        ) = Rettighetskategori(
            id = UUID.randomUUID(),
            fødselsnummer = fødselsnummer,
            skjæringstidspunkt = skjæringstidspunkt,
            hovedkategori = hovedkategori,
            kilde = Kilde.AUTOMATISK,
            tidspunkt = Instant.now(),
            søknader = søknader,
        )
    }
}
