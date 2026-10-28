package no.nav.helse.sykepenger.rettighetskategori

import kotliquery.Row
import kotliquery.queryOf
import kotliquery.sessionOf
import org.intellij.lang.annotations.Language
import java.util.UUID
import javax.sql.DataSource

internal class RettighetskategoriRepository(
    private val dataSource: DataSource,
) {
    fun lagre(rettighetskategori: Rettighetskategori) {
        @Language("PostgreSQL")
        val sql = """
            INSERT INTO rettighetskategori (id, fødselsnummer, skjæringstidspunkt, hovedkategori, kilde, vurderingstidspunkt, søknader)
            VALUES (:id, :fodselsnummer, :skjaeringstidspunkt, :hovedkategori, :kilde, :vurderingstidspunkt, :soknader)
        """
        sessionOf(dataSource).use { session ->
            session.run(
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
