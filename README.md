# sp-rettighetskategori

Avgjør rettighetskategorien for et sykefraværstilfelle. Appen svarer på to behov på rapiden:

- `Rettighetskategori`: inn kommer `fødselsnummer`, `Rettighetskategori.skjæringstidspunkt` og `Rettighetskategori.søknader`, som er referanse-ID-ene (`@id`) til de sendte søknadene. Appen henter søknadene fra Spedisjon, avgjør hovedkategorien og lagrer avgjørelsen. Løsningen har `id` og `hovedkategori`.
- `RettighetskategoriResultat`: inn kommer `fødselsnummer` og `RettighetskategoriResultat.rettighetskategoriId`. Løsningen har `hovedkategori`. Kjenner ikke appen ID-en, kjører den backfill for personen fra Spleis først.

Hver søknad gir en yrkesaktivitet ut fra søknadstypen og `arbeidssituasjon`, på samme måte som i Spleis. Appen gir hovedkategoriene `ARBEIDSTAKER`, `SELVSTENDIG_NÆRINGSDRIVENDE` og `ARBEIDSTAKER_OG_SELVSTENDIG_NÆRINGSDRIVENDE`. En søknad av typen `sendt_søknad_selvstendig` med `arbeidssituasjon = JORDBRUKER` gir i tillegg tilleggskategorien `JORDBRUKER`. Tilleggskategoriene sendes ikke i løsningen.

Appen skiller mellom en avgjørelse (tabellen `rettighetskategori`) og vurderingene bak den (tabellen `vurdering`), slik ADR 0006 i helse-sas-meta beskriver. Konklusjonen i en avgjørelse er hovedkategorien og tilleggskategoriene. Gjeldende avgjørelse er den med nyest vurderingstidspunkt for personen og skjæringstidspunktet.

- Samme søknader som i siste vurdering: appen svarer med gjeldende avgjørelse og lagrer ingenting.
- Nye søknader med samme konklusjon: vurderingen føyes til gjeldende avgjørelse, og ID-en blir den samme.
- Nye søknader med en annen konklusjon: appen lagrer en ny automatisk avgjørelse med ny ID.

Hvis appen ikke klarer å avgjøre kategorien, svarer den ikke på behovet, og feilen logges. Det skjer for eksempel når Spedisjon ikke svarer eller mangler en søknad, og når en søknad gjelder en annen yrkesaktivitet, som frilanser eller arbeidsledig.

## Oppslag fra Speil

`GET /api/personer/{personPseudoId}/rettighetskategorier/{rettighetskategoriId}` henter én avgjørelse. Svaret inneholder `id`, `skjæringstidspunkt`, `hovedkategori`, `tilleggskategorier`, `kilde` og `vurderingstidspunkt`, men ikke fødselsnummer eller søknader. `tilleggskategorier` er en liste som kan inneholde `JORDBRUKER`, og er tom når personen ikke har noen. Hovedkategoriene i REST-API-et bruker ASCII (`NAERINGSDRIVENDE`). Tidspunktet er i UTC.

Kallet krever et Azure AD-token med appen som audience og claimene `NAVident` og `oid`. Gruppene i `TILGANG_LES` og `TILGANG_SKRIV` gir lesetilgang. Begge variablene tar kommaseparerte gruppe-ID-er. Appen slår opp pseudo-ID-en i Valkey-instansen `personpseudoid` og kontrollerer kjernetilgang gjennom tilgangsmaskinen med saksbehandlerens token, vekslet av Texas.

En ukjent rettighetskategori-ID utløser backfill fra Spleis og et nytt oppslag i samme kall. Kjente ID-er krever ikke at Spleis svarer. En ID for en annen person gir 404 uten backfill.

