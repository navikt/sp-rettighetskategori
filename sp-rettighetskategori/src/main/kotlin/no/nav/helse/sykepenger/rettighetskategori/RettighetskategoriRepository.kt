package no.nav.helse.sykepenger.rettighetskategori

import kotliquery.Row
import kotliquery.Session
import kotliquery.queryOf
import kotliquery.sessionOf
import org.intellij.lang.annotations.Language
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

internal class RettighetskategoriRepository(
    private val dataSource: DataSource,
) {
    fun lagre(rettighetskategori: Rettighetskategori) {
        sessionOf(dataSource).use { session -> lagre(session, rettighetskategori, emptyList()) }
    }

    fun lagreAutomatisk(
        rettighetskategori: Rettighetskategori,
        vurdering: Vurdering,
    ) {
        sessionOf(dataSource).use { session ->
            session.transaction { tx ->
                låsSkjæringstidspunkt(tx, rettighetskategori.fødselsnummer, rettighetskategori.skjæringstidspunkt)
                lagre(tx, rettighetskategori, vurdering.søknader)
                føyTilVurdering(tx, rettighetskategori.id, vurdering)
            }
        }
    }

    /** Lagrer ikke, og gir `false`, når avgjørelsen som erstattes ikke lenger er gjeldende for skjæringstidspunktet. */
    fun lagreManuellVurdering(
        session: Session,
        rettighetskategori: Rettighetskategori,
        manuellVurdering: ManuellVurdering,
    ): Boolean {
        check(!session.connection.underlying.autoCommit) { "Manuell vurdering må lagres i en transaksjon" }
        låsSkjæringstidspunkt(session, rettighetskategori.fødselsnummer, rettighetskategori.skjæringstidspunkt)
        val gjeldende = gjeldende(session, rettighetskategori.fødselsnummer, rettighetskategori.skjæringstidspunkt)
        if (gjeldende?.id != manuellVurdering.erstatter) return false
        check(lagre(session, rettighetskategori, emptyList(), manuellVurdering) == 1) {
            "Den manuelle avgjørelsen ble ikke lagret"
        }
        return true
    }

    // Automatiske og manuelle avgjørelser tar samme lås, så en manuell vurdering ser en automatisk avgjørelse som lagres samtidig.
    private fun låsSkjæringstidspunkt(
        session: Session,
        fødselsnummer: String,
        skjæringstidspunkt: LocalDate,
    ) {
        @Language("PostgreSQL")
        val sql = "SELECT pg_advisory_xact_lock(hashtextextended(:fodselsnummer || :skjaeringstidspunkt, 0))"
        session.run(
            queryOf(sql, mapOf("fodselsnummer" to fødselsnummer, "skjaeringstidspunkt" to skjæringstidspunkt.toString()))
                .map { }
                .asSingle,
        )
    }

    fun føyTilVurdering(
        rettighetskategoriId: UUID,
        vurdering: Vurdering,
    ) {
        sessionOf(dataSource).use { session -> føyTilVurdering(session, rettighetskategoriId, vurdering) }
    }

    private fun føyTilVurdering(
        session: Session,
        rettighetskategoriId: UUID,
        vurdering: Vurdering,
    ) {
        @Language("PostgreSQL")
        val sql = """
            INSERT INTO vurdering (rettighetskategori_id, søknader, vurderingstidspunkt)
            VALUES (:rettighetskategoriId, :soknader, :vurderingstidspunkt)
        """
        session.run(
            queryOf(
                sql,
                mapOf(
                    "rettighetskategoriId" to rettighetskategoriId,
                    "soknader" to session.createArrayOf("uuid", vurdering.søknader),
                    "vurderingstidspunkt" to vurdering.vurderingstidspunkt,
                ),
            ).asUpdate,
        )
    }

    fun søknaderISisteVurdering(
        fødselsnummer: String,
        skjæringstidspunkt: LocalDate,
    ): Set<UUID>? {
        @Language("PostgreSQL")
        val sql = """
            SELECT v.søknader
            FROM vurdering v
            JOIN rettighetskategori r ON r.id = v.rettighetskategori_id
            WHERE r.fødselsnummer = :fodselsnummer AND r.skjæringstidspunkt = :skjaeringstidspunkt
            ORDER BY v.løpenummer DESC
            LIMIT 1
        """
        return sessionOf(dataSource).use { session ->
            session.run(
                queryOf(sql, mapOf("fodselsnummer" to fødselsnummer, "skjaeringstidspunkt" to skjæringstidspunkt))
                    .map { it.array<UUID>("søknader").toSet() }
                    .asSingle,
            )
        }
    }

    fun lagreBackfill(
        fødselsnummer: String,
        rettighetskategorier: List<Rettighetskategori>,
        antallFraSpleis: Int,
    ): Int =
        sessionOf(dataSource).use { session ->
            session.transaction { tx ->
                sjekkAtIngenTilhørerAndrePersoner(tx, fødselsnummer, rettighetskategorier)
                val antallNye = rettighetskategorier.sumOf { lagre(tx, it, emptyList()) }
                if (antallNye > 0) loggBackfill(tx, fødselsnummer, antallFraSpleis, antallNye)
                antallNye
            }
        }

    private fun loggBackfill(
        session: Session,
        fødselsnummer: String,
        antallFraSpleis: Int,
        antallNye: Int,
    ) {
        @Language("PostgreSQL")
        val sql = """
            INSERT INTO migreringslogg (fødselsnummer, antall_fra_spleis, antall_nye)
            VALUES (:fodselsnummer, :antallFraSpleis, :antallNye)
        """
        session.run(
            queryOf(
                sql,
                mapOf("fodselsnummer" to fødselsnummer, "antallFraSpleis" to antallFraSpleis, "antallNye" to antallNye),
            ).asUpdate,
        )
    }

    private fun sjekkAtIngenTilhørerAndrePersoner(
        session: Session,
        fødselsnummer: String,
        rettighetskategorier: List<Rettighetskategori>,
    ) {
        @Language("PostgreSQL")
        val sql = "SELECT id FROM rettighetskategori WHERE id = ANY(:ider) AND fødselsnummer <> :fodselsnummer"
        val ider =
            session.run(
                queryOf(
                    sql,
                    mapOf("ider" to session.createArrayOf("uuid", rettighetskategorier.map { it.id }), "fodselsnummer" to fødselsnummer),
                ).map { it.uuid("id") }.asList,
            )
        check(ider.isEmpty()) { "Rettighetskategoriene $ider er allerede lagret for en annen person" }
    }

    // Søknadene ligger i vurdering. Kolonnen skrives fortsatt så poder med forrige versjon virker under utrulling.
    private fun lagre(
        session: Session,
        rettighetskategori: Rettighetskategori,
        søknader: List<UUID>,
        manuellVurdering: ManuellVurdering? = null,
    ): Int {
        @Language("PostgreSQL")
        val sql = """
            INSERT INTO rettighetskategori (id, fødselsnummer, skjæringstidspunkt, hovedkategori, tilleggskategorier, kilde, vurderingstidspunkt, søknader,
                                            saksbehandler_ident, begrunnelse, erstatter)
            VALUES (:id, :fodselsnummer, :skjaeringstidspunkt, :hovedkategori, :tilleggskategorier, :kilde, :vurderingstidspunkt, :soknader,
                    :saksbehandlerIdent, :begrunnelse, :erstatter)
            ON CONFLICT (id) DO NOTHING
        """
        return session.run(
            queryOf(
                sql,
                mapOf(
                    "id" to rettighetskategori.id,
                    "fodselsnummer" to rettighetskategori.fødselsnummer,
                    "skjaeringstidspunkt" to rettighetskategori.skjæringstidspunkt,
                    "hovedkategori" to rettighetskategori.konklusjon.hovedkategori.name,
                    "tilleggskategorier" to tilleggskategorier(session, rettighetskategori.konklusjon.tilleggskategorier),
                    "kilde" to rettighetskategori.kilde.dbVerdi,
                    "vurderingstidspunkt" to rettighetskategori.vurderingstidspunkt,
                    "soknader" to session.createArrayOf("uuid", søknader),
                    "saksbehandlerIdent" to manuellVurdering?.saksbehandlerIdent,
                    "begrunnelse" to manuellVurdering?.begrunnelse,
                    "erstatter" to manuellVurdering?.erstatter,
                ),
            ).asUpdate,
        )
    }

    private fun tilleggskategorier(
        session: Session,
        tilleggskategorier: Set<Tilleggskategori>,
    ) = session.createArrayOf("text", tilleggskategorier.map { it.name }.sorted())

    fun finn(id: UUID): Rettighetskategori? {
        @Language("PostgreSQL")
        val sql = """
            SELECT id, fødselsnummer, skjæringstidspunkt, hovedkategori, tilleggskategorier, kilde, vurderingstidspunkt
            FROM rettighetskategori
            WHERE id = :id
        """
        return sessionOf(dataSource).use { session ->
            session.run(queryOf(sql, mapOf("id" to id)).map(::tilRettighetskategori).asSingle)
        }
    }

    fun gjeldende(
        fødselsnummer: String,
        skjæringstidspunkt: LocalDate,
    ): Rettighetskategori? = sessionOf(dataSource).use { session -> gjeldende(session, fødselsnummer, skjæringstidspunkt) }

    private fun gjeldende(
        session: Session,
        fødselsnummer: String,
        skjæringstidspunkt: LocalDate,
    ): Rettighetskategori? {
        @Language("PostgreSQL")
        val sql = """
            SELECT id, fødselsnummer, skjæringstidspunkt, hovedkategori, tilleggskategorier, kilde, vurderingstidspunkt
            FROM rettighetskategori
            WHERE fødselsnummer = :fodselsnummer AND skjæringstidspunkt = :skjaeringstidspunkt
            ORDER BY vurderingstidspunkt DESC
            LIMIT 1
        """
        return session.run(
            queryOf(sql, mapOf("fodselsnummer" to fødselsnummer, "skjaeringstidspunkt" to skjæringstidspunkt))
                .map(::tilRettighetskategori)
                .asSingle,
        )
    }

    private fun tilRettighetskategori(row: Row) =
        Rettighetskategori(
            id = row.uuid("id"),
            fødselsnummer = row.string("fødselsnummer"),
            skjæringstidspunkt = row.localDate("skjæringstidspunkt"),
            konklusjon =
                Konklusjon(
                    hovedkategori = Hovedkategori.valueOf(row.string("hovedkategori")),
                    tilleggskategorier = row.array<String>("tilleggskategorier").map(Tilleggskategori::valueOf).toSet(),
                ),
            kilde = Kilde.fraDbVerdi(row.string("kilde")),
            vurderingstidspunkt = row.instant("vurderingstidspunkt"),
        )
}
