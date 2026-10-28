package no.nav.helse.sykepenger.rettighetskategori

import com.github.navikt.tbd_libs.rapids_and_rivers.JsonMessage
import com.github.navikt.tbd_libs.rapids_and_rivers.River
import com.github.navikt.tbd_libs.rapids_and_rivers.asLocalDate
import com.github.navikt.tbd_libs.rapids_and_rivers_api.MessageContext
import com.github.navikt.tbd_libs.rapids_and_rivers_api.MessageMetadata
import com.github.navikt.tbd_libs.rapids_and_rivers_api.RapidsConnection
import io.micrometer.core.instrument.MeterRegistry
import no.nav.sykepenger.libs.logging.MdcKey
import no.nav.sykepenger.libs.logging.loggError
import no.nav.sykepenger.libs.logging.loggInfo
import no.nav.sykepenger.libs.logging.medMdc
import tools.jackson.databind.JsonNode
import java.util.UUID

internal class RettighetskategoriRiver(
    rapidsConnection: RapidsConnection,
    private val søknader: Søknader,
    private val repository: RettighetskategoriRepository,
) : River.PacketListener {
    init {
        River(rapidsConnection)
            .apply {
                precondition {
                    it.requireValue("@event_name", "behov")
                    it.requireAllOrAny("@behov", listOf(BEHOV))
                    it.forbid("@løsning")
                }
                validate {
                    it.requireKey("fødselsnummer")
                    it.require("$BEHOV.skjæringstidspunkt", JsonNode::asLocalDate)
                    it.requireArray("$BEHOV.søknader")
                }
            }.register(this)
    }

    override fun onPacket(
        packet: JsonMessage,
        context: MessageContext,
        metadata: MessageMetadata,
        meterRegistry: MeterRegistry,
    ) {
        val fødselsnummer = packet["fødselsnummer"].asString()
        val skjæringstidspunkt = packet["$BEHOV.skjæringstidspunkt"].asLocalDate()
        val søknadIder = packet["$BEHOV.søknader"].toList().map { UUID.fromString(it.asString()) }

        medMdc(MdcKey.IDENTITETSNUMMER to fødselsnummer) {
            loggInfo("Mottatt behov for $BEHOV", "skjæringstidspunkt" to skjæringstidspunkt.toString())
            try {
                val yrkesaktiviteter = søknader.yrkesaktiviteter(søknadIder)
                val hovedkategori =
                    checkNotNull(Hovedkategori.avgjør(yrkesaktiviteter)) {
                        "Fant ingen hovedkategori for yrkesaktivitetene $yrkesaktiviteter"
                    }
                val rettighetskategori =
                    Rettighetskategori.automatisk(
                        fødselsnummer = fødselsnummer,
                        skjæringstidspunkt = skjæringstidspunkt,
                        hovedkategori = hovedkategori,
                        søknader = søknadIder,
                    )
                repository.lagre(rettighetskategori)

                packet["@løsning"] =
                    mapOf(
                        BEHOV to
                            mapOf(
                                "id" to rettighetskategori.id.toString(),
                                "hovedkategori" to rettighetskategori.hovedkategori.name,
                            ),
                    )
                loggInfo(
                    "Publiserer løsning på $BEHOV",
                    "rettighetskategoriId" to rettighetskategori.id.toString(),
                    "skjæringstidspunkt" to skjæringstidspunkt.toString(),
                    "hovedkategori" to rettighetskategori.hovedkategori.name,
                )
                context.publish(packet.toJson())
            } catch (ex: Exception) {
                loggError(
                    "Feil under håndtering av behov $BEHOV",
                    ex,
                    "skjæringstidspunkt" to skjæringstidspunkt.toString(),
                    "søknader" to søknadIder.toString(),
                )
            }
        }
    }

    private companion object {
        const val BEHOV = "Rettighetskategori"
    }
}
