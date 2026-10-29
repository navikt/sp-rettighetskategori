package no.nav.helse.sykepenger.rettighetskategori

import com.github.navikt.tbd_libs.personpseudoid.Identitetsnummer
import com.github.navikt.tbd_libs.personpseudoid.PersonPseudoId
import com.github.navikt.tbd_libs.personpseudoid.PersonPseudoIdProvider
import com.github.navikt.tbd_libs.populasjonstilgang.api.PopulasjonstilgangskontrollProvider
import com.github.navikt.tbd_libs.populasjonstilgang.api.TilgangSomMangler
import com.github.navikt.tbd_libs.populasjonstilgang.api.TilgangskontrollResultat
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.testing.testApplication
import no.nav.helse.sykepenger.rettighetskategori.rest.configureRestApi
import no.nav.sykepenger.libs.ktor.auth.AZURE_AD_AUTHENTICATION_NAME
import no.nav.sykepenger.libs.ktor.auth.AccessToken
import no.nav.sykepenger.libs.ktor.auth.NavIdent
import no.nav.sykepenger.libs.ktor.auth.Saksbehandler
import no.nav.sykepenger.libs.ktor.auth.SaksbehandlerOid
import no.nav.sykepenger.libs.ktor.auth.SaksbehandlerPrincipal
import no.nav.sykepenger.libs.ktor.auth.Tilgang
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class RettighetskategoriApiTest {
    private val repository = RettighetskategoriRepository(TestDatabase.dataSource)
    private val spleis = FakeSpleis()
    private val pseudoId = PersonPseudoId(UUID.fromString("739e8064-2e87-4ec4-80a6-b521872b3e20"))
    private val fødselsnummer = "12345678901"
    private val id = UUID.fromString("099b102a-e684-4791-9c60-82bd9d4788d5")
    private val path = "/api/personer/$pseudoId/rettighetskategorier/$id"
    private var harLesetilgang = true
    private var tilgangsresultat: TilgangskontrollResultat = TilgangskontrollResultat.Ok
    private var tilgangsfeil: Exception? = null
    private val personProvider =
        object : PersonPseudoIdProvider {
            override fun nyPersonPseudoId(identitetsnummer: Identitetsnummer): PersonPseudoId = error("Bare lesetilgang")

            override fun finnIdentitetsnummer(personPseudoId: PersonPseudoId): Identitetsnummer? = if (personPseudoId == pseudoId) Identitetsnummer(fødselsnummer) else null
        }
    private val tilgangskontroll =
        object : PopulasjonstilgangskontrollProvider {
            override fun kontrollerKomplettTilgang(
                accessToken: String,
                fødselsnummer: String,
            ): TilgangskontrollResultat = error("Skal bruke kjernetilgang")

            override fun kontrollerKjerneTilgang(
                accessToken: String,
                fødselsnummer: String,
            ): TilgangskontrollResultat {
                tilgangsfeil?.let { throw it }
                return tilgangsresultat
            }

            override fun kontrollerKjerneTilgangForAnsatt(
                ansattId: String,
                fødselsnummer: String,
            ): TilgangskontrollResultat = error("Skal bruke saksbehandlers token")
        }

    @BeforeEach
    fun tømDatabase() {
        TestDatabase.tøm()
    }

    private fun Application.settOpp() {
        install(Authentication) {
            provider(AZURE_AD_AUTHENTICATION_NAME) {
                authenticate { context -> context.principal(saksbehandlerPrincipal()) }
            }
        }
        configureRestApi(repository, Backfill(spleis.client, repository), personProvider, tilgangskontroll)
    }

    private fun saksbehandlerPrincipal() =
        SaksbehandlerPrincipal(
            Saksbehandler(NavIdent("Z999999"), SaksbehandlerOid("oid"), "Saksbehandler"),
            if (harLesetilgang) setOf(Tilgang.Les) else emptySet(),
            AccessToken("token"),
        )

    @Test
    fun `OpenAPI og Swagger finnes ikke`() =
        testApplication {
            application { settOpp() }
            assertEquals(HttpStatusCode.NotFound, client.get("/api/openapi.json").status)
            assertEquals(HttpStatusCode.NotFound, client.get("/api/swagger").status)
        }

    @Test
    fun `ukjent avgjørelse hentes fra Spleis og returneres i samme kall`() =
        testApplication {
            spleis.rettighetskategori(fødselsnummer, aktive = listOf("SELVSTENDIG"), rettighetskategoriId = id)
            application { settOpp() }

            val response = client.get(path)

            assertEquals(HttpStatusCode.OK, response.status)
            val json = jacksonObjectMapper().readTree(response.bodyAsText())
            assertEquals(id.toString(), json["id"].asString())
            assertEquals("SPLEIS", json["kilde"].asString())
            assertEquals("SELVSTENDIG_NAERINGSDRIVENDE", json["hovedkategori"].asString())
            assertEquals(1, spleis.antallKall)
            assertEquals(response.bodyAsText(), client.get(path).bodyAsText())
            assertEquals(1, spleis.antallKall)
        }

    @Test
    fun `feil fra Spleis gir 503 med problem details`() =
        testApplication {
            spleis.feiler()
            application { settOpp() }

            val response = client.get(path)

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertEquals("application/problem+json", response.headers["Content-Type"]?.substringBefore(";"))
            val json = jacksonObjectMapper().readTree(response.bodyAsText())
            assertEquals("about:blank", json["type"].asString())
            assertEquals("Spleis er utilgjengelig", json["title"].asString())
            assertEquals(503, json["status"].asInt())
            assertTrue(json["detail"].isNull)
            assertEquals(path, json["instance"].asString())
        }

    @Test
    fun `ukjent avgjørelse etter backfill gir 404`() =
        testApplication {
            application { settOpp() }
            assertEquals(HttpStatusCode.NotFound, client.get(path).status)
            assertEquals(1, spleis.antallKall)
        }

    @Test
    fun `avgjørelse for annen person gir 404 uten backfill`() =
        testApplication {
            repository.lagre(
                Rettighetskategori.fraSpleis(id, "98765432109", LocalDate.parse("2026-01-01"), Hovedkategori.ARBEIDSTAKER, Instant.now()),
            )
            application { settOpp() }
            assertEquals(HttpStatusCode.NotFound, client.get(path).status)
            assertEquals(0, spleis.antallKall)
        }

    @Test
    fun `ugyldig og ukjent pseudo-ID gir 404 uten backfill`() =
        testApplication {
            application { settOpp() }
            listOf("ugyldig", UUID.randomUUID().toString()).forEach {
                assertEquals(HttpStatusCode.NotFound, client.get("/api/personer/$it/rettighetskategorier/$id").status)
            }
            assertEquals(0, spleis.antallKall)
        }

    @Test
    fun `ugyldig UUID gir 400 med problem details uten backfill`() =
        testApplication {
            application { settOpp() }
            listOf("ugyldig", "1-1-1-1-1").forEach {
                val response = client.get("/api/personer/$pseudoId/rettighetskategorier/$it")
                assertEquals(HttpStatusCode.BadRequest, response.status)
                assertEquals("application/problem+json", response.headers["Content-Type"]?.substringBefore(";"))
                assertEquals(400, jacksonObjectMapper().readTree(response.bodyAsText())["status"].asInt())
            }
            assertEquals(0, spleis.antallKall)
        }

    @Test
    fun `mangler lesetilgang gir 403 før personoppslag og backfill`() =
        testApplication {
            harLesetilgang = false
            application { settOpp() }
            assertEquals(HttpStatusCode.Forbidden, client.get(path).status)
            assertEquals(HttpStatusCode.Forbidden, client.get("/api/personer/ugyldig/rettighetskategorier/$id").status)
            assertEquals(0, spleis.antallKall)
        }

    @Test
    fun `avslag fra tilgangsmaskinen gir 403 uten backfill`() =
        testApplication {
            tilgangsresultat = TilgangskontrollResultat.ManglerTilgang(TilgangSomMangler.Habilitet)
            application { settOpp() }
            assertEquals(HttpStatusCode.Forbidden, client.get(path).status)
            assertEquals(0, spleis.antallKall)
        }

    @Test
    fun `uventet feil fra tilgangsmaskinen gir 403 uten backfill`() =
        testApplication {
            tilgangsresultat = TilgangskontrollResultat.UventetFeil("Ingen beslutning")
            application { settOpp() }
            assertEquals(HttpStatusCode.Forbidden, client.get(path).status)
            assertEquals(0, spleis.antallKall)
        }

    @Test
    fun `ident ikke funnet i tilgangsmaskinen gir 404 uten backfill`() =
        testApplication {
            tilgangsresultat = TilgangskontrollResultat.IdentIkkeFunnet
            application { settOpp() }
            assertEquals(HttpStatusCode.NotFound, client.get(path).status)
            assertEquals(0, spleis.antallKall)
        }

    @Test
    fun `nettverksfeil i tilgangskontrollen gir 403 uten backfill`() =
        testApplication {
            tilgangsfeil = IOException("Tilgangsmaskinen svarte ikke")
            application { settOpp() }
            assertEquals(HttpStatusCode.Forbidden, client.get(path).status)
            assertEquals(0, spleis.antallKall)
        }

    @Test
    fun `nettverksfeil under backfill gir 503`() =
        testApplication {
            spleis.nettverksfeil()
            application { settOpp() }
            assertEquals(HttpStatusCode.ServiceUnavailable, client.get(path).status)
        }

    @Test
    fun `andre feil under backfill gir 500 uten interne detaljer`() =
        testApplication {
            spleis.rettighetskategori(fødselsnummer, aktive = listOf("FRILANSER"), rettighetskategoriId = id)
            application { settOpp() }
            val response = client.get(path)
            assertEquals(HttpStatusCode.InternalServerError, response.status)
            val json = jacksonObjectMapper().readTree(response.bodyAsText())
            assertEquals("Intern serverfeil", json["title"].asString())
            assertTrue(json["detail"].isNull)
        }

    @ParameterizedTest
    @CsvSource(
        "ARBEIDSTAKER,AUTOMATISK,ARBEIDSTAKER,AUTOMATISK",
        "SELVSTENDIG_NÆRINGSDRIVENDE,SAKSBEHANDLER,SELVSTENDIG_NAERINGSDRIVENDE,SAKSBEHANDLER",
        "ARBEIDSTAKER_OG_SELVSTENDIG_NÆRINGSDRIVENDE,SPLEIS,ARBEIDSTAKER_OG_SELVSTENDIG_NAERINGSDRIVENDE,SPLEIS",
    )
    fun `API-et bruker egne ASCII-enumerasjoner`(
        hovedkategori: Hovedkategori,
        kilde: Kilde,
        forventetHovedkategori: String,
        forventetKilde: String,
    ) = testApplication {
        repository.lagre(
            Rettighetskategori(id, fødselsnummer, LocalDate.parse("2026-01-01"), hovedkategori, kilde, Instant.now(), emptyList()),
        )
        application { settOpp() }
        val response = client.get(path)
        assertEquals(HttpStatusCode.OK, response.status)
        val json = jacksonObjectMapper().readTree(response.bodyAsText())
        assertEquals(forventetHovedkategori, json["hovedkategori"].asString())
        assertEquals(forventetKilde, json["kilde"].asString())
    }

    @Test
    fun `kjent avgjørelse gir alle kontraktsfeltene uten personopplysninger eller kall til Spleis`() =
        testApplication {
            repository.lagre(
                Rettighetskategori(
                    id,
                    fødselsnummer,
                    LocalDate.parse("2026-01-01"),
                    Hovedkategori.SELVSTENDIG_NÆRINGSDRIVENDE,
                    Kilde.AUTOMATISK,
                    Instant.parse("2026-02-01T10:00:00Z"),
                    emptyList(),
                ),
            )
            application { settOpp() }

            val response = client.get(path)

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(
                jacksonObjectMapper().readTree(
                    """{
                      "id":"099b102a-e684-4791-9c60-82bd9d4788d5",
                      "skjæringstidspunkt":"2026-01-01",
                      "hovedkategori":"SELVSTENDIG_NAERINGSDRIVENDE",
                      "kilde":"AUTOMATISK",
                      "vurderingstidspunkt":"2026-02-01T10:00:00Z"
                    }""",
                ),
                jacksonObjectMapper().readTree(response.bodyAsText()),
            )
            assertEquals(0, spleis.antallKall)
        }
}
