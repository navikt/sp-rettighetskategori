package no.nav.helse.sykepenger.rettighetskategori.opprydding_dev

import no.nav.sykepenger.libs.postgres.TestDatabase
import org.flywaydb.core.Flyway
import javax.sql.DataSource

object Database {
    private val testDatabase = TestDatabase.start(postgresImage = "postgres:18")

    val dataSource: DataSource = testDatabase.dataSource

    private val flyway =
        Flyway
            .configure()
            .dataSource(dataSource)
            .cleanDisabled(false)
            .load()
            .also { it.migrate() }

    fun reset() {
        flyway.clean()
        flyway.migrate()
    }

    fun shutdown() {
        testDatabase.stop()
    }
}
