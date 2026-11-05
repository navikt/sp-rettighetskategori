package no.nav.helse.sykepenger.rettighetskategori

import com.github.navikt.tbd_libs.rapids_and_rivers.JsonMessage
import kotliquery.sessionOf
import no.nav.sykepenger.libs.outbox.Outbox
import javax.sql.DataSource

internal class LagreManuellVurdering(
    private val dataSource: DataSource,
    private val repository: RettighetskategoriRepository,
) {
    fun lagre(
        rettighetskategori: Rettighetskategori,
        manuellVurdering: ManuellVurdering,
    ): Boolean =
        sessionOf(dataSource).use { session ->
            session.transaction { tx ->
                if (!repository.lagreManuellVurdering(tx, rettighetskategori, manuellVurdering)) return@transaction false
                Outbox.leggTil(
                    connection = tx.connection.underlying,
                    fødselsnummer = rettighetskategori.fødselsnummer,
                    melding =
                        JsonMessage
                            .newMessage(
                                "rettighetskategori_endret",
                                mapOf(
                                    "fødselsnummer" to rettighetskategori.fødselsnummer,
                                    "skjæringstidspunkt" to rettighetskategori.skjæringstidspunkt.toString(),
                                    "rettighetskategoriId" to rettighetskategori.id.toString(),
                                    "hovedkategori" to rettighetskategori.konklusjon.hovedkategori.name,
                                ),
                            ).toJson(),
                )
                true
            }
        }
}
