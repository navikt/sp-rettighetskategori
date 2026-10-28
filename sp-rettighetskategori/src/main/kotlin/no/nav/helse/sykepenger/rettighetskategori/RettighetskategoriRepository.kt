package no.nav.helse.sykepenger.rettighetskategori

import kotliquery.Row
import kotliquery.Session
import kotliquery.queryOf
import kotliquery.sessionOf
import org.intellij.lang.annotations.Language
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

internal class RettighetskategoriRepository(
    private val dataSource: DataSource,
) {
    fun lagre(rettighetskategori: Rettighetskategori) {
        sessionOf(dataSource).use { session -> lagre(session, rettighetskategori) }
    }

    fun fyllInn(
        fødselsnummer: String,
        rettighetskategorier: List<Rettighetskategori>,
    ): Int =
        sessionOf(dataSource).use { session ->
            session.transaction { tx ->
                sjekkAtIngenTilhørerAndrePersoner(tx, fødselsnummer, rettighetskategorier)
                val antallNye = rettighetskategorier.sumOf { lagre(tx, it) }

                @Language("PostgreSQL")
                val sql = """
                    INSERT INTO migreringslogg (fødselsnummer, antall_fra_spleis, antall_nye)
                    VALUES (:fodselsnummer, :antallFraSpleis, :antallNye)
                """
                tx.run(
                    queryOf(
                        sql,
                        mapOf("fodselsnummer" to fødselsnummer, "antallFraSpleis" to rettighetskategorier.size, "antallNye" to antallNye),
                    ).asUpdate,
                )
                antallNye
            }
        }

    private fun sjekkAtIngenTilhørerAndrePersoner(
        session: Session,
        fødselsnummer: String,
        rettighetskategorier: List<Rettighetskategori>,
    ) {
        @Language("PostgreSQL")
        val sql = "SELECT id FROM rettighetskategori WHERE id = ANY(:ider) AND fødselsnummer <> :fodselsnummer"
        val ider =
            session.run(
                queryOf(
                    sql,
                    mapOf("ider" to session.createArrayOf("uuid", rettighetskategorier.map { it.id }), "fodselsnummer" to fødselsnummer),
                ).map { it.uuid("id") }.asList,
            )
        check(ider.isEmpty()) { "Rettighetskategoriene $ider er allerede lagret for en annen person" }
    }

    private fun lagre(
        session: Session,
        rettighetskategori: Rettighetskategori,
    ): Int {
        @Language("PostgreSQL")
        val sql = """
            INSERT INTO rettighetskategori (id, fødselsnummer, skjæringstidspunkt, hovedkategori, kilde, vurderingstidspunkt, søknader)
            VALUES (:id, :fodselsnummer, :skjaeringstidspunkt, :hovedkategori, :kilde, :vurderingstidspunkt, :soknader)
            ON CONFLICT (id) DO NOTHING
        """
        return session.run(
            queryOf(
                sql,
                mapOf(
                    "id" to rettighetskategori.id,
                    "fodselsnummer" to rettighetskategori.fødselsnummer,
                    "skjaeringstidspunkt" to rettighetskategori.skjæringstidspunkt,
                    "hovedkategori" to rettighetskategori.hovedkategori.name,
                    "kilde" to rettighetskategori.kilde.dbVerdi,
                    "vurderingstidspunkt" to rettighetskategori.vurderingstidspunkt,
                    "soknader" to session.createArrayOf("uuid", rettighetskategori.søknader),
                ),
            ).asUpdate,
        )
    }

    fun finn(id: UUID): Rettighetskategori? {
        @Language("PostgreSQL")
        val sql = """
            SELECT id, fødselsnummer, skjæringstidspunkt, hovedkategori, kilde, vurderingstidspunkt, søknader
            FROM rettighetskategori
            WHERE id = :id
        """
        return sessionOf(dataSource).use { session ->
            session.run(queryOf(sql, mapOf("id" to id)).map(::tilRettighetskategori).asSingle)
        }
    }

    fun gjeldende(
        fødselsnummer: String,
        skjæringstidspunkt: LocalDate,
    ): Rettighetskategori? {
        @Language("PostgreSQL")
        val sql = """
            SELECT id, fødselsnummer, skjæringstidspunkt, hovedkategori, kilde, vurderingstidspunkt, søknader
            FROM rettighetskategori
            WHERE fødselsnummer = :fodselsnummer AND skjæringstidspunkt = :skjaeringstidspunkt
            ORDER BY vurderingstidspunkt DESC
            LIMIT 1
        """
        return sessionOf(dataSource).use { session ->
            session.run(
                queryOf(sql, mapOf("fodselsnummer" to fødselsnummer, "skjaeringstidspunkt" to skjæringstidspunkt))
                    .map(::tilRettighetskategori)
                    .asSingle,
            )
        }
    }

    private fun tilRettighetskategori(row: Row) =
        Rettighetskategori(
            id = row.uuid("id"),
            fødselsnummer = row.string("fødselsnummer"),
            skjæringstidspunkt = row.localDate("skjæringstidspunkt"),
            hovedkategori = Hovedkategori.valueOf(row.string("hovedkategori")),
            kilde = Kilde.fraDbVerdi(row.string("kilde")),
            vurderingstidspunkt = row.instant("vurderingstidspunkt"),
            søknader = row.array<UUID>("søknader").toList(),
        )
}
