package no.nav.helse.sykepenger.rettighetskategori

import com.github.navikt.tbd_libs.rapids_and_rivers.test_support.TestRapid
import kotliquery.queryOf
import kotliquery.sessionOf
import org.intellij.lang.annotations.Language
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
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
        assertEquals("ARBEIDSTAKER", løsning.path("hovedkategori").asString())
        assertEquals(
            Rad(
                id = UUID.fromString(løsning.path("id").asString()),
                fødselsnummer = FØDSELSNUMMER,
                skjæringstidspunkt = SKJÆRINGSTIDSPUNKT,
                hovedkategori = "ARBEIDSTAKER",
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
        assertEquals("SELVSTENDIG_NÆRINGSDRIVENDE", løsning.path("hovedkategori").asString())
        val lagret = lagredeRader().single()
        assertEquals(UUID.fromString(løsning.path("id").asString()), lagret.id)
        assertEquals("SELVSTENDIG_NÆRINGSDRIVENDE", lagret.hovedkategori)
        assertEquals(listOf(søknad), lagret.søknader)
    }

    @ParameterizedTest(name = "{0} med arbeidssituasjon {1} gir {2}")
    @CsvSource(
        "sendt_søknad_arbeidsgiver, ARBEIDSTAKER, ARBEIDSTAKER",
        "sendt_søknad_nav, ARBEIDSTAKER, ARBEIDSTAKER",
        "sendt_søknad_selvstendig, SELVSTENDIG_NARINGSDRIVENDE, SELVSTENDIG_NÆRINGSDRIVENDE",
        "sendt_søknad_selvstendig, FISKER, SELVSTENDIG_NÆRINGSDRIVENDE",
        "sendt_søknad_selvstendig, JORDBRUKER, SELVSTENDIG_NÆRINGSDRIVENDE",
        "sendt_søknad_selvstendig, BARNEPASSER, SELVSTENDIG_NÆRINGSDRIVENDE",
        "sendt_søknad_selvstendig, ANNET, SELVSTENDIG_NÆRINGSDRIVENDE",
    )
    fun `søknadstypen og arbeidssituasjonen gir hovedkategorien`(
        type: String,
        arbeidssituasjon: String,
        hovedkategori: String,
    ) {
        val søknad = spedisjon.sendtSøknad(FØDSELSNUMMER, type, arbeidssituasjon)

        rapid.sendTestMessage(rettighetskategoriBehov(søknad), FØDSELSNUMMER)

        assertEquals(
            hovedkategori,
            hovedkategoriILøsning(),
        )
    }

    @Test
    fun `søknad fra arbeidsledig med tidligere arbeidsgiver gir ubesvart behov`() {
        val søknad =
            spedisjon.sendtSøknad(
                fødselsnummer = FØDSELSNUMMER,
                type = "sendt_søknad_arbeidsledig",
                arbeidssituasjon = "ARBEIDSLEDIG",
                felter = mapOf("tidligereArbeidsgiverOrgnummer" to "987654321"),
            )

        rapid.sendTestMessage(rettighetskategoriBehov(søknad), FØDSELSNUMMER)

        assertEquals(0, rapid.inspektør.size)
        assertEquals(emptyList<Rad>(), lagredeRader())
    }

    @Test
    fun `tjenesten ser ikke på andre inntektskilder`() {
        val søknad =
            spedisjon.sendtSøknad(
                fødselsnummer = FØDSELSNUMMER,
                type = "sendt_søknad_nav",
                arbeidssituasjon = "ARBEIDSTAKER",
                felter = mapOf("andreInntektskilder" to listOf(mapOf("type" to "SELVSTENDIG_NARINGSDRIVENDE", "sykmeldt" to true))),
            )

        rapid.sendTestMessage(rettighetskategoriBehov(søknad), FØDSELSNUMMER)

        assertEquals(
            "ARBEIDSTAKER",
            hovedkategoriILøsning(),
        )
    }

    @ParameterizedTest(name = "{0} med arbeidssituasjon {1} gir ubesvart behov")
    @CsvSource(
        "sendt_søknad_frilans, FRILANSER",
        "sendt_søknad_arbeidsledig, ARBEIDSLEDIG",
        "sendt_søknad_selvstendig, FRILANSER",
    )
    fun `søknad fra en annen yrkesaktivitet gir ubesvart behov`(
        type: String,
        arbeidssituasjon: String,
    ) {
        val søknad = spedisjon.sendtSøknad(FØDSELSNUMMER, type, arbeidssituasjon)

        rapid.sendTestMessage(rettighetskategoriBehov(søknad), FØDSELSNUMMER)

        assertEquals(0, rapid.inspektør.size)
        assertEquals(emptyList<Rad>(), lagredeRader())
    }

    @Test
    fun `søknader fra arbeidstaker og selvstendig næringsdrivende gir hovedkategorien Arbeidstaker og selvstendig næringsdrivende`() {
        val arbeidstaker = spedisjon.sendtSøknadArbeidsgiver(FØDSELSNUMMER)
        val selvstendig = spedisjon.sendtSøknad(FØDSELSNUMMER, "sendt_søknad_selvstendig", "FISKER")

        rapid.sendTestMessage(rettighetskategoriBehov(arbeidstaker, selvstendig), FØDSELSNUMMER)

        val løsning = rapid.inspektør.message(0).løsning()
        assertEquals("ARBEIDSTAKER_OG_SELVSTENDIG_NÆRINGSDRIVENDE", løsning.path("hovedkategori").asString())
        val lagret = lagredeRader().single()
        assertEquals("ARBEIDSTAKER_OG_SELVSTENDIG_NÆRINGSDRIVENDE", lagret.hovedkategori)
        assertEquals(listOf(arbeidstaker, selvstendig), lagret.søknader)
    }

    @Test
    fun `annen yrkesaktivitet sammen med arbeidstaker gir ubesvart behov`() {
        val arbeidstaker = spedisjon.sendtSøknadArbeidsgiver(FØDSELSNUMMER)
        val frilans = spedisjon.sendtSøknad(FØDSELSNUMMER, "sendt_søknad_frilans", "FRILANSER")

        rapid.sendTestMessage(rettighetskategoriBehov(arbeidstaker, frilans), FØDSELSNUMMER)

        assertEquals(0, rapid.inspektør.size)
    }

    @Test
    fun `samme søknadssett gir gjeldende ID og hovedkategori uten ny rad`() {
        val arbeidstaker = spedisjon.sendtSøknadArbeidsgiver(FØDSELSNUMMER)
        val selvstendig = spedisjon.sendtSøknadSelvstendig(FØDSELSNUMMER)

        rapid.sendTestMessage(rettighetskategoriBehov(arbeidstaker, selvstendig), FØDSELSNUMMER)
        rapid.sendTestMessage(rettighetskategoriBehov(selvstendig, arbeidstaker), FØDSELSNUMMER)

        assertEquals(2, rapid.inspektør.size)
        val første = rapid.inspektør.message(0).løsning()
        val andre = rapid.inspektør.message(1).løsning()
        assertEquals(første.path("id").asString(), andre.path("id").asString())
        assertEquals("ARBEIDSTAKER_OG_SELVSTENDIG_NÆRINGSDRIVENDE", andre.path("hovedkategori").asString())
        assertEquals(1, lagredeRader().size)
    }

    @Test
    fun `endret søknadssett gir en ny automatisk avgjørelse med ny ID`() {
        val første = spedisjon.sendtSøknadArbeidsgiver(FØDSELSNUMMER)
        val korrigert = spedisjon.sendtSøknadArbeidsgiver(FØDSELSNUMMER)
        val selvstendig = spedisjon.sendtSøknadSelvstendig(FØDSELSNUMMER)

        rapid.sendTestMessage(rettighetskategoriBehov(første), FØDSELSNUMMER)
        rapid.sendTestMessage(rettighetskategoriBehov(korrigert, selvstendig), FØDSELSNUMMER)

        val førsteId =
            UUID.fromString(
                rapid.inspektør
                    .message(0)
                    .løsning()
                    .path("id")
                    .asString(),
            )
        val andreLøsning = rapid.inspektør.message(1).løsning()
        val andreId = UUID.fromString(andreLøsning.path("id").asString())
        assertNotEquals(førsteId, andreId)
        assertEquals("ARBEIDSTAKER_OG_SELVSTENDIG_NÆRINGSDRIVENDE", andreLøsning.path("hovedkategori").asString())
        assertEquals(
            listOf(
                Triple(førsteId, "automatisk", listOf(første)),
                Triple(andreId, "automatisk", listOf(korrigert, selvstendig)),
            ),
            lagredeRader().map { Triple(it.id, it.kilde, it.søknader) },
        )
    }

    @Test
    fun `avgjørelse fra saksbehandler overskrives ikke av en automatisk avgjørelse for samme søknadssett`() {
        val søknad = spedisjon.sendtSøknadArbeidsgiver(FØDSELSNUMMER)
        rapid.sendTestMessage(rettighetskategoriBehov(søknad), FØDSELSNUMMER)
        val overstyring = UUID.randomUUID()
        lagreAvgjørelseFraSaksbehandler(overstyring, "SELVSTENDIG_NÆRINGSDRIVENDE", listOf(søknad))

        rapid.sendTestMessage(rettighetskategoriBehov(søknad), FØDSELSNUMMER)

        val løsning = rapid.inspektør.message(1).løsning()
        assertEquals(overstyring.toString(), løsning.path("id").asString())
        assertEquals("SELVSTENDIG_NÆRINGSDRIVENDE", løsning.path("hovedkategori").asString())
        assertEquals(2, lagredeRader().size)
    }

    @Test
    fun `Spedisjon-feil gir ubesvart behov`() {
        val søknad = spedisjon.sendtSøknadArbeidsgiver(FØDSELSNUMMER)
        spedisjon.feiler()

        rapid.sendTestMessage(rettighetskategoriBehov(søknad), FØDSELSNUMMER)

        assertEquals(0, rapid.inspektør.size)
        assertEquals(emptyList<Rad>(), lagredeRader())
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

    private fun hovedkategoriILøsning() =
        rapid.inspektør
            .message(0)
            .løsning()
            .path("hovedkategori")
            .asString()

    private data class Rad(
        val id: UUID,
        val fødselsnummer: String,
        val skjæringstidspunkt: LocalDate,
        val hovedkategori: String,
        val kilde: String,
        val søknader: List<UUID>,
    )

    private fun lagreAvgjørelseFraSaksbehandler(
        id: UUID,
        hovedkategori: String,
        søknader: List<UUID>,
    ) {
        sessionOf(TestDatabase.dataSource).use { session ->
            session.run(
                queryOf(
                    """
                    INSERT INTO rettighetskategori (id, fødselsnummer, skjæringstidspunkt, hovedkategori, kilde, vurderingstidspunkt, søknader)
                    VALUES (:id, :fodselsnummer, :skjaeringstidspunkt, :hovedkategori, 'saksbehandler', now(), :soknader)
                    """,
                    mapOf(
                        "id" to id,
                        "fodselsnummer" to FØDSELSNUMMER,
                        "skjaeringstidspunkt" to SKJÆRINGSTIDSPUNKT,
                        "hovedkategori" to hovedkategori,
                        "soknader" to session.createArrayOf("uuid", søknader),
                    ),
                ).asUpdate,
            )
        }
    }

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
