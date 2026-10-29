package no.nav.helse.sykepenger.rettighetskategori

import kotliquery.queryOf
import kotliquery.sessionOf
import javax.sql.DataSource
import no.nav.sykepenger.libs.postgres.TestDatabase as PostgresTestDatabase

internal object TestDatabase {
    private val database = PostgresTestDatabase.start().also { it.migrer() }

    val dataSource: DataSource = database.dataSource

    fun tøm() = database.tøm("rettighetskategori", "migreringslogg")

    data class Logginnslag(
        val fødselsnummer: String,
        val antallFraSpleis: Int,
        val antallNye: Int,
    )

    fun migreringslogg(): List<Logginnslag> =
        sessionOf(dataSource).use { session ->
            session.run(
                queryOf("SELECT * FROM migreringslogg ORDER BY løpenummer")
                    .map { row -> Logginnslag(row.string("fødselsnummer"), row.int("antall_fra_spleis"), row.int("antall_nye")) }
                    .asList,
            )
        }
}
