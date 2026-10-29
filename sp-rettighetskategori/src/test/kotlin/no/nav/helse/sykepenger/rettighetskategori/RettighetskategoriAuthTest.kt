package no.nav.helse.sykepenger.rettighetskategori

import com.github.navikt.tbd_libs.personpseudoid.Identitetsnummer
import com.github.navikt.tbd_libs.personpseudoid.PersonPseudoId
import com.github.navikt.tbd_libs.personpseudoid.PersonPseudoIdProvider
import com.github.navikt.tbd_libs.populasjonstilgang.api.PopulasjonstilgangskontrollProvider
import com.github.navikt.tbd_libs.populasjonstilgang.api.TilgangskontrollResultat
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import no.nav.helse.sykepenger.rettighetskategori.rest.configureRestApi
import no.nav.security.mock.oauth2.MockOAuth2Server
import no.nav.sykepenger.libs.ktor.auth.AzureAdConfig
import no.nav.sykepenger.libs.ktor.auth.TilgangsgrupperTilTilganger
import no.nav.sykepenger.libs.ktor.auth.configureJwtAuthentication
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class RettighetskategoriAuthTest {
    private val oauth = MockOAuth2Server().also { it.start() }
    private val spleis = FakeSpleis()
    private val id = UUID.randomUUID()
    private val fødselsnummer = "12345678901"
    private val claims = mapOf("NAVident" to "Z999999", "oid" to UUID.randomUUID().toString(), "groups" to listOf("les"))
    private val personProvider =
        object : PersonPseudoIdProvider {
            override fun nyPersonPseudoId(identitetsnummer: Identitetsnummer): PersonPseudoId = error("Bare lesetilgang")

            override fun finnIdentitetsnummer(personPseudoId: PersonPseudoId): Identitetsnummer = Identitetsnummer(fødselsnummer)
        }
    private val tilgangskontroll =
        object : PopulasjonstilgangskontrollProvider {
            override fun kontrollerKomplettTilgang(
                accessToken: String,
                fødselsnummer: String,
            ): TilgangskontrollResultat = error("Ikke brukt")

            override fun kontrollerKjerneTilgang(
                accessToken: String,
                fødselsnummer: String,
            ): TilgangskontrollResultat = TilgangskontrollResultat.Ok

            override fun kontrollerKjerneTilgangForAnsatt(
                ansattId: String,
                fødselsnummer: String,
            ): TilgangskontrollResultat = error("Ikke brukt")
        }

    @AfterAll
    fun stoppUtsteder() {
        oauth.shutdown()
    }

    @Test
    fun `gyldig Azure-token slipper gjennom til oppslag`() = sjekk(HttpStatusCode.OK)

    @Test
    fun `skrivegruppe alene gir også lesetilgang`() = sjekk(HttpStatusCode.OK, claims + ("groups" to listOf("skriv")))

    @Test
    fun `token uten tilgangsgruppe gir 403`() = sjekk(HttpStatusCode.Forbidden, claims - "groups")

    @Test
    fun `feil audience gir 401 uten body`() = sjekk(HttpStatusCode.Unauthorized, audience = "annen-tjeneste")

    @Test
    fun `feil issuer gir 401 uten body`() = sjekk(HttpStatusCode.Unauthorized, issuer = "annen-utsteder")

    @Test
    fun `mangler NAVident gir 401 uten body`() = sjekk(HttpStatusCode.Unauthorized, claims - "NAVident")

    @Test
    fun `mangler oid gir 401 uten body`() = sjekk(HttpStatusCode.Unauthorized, claims - "oid")

    @Test
    fun `mangler token gir 401 uten body`() = sjekk(HttpStatusCode.Unauthorized, utenToken = true)

    @Test
    fun `ugyldig token gir 401 uten body`() = sjekk(HttpStatusCode.Unauthorized, rawToken = "ugyldig")

    @Test
    fun `ugyldig gruppeclaim gir 401 uten body`() = sjekk(HttpStatusCode.Unauthorized, claims + ("groups" to "les"))

    private fun sjekk(
        forventetStatus: HttpStatusCode,
        tokenClaims: Map<String, Any> = claims,
        audience: String = "sp-rettighetskategori",
        issuer: String = "azure",
        utenToken: Boolean = false,
        rawToken: String? = null,
    ) = testApplication {
        val repository = RettighetskategoriRepository(TestDatabase.dataSource)
        repository.lagre(Rettighetskategori.fraSpleis(id, fødselsnummer, LocalDate.parse("2026-01-01"), Hovedkategori.ARBEIDSTAKER, Instant.now()))
        application {
            configureJwtAuthentication(
                AzureAdConfig("sp-rettighetskategori", oauth.issuerUrl("azure").toString(), oauth.jwksUrl("azure").toString()),
                TilgangsgrupperTilTilganger.fraEnv(
                    mapOf("TILGANG_LES" to "annen-lesegruppe, les ", "TILGANG_SKRIV" to "annen-skrivegruppe, skriv "),
                ),
            )
            configureRestApi(repository, Backfill(spleis.client, repository), personProvider, tilgangskontroll)
        }
        val token =
            rawToken ?: oauth
                .issueToken(
                    issuerId = issuer,
                    audience = audience,
                    claims = tokenClaims,
                ).serialize()

        val response =
            client.get("/api/personer/${UUID.randomUUID()}/rettighetskategorier/$id") {
                if (!utenToken) bearerAuth(token)
            }

        assertEquals(forventetStatus, response.status)
        if (forventetStatus == HttpStatusCode.Unauthorized) assertEquals("", response.bodyAsText())
    }
}
