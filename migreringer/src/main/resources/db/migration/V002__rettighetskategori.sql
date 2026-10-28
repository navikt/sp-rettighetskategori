CREATE TABLE rettighetskategori
(
    id                  UUID PRIMARY KEY,
    fødselsnummer       VARCHAR(11) NOT NULL,
    skjæringstidspunkt  DATE        NOT NULL,
    hovedkategori       TEXT        NOT NULL,
    kilde               TEXT        NOT NULL,
    vurderingstidspunkt TIMESTAMPTZ NOT NULL,
    søknader            UUID[]      NOT NULL,
    opprettet           TIMESTAMPTZ NOT NULL DEFAULT now()
);
