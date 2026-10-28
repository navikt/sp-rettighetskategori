DO
$$
    BEGIN
        IF EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'sp-rettighetskategori-opprydding-dev')
        THEN
            GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO "sp-rettighetskategori-opprydding-dev";
            GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO "sp-rettighetskategori-opprydding-dev";
            ALTER DEFAULT PRIVILEGES FOR USER "sp-rettighetskategori" IN SCHEMA public GRANT ALL PRIVILEGES ON SEQUENCES TO "sp-rettighetskategori-opprydding-dev";
            ALTER DEFAULT PRIVILEGES FOR USER "sp-rettighetskategori" IN SCHEMA public GRANT ALL PRIVILEGES ON TABLES TO "sp-rettighetskategori-opprydding-dev";
        END IF;
    END
$$;
