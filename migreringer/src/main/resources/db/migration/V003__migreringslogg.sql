CREATE TABLE migreringslogg
(
    løpenummer        BIGSERIAL PRIMARY KEY,
    fødselsnummer     VARCHAR(11) NOT NULL,
    opprettet         TIMESTAMPTZ NOT NULL DEFAULT now(),
    antall_fra_spleis INTEGER     NOT NULL CHECK (antall_fra_spleis > 0),
    antall_nye        INTEGER     NOT NULL CHECK (antall_nye > 0 AND antall_nye <= antall_fra_spleis)
);
