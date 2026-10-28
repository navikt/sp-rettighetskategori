package no.nav.helse.sykepenger.rettighetskategori.opprydding_dev

import com.github.navikt.tbd_libs.kafka.AivenConfig
import com.github.navikt.tbd_libs.kafka.ConsumerProducerFactory
import no.nav.helse.rapids_rivers.RapidApplication

fun main() {
    val env = System.getenv()

    val kafkaConfig = AivenConfig.default
    val consumerProducerFactory = ConsumerProducerFactory(kafkaConfig)
    val dataSource = DataSourceBuilder(env).getDataSource()

    RapidApplication
        .create(env, consumerProducerFactory = consumerProducerFactory)
        .apply {
            SlettPersonRiver(this, dataSource)
        }.start()
}
