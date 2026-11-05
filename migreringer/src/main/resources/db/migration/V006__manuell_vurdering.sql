ALTER TABLE rettighetskategori
    ADD COLUMN saksbehandler_ident TEXT,
    ADD COLUMN begrunnelse         TEXT,
    ADD COLUMN erstatter           UUID REFERENCES rettighetskategori (id);
