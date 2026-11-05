package no.nav.helse.sykepenger.rettighetskategori

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import no.nav.helse.sykepenger.rettighetskategori.rest.configureRestApi
import no.nav.security.mock.oauth2.MockOAuth2Server
import no.nav.sykepenger.libs.ktor.auth.AzureAdConfig
import no.nav.sykepenger.libs.ktor.auth.TilgangsgrupperTilTilganger
import no.nav.sykepenger.libs.ktor.auth.configureAppAuthentication
import no.nav.sykepenger.libs.ktor.auth.configureJwtAuthentication
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class RettighetskategoriTjenesteApiTest {
    private val oauth = MockOAuth2Server().also { it.start() }
    private val repository = RettighetskategoriRepository(TestDatabase.dataSource)
    private lateinit var spleis: FakeSpleis
    private val fødselsnummer = "12345678901"
    private val id = UUID.fromString("099b102a-e684-4791-9c60-82bd9d4788d5")
    private val path = "/api/rettighetskategorier/$id"
    private val appClaims = mapOf("idtyp" to "app", "azp" to "sp-forsikring-klient-id", "azp_name" to "dev-gcp:tbd:sp-forsikring")

    @BeforeEach
    fun nullstill() {
        TestDatabase.tøm()
        spleis = FakeSpleis()
    }

    @AfterAll
    fun stoppUtsteder() {
        oauth.shutdown()
    }

    @Test
    fun `kjent avgjørelse gir alle kontraktsfeltene uten fødselsnummer eller kall til Spleis`() =
        testApplication {
            repository.lagre(
                Rettighetskategori(
                    id,
                    fødselsnummer,
                    LocalDate.parse("2026-01-01"),
                    Konklusjon(Hovedkategori.SELVSTENDIG_NÆRINGSDRIVENDE, setOf(Tilleggskategori.JORDBRUKER)),
                    Kilde.AUTOMATISK,
                    Instant.parse("2026-02-01T10:00:00Z"),
                ),
            )
            settOpp()

            val response = hent()

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(
                jacksonObjectMapper().readTree(
                    """{
                      "id":"099b102a-e684-4791-9c60-82bd9d4788d5",
                      "skjæringstidspunkt":"2026-01-01",
                      "hovedkategori":"SELVSTENDIG_NAERINGSDRIVENDE",
                      "tilleggskategorier":["JORDBRUKER"],
                      "kilde":"AUTOMATISK",
                      "vurderingstidspunkt":"2026-02-01T10:00:00Z"
                    }""",
                ),
                jacksonObjectMapper().readTree(response.bodyAsText()),
            )
            assertEquals(0, spleis.antallKall)
        }

    @Test
    fun `ukjent avgjørelse hentes fra Spleis og returneres i samme kall`() =
        testApplication {
            spleis.rettighetskategori(fødselsnummer, aktive = listOf("SELVSTENDIG"), rettighetskategoriId = id)
            settOpp()

            val response = hent()

            assertEquals(HttpStatusCode.OK, response.status)
            val json = jacksonObjectMapper().readTree(response.bodyAsText())
            assertEquals(id.toString(), json["id"].asString())
            assertEquals("SPLEIS", json["kilde"].asString())
            assertEquals("SELVSTENDIG_NAERINGSDRIVENDE", json["hovedkategori"].asString())
            assertEquals(jacksonObjectMapper().readTree("[]"), json["tilleggskategorier"])
            assertEquals(1, spleis.antallKall)
        }

    @Test
    fun `ukjent avgjørelse etter backfill gir 404 med problem details`() =
        testApplication {
            settOpp()

            val response = hent()

            assertProblem(HttpStatusCode.NotFound, response)
            assertEquals(1, spleis.antallKall)
        }

    @Test
    fun `avgjørelse for annet fødselsnummer gir 404 uten backfill`() =
        testApplication {
            repository.lagre(
                Rettighetskategori.fraSpleis(id, "98765432109", LocalDate.parse("2026-01-01"), Hovedkategori.ARBEIDSTAKER, Instant.now()),
            )
            settOpp()

            val response = hent()

            assertProblem(HttpStatusCode.NotFound, response)
            assertFalse(response.bodyAsText().contains("98765432109"))
            assertEquals(0, spleis.antallKall)
        }

    @Test
    fun `feil fra Spleis under backfill gir 503`() =
        testApplication {
            spleis.feiler()
            settOpp()
            assertProblem(HttpStatusCode.ServiceUnavailable, hent())
        }

    @Test
    fun `nettverksfeil mot Spleis under backfill gir 503`() =
        testApplication {
            spleis.nettverksfeil()
            settOpp()
            assertProblem(HttpStatusCode.ServiceUnavailable, hent())
        }

    @ParameterizedTest
    @ValueSource(strings = ["ugyldig", "1-1-1-1-1"])
    fun `ugyldig UUID gir 400 uten backfill`(rettighetskategoriId: String) =
        testApplication {
            settOpp()
            assertProblem(HttpStatusCode.BadRequest, hent(path = "/api/rettighetskategorier/$rettighetskategoriId"))
            assertEquals(0, spleis.antallKall)
        }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "",
            "ikke json",
            "[]",
            "{}",
            """{"fødselsnummer":null}""",
            """{"fødselsnummer":12345678901}""",
            """{"fødselsnummer":"1234567890"}""",
            """{"fødselsnummer":"123456789012"}""",
            """{"fødselsnummer":"1234567890a"}""",
            """{"fødselsnummer":" 12345678901"}""",
        ],
    )
    fun `ugyldig body gir 400 uten backfill`(body: String) =
        testApplication {
            settOpp()
            assertProblem(HttpStatusCode.BadRequest, hent(body = body))
            assertEquals(0, spleis.antallKall)
        }

    @Test
    fun `saksbehandlertoken gir 401 uten body`() =
        testApplication {
            settOpp()
            val saksbehandlerClaims = mapOf("NAVident" to "Z999999", "oid" to UUID.randomUUID().toString(), "azp" to "speil-klient-id", "groups" to listOf("les"))
            assertUautentisert(hent(token = token(saksbehandlerClaims)))
            assertEquals(0, spleis.antallKall)
        }

    @Test
    fun `token uten idtyp app gir 401 uten body`() =
        testApplication {
            settOpp()
            assertUautentisert(hent(token = token(appClaims + ("idtyp" to "user"))))
        }

    @Test
    fun `token til en annen app gir 401 uten body`() =
        testApplication {
            settOpp()
            assertUautentisert(hent(token = token(appClaims, audience = "annen-tjeneste")))
        }

    @Test
    fun `token fra en annen utsteder gir 401 uten body`() =
        testApplication {
            settOpp()
            assertUautentisert(hent(token = token(appClaims, issuer = "annen-utsteder")))
        }

    @Test
    fun `mangler token gir 401 uten body`() =
        testApplication {
            settOpp()
            assertUautentisert(hent(token = null))
        }

    @Test
    fun `app-token gir 401 på endepunktet for saksbehandlere`() =
        testApplication {
            repository.lagre(Rettighetskategori.fraSpleis(id, fødselsnummer, LocalDate.parse("2026-01-01"), Hovedkategori.ARBEIDSTAKER, Instant.now()))
            settOpp()
            val response =
                client.get("/api/personer/${UUID.randomUUID()}/rettighetskategorier/$id") {
                    bearerAuth(token(appClaims))
                }
            assertUautentisert(response)
        }

    private fun ApplicationTestBuilder.settOpp() {
        application {
            val azureAdConfig = AzureAdConfig(CLIENT_ID, oauth.issuerUrl(ISSUER_ID).toString(), oauth.jwksUrl(ISSUER_ID).toString())
            configureJwtAuthentication(azureAdConfig, TilgangsgrupperTilTilganger(setOf("les"), emptySet()))
            configureAppAuthentication(azureAdConfig)
            configureRestApi(repository, Backfill(spleis.client, repository), FakePersonProvider(), FakeTilgangskontroll())
        }
    }

    private suspend fun ApplicationTestBuilder.hent(
        path: String = this@RettighetskategoriTjenesteApiTest.path,
        body: String = """{"fødselsnummer":"$fødselsnummer"}""",
        token: String? = token(appClaims),
    ) = client.post(path) {
        if (token != null) bearerAuth(token)
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private fun token(
        claims: Map<String, Any>,
        audience: String = CLIENT_ID,
        issuer: String = ISSUER_ID,
    ) = oauth.anyToken(oauth.issuerUrl(issuer), claims + ("aud" to audience)).serialize()

    private suspend fun assertProblem(
        forventetStatus: HttpStatusCode,
        response: HttpResponse,
    ) {
        assertEquals(forventetStatus, response.status)
        assertEquals("application/problem+json", response.headers["Content-Type"]?.substringBefore(";"))
        assertEquals(forventetStatus.value, jacksonObjectMapper().readTree(response.bodyAsText())["status"].asInt())
    }

    private suspend fun assertUautentisert(response: HttpResponse) {
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals("", response.bodyAsText())
    }

    private companion object {
        const val CLIENT_ID = "sp-rettighetskategori"
        const val ISSUER_ID = "azure"
    }
}
