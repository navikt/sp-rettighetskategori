CREATE INDEX rettighetskategori_fnr_skjaeringstidspunkt_idx
    ON rettighetskategori (fødselsnummer, skjæringstidspunkt, vurderingstidspunkt DESC);
