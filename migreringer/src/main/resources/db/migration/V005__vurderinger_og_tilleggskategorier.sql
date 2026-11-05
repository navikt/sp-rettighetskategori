ALTER TABLE rettighetskategori
    ADD COLUMN tilleggskategorier TEXT[] NOT NULL DEFAULT '{}';

CREATE TABLE vurdering
(
    løpenummer            BIGSERIAL PRIMARY KEY,
    rettighetskategori_id UUID        NOT NULL REFERENCES rettighetskategori (id),
    søknader              UUID[]      NOT NULL,
    vurderingstidspunkt   TIMESTAMPTZ NOT NULL,
    opprettet             TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX vurdering_rettighetskategori_id_idx ON vurdering (rettighetskategori_id);

INSERT INTO vurdering (rettighetskategori_id, søknader, vurderingstidspunkt)
SELECT id, søknader, vurderingstidspunkt
FROM rettighetskategori
WHERE kilde = 'automatisk'
ORDER BY vurderingstidspunkt;
