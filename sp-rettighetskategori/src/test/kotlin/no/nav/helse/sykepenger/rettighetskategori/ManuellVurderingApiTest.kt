package no.nav.helse.sykepenger.rettighetskategori

import com.github.navikt.tbd_libs.personpseudoid.Identitetsnummer
import com.github.navikt.tbd_libs.personpseudoid.PersonPseudoId
import com.github.navikt.tbd_libs.populasjonstilgang.api.TilgangSomMangler
import com.github.navikt.tbd_libs.populasjonstilgang.api.TilgangskontrollResultat
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotliquery.queryOf
import kotliquery.sessionOf
import no.nav.helse.sykepenger.rettighetskategori.rest.configureRestApi
import no.nav.sykepenger.libs.ktor.auth.AZURE_AD_APP_AUTHENTICATION_NAME
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
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

internal class ManuellVurderingApiTest {
    private val repository = RettighetskategoriRepository(TestDatabase.dataSource)
    private val spleis = FakeSpleis()
    private val personProvider = FakePersonProvider()
    private val tilgangskontroll = FakeTilgangskontroll()
    private val fødselsnummer = "12345678901"
    private val pseudoId: PersonPseudoId = personProvider.nyPersonPseudoId(Identitetsnummer(fødselsnummer))
    private val skjæringstidspunkt = LocalDate.parse("2026-01-01")
    private val id = UUID.fromString("099b102a-e684-4791-9c60-82bd9d4788d5")
    private var tilganger = setOf(Tilgang.Les, Tilgang.Skriv)
    private var aktivert = true

    @BeforeEach
    fun tømDatabase() {
        TestDatabase.tøm()
    }

    @Test
    fun `manuell vurdering gir ny avgjørelse med kilde saksbehandler`() =
        testApplication {
            lagreAvgjørelse(Hovedkategori.SELVSTENDIG_NÆRINGSDRIVENDE)
            application { settOpp() }

            val response = manuellVurdering("""{"tilleggskategorier":["JORDBRUKER"],"begrunnelse":"Driver gård"}""")

            assertEquals(HttpStatusCode.OK, response.status)
            val json = jacksonObjectMapper().readTree(response.bodyAsText())
            val nyId = UUID.fromString(json["id"].asString())
            assertNotEquals(id, nyId)
            assertEquals("2026-01-01", json["skjæringstidspunkt"].asString())
            assertEquals("SELVSTENDIG_NAERINGSDRIVENDE", json["hovedkategori"].asString())
            assertEquals(jacksonObjectMapper().readTree("""["JORDBRUKER"]"""), json["tilleggskategorier"])
            assertEquals("SAKSBEHANDLER", json["kilde"].asString())
            assertTrue(Instant.parse(json["vurderingstidspunkt"].asString()).isAfter(Instant.parse("2026-02-01T10:00:00Z")))
            assertEquals(nyId, repository.gjeldende(fødselsnummer, skjæringstidspunkt)?.id)
            assertEquals(
                ManuellVurderingsrad("Z999999", "Driver gård", id),
                manuellVurdering(nyId),
            )
        }

    @Test
    fun `tom liste fjerner jordbruker, og begrunnelsen kan mangle`() =
        testApplication {
            lagreAvgjørelse(Hovedkategori.ARBEIDSTAKER_OG_SELVSTENDIG_NÆRINGSDRIVENDE, setOf(Tilleggskategori.JORDBRUKER))
            application { settOpp() }

            val response = manuellVurdering("""{"tilleggskategorier":[]}""")

            assertEquals(HttpStatusCode.OK, response.status)
            val json = jacksonObjectMapper().readTree(response.bodyAsText())
            assertEquals("ARBEIDSTAKER_OG_SELVSTENDIG_NAERINGSDRIVENDE", json["hovedkategori"].asString())
            assertEquals(0, json["tilleggskategorier"].size())
            assertEquals(ManuellVurderingsrad("Z999999", null, id), manuellVurdering(UUID.fromString(json["id"].asString())))
        }

    @Test
    fun `en manuell avgjørelse kan vurderes manuelt på nytt`() =
        testApplication {
            lagreAvgjørelse(Hovedkategori.SELVSTENDIG_NÆRINGSDRIVENDE)
            application { settOpp() }
            val første = UUID.fromString(jacksonObjectMapper().readTree(manuellVurdering(JORDBRUKER).bodyAsText())["id"].asString())

            val response = manuellVurdering("""{"tilleggskategorier":[],"begrunnelse":null}""", rettighetskategoriId = første.toString())

            assertEquals(HttpStatusCode.OK, response.status)
            val andre = UUID.fromString(jacksonObjectMapper().readTree(response.bodyAsText())["id"].asString())
            assertEquals(andre, repository.gjeldende(fødselsnummer, skjæringstidspunkt)?.id)
            assertEquals(første, manuellVurdering(andre)?.erstatter)
        }

