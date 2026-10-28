package no.nav.helse.sykepenger.rettighetskategori

import com.github.navikt.tbd_libs.mock.MockHttpResponse
import com.github.navikt.tbd_libs.mock.bodyAsString
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

internal class FakeSpleis {
    private val rettighetskategorier = mutableMapOf<String, MutableList<Map<String, Any>>>()
    private var feiler = false

    var antallKall = 0
        private set

    val client =
        SpleisClient(
            httpClient = FakeHttpClient(::hentRettighetskategorier),
            tokenProvider = FakeTokenProvider,
            baseUrl = "http://spleis-api",
            scope = "api://test.tbd.spleis-api/.default",
        )

    fun feiler() {
        feiler = true
    }

    fun rettighetskategori(
        fødselsnummer: String,
        aktive: List<String> = emptyList(),
        forkastede: List<String> = emptyList(),
        kilde: String = "SPLEIS",
        harSelvstendigInntekt: Boolean = false,
        skjæringstidspunkt: LocalDate = LocalDate.parse("2018-01-01"),
        opprettet: LocalDateTime = LocalDateTime.parse("2018-02-01T12:00:00"),
        rettighetskategoriId: UUID = UUID.randomUUID(),
    ): UUID {
        rettighetskategorier.getOrPut(fødselsnummer) { mutableListOf() }.add(
            mapOf(
                "rettighetskategoriId" to rettighetskategoriId.toString(),
                "skjæringstidspunkt" to skjæringstidspunkt.toString(),
                "opprettet" to opprettet.toString(),
                "kilde" to kilde,
                "harSelvstendigInntekt" to harSelvstendigInntekt,
                "yrkesaktivitetstyper" to mapOf("aktive" to aktive, "forkastede" to forkastede),
            ),
        )
        return rettighetskategoriId
    }

    private fun hentRettighetskategorier(request: HttpRequest): HttpResponse<String> {
        check(request.method() == "POST" && request.uri().path == "/api/rettighetskategorier") {
            "Uventet kall til spleis-api: ${request.method()} ${request.uri()}"
        }
        check(request.headers().firstValue("Authorization").orElse(null) == "Bearer token") { "Mangler token i kall til spleis-api" }
        antallKall++
        if (feiler) return MockHttpResponse("""{"detail":"spleis-api er nede"}""", 503)
        val fødselsnummer = objectMapper.readTree(request.bodyAsString()).path("fødselsnummer").asString()
        val body = mapOf("rettighetskategorier" to rettighetskategorier[fødselsnummer].orEmpty())
        return MockHttpResponse(objectMapper.writeValueAsString(body), 200)
    }

    private companion object {
        val objectMapper = jacksonObjectMapper()
    }
}
