package no.nav.helse.sykepenger.rettighetskategori

import com.github.navikt.tbd_libs.rapids_and_rivers.JsonMessage
import com.github.navikt.tbd_libs.rapids_and_rivers.River
import com.github.navikt.tbd_libs.rapids_and_rivers_api.MessageContext
import com.github.navikt.tbd_libs.rapids_and_rivers_api.MessageMetadata
import com.github.navikt.tbd_libs.rapids_and_rivers_api.RapidsConnection
import io.micrometer.core.instrument.MeterRegistry
import no.nav.sykepenger.libs.logging.MdcKey
import no.nav.sykepenger.libs.logging.loggError
import no.nav.sykepenger.libs.logging.loggInfo
import no.nav.sykepenger.libs.logging.medMdc

internal class PersonAvstemmingRiver(
    rapidsConnection: RapidsConnection,
    private val backfill: Backfill,
) : River.PacketListener {
    init {
        River(rapidsConnection)
            .apply {
                precondition { it.requireValue("@event_name", EVENT_NAME) }
                validate { it.requireKey("@id", "fødselsnummer") }
            }.register(this)
    }

    override fun onPacket(
        packet: JsonMessage,
        context: MessageContext,
        metadata: MessageMetadata,
        meterRegistry: MeterRegistry,
    ) {
        medMdc(
            MdcKey.IDENTITETSNUMMER to packet["fødselsnummer"].asString(),
            MdcKey.MELDING_ID to packet["@id"].asString(),
        ) {
            loggInfo("Mottatt $EVENT_NAME")
            try {
                backfill.backfill(packet["fødselsnummer"].asString())
            } catch (ex: Exception) {
                loggError("Feil under backfill av rettighetskategorier ved $EVENT_NAME", ex)
            }
        }
    }

    private companion object {
        const val EVENT_NAME = "person_avstemming"
    }
}
