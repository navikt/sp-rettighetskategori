package no.nav.helse.sykepenger.rettighetskategori

import com.github.navikt.tbd_libs.rapids_and_rivers.test_support.TestRapid
import org.intellij.lang.annotations.Language
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

internal class RettighetskategoriResultatRiverTest {
    private val spedisjon = FakeSpedisjon()
    private val rapid =
        TestRapid().apply {
            registrerRivers(TestDatabase.dataSource, spedisjon.client)
        }

    @BeforeEach
    fun tømDatabasen() {
        TestDatabase.tøm()
    }

    @Test
    fun `gir hovedkategorien for en avgjort rettighetskategori`() {
        val arbeidstaker = avgjørRettighetskategori(spedisjon.sendtSøknadArbeidsgiver(FØDSELSNUMMER))
        val selvstendig = avgjørRettighetskategori(spedisjon.sendtSøknadSelvstendig(FØDSELSNUMMER))

        assertEquals("Arbeidstaker", hovedkategoriFor(arbeidstaker))
        assertEquals("SelvstendigNæringsdrivende", hovedkategoriFor(selvstendig))
    }

    @Test
    fun `ukjent rettighetskategori-ID gir ubesvart behov`() {
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

    private companion object {
        const val FØDSELSNUMMER = "12029240045"

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
