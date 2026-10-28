CREATE TABLE rettighetskategori
(
    løpenummer         BIGSERIAL PRIMARY KEY,
    id                 UUID        NOT NULL UNIQUE,
    fødselsnummer      VARCHAR(11) NOT NULL,
    skjæringstidspunkt DATE        NOT NULL,
    hovedkategori      TEXT        NOT NULL,
    kilde              TEXT        NOT NULL,
    tidspunkt          TIMESTAMPTZ NOT NULL,
    søknader           UUID[]      NOT NULL
);

CREATE INDEX idx_rettighetskategori_noekkel
    ON rettighetskategori (fødselsnummer, skjæringstidspunkt, løpenummer DESC);
