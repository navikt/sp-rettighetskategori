package no.nav.helse.sykepenger.rettighetskategori

import com.github.navikt.tbd_libs.rapids_and_rivers.test_support.TestRapid
import org.intellij.lang.annotations.Language
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.time.LocalDate
import java.util.UUID

internal class RettighetskategoriResultatRiverTest {
    private val spedisjon = FakeSpedisjon()
    private val spleis = FakeSpleis()
    private val rapid =
        TestRapid().apply {
            registrerRivers(TestDatabase.dataSource, spedisjon.client, spleis.client)
        }

    @BeforeEach
    fun tømDatabasen() {
        TestDatabase.tøm()
    }

    @Test
    fun `gir hovedkategorien for en avgjort rettighetskategori`() {
        val arbeidstaker = avgjørRettighetskategori(spedisjon.sendtSøknadArbeidsgiver(FØDSELSNUMMER))
        val selvstendig = avgjørRettighetskategori(spedisjon.sendtSøknadSelvstendig(FØDSELSNUMMER))

        assertEquals("ARBEIDSTAKER", hovedkategoriFor(arbeidstaker))
        assertEquals("SELVSTENDIG_NÆRINGSDRIVENDE", hovedkategoriFor(selvstendig))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("historiskePersoner")
    fun `hovedkategorien for en ID fra Spleis avgjøres slik Spleis gjorde`(
        @Suppress("unused") beskrivelse: String,
        person: HistoriskPerson,
    ) {
        val rettighetskategoriId =
            spleis.rettighetskategori(
                FØDSELSNUMMER,
                aktive = person.aktive,
                forkastede = person.forkastede,
                kilde = person.kilde,
                harSelvstendigInntekt = person.harSelvstendigInntekt,
            )

        assertEquals(person.hovedkategori, hovedkategoriFor(rettighetskategoriId.toString()))
    }

    @ParameterizedTest
    @ValueSource(strings = ["FRILANS", "ARBEIDSLEDIG"])
    fun `yrkesaktivitetstype som ikke gir støttet hovedkategori gir ubesvart behov`(yrkesaktivitetstype: String) {
        val rettighetskategoriId = spleis.rettighetskategori(FØDSELSNUMMER, aktive = listOf("ARBEIDSTAKER", yrkesaktivitetstype))

        rapid.sendTestMessage(rettighetskategoriResultatBehov(rettighetskategoriId), FØDSELSNUMMER)

        assertEquals(0, rapid.inspektør.size)
    }

    @Test
    fun `ukjent rettighetskategori-ID fra Spleis backfilles og besvares`() {
        spleis.rettighetskategori(FØDSELSNUMMER, aktive = listOf("ARBEIDSTAKER"), skjæringstidspunkt = LocalDate.parse("2017-01-01"))
        val rettighetskategoriId = spleis.rettighetskategori(FØDSELSNUMMER, aktive = listOf("SELVSTENDIG"))

        assertEquals("SELVSTENDIG_NÆRINGSDRIVENDE", hovedkategoriFor(rettighetskategoriId.toString()))
        assertEquals(listOf(TestDatabase.Logginnslag(FØDSELSNUMMER, antallFraSpleis = 2, antallNye = 2)), TestDatabase.migreringslogg())

        assertEquals("SELVSTENDIG_NÆRINGSDRIVENDE", hovedkategoriFor(rettighetskategoriId.toString()))
        assertEquals(1, spleis.antallKall)
    }

    @Test
    fun `ukjent rettighetskategori-ID gir ubesvart behov`() {
        spleis.rettighetskategori(FØDSELSNUMMER, aktive = listOf("ARBEIDSTAKER"))

        rapid.sendTestMessage(rettighetskategoriResultatBehov(UUID.randomUUID()), FØDSELSNUMMER)

        assertEquals(0, rapid.inspektør.size)
        assertEquals(1, spleis.antallKall)
        assertEquals(listOf(TestDatabase.Logginnslag(FØDSELSNUMMER, antallFraSpleis = 1, antallNye = 1)), TestDatabase.migreringslogg())
    }

    @Test
    fun `feil fra spleis-api gir ubesvart behov`() {
        spleis.feiler()

        rapid.sendTestMessage(rettighetskategoriResultatBehov(UUID.randomUUID()), FØDSELSNUMMER)

        assertEquals(0, rapid.inspektør.size)
    }

    @Test
    fun `behov uten rettighetskategori-ID ignoreres`() {
        @Language("JSON")
        val behov = """
        {
          "@event_name": "behov",
          "@id": "${UUID.randomUUID()}",
          "@behov": ["RettighetskategoriResultat"],
          "fødselsnummer": "$FØDSELSNUMMER",
          "RettighetskategoriResultat": {}
        }
        """
        rapid.sendTestMessage(behov, FØDSELSNUMMER)

        assertEquals(0, rapid.inspektør.size)
    }

    private fun avgjørRettighetskategori(søknad: UUID): String {
        rapid.sendTestMessage(rettighetskategoriBehov(søknad), FØDSELSNUMMER)
        return rapid.inspektør
            .message(rapid.inspektør.size - 1)
            .path("@løsning")
            .path("Rettighetskategori")
            .path("id")
            .asString()
    }

    private fun hovedkategoriFor(rettighetskategoriId: String): String {
        val antallFør = rapid.inspektør.size
        rapid.sendTestMessage(rettighetskategoriResultatBehov(UUID.fromString(rettighetskategoriId)), FØDSELSNUMMER)
        assertEquals(antallFør + 1, rapid.inspektør.size)
        assertEquals(FØDSELSNUMMER, rapid.inspektør.key(antallFør))
        return rapid.inspektør
            .message(antallFør)
            .path("@løsning")
            .path("RettighetskategoriResultat")
            .path("hovedkategori")
            .asString()
    }

    internal data class HistoriskPerson(
        val aktive: List<String> = emptyList(),
        val forkastede: List<String> = emptyList(),
        val kilde: String = "SPLEIS",
        val harSelvstendigInntekt: Boolean = false,
        val hovedkategori: String,
    )

    private companion object {
        const val FØDSELSNUMMER = "12029240045"

        @JvmStatic
        fun historiskePersoner() =
            listOf(
                Arguments.of("arbeidstaker", HistoriskPerson(aktive = listOf("ARBEIDSTAKER"), hovedkategori = "ARBEIDSTAKER")),
                Arguments.of(
                    "selvstendig med selvstendig inntekt",
                    HistoriskPerson(aktive = listOf("SELVSTENDIG"), harSelvstendigInntekt = true, hovedkategori = "SELVSTENDIG_NÆRINGSDRIVENDE"),
                ),
                Arguments.of(
                    "arbeidstaker og selvstendig på samme vilkårsgrunnlag",
                    HistoriskPerson(
                        aktive = listOf("ARBEIDSTAKER", "SELVSTENDIG"),
                        harSelvstendigInntekt = true,
                        hovedkategori = "ARBEIDSTAKER_OG_SELVSTENDIG_NÆRINGSDRIVENDE",
                    ),
                ),
                Arguments.of("to arbeidsgivere", HistoriskPerson(aktive = listOf("ARBEIDSTAKER", "ARBEIDSTAKER"), hovedkategori = "ARBEIDSTAKER")),
                Arguments.of(
                    "aktive perioder går foran forkastede",
                    HistoriskPerson(aktive = listOf("ARBEIDSTAKER"), forkastede = listOf("ARBEIDSTAKER", "SELVSTENDIG"), hovedkategori = "ARBEIDSTAKER"),
                ),
                Arguments.of(
                    "bare forkastede perioder",
                    HistoriskPerson(forkastede = listOf("SELVSTENDIG"), hovedkategori = "SELVSTENDIG_NÆRINGSDRIVENDE"),
                ),
                Arguments.of(
                    "vilkårsgrunnlag fra Infotrygd",
                    HistoriskPerson(aktive = listOf("ARBEIDSTAKER"), kilde = "INFOTRYGD", hovedkategori = "ARBEIDSTAKER"),
                ),
                Arguments.of(
                    "vilkårsgrunnlag fra Infotrygd med bare forkastede selvstendig-perioder",
                    HistoriskPerson(forkastede = listOf("SELVSTENDIG"), kilde = "INFOTRYGD", hovedkategori = "ARBEIDSTAKER"),
                ),
                Arguments.of(
                    "uten perioder, med selvstendig inntekt",
                    HistoriskPerson(harSelvstendigInntekt = true, hovedkategori = "SELVSTENDIG_NÆRINGSDRIVENDE"),
                ),
                Arguments.of("uten perioder og uten selvstendig inntekt", HistoriskPerson(hovedkategori = "ARBEIDSTAKER")),
            )

        @Language("JSON")
        fun rettighetskategoriBehov(søknad: UUID) =
            """
            {
              "@event_name": "behov",
              "@id": "${UUID.randomUUID()}",
              "@behov": ["Rettighetskategori"],
              "fødselsnummer": "$FØDSELSNUMMER",
              "Rettighetskategori": {
                "skjæringstidspunkt": "2018-01-01",
                "søknader": ["$søknad"]
              }
            }
            """

        @Language("JSON")
        fun rettighetskategoriResultatBehov(rettighetskategoriId: UUID) =
            """
            {
              "@event_name": "behov",
              "@id": "${UUID.randomUUID()}",
              "@behov": ["RettighetskategoriResultat"],
              "fødselsnummer": "$FØDSELSNUMMER",
              "RettighetskategoriResultat": {
                "rettighetskategoriId": "$rettighetskategoriId"
              }
            }
            """
    }
}
