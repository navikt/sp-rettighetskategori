package no.nav.helse.sykepenger.rettighetskategori

import com.github.navikt.tbd_libs.rapids_and_rivers.test_support.TestRapid
import kotliquery.queryOf
import kotliquery.sessionOf
import org.intellij.lang.annotations.Language
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.LocalDate
import java.util.UUID

internal class RettighetskategoriRiverTest {
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
    fun `søknad fra arbeidstaker gir hovedkategorien Arbeidstaker`() {
        val søknad = spedisjon.sendtSøknadArbeidsgiver(FØDSELSNUMMER)

        rapid.sendTestMessage(rettighetskategoriBehov(søknad), FØDSELSNUMMER)

        assertEquals(1, rapid.inspektør.size)
        assertEquals(FØDSELSNUMMER, rapid.inspektør.key(0))
        val løsning = rapid.inspektør.message(0).løsning()
        assertEquals("Arbeidstaker", løsning.path("hovedkategori").asString())
        assertEquals(
            Rad(
                id = UUID.fromString(løsning.path("id").asString()),
                fødselsnummer = FØDSELSNUMMER,
                skjæringstidspunkt = SKJÆRINGSTIDSPUNKT,
                hovedkategori = "Arbeidstaker",
                kilde = "automatisk",
                søknader = listOf(søknad),
            ),
            lagredeRader().single(),
        )
    }

    @Test
    fun `søknad fra selvstendig næringsdrivende gir hovedkategorien Selvstendig næringsdrivende`() {
        val søknad = spedisjon.sendtSøknadSelvstendig(FØDSELSNUMMER)

        rapid.sendTestMessage(rettighetskategoriBehov(søknad), FØDSELSNUMMER)

        val løsning = rapid.inspektør.message(0).løsning()
        assertEquals("SelvstendigNæringsdrivende", løsning.path("hovedkategori").asString())
        val lagret = lagredeRader().single()
        assertEquals(UUID.fromString(løsning.path("id").asString()), lagret.id)
        assertEquals("SelvstendigNæringsdrivende", lagret.hovedkategori)
        assertEquals(listOf(søknad), lagret.søknader)
    }

    @Test
    fun `søknad som Spedisjon ikke har, gir ubesvart behov`() {
        rapid.sendTestMessage(rettighetskategoriBehov(UUID.randomUUID()), FØDSELSNUMMER)

        assertEquals(0, rapid.inspektør.size)
        assertEquals(emptyList<Rad>(), lagredeRader())
    }

    @Test
    fun `behov med løsning ignoreres`() {
        val søknad = spedisjon.sendtSøknadArbeidsgiver(FØDSELSNUMMER)
        val behov =
            (objectMapper.readTree(rettighetskategoriBehov(søknad)) as ObjectNode).apply {
                putObject("@løsning").putObject("Rettighetskategori").put("id", "${UUID.randomUUID()}")
            }

        rapid.sendTestMessage(behov.toString(), FØDSELSNUMMER)

        assertEquals(0, rapid.inspektør.size)
    }

    private fun JsonNode.løsning() = path("@løsning").path("Rettighetskategori")

    private data class Rad(
        val id: UUID,
        val fødselsnummer: String,
        val skjæringstidspunkt: LocalDate,
        val hovedkategori: String,
        val kilde: String,
        val søknader: List<UUID>,
    )

    private fun lagredeRader(): List<Rad> =
        sessionOf(TestDatabase.dataSource).use { session ->
            session.run(
                queryOf("SELECT * FROM rettighetskategori ORDER BY opprettet")
                    .map { row ->
                        Rad(
                            id = row.uuid("id"),
                            fødselsnummer = row.string("fødselsnummer"),
                            skjæringstidspunkt = row.localDate("skjæringstidspunkt"),
                            hovedkategori = row.string("hovedkategori"),
                            kilde = row.string("kilde"),
                            søknader = row.array<UUID>("søknader").toList(),
                        )
                    }.asList,
            )
        }

    private companion object {
        val objectMapper = jacksonObjectMapper()
        const val FØDSELSNUMMER = "12029240045"
        val SKJÆRINGSTIDSPUNKT: LocalDate = LocalDate.of(2018, 1, 1)

        @Language("JSON")
        fun rettighetskategoriBehov(vararg søknader: UUID) =
            """
            {
              "@event_name": "behov",
              "@id": "${UUID.randomUUID()}",
              "@behov": ["Rettighetskategori"],
              "fødselsnummer": "$FØDSELSNUMMER",
              "Rettighetskategori": {
                "skjæringstidspunkt": "$SKJÆRINGSTIDSPUNKT",
                "søknader": [${søknader.joinToString { "\"$it\"" }}]
              }
            }
            """
    }
}
