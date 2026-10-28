package no.nav.helse.sykepenger.rettighetskategori

import com.github.navikt.tbd_libs.rapids_and_rivers.test_support.TestRapid
import kotliquery.queryOf
import kotliquery.sessionOf
import org.intellij.lang.annotations.Language
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

internal class PersonAvstemmingRiverTest {
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
    fun `avstemming lagrer ID-ene fra Spleis som avgjørelser fra backfill`() {
        val arbeidstaker =
            spleis.rettighetskategori(
                FØDSELSNUMMER,
                aktive = listOf("ARBEIDSTAKER"),
                skjæringstidspunkt = LocalDate.parse("2018-01-01"),
                opprettet = LocalDateTime.parse("2018-02-01T12:00:00"),
            )
        val selvstendig =
            spleis.rettighetskategori(
                FØDSELSNUMMER,
                aktive = listOf("SELVSTENDIG"),
                skjæringstidspunkt = LocalDate.parse("2019-01-01"),
                opprettet = LocalDateTime.parse("2019-07-01T12:00:00"),
            )

        rapid.sendTestMessage(personAvstemming(), FØDSELSNUMMER)

        assertEquals(
            listOf(
                Rad(
                    id = arbeidstaker,
                    skjæringstidspunkt = LocalDate.parse("2018-01-01"),
                    hovedkategori = "ARBEIDSTAKER",
                    vurderingstidspunkt = Instant.parse("2018-02-01T11:00:00Z"),
                ),
                Rad(
                    id = selvstendig,
                    skjæringstidspunkt = LocalDate.parse("2019-01-01"),
                    hovedkategori = "SELVSTENDIG_NÆRINGSDRIVENDE",
                    vurderingstidspunkt = Instant.parse("2019-07-01T10:00:00Z"),
                ),
            ),
            lagredeRader(),
        )
        assertEquals(0, rapid.inspektør.size)
    }

    @Test
    fun `to avstemminger etter hverandre lagrer bare nye ID-er og føres begge i migreringsloggen`() {
        val første = spleis.rettighetskategori(FØDSELSNUMMER, aktive = listOf("ARBEIDSTAKER"), opprettet = LocalDateTime.parse("2018-02-01T12:00:00"))
        rapid.sendTestMessage(personAvstemming(), FØDSELSNUMMER)

        val andre = spleis.rettighetskategori(FØDSELSNUMMER, aktive = listOf("ARBEIDSTAKER"), opprettet = LocalDateTime.parse("2018-03-01T12:00:00"))
        rapid.sendTestMessage(personAvstemming(), FØDSELSNUMMER)

        assertEquals(2, spleis.antallKall)
        assertEquals(listOf(første, andre), lagredeRader().map { it.id })
        assertEquals(
            listOf(TestDatabase.Logginnslag(FØDSELSNUMMER, antallFraSpleis = 1, antallNye = 1), TestDatabase.Logginnslag(FØDSELSNUMMER, antallFraSpleis = 2, antallNye = 1)),
            TestDatabase.migreringslogg(),
        )
    }

    @Test
    fun `avstemming uten nye ID-er føres ikke i migreringsloggen`() {
        spleis.rettighetskategori(FØDSELSNUMMER, aktive = listOf("ARBEIDSTAKER"))
        rapid.sendTestMessage(personAvstemming(), FØDSELSNUMMER)
        rapid.sendTestMessage(personAvstemming(), FØDSELSNUMMER)
        rapid.sendTestMessage(personAvstemming("01020312345"), "01020312345")

        assertEquals(3, spleis.antallKall)
        assertEquals(listOf(TestDatabase.Logginnslag(FØDSELSNUMMER, antallFraSpleis = 1, antallNye = 1)), TestDatabase.migreringslogg())
    }

    @Test
    fun `avgjørelse fra backfill blir ikke gjeldende når en automatisk avgjørelse er nyere`() {
        val søknad = spedisjon.sendtSøknadArbeidsgiver(FØDSELSNUMMER)
        val automatisk = rettighetskategoriIdFor(søknad)
        spleis.rettighetskategori(FØDSELSNUMMER, aktive = listOf("ARBEIDSTAKER"), skjæringstidspunkt = SKJÆRINGSTIDSPUNKT)

        rapid.sendTestMessage(personAvstemming(), FØDSELSNUMMER)

        assertEquals(automatisk, rettighetskategoriIdFor(søknad))
        assertEquals(listOf("spleis", "automatisk"), lagredeRader().map { it.kilde })
    }

