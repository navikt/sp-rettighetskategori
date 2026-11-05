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

internal enum class Tilleggskategori {
    JORDBRUKER,
}

internal data class Konklusjon(
    val hovedkategori: Hovedkategori,
    val tilleggskategorier: Set<Tilleggskategori>,
) {
    companion object {
        fun avgjør(søknader: List<Søknad>): Konklusjon {
            val yrkesaktiviteter = søknader.map { it.yrkesaktivitet }.toSet()
            val hovedkategori =
                checkNotNull(Hovedkategori.avgjør(yrkesaktiviteter)) {
                    "Fant ingen hovedkategori for yrkesaktivitetene $yrkesaktiviteter"
                }
            return Konklusjon(hovedkategori, søknader.flatMap { it.tilleggskategorier }.toSet())
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

internal data class Vurdering(
    val søknader: List<UUID>,
    val konklusjon: Konklusjon,
    val vurderingstidspunkt: Instant,
)

internal data class ManuellVurdering(
    val saksbehandlerIdent: String,
    val begrunnelse: String?,
    val erstatter: UUID,
)

internal data class Rettighetskategori(
    val id: UUID,
    val fødselsnummer: String,
    val skjæringstidspunkt: LocalDate,
    val konklusjon: Konklusjon,
    val kilde: Kilde,
    val vurderingstidspunkt: Instant,
) {
    val kanHaTilleggskategorier get() = konklusjon.hovedkategori in hovedkategorierMedTilleggskategorier

    fun manueltVurdert(
        tilleggskategorier: Set<Tilleggskategori>,
        vurderingstidspunkt: Instant,
    ): Rettighetskategori {
        check(kanHaTilleggskategorier) { "Hovedkategorien ${konklusjon.hovedkategori} kan ikke ha tilleggskategorier" }
        return copy(
            id = UUID.randomUUID(),
            konklusjon = konklusjon.copy(tilleggskategorier = tilleggskategorier),
            kilde = Kilde.SAKSBEHANDLER,
            vurderingstidspunkt = vurderingstidspunkt,
        )
    }

    companion object {
        private val hovedkategorierMedTilleggskategorier =
            setOf(Hovedkategori.SELVSTENDIG_NÆRINGSDRIVENDE, Hovedkategori.ARBEIDSTAKER_OG_SELVSTENDIG_NÆRINGSDRIVENDE)

        fun automatisk(
            fødselsnummer: String,
            skjæringstidspunkt: LocalDate,
            vurdering: Vurdering,
        ) = Rettighetskategori(
            id = UUID.randomUUID(),
            fødselsnummer = fødselsnummer,
            skjæringstidspunkt = skjæringstidspunkt,
            konklusjon = vurdering.konklusjon,
            kilde = Kilde.AUTOMATISK,
            vurderingstidspunkt = vurdering.vurderingstidspunkt,
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
            konklusjon = Konklusjon(hovedkategori, emptySet()),
            kilde = Kilde.SPLEIS,
            vurderingstidspunkt = vurderingstidspunkt,
        )
    }
}
