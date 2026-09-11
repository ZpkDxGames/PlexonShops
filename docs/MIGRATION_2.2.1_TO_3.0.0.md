# Migrating PlexonShops 2.2.1 to 3.0.0

The rollback baseline is `v2.2.1` (`81c77e936194de2d46fd40221ea57a1f65c07b34`). Back up the PlexonShops data directory before installing the 3.0 candidate. The 3.0 storage layer uses schema 3 and creates a migration backup for an older existing database before schema work. Existing shop UUIDs and owner UUIDs remain authoritative; the premium discovery metadata is additive.

If the database declares a schema newer than 3, the plugin intentionally fails startup without running migrations, DDL, or rewriting the schema marker. Use a PlexonShops build that supports that newer schema instead of attempting a downgrade.

Rollback testing must use the saved 2.2.1 data backup. Do not point 2.2.1 at a database after intentionally advancing it with a newer schema without restoring the corresponding backup first.
