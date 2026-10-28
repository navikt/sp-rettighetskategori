package no.nav.helse.sykepenger.rettighetskategori

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotliquery.queryOf
import kotliquery.sessionOf
import org.flywaydb.core.Flyway
import org.testcontainers.postgresql.PostgreSQLContainer

internal object TestDatabase {
    private val postgres = PostgreSQLContainer("postgres:18").also { it.start() }

    val dataSource: HikariDataSource =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = postgres.jdbcUrl
                username = postgres.username
                password = postgres.password
                maximumPoolSize = 2
            },
        ).also {
            Flyway
                .configure()
                .dataSource(it)
                .load()
                .migrate()
        }

    fun tøm() {
        sessionOf(dataSource).use { it.run(queryOf("TRUNCATE rettighetskategori").asExecute) }
    }
}
