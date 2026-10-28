package no.nav.helse.sykepenger.rettighetskategori

import com.github.navikt.tbd_libs.azure.createAzureTokenClientFromEnvironment
import com.github.navikt.tbd_libs.kafka.AivenConfig
import com.github.navikt.tbd_libs.kafka.Config
import com.github.navikt.tbd_libs.kafka.ConsumerProducerFactory
import com.github.navikt.tbd_libs.rapids_and_rivers_api.RapidsConnection
import com.github.navikt.tbd_libs.spedisjon.SpedisjonClient
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopped
import no.nav.helse.rapids_rivers.RapidApplication
import no.nav.sykepenger.libs.logging.loggInfo
import org.flywaydb.core.Flyway
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.net.http.HttpClient
import java.time.Duration
import javax.sql.DataSource

fun main() {
    val env = System.getenv()
    val tokenProvider = createAzureTokenClientFromEnvironment(env)
    launchApplication(
        env = env,
        kafkaConfig = AivenConfig.default,
        spedisjonClient =
            SpedisjonClient(
                objectMapper = jacksonObjectMapper(),
                tokenProvider = tokenProvider,
            ),
        spleisClient = SpleisClient.fraMiljø(env, HttpClient.newHttpClient(), tokenProvider),
    )
}

internal fun launchApplication(
    env: Map<String, String>,
    kafkaConfig: Config,
    spedisjonClient: SpedisjonClient,
    spleisClient: SpleisClient,
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
        ).apply { registrerRivers(dataSource, spedisjonClient, spleisClient) }
        .start()
}

internal fun RapidsConnection.registrerRivers(
    dataSource: DataSource,
    spedisjonClient: SpedisjonClient,
    spleisClient: SpleisClient,
) {
    val repository = RettighetskategoriRepository(dataSource)
    val innfylling = Innfylling(spleisClient, repository)
    RettighetskategoriRiver(this, Søknader(spedisjonClient), repository)
    RettighetskategoriResultatRiver(this, repository, innfylling)
    PersonAvstemmingRiver(this, innfylling)
}
