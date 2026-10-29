package no.nav.helse.sykepenger.rettighetskategori

import com.github.navikt.tbd_libs.personpseudoid.Identitetsnummer
import com.github.navikt.tbd_libs.personpseudoid.PersonPseudoId
import com.github.navikt.tbd_libs.personpseudoid.PersonPseudoIdProvider
import com.github.navikt.tbd_libs.populasjonstilgang.api.PopulasjonstilgangskontrollProvider
import com.github.navikt.tbd_libs.populasjonstilgang.api.TilgangskontrollResultat
import java.util.UUID

internal class FakePersonProvider : PersonPseudoIdProvider {
    private val personer = mutableMapOf<PersonPseudoId, Identitetsnummer>()

    override fun nyPersonPseudoId(identitetsnummer: Identitetsnummer): PersonPseudoId = PersonPseudoId(UUID.randomUUID()).also { personer[it] = identitetsnummer }

    override fun finnIdentitetsnummer(personPseudoId: PersonPseudoId): Identitetsnummer? = personer[personPseudoId]
}

internal class FakeTilgangskontroll(
    var resultat: TilgangskontrollResultat = TilgangskontrollResultat.Ok,
) : PopulasjonstilgangskontrollProvider {
    override fun kontrollerKomplettTilgang(
        accessToken: String,
        fødselsnummer: String,
    ): TilgangskontrollResultat = error("Skal bruke kjernetilgang")

    override fun kontrollerKjerneTilgang(
        accessToken: String,
        fødselsnummer: String,
    ): TilgangskontrollResultat = resultat

    override fun kontrollerKjerneTilgangForAnsatt(
        ansattId: String,
        fødselsnummer: String,
    ): TilgangskontrollResultat = error("Skal bruke saksbehandlers token")
}
