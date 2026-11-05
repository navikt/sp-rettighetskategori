package no.nav.helse.sykepenger.rettighetskategori

import com.github.navikt.tbd_libs.kafka.Config
import com.github.navikt.tbd_libs.rapids_and_rivers.JsonMessage
import com.sun.net.httpserver.HttpServer
import kotliquery.queryOf
import kotliquery.sessionOf
import no.nav.sykepenger.libs.outbox.Outbox
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
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.kafka.KafkaContainer
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import no.nav.sykepenger.libs.postgres.TestDatabase as PostgresTestDatabase

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class AppTest {
    private val rapidTopic = "tbd.rapid.v1"
    private val testDatabase = PostgresTestDatabase.start()
    private val kafka = KafkaContainer("apache/kafka-native:3.9.1").also { it.start() }
    private val kafkaConfig = TestKafkaConfig(kafka.bootstrapServers)
    private val electorMessages = LinkedBlockingQueue<String>()
    private val electorRunning = AtomicBoolean(true)
    private val electorServer =
        HttpServer
            .create(InetSocketAddress("localhost", 0), 0)
            .apply {
                createContext("/sse") { exchange ->
                    exchange.responseHeaders.add("Content-Type", "text/event-stream")
                    exchange.sendResponseHeaders(200, 0)
                    try {
                        exchange.responseBody.bufferedWriter().use { writer ->
                            while (electorRunning.get()) {
                                val leader = electorMessages.poll(100, TimeUnit.MILLISECONDS) ?: continue
                                writer.write("data: {\"name\":\"$leader\"}\n\n")
                                writer.flush()
                            }
                        }
                    } catch (_: java.io.IOException) {
                    }
                }
                executor =
                    Executors.newCachedThreadPool { task ->
                        Thread(task, "elector-test").apply { isDaemon = true }
                    }
                start()
            }
    private val httpPort = ServerSocket(0).use { it.localPort }
    private val httpClient = HttpClient.newHttpClient()

    @BeforeAll
    fun startApplikasjonen() {
        electorMessages.add("another-pod")
        AdminClient.create(kafkaConfig.adminConfig(Properties())).use {
            it.createTopics(listOf(NewTopic(rapidTopic, 1, 1))).all().get()
        }
        thread(name = "applikasjon", isDaemon = true) {
            launchApplication(
                env =
                    testDatabase.env() +
                        mapOf(
                            "DB_POOL_SIZE" to "2",
                            "HTTP_PORT" to httpPort.toString(),
                            "NAIS_APP_NAME" to "sp-rettighetskategori",
                            "RAPID_APP_NAME" to "sp-rettighetskategori",
                            "KAFKA_CONSUMER_GROUP_ID" to "sp-rettighetskategori-test",
                            "KAFKA_RAPID_TOPIC" to rapidTopic,
                            "KAFKA_RESET_POLICY" to "earliest",
                            "AZURE_APP_CLIENT_ID" to "sp-rettighetskategori",
                            "AZURE_OPENID_CONFIG_ISSUER" to "http://localhost/azure",
                            "AZURE_OPENID_CONFIG_JWKS_URI" to "http://localhost/azure/jwks",
                            "ELECTOR_SSE_URL" to "http://localhost:${electorServer.address.port}/sse",
                            "HOSTNAME" to "test-pod",
                            "TILGANG_LES" to "les",
                            "TILGANG_SKRIV" to "skriv",
                            "EKSPONER_OPENAPI" to "true",
                        ),
                kafkaConfig = kafkaConfig,
                spedisjonClient = FakeSpedisjon().client,
                spleisClient = FakeSpleis().client,
                personProvider = FakePersonProvider(),
                tilgangskontroll = FakeTilgangskontroll(),
            )
        }
        ventTil("applikasjonen er klar") { statuskode("/isready") == 200 }
    }

    @AfterAll
    fun stopElector() {
        electorRunning.set(false)
        electorServer.stop(0)
    }

    @Test
    fun `svarer på helsesjekker`() {
        assertEquals(200, statuskode("/isalive"))
        assertEquals(200, statuskode("/isready"))
    }

    @Test
    fun `REST-endepunktet er registrert og krever autentisering`() {
        assertEquals(401, statuskode("/api/personer/${UUID.randomUUID()}/rettighetskategorier/${UUID.randomUUID()}"))
        assertEquals(404, statuskode("/api/openapi.json"))
        assertEquals(404, statuskode("/api/swagger"))
    }

    @Test
    fun `har migrert databasen`() {
        val antallVellykkedeMigreringer =
            sessionOf(testDatabase.dataSource).use { session ->
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

    @Test
    fun `publiserer outbox-meldinger bare når poden er leder`() {
        val rettighetskategoriId = UUID.randomUUID().toString()
        val melding =
            JsonMessage
                .newMessage(
                    "rettighetskategori_endret",
                    mapOf(
                        "fødselsnummer" to "12345678901",
                        "skjæringstidspunkt" to "2026-01-01",
                        "rettighetskategoriId" to rettighetskategoriId,
                        "hovedkategori" to "SELVSTENDIG_NÆRINGSDRIVENDE",
                    ),
                ).toJson()
        val partition = TopicPartition(rapidTopic, 0)

        KafkaConsumer(
            kafkaConfig.consumerConfig("outbox-test-${UUID.randomUUID()}", Properties()),
            StringDeserializer(),
            StringDeserializer(),
        ).use { consumer ->
            consumer.assign(listOf(partition))
            consumer.seekToEnd(listOf(partition))
            consumer.position(partition)
            testDatabase.dataSource.connection.use { connection ->
                Outbox.leggTil(connection, "12345678901", melding)
            }

            val beforeLeadership = consumer.poll(Duration.ofSeconds(2)).firstOrNull { it.value().contains(rettighetskategoriId) }
            assertNull(beforeLeadership)

            electorMessages.put("test-pod")
            val record = ventTilMelding(consumer, rettighetskategoriId)
            assertEquals("12345678901", record.key())
            assertTrue(record.value().contains("rettighetskategori_endret"))
            assertTrue(record.value().contains(rettighetskategoriId))
            ventTil("outbox-meldingen er merket som publisert") {
                sessionOf(testDatabase.dataSource).use { session ->
                    session.run(
                        queryOf(
                            "SELECT count(*) FROM outbox WHERE melding ->> 'rettighetskategoriId' = :id AND publisert_tidspunkt IS NOT NULL",
                            mapOf("id" to rettighetskategoriId),
                        ).map { it.int(1) }.asSingle,
                    ) == 1
                }
            }
        }
    }

    private fun ventTilMelding(
        consumer: KafkaConsumer<String, String>,
        rettighetskategoriId: String,
    ) = run {
        val frist = Instant.now().plusSeconds(15)
        while (Instant.now() < frist) {
            val melding = consumer.poll(Duration.ofMillis(250)).firstOrNull { it.value().contains(rettighetskategoriId) }
            if (melding != null) return@run melding
        }
        fail("Fikk ikke publisert outbox-meldingen med rettighetskategoriId $rettighetskategoriId")
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
