# sp-rettighetskategori

Avgjør rettighetskategorien for et sykefraværstilfelle. Appen svarer på to behov på rapiden:

- `Rettighetskategori`: inn kommer `fødselsnummer`, `Rettighetskategori.skjæringstidspunkt` og `Rettighetskategori.søknader`, som er referanse-ID-ene (`@id`) til de sendte søknadene. Appen henter søknadene fra Spedisjon, avgjør hovedkategorien og lagrer avgjørelsen. Løsningen har `id` og `hovedkategori`.
- `RettighetskategoriResultat`: inn kommer `fødselsnummer` og `RettighetskategoriResultat.rettighetskategoriId`. Løsningen har `hovedkategori`. Kjenner ikke appen ID-en, kjører den backfill for personen fra Spleis først.

Hver søknad gir en yrkesaktivitet ut fra søknadstypen og `arbeidssituasjon`, på samme måte som i Spleis. Appen gir hovedkategoriene `ARBEIDSTAKER`, `SELVSTENDIG_NÆRINGSDRIVENDE` og `ARBEIDSTAKER_OG_SELVSTENDIG_NÆRINGSDRIVENDE`. Får appen samme søknader for samme person og skjæringstidspunkt som i den gjeldende avgjørelsen, svarer den med den gjeldende avgjørelsen og lagrer ingen ny. Gjeldende avgjørelse er den med nyest vurderingstidspunkt.

Hvis appen ikke klarer å avgjøre kategorien, svarer den ikke på behovet, og feilen logges. Det skjer for eksempel når Spedisjon ikke svarer eller mangler en søknad, og når en søknad gjelder en annen yrkesaktivitet, som frilanser eller arbeidsledig.

## Backfill av historikken fra Spleis

Spleis har rettighetskategori-ID-er på vilkårsgrunnlag som ble laget før denne appen fantes. Med backfill lager appen avgjørelser for dem med data fra `POST /api/rettighetskategorier` i spleis-api. Det skjer ved hver `person_avstemming` og når `RettighetskategoriResultat` gjelder en ID appen ikke kjenner.

- Vilkårsgrunnlag fra Infotrygd gir `ARBEIDSTAKER`.
- Ellers gir yrkesaktivitetstypene til de aktive periodene som bruker vilkårsgrunnlaget, hovedkategorien. Finnes ingen aktive perioder, brukes de forkastede.
- Finnes ingen perioder, gir selvstendig inntekt i inntektsgrunnlaget `SELVSTENDIG_NÆRINGSDRIVENDE` og ellers `ARBEIDSTAKER`.
- Frilanser eller arbeidsledig blant periodene som brukes, gir feil, og ingenting lagres for personen.

Avgjørelser fra backfill har kilde `spleis`, vurderingstidspunktet fra Spleis og ingen søknader. En ID som er lagret fra før, blir ikke endret. Hver backfill som lagrer minst én ny ID, føres i tabellen `migreringslogg` med antall ID-er fra Spleis og antall nye.

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
