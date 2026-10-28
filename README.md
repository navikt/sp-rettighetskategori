# sp-rettighetskategori

Skjelett for appen. Foreløpig kjører den på rapiden, svarer på ping, eksponerer helsesjekker og migrerer en egen database.

## Moduler

- `sp-rettighetskategori`: selve appen.
- `migreringer`: Flyway-migreringer for databasen.
- `opprydding-dev`: sletter persondata i dev når det kommer `slett_person` på rapiden.

## Før første deploy av opprydding-dev

`opprydding-dev` kobler seg på databasen til `sp-rettighetskategori` og trenger IP-en til Cloud SQL-instansen i `.nais/dev-db-policy.yaml`.
Instansen opprettes når `sp-rettighetskategori` deployes til dev første gang. Slå opp IP-en med

```shell
kubectl get sqlinstance sp-rettighetskategori -n tbd -o jsonpath='{.status.publicIpAddress}'
```

## Henvendelser

Spørsmål knyttet til koden eller prosjektet kan stilles som issues her på GitHub.

### For Nav-ansatte

Interne henvendelser kan sendes via Slack i
kanalen [#sykepenger-værsågod](https://nav-it.slack.com/archives/C019637N90X).

## Kode generert av GitHub Copilot

Dette repoet bruker GitHub Copilot til å generere kode.