    @Test
    fun `samme tilleggskategorier gir gjeldende avgjørelse uten ny avgjørelse`() =
        testApplication {
            lagreAvgjørelse(Hovedkategori.SELVSTENDIG_NÆRINGSDRIVENDE, setOf(Tilleggskategori.JORDBRUKER))
            application { settOpp() }

            val response = manuellVurdering("""{"tilleggskategorier":["JORDBRUKER","JORDBRUKER"]}""")

            assertEquals(HttpStatusCode.OK, response.status)
            val json = jacksonObjectMapper().readTree(response.bodyAsText())
            assertEquals(id.toString(), json["id"].asString())
            assertEquals("AUTOMATISK", json["kilde"].asString())
            assertEquals(1, antallAvgjørelser())
        }

    @Test
    fun `hovedkategorien Arbeidstaker gir 400`() =
        testApplication {
            lagreAvgjørelse(Hovedkategori.ARBEIDSTAKER)
            application { settOpp() }

            val response = manuellVurdering("""{"tilleggskategorier":[]}""")

            assertProblem(HttpStatusCode.BadRequest, response)
            assertEquals(1, antallAvgjørelser())
        }

    @ParameterizedTest
    @ValueSource(
        strings = [
            """{"tilleggskategorier":["FISKER"]}""",
            """{"tilleggskategorier":["jordbruker"]}""",
            """{"tilleggskategorier":[0]}""",
            """{"tilleggskategorier":[null]}""",
            """{"tilleggskategorier":null}""",
            """{"tilleggskategorier":"JORDBRUKER"}""",
            """{"begrunnelse":"Mangler tilleggskategorier"}""",
            """{"tilleggskategorier":[],"begrunnelse":1}""",
            """["JORDBRUKER"]""",
            """ugyldig""",
            """""",
        ],
    )
    fun `ugyldig body gir 400`(body: String) =
        testApplication {
            lagreAvgjørelse(Hovedkategori.SELVSTENDIG_NÆRINGSDRIVENDE)
            application { settOpp() }

            assertProblem(HttpStatusCode.BadRequest, manuellVurdering(body))
            assertEquals(1, antallAvgjørelser())
        }

    @Test
    fun `ugyldig UUID gir 400`() =
        testApplication {
            application { settOpp() }
            assertProblem(HttpStatusCode.BadRequest, manuellVurdering(JORDBRUKER, rettighetskategoriId = "ugyldig"))
            assertEquals(0, spleis.antallKall)
        }

    @Test
    fun `avgjørelse som ikke lenger er gjeldende gir 409`() =
        testApplication {
            lagreAvgjørelse(Hovedkategori.SELVSTENDIG_NÆRINGSDRIVENDE)
            lagreAvgjørelse(Hovedkategori.SELVSTENDIG_NÆRINGSDRIVENDE, avgjørelseId = UUID.randomUUID(), vurderingstidspunkt = Instant.parse("2026-02-02T10:00:00Z"))
            application { settOpp() }

            assertProblem(HttpStatusCode.Conflict, manuellVurdering(JORDBRUKER))
            assertEquals(2, antallAvgjørelser())
        }

    @Test
    fun `ukjent avgjørelse gir 404 etter backfill`() =
        testApplication {
            application { settOpp() }
            assertProblem(HttpStatusCode.NotFound, manuellVurdering(JORDBRUKER))
            assertEquals(1, spleis.antallKall)
        }

    @Test
    fun `avgjørelse for annen person gir 404`() =
        testApplication {
            repository.lagre(
                Rettighetskategori(
                    id,
                    "98765432109",
                    skjæringstidspunkt,
                    Konklusjon(Hovedkategori.SELVSTENDIG_NÆRINGSDRIVENDE, emptySet()),
                    Kilde.AUTOMATISK,
                    Instant.now(),
                ),
            )
            application { settOpp() }
            assertProblem(HttpStatusCode.NotFound, manuellVurdering(JORDBRUKER))
            assertEquals(1, antallAvgjørelser())
        }

    @Test
    fun `lesetilgang alene gir 403`() =
        testApplication {
            tilganger = setOf(Tilgang.Les)
            lagreAvgjørelse(Hovedkategori.SELVSTENDIG_NÆRINGSDRIVENDE)
            application { settOpp() }
            assertProblem(HttpStatusCode.Forbidden, manuellVurdering(JORDBRUKER))
            assertEquals(1, antallAvgjørelser())
        }

