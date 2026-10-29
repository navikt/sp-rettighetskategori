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
import io.ktor.server.application.ApplicationStopped
import no.nav.helse.rapids_rivers.RapidApplication
import no.nav.helse.sykepenger.rettighetskategori.rest.configureRestApi
import no.nav.sykepenger.libs.ktor.auth.AzureAdConfig
import no.nav.sykepenger.libs.ktor.auth.TilgangsgrupperTilTilganger
import no.nav.sykepenger.libs.ktor.auth.configureJwtAuthentication
import no.nav.sykepenger.libs.logging.loggInfo
import no.nav.sykepenger.libs.logging.navngittLogger
import no.nav.sykepenger.libs.postgres.DatabaseConfig
import no.nav.sykepenger.libs.postgres.dataSource
import no.nav.sykepenger.libs.postgres.migrerSynkront
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.net.http.HttpClient
import javax.sql.DataSource

private val logger = navngittLogger("no.nav.helse.sykepenger.rettighetskategori.App")

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
    val databaseConfig = DatabaseConfig.fraEnv(env)
    logger.info("Migrerer database")
    migrerSynkront(databaseConfig)
    logger.info("Migrering ferdig")
    val dataSource = databaseConfig.dataSource()
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
