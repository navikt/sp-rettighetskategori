package no.nav.helse.sykepenger.rettighetskategori.opprydding_dev

import com.github.navikt.tbd_libs.rapids_and_rivers.test_support.TestRapid
import kotliquery.queryOf
import kotliquery.sessionOf
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.*
import kotlin.test.assertEquals

internal class SlettPersonRiverTest {
    private val rapid =
        TestRapid().apply {
            SlettPersonRiver(this, Database.dataSource)
        }

    @BeforeEach
    fun beforeEach() {
        Database.reset()
        rapid.reset()
    }

    companion object {
        @JvmStatic
        @AfterAll
        fun shutdown() {
            Database.shutdown()
        }
    }

    @Test
    fun `publiserer person_slettet etter sletting`() {
        val fødselsnummer = "01020312345"

        rapid.sendTestMessage(slettPersonMelding(fødselsnummer))

        assertEquals(1, rapid.inspektør.size)
        val melding = rapid.inspektør.message(0)
        assertEquals("person_slettet", melding["@event_name"].asString())
        assertEquals(fødselsnummer, melding["fødselsnummer"].asString())
        assertEquals(fødselsnummer, rapid.inspektør.key(0))
    }

    @Test
    fun `sletter rettighetskategoriene til personen`() {
        val fødselsnummer = "01020312345"
        val annenPerson = "02030412345"
        lagreRettighetskategori(fødselsnummer)
        lagreRettighetskategori(annenPerson)

        rapid.sendTestMessage(slettPersonMelding(fødselsnummer))

        assertEquals(0, antallRettighetskategorier(fødselsnummer))
        assertEquals(1, antallRettighetskategorier(annenPerson))
    }

    @Test
    fun `sletter migreringsloggen til personen`() {
        val fødselsnummer = "01020312345"
        val annenPerson = "02030412345"
        lagreMigreringslogg(fødselsnummer)
        lagreMigreringslogg(annenPerson)

        rapid.sendTestMessage(slettPersonMelding(fødselsnummer))

        assertEquals(0, antallILoggen(fødselsnummer))
        assertEquals(1, antallILoggen(annenPerson))
    }

    private fun lagreMigreringslogg(fødselsnummer: String) {
        sessionOf(Database.dataSource).use {
            it.run(
                queryOf(
                    "INSERT INTO migreringslogg (fødselsnummer, antall_fra_spleis, antall_nye) VALUES (:fodselsnummer, 1, 1)",
                    mapOf("fodselsnummer" to fødselsnummer),
                ).asUpdate,
            )
        }
    }

    private fun antallILoggen(fødselsnummer: String) =
        sessionOf(Database.dataSource).use {
            it.run(
                queryOf("SELECT COUNT(*) FROM migreringslogg WHERE fødselsnummer = :fodselsnummer", mapOf("fodselsnummer" to fødselsnummer))
                    .map { row -> row.int(1) }
                    .asSingle,
            )
        }

    @Test
    fun `sletter vurderingene til personen`() {
        val fødselsnummer = "01020312345"
        val annenPerson = "02030412345"
        lagreRettighetskategori(fødselsnummer)
        lagreRettighetskategori(annenPerson)

        rapid.sendTestMessage(slettPersonMelding(fødselsnummer))

        assertEquals(0, antallVurderinger(fødselsnummer))
        assertEquals(1, antallVurderinger(annenPerson))
    }

    private fun lagreRettighetskategori(fødselsnummer: String) {
        sessionOf(Database.dataSource).use {
            it.run(
                queryOf(
                    """
                    WITH avgjørelse AS (
                        INSERT INTO rettighetskategori (id, fødselsnummer, skjæringstidspunkt, hovedkategori, kilde, vurderingstidspunkt, søknader)
                        VALUES (gen_random_uuid(), :fodselsnummer, '2018-01-01', 'ARBEIDSTAKER', 'automatisk', now(), ARRAY[gen_random_uuid()])
                        RETURNING id, søknader
                    )
                    INSERT INTO vurdering (rettighetskategori_id, søknader, vurderingstidspunkt)
                    SELECT id, søknader, now() FROM avgjørelse
                    """,
                    mapOf("fodselsnummer" to fødselsnummer),
                ).asUpdate,
            )
        }
    }

    private fun antallVurderinger(fødselsnummer: String) =
        sessionOf(Database.dataSource).use {
            it.run(
                queryOf(
                    "SELECT COUNT(*) FROM vurdering v JOIN rettighetskategori r ON r.id = v.rettighetskategori_id WHERE r.fødselsnummer = :fodselsnummer",
                    mapOf("fodselsnummer" to fødselsnummer),
                ).map { row -> row.int(1) }
                    .asSingle,
            )
        }

    private fun antallRettighetskategorier(fødselsnummer: String) =
        sessionOf(Database.dataSource).use {
            it.run(
                queryOf("SELECT COUNT(*) FROM rettighetskategori WHERE fødselsnummer = :fodselsnummer", mapOf("fodselsnummer" to fødselsnummer))
                    .map { row -> row.int(1) }
                    .asSingle,
            )
        }

    private fun slettPersonMelding(fødselsnummer: String) =
        """
        {
            "@event_name": "slett_person",
            "@id": "${UUID.randomUUID()}",
            "fødselsnummer": "$fødselsnummer"
        }
        """.trimIndent()
}