    @Test
    fun `avslag fra tilgangsmaskinen gir 403`() =
        testApplication {
            tilgangskontroll.resultat = TilgangskontrollResultat.ManglerTilgang(TilgangSomMangler.Habilitet)
            lagreAvgjørelse(Hovedkategori.SELVSTENDIG_NÆRINGSDRIVENDE)
            application { settOpp() }
            assertProblem(HttpStatusCode.Forbidden, manuellVurdering(JORDBRUKER))
            assertEquals(1, antallAvgjørelser())
        }

    @Test
    fun `ukjent pseudo-ID gir 404`() =
        testApplication {
            lagreAvgjørelse(Hovedkategori.SELVSTENDIG_NÆRINGSDRIVENDE)
            application { settOpp() }
            assertProblem(HttpStatusCode.NotFound, manuellVurdering(JORDBRUKER, personPseudoId = UUID.randomUUID().toString()))
        }

    @Test
    fun `endepunktet finnes ikke når togglen er av`() =
        testApplication {
            aktivert = false
            lagreAvgjørelse(Hovedkategori.SELVSTENDIG_NÆRINGSDRIVENDE)
            application { settOpp() }
            assertProblem(HttpStatusCode.NotFound, manuellVurdering(JORDBRUKER))
            assertEquals(1, antallAvgjørelser())
        }

    private suspend fun assertProblem(
        status: HttpStatusCode,
        response: HttpResponse,
    ) {
        assertEquals(status, response.status)
        assertEquals("application/problem+json", response.headers["Content-Type"]?.substringBefore(";"))
        assertEquals(status.value, jacksonObjectMapper().readTree(response.bodyAsText())["status"].asInt())
    }

    private fun antallAvgjørelser() =
        sessionOf(TestDatabase.dataSource).use { session ->
            session.run(queryOf("SELECT count(*) FROM rettighetskategori").map { it.int(1) }.asSingle)
        }

    private fun Application.settOpp() {
        install(Authentication) {
            provider(AZURE_AD_AUTHENTICATION_NAME) {
                authenticate { context ->
                    context.principal(
                        SaksbehandlerPrincipal(
                            Saksbehandler(NavIdent("Z999999"), SaksbehandlerOid("oid"), "Saksbehandler"),
                            tilganger,
                            AccessToken("token"),
                        ),
                    )
                }
            }
            provider(AZURE_AD_APP_AUTHENTICATION_NAME) {
                authenticate { error("Endepunktet for tjenester testes i RettighetskategoriTjenesteApiTest") }
            }
        }
        configureRestApi(repository, Backfill(spleis.client, repository), personProvider, tilgangskontroll, aktivert)
    }

    private fun lagreAvgjørelse(
        hovedkategori: Hovedkategori,
        tilleggskategorier: Set<Tilleggskategori> = emptySet(),
        avgjørelseId: UUID = id,
        vurderingstidspunkt: Instant = Instant.parse("2026-02-01T10:00:00Z"),
    ) = repository.lagre(
        Rettighetskategori(
            avgjørelseId,
            fødselsnummer,
            skjæringstidspunkt,
            Konklusjon(hovedkategori, tilleggskategorier),
            Kilde.AUTOMATISK,
            vurderingstidspunkt,
        ),
    )

    private suspend fun ApplicationTestBuilder.manuellVurdering(
        body: String,
        rettighetskategoriId: String = id.toString(),
        personPseudoId: String = pseudoId.toString(),
    ): HttpResponse =
        client.post("/api/personer/$personPseudoId/rettighetskategorier/$rettighetskategoriId/manuell-vurdering") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private companion object {
        const val JORDBRUKER = """{"tilleggskategorier":["JORDBRUKER"]}"""
    }

    private data class ManuellVurderingsrad(
        val saksbehandlerIdent: String,
        val begrunnelse: String?,
        val erstatter: UUID,
    )

    private fun manuellVurdering(avgjørelseId: UUID): ManuellVurderingsrad? =
        sessionOf(TestDatabase.dataSource).use { session ->
            session.run(
                queryOf("SELECT saksbehandler_ident, begrunnelse, erstatter FROM rettighetskategori WHERE id = :id", mapOf("id" to avgjørelseId))
                    .map { ManuellVurderingsrad(it.string("saksbehandler_ident"), it.stringOrNull("begrunnelse"), it.uuid("erstatter")) }
                    .asSingle,
            )
        }
}
