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
import java.util.UUID

internal class RettighetskategoriResultatRiver(
    rapidsConnection: RapidsConnection,
    private val repository: RettighetskategoriRepository,
    private val innfylling: Innfylling,
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
                    it.requireKey("fødselsnummer", ID_FELT)
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
        val rettighetskategoriId = packet[ID_FELT].asString()

        medMdc(MdcKey.IDENTITETSNUMMER to fødselsnummer) {
            loggInfo("Mottatt behov for $BEHOV", "rettighetskategoriId" to rettighetskategoriId)
            try {
                val id = UUID.fromString(rettighetskategoriId)
                val hovedkategori =
                    repository.finn(id)?.hovedkategori
                        ?: innfylling.fyllInn(fødselsnummer).find { it.id == id }?.hovedkategori
                        ?: error("Fant ikke rettighetskategori med id $rettighetskategoriId, heller ikke i Spleis")
                packet["@løsning"] = mapOf(BEHOV to mapOf("hovedkategori" to hovedkategori.name))
                loggInfo(
                    "Publiserer løsning på $BEHOV",
                    "rettighetskategoriId" to rettighetskategoriId,
                    "hovedkategori" to hovedkategori.name,
                )
                context.publish(packet.toJson())
            } catch (ex: Exception) {
                loggError("Feil under håndtering av behov $BEHOV", ex, "rettighetskategoriId" to rettighetskategoriId)
            }
        }
    }

    private companion object {
        const val BEHOV = "RettighetskategoriResultat"
        const val ID_FELT = "$BEHOV.rettighetskategoriId"
    }
}
