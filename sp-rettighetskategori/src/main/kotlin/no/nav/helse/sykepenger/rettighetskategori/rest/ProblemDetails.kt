package no.nav.helse.sykepenger.rettighetskategori.rest

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.path
import io.ktor.server.response.respondText
import tools.jackson.module.kotlin.jacksonObjectMapper

internal data class ProblemDetails(
    val type: String = "about:blank",
    val title: String,
    val status: Int,
    val detail: String? = null,
    val instance: String,
)

internal suspend fun ApplicationCall.respondProblem(
    status: HttpStatusCode,
    title: String,
) {
    respondText(
        problemMapper.writeValueAsString(ProblemDetails(title = title, status = status.value, instance = request.path())),
        ContentType.Application.ProblemJson,
        status,
    )
}

private val problemMapper = jacksonObjectMapper()
