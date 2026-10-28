# sp-rettighetskategori

Avgjør rettighetskategorien for et sykefraværstilfelle. Appen svarer på to behov på rapiden:

- `Rettighetskategori`: inn kommer `fødselsnummer`, `Rettighetskategori.skjæringstidspunkt` og `Rettighetskategori.søknader`, som er referanse-ID-ene (`@id`) til de sendte søknadene. Appen henter søknadene fra Spedisjon, avgjør hovedkategorien og lagrer avgjørelsen. Løsningen har `id` og `hovedkategori`.
- `RettighetskategoriResultat`: inn kommer `fødselsnummer` og `RettighetskategoriResultat.rettighetskategoriId`. Løsningen har `hovedkategori`.

Hver søknad gir en yrkesaktivitet ut fra søknadstypen og `arbeidssituasjon`, på samme måte som i Spleis. Appen gir hovedkategoriene `Arbeidstaker`, `SelvstendigNæringsdrivende` og `ArbeidstakerOgSelvstendigNæringsdrivende`. Får appen samme søknader for samme person og skjæringstidspunkt som i den gjeldende avgjørelsen, svarer den med den gjeldende avgjørelsen og lagrer ingen ny. Gjeldende avgjørelse er den med nyest vurderingstidspunkt.

Hvis appen ikke klarer å avgjøre kategorien, svarer den ikke på behovet, og feilen logges. Det skjer for eksempel når Spedisjon ikke svarer eller mangler en søknad, og når en søknad gjelder en annen yrkesaktivitet, som frilanser eller arbeidsledig.

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
