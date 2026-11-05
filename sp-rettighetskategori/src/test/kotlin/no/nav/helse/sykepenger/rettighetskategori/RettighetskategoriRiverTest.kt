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
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

internal class RettighetskategoriRiverTest {
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
    fun `søknad fra arbeidstaker gir hovedkategorien Arbeidstaker`() {
        val søknad = spedisjon.sendtSøknadArbeidsgiver(FØDSELSNUMMER)

        rapid.sendTestMessage(rettighetskategoriBehov(søknad), FØDSELSNUMMER)

        assertEquals(1, rapid.inspektør.size)
        assertEquals(FØDSELSNUMMER, rapid.inspektør.key(0))
        val løsning = rapid.inspektør.message(0).løsning()
        assertEquals("ARBEIDSTAKER", løsning.path("hovedkategori").asString())
        val id = UUID.fromString(løsning.path("id").asString())
        assertEquals(
            Rad(
                id = id,
                fødselsnummer = FØDSELSNUMMER,
                skjæringstidspunkt = SKJÆRINGSTIDSPUNKT,
                hovedkategori = "ARBEIDSTAKER",
                tilleggskategorier = emptyList(),
                kilde = "automatisk",
            ),
            lagredeRader().single(),
        )
        assertEquals(
            Vurderingsrad(
                rettighetskategoriId = id,
                søknader = listOf(søknad),
            ),
            lagredeVurderinger().single(),
        )
        assertEquals(0, TestDatabase.antallOutboxMeldinger())
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
        assertEquals(listOf(søknad), lagredeVurderinger().single().søknader)
    }

    @Test
    fun `søknad fra jordbruker gir Selvstendig næringsdrivende og tilleggskategorien Jordbruker`() {
        val søknad = spedisjon.sendtSøknad(FØDSELSNUMMER, "sendt_søknad_selvstendig", "JORDBRUKER")

        rapid.sendTestMessage(rettighetskategoriBehov(søknad), FØDSELSNUMMER)

        val løsning = rapid.inspektør.message(0).løsning()
        assertEquals(setOf("id", "hovedkategori"), løsning.propertyNames().toSet())
        assertEquals("SELVSTENDIG_NÆRINGSDRIVENDE", løsning.path("hovedkategori").asString())
        val lagret = lagredeRader().single()
        assertEquals(UUID.fromString(løsning.path("id").asString()), lagret.id)
        assertEquals("SELVSTENDIG_NÆRINGSDRIVENDE", lagret.hovedkategori)
        assertEquals(listOf("JORDBRUKER"), lagret.tilleggskategorier)
    }

