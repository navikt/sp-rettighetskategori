package no.nav.helse.sykepenger.rettighetskategori

import com.github.navikt.tbd_libs.azure.AzureTokenProvider
import com.github.navikt.tbd_libs.result_object.getOrThrow
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

internal class SpleisClient(
    private val httpClient: HttpClient,
    private val tokenProvider: AzureTokenProvider,
    private val baseUrl: String,
    private val scope: String,
) {
    fun rettighetskategorier(fødselsnummer: String): List<SpleisRettighetskategori> {
        val request =
            HttpRequest
                .newBuilder(URI("$baseUrl/api/rettighetskategorier"))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer ${tokenProvider.bearerToken(scope).getOrThrow().token}")
                .header("callId", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(mapOf("fødselsnummer" to fødselsnummer))))
                .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) { "Uventet svar fra spleis-api: HTTP ${response.statusCode()}" }
        return objectMapper.readValue<RettighetskategorierResponse>(response.body()).rettighetskategorier.map { it.tilDomene() }
    }

    companion object {
        private val objectMapper = jacksonObjectMapper()

        fun fraMiljø(
            env: Map<String, String>,
            httpClient: HttpClient,
            tokenProvider: AzureTokenProvider,
        ): SpleisClient {
            val cluster = if (env["NAIS_CLUSTER_NAME"]?.startsWith("prod") == true) "prod-gcp" else "dev-gcp"
            return SpleisClient(
                httpClient = httpClient,
                tokenProvider = tokenProvider,
                baseUrl = "http://spleis-api",
                scope = "api://$cluster.tbd.spleis-api/.default",
            )
        }
    }

    private data class RettighetskategorierResponse(
        val rettighetskategorier: List<RettighetskategoriDto>,
    )

    private data class RettighetskategoriDto(
        val rettighetskategoriId: UUID,
        val skjæringstidspunkt: LocalDate,
        val opprettet: LocalDateTime,
        val kilde: SpleisRettighetskategori.Kilde,
        val harSelvstendigInntekt: Boolean,
        val yrkesaktivitetstyper: YrkesaktivitetstyperDto,
    ) {
        fun tilDomene() =
            SpleisRettighetskategori(
                id = rettighetskategoriId,
                skjæringstidspunkt = skjæringstidspunkt,
                opprettet = opprettet.atZone(ZoneId.of("Europe/Oslo")).toInstant(),
                kilde = kilde,
                harSelvstendigInntekt = harSelvstendigInntekt,
                aktive = yrkesaktivitetstyper.aktive,
                forkastede = yrkesaktivitetstyper.forkastede,
            )
    }

    private data class YrkesaktivitetstyperDto(
        val aktive: List<String>,
        val forkastede: List<String>,
    )
}
