package no.nav.helse.sykepenger.rettighetskategori

import com.github.navikt.tbd_libs.access_token.TexasClient
import com.github.navikt.tbd_libs.azure.createAzureTokenClientFromEnvironment
import com.github.navikt.tbd_libs.kafka.AivenConfig
import com.github.navikt.tbd_libs.kafka.Config
import com.github.navikt.tbd_libs.kafka.ConsumerProducerFactory
import com.github.navikt.tbd_libs.personpseudoid.PersonPseudoIdProvider
import com.github.navikt.tbd_libs.personpseudoid.ValkeyPersonPseudoIdProvider
import com.github.navikt.tbd_libs.populasjonstilgang.api.PopulasjonstilgangskontrollProvider
import com.github.navikt.tbd_libs.populasjonstilgang.client.PopulasjonstilgangConfig
import com.github.navikt.tbd_libs.populasjonstilgang.client.tilgangsmaskinenClient
import com.github.navikt.tbd_libs.rapids_and_rivers_api.RapidsConnection
import com.github.navikt.tbd_libs.spedisjon.SpedisjonClient
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopped
import no.nav.helse.rapids_rivers.RapidApplication
import no.nav.helse.sykepenger.rettighetskategori.rest.configureRestApi
import no.nav.sykepenger.libs.ktor.auth.AzureAdConfig
import no.nav.sykepenger.libs.ktor.auth.TilgangsgrupperTilTilganger
import no.nav.sykepenger.libs.ktor.auth.configureJwtAuthentication
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
        personProvider = ValkeyPersonPseudoIdProvider.fraEnv("personpseudoid", env),
        tilgangskontroll = PopulasjonstilgangConfig.fraEnv(env).tilgangsmaskinenClient(TexasClient.fromEnv()),
    )
}

internal fun launchApplication(
    env: Map<String, String>,
    kafkaConfig: Config,
    spedisjonClient: SpedisjonClient,
    spleisClient: SpleisClient,
    personProvider: PersonPseudoIdProvider,
    tilgangskontroll: PopulasjonstilgangskontrollProvider,
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
    val repository = RettighetskategoriRepository(dataSource)
    val backfill = Backfill(spleisClient, repository)

    RapidApplication
        .create(
            env = env,
            consumerProducerFactory = ConsumerProducerFactory(kafkaConfig),
            builder = {
                env["HTTP_PORT"]?.toInt()?.let(::withHttpPort)
                withKtorModule {
                    configureJwtAuthentication(AzureAdConfig.fraEnv(env), TilgangsgrupperTilTilganger.fraEnv(env))
                    configureRestApi(
                        repository,
                        backfill,
                        personProvider,
                        tilgangskontroll,
                    )
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
    val backfill = Backfill(spleisClient, repository)
    RettighetskategoriRiver(this, Søknader(spedisjonClient), repository)
    RettighetskategoriResultatRiver(this, repository, backfill)
    PersonAvstemmingRiver(this, backfill)
}