    @ParameterizedTest(name = "{0} med arbeidssituasjon {1} gir {2} og tilleggskategoriene [{3}]")
    @CsvSource(
        "sendt_søknad_arbeidsgiver, ARBEIDSTAKER, ARBEIDSTAKER, ''",
        "sendt_søknad_nav, ARBEIDSTAKER, ARBEIDSTAKER, ''",
        "sendt_søknad_selvstendig, SELVSTENDIG_NARINGSDRIVENDE, SELVSTENDIG_NÆRINGSDRIVENDE, ''",
        "sendt_søknad_selvstendig, FISKER, SELVSTENDIG_NÆRINGSDRIVENDE, ''",
        "sendt_søknad_selvstendig, BARNEPASSER, SELVSTENDIG_NÆRINGSDRIVENDE, ''",
        "sendt_søknad_selvstendig, ANNET, SELVSTENDIG_NÆRINGSDRIVENDE, ''",
    )
    fun `søknadstypen og arbeidssituasjonen gir hovedkategorien og tilleggskategoriene`(
        type: String,
        arbeidssituasjon: String,
        hovedkategori: String,
        tilleggskategorier: String,
    ) {
        val søknad = spedisjon.sendtSøknad(FØDSELSNUMMER, type, arbeidssituasjon)

        rapid.sendTestMessage(rettighetskategoriBehov(søknad), FØDSELSNUMMER)

        assertEquals(
            hovedkategori,
            hovedkategoriILøsning(),
        )
        assertEquals(
            tilleggskategorier.split(",").filter(String::isNotBlank),
            lagredeRader().single().tilleggskategorier,
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
        assertEquals(listOf(arbeidstaker, selvstendig), lagredeVurderinger().single().søknader)
    }

    @Test
    fun `søknader fra arbeidstaker og jordbruker gir Arbeidstaker og selvstendig næringsdrivende og tilleggskategorien Jordbruker`() {
        val arbeidstaker = spedisjon.sendtSøknadArbeidsgiver(FØDSELSNUMMER)
        val jordbruker = spedisjon.sendtSøknad(FØDSELSNUMMER, "sendt_søknad_selvstendig", "JORDBRUKER")

        rapid.sendTestMessage(rettighetskategoriBehov(arbeidstaker, jordbruker), FØDSELSNUMMER)

        assertEquals("ARBEIDSTAKER_OG_SELVSTENDIG_NÆRINGSDRIVENDE", hovedkategoriILøsning())
        val lagret = lagredeRader().single()
        assertEquals("ARBEIDSTAKER_OG_SELVSTENDIG_NÆRINGSDRIVENDE", lagret.hovedkategori)
        assertEquals(listOf("JORDBRUKER"), lagret.tilleggskategorier)
    }

    @Test
    fun `annen yrkesaktivitet sammen med arbeidstaker gir ubesvart behov`() {
        val arbeidstaker = spedisjon.sendtSøknadArbeidsgiver(FØDSELSNUMMER)
        val frilans = spedisjon.sendtSøknad(FØDSELSNUMMER, "sendt_søknad_frilans", "FRILANSER")

        rapid.sendTestMessage(rettighetskategoriBehov(arbeidstaker, frilans), FØDSELSNUMMER)

        assertEquals(0, rapid.inspektør.size)
    }

    @Test
    fun `samme søknadssett gir gjeldende ID og hovedkategori uten ny avgjørelse eller vurdering`() {
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
        assertEquals(1, lagredeVurderinger().size)
    }

    @Test
    fun `endret søknadssett med samme konklusjon føyer vurderingen til gjeldende avgjørelse`() {
        val første = spedisjon.sendtSøknadArbeidsgiver(FØDSELSNUMMER)
        val annenArbeidsgiver = spedisjon.sendtSøknadArbeidsgiver(FØDSELSNUMMER)

        rapid.sendTestMessage(rettighetskategoriBehov(første), FØDSELSNUMMER)
        rapid.sendTestMessage(rettighetskategoriBehov(første, annenArbeidsgiver), FØDSELSNUMMER)

        val førsteId = idILøsning(0)
        assertEquals(førsteId, idILøsning(1))
        assertEquals("ARBEIDSTAKER", hovedkategoriILøsning(1))
        assertEquals(listOf(førsteId), lagredeRader().map { it.id })
        assertEquals(
            listOf(
                Vurderingsrad(førsteId, listOf(første)),
                Vurderingsrad(førsteId, listOf(første, annenArbeidsgiver)),
            ),
            lagredeVurderinger(),
        )
    }

    @Test
    fun `endret hovedkategori gir en ny automatisk avgjørelse med ny ID`() {
        val første = spedisjon.sendtSøknadArbeidsgiver(FØDSELSNUMMER)
        val korrigert = spedisjon.sendtSøknadArbeidsgiver(FØDSELSNUMMER)
        val selvstendig = spedisjon.sendtSøknadSelvstendig(FØDSELSNUMMER)

        rapid.sendTestMessage(rettighetskategoriBehov(første), FØDSELSNUMMER)
        rapid.sendTestMessage(rettighetskategoriBehov(korrigert, selvstendig), FØDSELSNUMMER)

        val førsteId = idILøsning(0)
        val andreId = idILøsning(1)
        assertNotEquals(førsteId, andreId)
        assertEquals("ARBEIDSTAKER_OG_SELVSTENDIG_NÆRINGSDRIVENDE", hovedkategoriILøsning(1))
        assertEquals(listOf(førsteId to "automatisk", andreId to "automatisk"), lagredeRader().map { it.id to it.kilde })
        assertEquals(
            listOf(førsteId to listOf(første), andreId to listOf(korrigert, selvstendig)),
            lagredeVurderinger().map { it.rettighetskategoriId to it.søknader },
        )
    }

    @Test
    fun `ny jordbrukersøknad på en avgjørelse uten tilleggskategori gir ny avgjørelse med ny ID`() {
        val selvstendig = spedisjon.sendtSøknadSelvstendig(FØDSELSNUMMER)
        val jordbruker = spedisjon.sendtSøknad(FØDSELSNUMMER, "sendt_søknad_selvstendig", "JORDBRUKER")

        rapid.sendTestMessage(rettighetskategoriBehov(selvstendig), FØDSELSNUMMER)
        rapid.sendTestMessage(rettighetskategoriBehov(jordbruker), FØDSELSNUMMER)

        val førsteId = idILøsning(0)
        val andreId = idILøsning(1)
        assertNotEquals(førsteId, andreId)
        assertEquals("SELVSTENDIG_NÆRINGSDRIVENDE", hovedkategoriILøsning(1))
        assertEquals(
            listOf(førsteId to emptyList(), andreId to listOf("JORDBRUKER")),
            lagredeRader().map { it.id to it.tilleggskategorier },
        )
    }

    @Test
    fun `første vurdering etter backfill føyes til avgjørelsen fra Spleis når konklusjonen er den samme`() {
        val fraSpleis = spleis.rettighetskategori(FØDSELSNUMMER, aktive = listOf("SELVSTENDIG"), skjæringstidspunkt = SKJÆRINGSTIDSPUNKT)
        rapid.sendTestMessage(personAvstemming(), FØDSELSNUMMER)
        val søknad = spedisjon.sendtSøknadSelvstendig(FØDSELSNUMMER)

        rapid.sendTestMessage(rettighetskategoriBehov(søknad), FØDSELSNUMMER)

        assertEquals(fraSpleis, idILøsning(0))
        assertEquals(listOf(Triple(fraSpleis, "spleis", emptyList<String>())), lagredeRader().map { Triple(it.id, it.kilde, it.tilleggskategorier) })
        assertEquals(listOf(fraSpleis to listOf(søknad)), lagredeVurderinger().map { it.rettighetskategoriId to it.søknader })
    }

    @Test
    fun `første vurdering etter backfill gir ny avgjørelse når personen er jordbruker`() {
        val fraSpleis = spleis.rettighetskategori(FØDSELSNUMMER, aktive = listOf("SELVSTENDIG"), skjæringstidspunkt = SKJÆRINGSTIDSPUNKT)
        rapid.sendTestMessage(personAvstemming(), FØDSELSNUMMER)
        val jordbruker = spedisjon.sendtSøknad(FØDSELSNUMMER, "sendt_søknad_selvstendig", "JORDBRUKER")

        rapid.sendTestMessage(rettighetskategoriBehov(jordbruker), FØDSELSNUMMER)

        val nyId = idILøsning(0)
        assertNotEquals(fraSpleis, nyId)
        assertEquals(
            listOf(Triple(fraSpleis, "spleis", emptyList()), Triple(nyId, "automatisk", listOf("JORDBRUKER"))),
            lagredeRader().map { Triple(it.id, it.kilde, it.tilleggskategorier) },
        )
    }

    @Test
    fun `samme søknadssett etter en avgjørelse fra saksbehandler gir saksbehandlerens ID uten ny vurdering`() {
        val søknad = spedisjon.sendtSøknadArbeidsgiver(FØDSELSNUMMER)
        rapid.sendTestMessage(rettighetskategoriBehov(søknad), FØDSELSNUMMER)
        val overstyring = UUID.randomUUID()
        lagreAvgjørelseFraSaksbehandler(overstyring, "SELVSTENDIG_NÆRINGSDRIVENDE")

        rapid.sendTestMessage(rettighetskategoriBehov(søknad), FØDSELSNUMMER)

        val løsning = rapid.inspektør.message(1).løsning()
        assertEquals(overstyring.toString(), løsning.path("id").asString())
        assertEquals("SELVSTENDIG_NÆRINGSDRIVENDE", løsning.path("hovedkategori").asString())
        assertEquals(2, lagredeRader().size)
        assertEquals(1, lagredeVurderinger().size)
    }

    @Test
    fun `nye søknader med samme konklusjon som en manuell vurdering gir ID-en til den manuelle avgjørelsen`() {
        val første = spedisjon.sendtSøknadSelvstendig(FØDSELSNUMMER)
        rapid.sendTestMessage(rettighetskategoriBehov(første), FØDSELSNUMMER)
        val manuell = vurderManuelt(idILøsning(0), setOf(Tilleggskategori.JORDBRUKER))
        val jordbruker = spedisjon.sendtSøknad(FØDSELSNUMMER, "sendt_søknad_selvstendig", "JORDBRUKER")

        rapid.sendTestMessage(rettighetskategoriBehov(første, jordbruker), FØDSELSNUMMER)

        assertEquals(manuell, idILøsning(1))
        assertEquals(2, lagredeRader().size)
        assertEquals(manuell to listOf(første, jordbruker), lagredeVurderinger().last().let { it.rettighetskategoriId to it.søknader })
    }

    @Test
    fun `nye søknader med en annen konklusjon enn en manuell vurdering gir ny automatisk avgjørelse`() {
        val første = spedisjon.sendtSøknadSelvstendig(FØDSELSNUMMER)
        rapid.sendTestMessage(rettighetskategoriBehov(første), FØDSELSNUMMER)
        val automatisk = idILøsning(0)
        val manuell = vurderManuelt(automatisk, setOf(Tilleggskategori.JORDBRUKER))
        val andre = spedisjon.sendtSøknadSelvstendig(FØDSELSNUMMER)

        rapid.sendTestMessage(rettighetskategoriBehov(første, andre), FØDSELSNUMMER)

        val ny = idILøsning(1)
        assertEquals(2, rapid.inspektør.size)
        assertEquals(
            listOf(Triple(automatisk, "automatisk", emptyList()), Triple(manuell, "saksbehandler", listOf("JORDBRUKER")), Triple(ny, "automatisk", emptyList<String>())),
            lagredeRader().map { Triple(it.id, it.kilde, it.tilleggskategorier) },
        )
        assertEquals(ny, RettighetskategoriRepository(TestDatabase.dataSource).gjeldende(FØDSELSNUMMER, SKJÆRINGSTIDSPUNKT)?.id)
        assertEquals(1, TestDatabase.antallOutboxMeldinger())
    }

    @Test
    fun `samme søknadssett etter en manuell vurdering gir ID-en til den manuelle avgjørelsen`() {
        val søknad = spedisjon.sendtSøknadSelvstendig(FØDSELSNUMMER)
        rapid.sendTestMessage(rettighetskategoriBehov(søknad), FØDSELSNUMMER)
        val manuell = vurderManuelt(idILøsning(0), setOf(Tilleggskategori.JORDBRUKER))

        rapid.sendTestMessage(rettighetskategoriBehov(søknad), FØDSELSNUMMER)

        assertEquals(manuell, idILøsning(1))
        assertEquals(2, lagredeRader().size)
        assertEquals(1, lagredeVurderinger().size)
    }

    private fun vurderManuelt(
        erstatter: UUID,
        tilleggskategorier: Set<Tilleggskategori>,
    ): UUID {
        val repository = RettighetskategoriRepository(TestDatabase.dataSource)
        val ny = checkNotNull(repository.finn(erstatter)).manueltVurdert(tilleggskategorier, Instant.now())
        check(LagreManuellVurdering(TestDatabase.dataSource, repository).lagre(ny, ManuellVurdering("Z999999", null, erstatter)))
        return ny.id
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

    private fun hovedkategoriILøsning(indeks: Int = 0) =
        rapid.inspektør
            .message(indeks)
            .løsning()
            .path("hovedkategori")
            .asString()

    private fun idILøsning(indeks: Int): UUID =
        UUID.fromString(
            rapid.inspektør
                .message(indeks)
                .løsning()
                .path("id")
                .asString(),
        )

    private data class Rad(
        val id: UUID,
        val fødselsnummer: String,
        val skjæringstidspunkt: LocalDate,
        val hovedkategori: String,
        val tilleggskategorier: List<String>,
        val kilde: String,
    )

    private data class Vurderingsrad(
        val rettighetskategoriId: UUID,
        val søknader: List<UUID>,
    )

    private fun lagreAvgjørelseFraSaksbehandler(
        id: UUID,
        hovedkategori: String,
    ) {
        sessionOf(TestDatabase.dataSource).use { session ->
            session.run(
                queryOf(
                    """
                    INSERT INTO rettighetskategori (id, fødselsnummer, skjæringstidspunkt, hovedkategori, kilde, vurderingstidspunkt, søknader)
                    VALUES (:id, :fodselsnummer, :skjaeringstidspunkt, :hovedkategori, 'saksbehandler', now(), '{}')
                    """,
                    mapOf(
                        "id" to id,
                        "fodselsnummer" to FØDSELSNUMMER,
                        "skjaeringstidspunkt" to SKJÆRINGSTIDSPUNKT,
                        "hovedkategori" to hovedkategori,
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
                            tilleggskategorier = row.array<String>("tilleggskategorier").toList(),
                            kilde = row.string("kilde"),
                        )
                    }.asList,
            )
        }

    private fun lagredeVurderinger(): List<Vurderingsrad> =
        sessionOf(TestDatabase.dataSource).use { session ->
            session.run(
                queryOf("SELECT * FROM vurdering ORDER BY løpenummer")
                    .map { row ->
                        Vurderingsrad(
                            rettighetskategoriId = row.uuid("rettighetskategori_id"),
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
        fun personAvstemming() =
            """
            {
              "@event_name": "person_avstemming",
              "@id": "${UUID.randomUUID()}",
              "fødselsnummer": "$FØDSELSNUMMER"
            }
            """

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
