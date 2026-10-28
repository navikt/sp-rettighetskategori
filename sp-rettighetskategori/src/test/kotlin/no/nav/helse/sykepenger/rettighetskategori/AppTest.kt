package no.nav.helse.sykepenger.rettighetskategori

import com.github.navikt.tbd_libs.kafka.Config
import kotliquery.queryOf
import kotliquery.sessionOf
import org.apache.kafka.clients.CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG
import org.apache.kafka.clients.CommonClientConfigs.SECURITY_PROTOCOL_CONFIG
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.security.auth.SecurityProtocol
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.util.*
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class AppTest {
    private val rapidTopic = "tbd.rapid.v1"
    private val postgres = PostgreSQLContainer("postgres:18").also { it.start() }
    private val kafka = KafkaContainer("apache/kafka-native:3.9.1").also { it.start() }
    private val kafkaConfig = TestKafkaConfig(kafka.bootstrapServers)
    private val httpPort = ServerSocket(0).use { it.localPort }
    private val httpClient = HttpClient.newHttpClient()

    @BeforeAll
    fun startApplikasjonen() {
        AdminClient.create(kafkaConfig.adminConfig(Properties())).use {
            it.createTopics(listOf(NewTopic(rapidTopic, 1, 1))).all().get()
        }
        thread(name = "applikasjon", isDaemon = true) {
            launchApplication(
                env =
                    mapOf(
                        "DATABASE_JDBC_URL" to postgres.jdbcUrl,
                        "DATABASE_USERNAME" to postgres.username,
                        "DATABASE_PASSWORD" to postgres.password,
                        "DB_MAX_POOL_SIZE" to "2",
                        "HTTP_PORT" to httpPort.toString(),
                        "NAIS_APP_NAME" to "sp-rettighetskategori",
                        "RAPID_APP_NAME" to "sp-rettighetskategori",
                        "KAFKA_CONSUMER_GROUP_ID" to "sp-rettighetskategori-test",
                        "KAFKA_RAPID_TOPIC" to rapidTopic,
                        "KAFKA_RESET_POLICY" to "earliest",
                    ),
                kafkaConfig = kafkaConfig,
                spedisjonClient = FakeSpedisjon().client,
            )
        }
        ventTil("applikasjonen er klar") { statuskode("/isready") == 200 }
    }

    @Test
    fun `svarer på helsesjekker`() {
        assertEquals(200, statuskode("/isalive"))
        assertEquals(200, statuskode("/isready"))
    }

    @Test
    fun `har migrert databasen`() {
        val dataSource =
            PGSimpleDataSource().apply {
                setURL(postgres.jdbcUrl)
                user = postgres.username
                password = postgres.password
            }
        val antallVellykkedeMigreringer =
            sessionOf(dataSource).use { session ->
                session.run(
                    queryOf("SELECT COUNT(*) FROM flyway_schema_history WHERE success")
                        .map { it.int(1) }
                        .asSingle,
                )
            }
        assertTrue((antallVellykkedeMigreringer ?: 0) > 0)
    }

    @Test
    fun `svarer på ping med pong`() {
        val pingId = UUID.randomUUID().toString()
        val partition = TopicPartition(rapidTopic, 0)
        KafkaConsumer(
            kafkaConfig.consumerConfig("test-${UUID.randomUUID()}", Properties()),
            StringDeserializer(),
            StringDeserializer(),
        ).use { consumer ->
            consumer.assign(listOf(partition))
            consumer.seekToEnd(listOf(partition))
            consumer.position(partition)

            KafkaProducer(kafkaConfig.producerConfig(Properties()), StringSerializer(), StringSerializer()).use { producer ->
                producer
                    .send(
                        ProducerRecord(
                            rapidTopic,
                            """{"@event_name":"ping","@id":"$pingId","ping_time":"${LocalDateTime.now()}"}""",
                        ),
                    ).get()
            }

            val frist = Instant.now().plusSeconds(15)
            while (Instant.now() < frist) {
                val pong =
                    consumer
                        .poll(Duration.ofMillis(250))
                        .map { it.value() }
                        .firstOrNull { it.contains("\"pong\"") && it.contains(pingId) }
                if (pong != null) {
                    assertTrue(pong.contains("\"app_name\":\"sp-rettighetskategori\""), pong)
                    return
                }
            }
            fail("Fikk ikke pong på ping $pingId")
        }
    }

    private fun statuskode(path: String): Int? =
        runCatching {
            httpClient
                .send(
                    HttpRequest.newBuilder(URI("http://localhost:$httpPort$path")).GET().build(),
                    HttpResponse.BodyHandlers.discarding(),
                ).statusCode()
        }.getOrNull()

    private fun ventTil(
        beskrivelse: String,
        betingelse: () -> Boolean,
    ) {
        val frist = Instant.now().plusSeconds(60)
        while (Instant.now() < frist) {
            if (betingelse()) return
            Thread.sleep(100)
        }
        fail("Ventet for lenge på at $beskrivelse")
    }

    private class TestKafkaConfig(
        private val bootstrapServers: String,
    ) : Config {
        override fun producerConfig(properties: Properties) =
            Properties().apply {
                put(BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
                put(SECURITY_PROTOCOL_CONFIG, SecurityProtocol.PLAINTEXT.name)
                put(ProducerConfig.ACKS_CONFIG, "all")
                put(ProducerConfig.LINGER_MS_CONFIG, "0")
                putAll(properties)
            }

        override fun consumerConfig(
            groupId: String,
            properties: Properties,
        ) = Properties().apply {
            put(BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
            put(SECURITY_PROTOCOL_CONFIG, SecurityProtocol.PLAINTEXT.name)
            put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
            putAll(properties)
            put(ConsumerConfig.GROUP_ID_CONFIG, groupId)
        }

        override fun adminConfig(properties: Properties) =
            Properties().apply {
                put(BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
                put(SECURITY_PROTOCOL_CONFIG, SecurityProtocol.PLAINTEXT.name)
                putAll(properties)
            }
    }
}