    @Test
    fun `ID som allerede er avgjort automatisk, overskrives ikke av backfill`() {
        val automatisk = rettighetskategoriIdFor(spedisjon.sendtSøknadArbeidsgiver(FØDSELSNUMMER))
        spleis.rettighetskategori(FØDSELSNUMMER, aktive = listOf("SELVSTENDIG"), rettighetskategoriId = automatisk)

        rapid.sendTestMessage(personAvstemming(), FØDSELSNUMMER)

        val lagret = lagredeRader().single()
        assertEquals(automatisk, lagret.id)
        assertEquals("automatisk", lagret.kilde)
        assertEquals("ARBEIDSTAKER", lagret.hovedkategori)
        assertEquals(emptyList<TestDatabase.Logginnslag>(), TestDatabase.migreringslogg())
    }

    @Test
    fun `ID som er lagret for en annen person, gir feil og lagrer ingenting`() {
        val annenPerson = "01020312345"
        val id = spleis.rettighetskategori(annenPerson, aktive = listOf("ARBEIDSTAKER"))
        rapid.sendTestMessage(personAvstemming(annenPerson), annenPerson)
        spleis.rettighetskategori(FØDSELSNUMMER, aktive = listOf("ARBEIDSTAKER"), rettighetskategoriId = id)
        spleis.rettighetskategori(FØDSELSNUMMER, aktive = listOf("SELVSTENDIG"))

        rapid.sendTestMessage(personAvstemming(), FØDSELSNUMMER)

        assertEquals(listOf(annenPerson), lagredeRader().map { it.fødselsnummer })
        assertEquals(listOf(annenPerson), TestDatabase.migreringslogg().map { it.fødselsnummer })
    }

    @Test
    fun `feil fra spleis-api lagrer ingenting`() {
        spleis.rettighetskategori(FØDSELSNUMMER, aktive = listOf("ARBEIDSTAKER"))
        spleis.feiler()

        rapid.sendTestMessage(personAvstemming(), FØDSELSNUMMER)

        assertEquals(emptyList<Rad>(), lagredeRader())
        assertEquals(emptyList<TestDatabase.Logginnslag>(), TestDatabase.migreringslogg())
    }

    private fun rettighetskategoriIdFor(søknad: UUID): UUID {
        rapid.sendTestMessage(rettighetskategoriBehov(søknad), FØDSELSNUMMER)
        return UUID.fromString(
            rapid.inspektør
                .message(rapid.inspektør.size - 1)
                .path("@løsning")
                .path("Rettighetskategori")
                .path("id")
                .asString(),
        )
    }

    private data class Rad(
        val id: UUID,
        val skjæringstidspunkt: LocalDate,
        val hovedkategori: String,
        val vurderingstidspunkt: Instant,
        val fødselsnummer: String = FØDSELSNUMMER,
        val kilde: String = "spleis",
        val søknader: List<UUID> = emptyList(),
    )

    private fun lagredeRader(): List<Rad> =
        sessionOf(TestDatabase.dataSource).use { session ->
            session.run(
                queryOf("SELECT * FROM rettighetskategori ORDER BY vurderingstidspunkt")
                    .map { row ->
                        Rad(
                            id = row.uuid("id"),
                            skjæringstidspunkt = row.localDate("skjæringstidspunkt"),
                            hovedkategori = row.string("hovedkategori"),
                            vurderingstidspunkt = row.instant("vurderingstidspunkt"),
                            fødselsnummer = row.string("fødselsnummer"),
                            kilde = row.string("kilde"),
                            søknader = row.array<UUID>("søknader").toList(),
                        )
                    }.asList,
            )
        }

    private companion object {
        const val FØDSELSNUMMER = "12029240045"
        val SKJÆRINGSTIDSPUNKT: LocalDate = LocalDate.parse("2018-01-01")

        @Language("JSON")
        fun rettighetskategoriBehov(søknad: UUID) =
            """
            {
              "@event_name": "behov",
              "@id": "${UUID.randomUUID()}",
              "@behov": ["Rettighetskategori"],
              "fødselsnummer": "$FØDSELSNUMMER",
              "Rettighetskategori": {
                "skjæringstidspunkt": "$SKJÆRINGSTIDSPUNKT",
                "søknader": ["$søknad"]
              }
            }
            """

        @Language("JSON")
        fun personAvstemming(fødselsnummer: String = FØDSELSNUMMER) =
            """
            {
              "@event_name": "person_avstemming",
              "@id": "${UUID.randomUUID()}",
              "fødselsnummer": "$fødselsnummer"
            }
            """
    }
}
