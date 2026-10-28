package no.nav.helse.sykepenger.rettighetskategori.opprydding_dev

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.time.Duration

internal class DataSourceBuilder(
    env: Map<String, String>,
) {
    private val gcpProjectId: String = env.envValue("GCP_TEAM_PROJECT_ID")
    private val databaseRegion: String = env.envValue("DATABASE_REGION")
    private val databaseInstance: String = env.envValue("DATABASE_INSTANCE")
    private val databaseUsername: String = env.envValue("DATABASE_SP_RETTIGHETSKATEGORI_OPPRYDDING_DEV_USERNAME")
    private val databasePassword: String = env.envValue("DATABASE_SP_RETTIGHETSKATEGORI_OPPRYDDING_DEV_PASSWORD")
    private val databaseName: String = env.envValue("DATABASE_SP_RETTIGHETSKATEGORI_OPPRYDDING_DEV_DATABASE")

    private fun Map<String, String>.envValue(key: String) = requireNotNull(get(key)) { "$key must be set" }

    private val hikariConfig =
        HikariConfig().apply {
            jdbcUrl =
                "jdbc:postgresql:///%s?%s&%s".format(
                    databaseName,
                    "cloudSqlInstance=$gcpProjectId:$databaseRegion:$databaseInstance",
                    "socketFactory=com.google.cloud.sql.postgres.SocketFactory",
                )
            username = databaseUsername
            password = databasePassword

            maximumPoolSize = 3
            minimumIdle = 1
            initializationFailTimeout = Duration.ofMinutes(1).toMillis()
            connectionTimeout = Duration.ofSeconds(5).toMillis()
            maxLifetime = Duration.ofMinutes(30).toMillis()
            idleTimeout = Duration.ofMinutes(10).toMillis()
        }

    internal fun getDataSource() = HikariDataSource(hikariConfig)
}
