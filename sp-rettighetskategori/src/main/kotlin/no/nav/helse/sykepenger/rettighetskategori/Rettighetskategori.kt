package no.nav.helse.sykepenger.rettighetskategori

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

internal enum class Yrkesaktivitet {
    Arbeidstaker,
    SelvstendigNæringsdrivende,
}

internal enum class Hovedkategori {
    ARBEIDSTAKER,
    SELVSTENDIG_NÆRINGSDRIVENDE,
    ARBEIDSTAKER_OG_SELVSTENDIG_NÆRINGSDRIVENDE,
    ;

    companion object {
        fun avgjør(yrkesaktiviteter: Set<Yrkesaktivitet>): Hovedkategori? =
            when (yrkesaktiviteter) {
                setOf(Yrkesaktivitet.Arbeidstaker) -> ARBEIDSTAKER
                setOf(Yrkesaktivitet.SelvstendigNæringsdrivende) -> SELVSTENDIG_NÆRINGSDRIVENDE
                setOf(Yrkesaktivitet.Arbeidstaker, Yrkesaktivitet.SelvstendigNæringsdrivende) -> ARBEIDSTAKER_OG_SELVSTENDIG_NÆRINGSDRIVENDE
                else -> null
            }
    }
}

internal enum class Kilde(
    val dbVerdi: String,
) {
    AUTOMATISK("automatisk"),
    SAKSBEHANDLER("saksbehandler"),
    SPLEIS("spleis"),
    ;

    companion object {
        fun fraDbVerdi(dbVerdi: String) = entries.single { it.dbVerdi == dbVerdi }
    }
}

internal data class Rettighetskategori(
    val id: UUID,
    val fødselsnummer: String,
    val skjæringstidspunkt: LocalDate,
    val hovedkategori: Hovedkategori,
    val kilde: Kilde,
    val vurderingstidspunkt: Instant,
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
            vurderingstidspunkt = Instant.now(),
            søknader = søknader,
        )

        fun fraSpleis(
            id: UUID,
            fødselsnummer: String,
            skjæringstidspunkt: LocalDate,
            hovedkategori: Hovedkategori,
            vurderingstidspunkt: Instant,
        ) = Rettighetskategori(
            id = id,
            fødselsnummer = fødselsnummer,
            skjæringstidspunkt = skjæringstidspunkt,
            hovedkategori = hovedkategori,
            kilde = Kilde.SPLEIS,
            vurderingstidspunkt = vurderingstidspunkt,
            søknader = emptyList(),
        )
    }
}
