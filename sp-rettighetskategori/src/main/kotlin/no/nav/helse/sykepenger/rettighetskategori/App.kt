package no.nav.helse.sykepenger.rettighetskategori

import com.github.navikt.tbd_libs.kafka.AivenConfig
import com.github.navikt.tbd_libs.kafka.Config
import com.github.navikt.tbd_libs.kafka.ConsumerProducerFactory
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopped
import no.nav.helse.rapids_rivers.RapidApplication
import no.nav.sykepenger.libs.logging.loggInfo
import org.flywaydb.core.Flyway
import java.time.Duration

fun main() {
    launchApplication(
        env = System.getenv(),
        kafkaConfig = AivenConfig.default,
    )
}

fun launchApplication(
    env: Map<String, String>,
    kafkaConfig: Config,
) {
    val dataSource =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = env.getValue("DATABASE_JDBC_URL")
                username = env.getValue("DATABASE_USERNAME")
                password = env.getValue("DATABASE_PASSWORD")
                maximumPoolSize = env.getValue("DB_MAX_POOL_SIZE").toInt()
                minimumIdle = 1
                idleTimeout = Duration.ofMinutes(10).toMillis()
            },
        )

    RapidApplication
        .create(
            env = env,
            consumerProducerFactory = ConsumerProducerFactory(kafkaConfig),
            builder = {
                env["HTTP_PORT"]?.toInt()?.let(::withHttpPort)
                withKtorModule {
                    monitor.subscribe(ApplicationStarted) {
                        loggInfo("Migrerer database")
                        Flyway
                            .configure()
                            .dataSource(dataSource)
                            .cleanDisabled(true)
                            .lockRetryCount(-1)
                            .load()
                            .migrate()
                        loggInfo("Migrering ferdig!")
                    }
                    monitor.subscribe(ApplicationStopped) {
                        loggInfo("Lukker datasource")
                        dataSource.close()
                    }
                }
            },
        ).start()
}
