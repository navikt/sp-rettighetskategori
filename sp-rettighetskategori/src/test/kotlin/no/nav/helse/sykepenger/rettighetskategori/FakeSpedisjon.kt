package no.nav.helse.sykepenger.rettighetskategori

import com.github.navikt.tbd_libs.azure.AzureToken
import com.github.navikt.tbd_libs.azure.AzureTokenProvider
import com.github.navikt.tbd_libs.mock.MockHttpResponse
import com.github.navikt.tbd_libs.mock.bodyAsString
import com.github.navikt.tbd_libs.result_object.Result
import com.github.navikt.tbd_libs.result_object.ok
import com.github.navikt.tbd_libs.spedisjon.SpedisjonClient
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.net.Authenticator
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.LocalDateTime
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters

internal class FakeSpedisjon {
    private val meldinger = mutableMapOf<UUID, Map<String, Any>>()

    val client =
        SpedisjonClient(
            httpClient = FakeHttpClient(),
            objectMapper = objectMapper,
            tokenProvider = FakeTokenProvider,
            baseUrl = "http://spedisjon",
            scope = "api://test.tbd.spedisjon/.default",
        )

    private var feiler = false

    fun feiler() {
        feiler = true
    }

    fun sendtSøknadArbeidsgiver(fødselsnummer: String): UUID =
        sendtSøknad(
            fødselsnummer = fødselsnummer,
            type = "sendt_søknad_arbeidsgiver",
            arbeidssituasjon = "ARBEIDSTAKER",
            felter = mapOf("arbeidsgiver" to mapOf("orgnummer" to "987654321")),
        )

    fun sendtSøknadSelvstendig(fødselsnummer: String): UUID =
        sendtSøknad(
            fødselsnummer = fødselsnummer,
            type = "sendt_søknad_selvstendig",
            arbeidssituasjon = "SELVSTENDIG_NARINGSDRIVENDE",
        )

    fun sendtSøknad(
        fødselsnummer: String,
        type: String,
        arbeidssituasjon: String,
        felter: Map<String, Any> = emptyMap(),
    ): UUID =
        lagre(
            fødselsnummer = fødselsnummer,
            type = type,
            søknad =
                mapOf(
                    "id" to UUID.randomUUID().toString(),
                    "fnr" to fødselsnummer,
                    "status" to "SENDT",
                    "arbeidssituasjon" to arbeidssituasjon,
                ) + felter,
        )

    private fun lagre(
        fødselsnummer: String,
        type: String,
        søknad: Map<String, Any>,
    ): UUID {
        val internDokumentId = UUID.randomUUID()
        meldinger[internDokumentId] =
            mapOf(
                "type" to type,
                "fnr" to fødselsnummer,
                "internDokumentId" to internDokumentId.toString(),
                "eksternDokumentId" to søknad.getValue("id"),
                "duplikatkontroll" to UUID.randomUUID().toString(),
                "jsonBody" to objectMapper.writeValueAsString(søknad + ("sendtNav" to LocalDateTime.now().toString())),
            )
        return internDokumentId
    }

    private fun hentMeldinger(request: HttpRequest): HttpResponse<String> {
        check(request.method() == "GET" && request.uri().path == "/api/meldinger") { "Uventet kall til Spedisjon: ${request.method()} ${request.uri()}" }
        if (feiler) return MockHttpResponse("""{"detail":"Spedisjon er nede"}""", 503)
        val internDokumentIder =
            objectMapper
                .readTree(request.bodyAsString())
                .path("internDokumentIder")
                .toList()
                .map { UUID.fromString(it.asString()) }
        val body = mapOf("meldinger" to internDokumentIder.mapNotNull { meldinger[it] })
        return MockHttpResponse(objectMapper.writeValueAsString(body), 200)
    }

    private inner class FakeHttpClient : HttpClient() {
        @Suppress("UNCHECKED_CAST")
        override fun <T> send(
            request: HttpRequest,
            responseBodyHandler: HttpResponse.BodyHandler<T>,
        ): HttpResponse<T> = hentMeldinger(request) as HttpResponse<T>

        override fun <T> sendAsync(
            request: HttpRequest,
            responseBodyHandler: HttpResponse.BodyHandler<T>,
        ): CompletableFuture<HttpResponse<T>> = CompletableFuture.completedFuture(send(request, responseBodyHandler))

        override fun <T> sendAsync(
            request: HttpRequest,
            responseBodyHandler: HttpResponse.BodyHandler<T>,
            pushPromiseHandler: HttpResponse.PushPromiseHandler<T>?,
        ): CompletableFuture<HttpResponse<T>> = sendAsync(request, responseBodyHandler)

        override fun cookieHandler(): Optional<CookieHandler> = Optional.empty()

        override fun connectTimeout(): Optional<Duration> = Optional.empty()

        override fun followRedirects(): Redirect = Redirect.NEVER

        override fun proxy(): Optional<ProxySelector> = Optional.empty()

        override fun sslContext(): SSLContext = SSLContext.getDefault()

        override fun sslParameters(): SSLParameters = SSLParameters()

        override fun authenticator(): Optional<Authenticator> = Optional.empty()

        override fun version(): Version = Version.HTTP_1_1

        override fun executor(): Optional<Executor> = Optional.empty()
    }

    private object FakeTokenProvider : AzureTokenProvider {
        private val token = AzureToken("token", LocalDateTime.now().plusHours(1))

        override fun bearerToken(scope: String): Result<AzureToken> = token.ok()

        override fun onBehalfOfToken(
            scope: String,
            token: String,
        ): Result<AzureToken> = error("Ikke i bruk")
    }

    private companion object {
        val objectMapper = jacksonObjectMapper()
    }
}