| Status | Betydning |
|---|---|
| 400 | Rettighetskategori-ID-en er ikke en gyldig UUID |
| 401 | Tokenet mangler eller er ugyldig. Svaret har ingen body |
| 403 | Saksbehandleren mangler lesetilgang, eller tilgangsmaskinen avslår eller feiler |
| 404 | Personen eller avgjørelsen finnes ikke for oppslaget |
| 503 | Spleis svarte med feil eller kunne ikke kontaktes under backfill |

Feilsvar unntatt 401 har typen `application/problem+json`. Årsaken til en 403 logges på debug-nivå, uten personopplysninger i meldingsteksten.

API-et bruker vanlige Ktor-ruter, uten et eget REST-rammeverk, OpenAPI eller Swagger UI.

## Oppslag fra tjenester

`POST /api/rettighetskategorier/{rettighetskategoriId}` henter én avgjørelse for en annen tjeneste, uten saksbehandler. Bodyen er `{ "fødselsnummer": "<11 sifre>" }`. Svaret har de samme feltene som oppslaget fra Speil, og heller ikke her fødselsnummer.

Kallet krever et maskin-til-maskin-token fra Azure AD (client credentials) med appen som audience og `idtyp=app`. Tokener på vegne av en saksbehandler avvises, og maskin-til-maskin-tokener avvises på endepunktet for Speil. `inbound` i Nais styrer hvilke apper som får token. Der står `speil` og `sp-forsikring`, og sp-forsikring er den eneste tjenesten som skal bruke dette endepunktet. Det er ingen populasjonstilgangskontroll, fordi ingen saksbehandler er med i kallet.

Ukjent ID utløser backfill fra Spleis for fødselsnummeret og et nytt oppslag, som for Speil. En ID for en annen person gir 404 uten backfill.

| Status | Betydning |
|---|---|
| 400 | Rettighetskategori-ID-en er ikke en gyldig UUID, eller bodyen mangler et gyldig fødselsnummer |
| 401 | Tokenet mangler, er ugyldig eller er ikke et maskin-til-maskin-token. Svaret har ingen body |
| 404 | Avgjørelsen finnes ikke etter backfill, eller hører til et annet fødselsnummer |
| 503 | Spleis svarte med feil eller kunne ikke kontaktes under backfill |

Feilsvar unntatt 401 har typen `application/problem+json`.

Bygg og kjør testene:

```shell
./gradlew build -x addKtlintFormatGitPreCommitHook
```

## Backfill av historikken fra Spleis

Spleis har rettighetskategori-ID-er på vilkårsgrunnlag som ble laget før denne appen fantes. Med backfill lager appen avgjørelser for dem med data fra `POST /api/rettighetskategorier` i spleis-api. Det skjer ved hver `person_avstemming` og når `RettighetskategoriResultat` gjelder en ID appen ikke kjenner.

- ID-er uten aktive perioder blir ikke lagret.
- Vilkårsgrunnlag fra Infotrygd gir `ARBEIDSTAKER`.
- Ellers gir yrkesaktivitetstypene til de aktive periodene som bruker vilkårsgrunnlaget, hovedkategorien.
- Frilanser eller arbeidsledig blant de aktive periodene gir feil, og ingenting lagres for personen.

Avgjørelser fra backfill har kilde `spleis`, vurderingstidspunktet fra Spleis, ingen tilleggskategorier og ingen vurderinger. En ID som er lagret fra før, blir ikke endret. Hver backfill som lagrer minst én ny ID, føres i tabellen `migreringslogg` med antall ID-er fra Spleis og antall nye.

## Moduler

- `sp-rettighetskategori`: selve appen.
- `migreringer`: Flyway-migreringer for databasen.
- `opprydding-dev`: sletter persondata i dev når det kommer `slett_person` på rapiden.

## Henvendelser

Spørsmål knyttet til koden eller prosjektet kan stilles som issues her på GitHub.

### For Nav-ansatte

Interne henvendelser kan sendes via Slack i
kanalen [#sykepenger-værsågod](https://nav-it.slack.com/archives/C019637N90X).

## Kode generert av GitHub Copilot

Dette repoet bruker GitHub Copilot til å generere kode.
