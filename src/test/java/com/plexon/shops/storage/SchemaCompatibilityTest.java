package com.plexon.shops.storage;

import com.plexon.shops.config.PluginConfig;
import com.plexon.shops.util.BoundedExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class SchemaCompatibilityTest {
    @TempDir
    Path directory;

    @Test
    void schemaTwoMigratesForwardToThree() throws Exception {
        Path db = directory.resolve("schema2.db");
        seedVersion(db, 2, false);
        BoundedExecutor worker = new BoundedExecutor("schema2-test", 1, 32);
        SqliteShopRepository repository = repository(db, worker);
        try {
            repository.initialize().get(10, TimeUnit.SECONDS);
            assertEquals(3, schemaVersion(db));
            assertEquals(3, repository.diagnostics().schemaVersion());
            assertFalse(repository.diagnostics().backupFile().isBlank());
        } finally {
            repository.close().get(10, TimeUnit.SECONDS);
            worker.shutdown(Duration.ofSeconds(5));
        }
    }

    @Test
    void schemaThreeStartsAsCurrent() throws Exception {
        Path db = directory.resolve("schema3.db");
        seedVersion(db, 3, false);
        BoundedExecutor worker = new BoundedExecutor("schema3-test", 1, 32);
        SqliteShopRepository repository = repository(db, worker);
        try {
            repository.initialize().get(10, TimeUnit.SECONDS);
            assertEquals(3, schemaVersion(db));
            assertEquals("current-v3", repository.diagnostics().migrationStatus());
        } finally {
            repository.close().get(10, TimeUnit.SECONDS);
            worker.shutdown(Duration.ofSeconds(5));
        }
    }

    @Test
    void futureSchemaIsRejectedWithoutMutationAndRemainsRejected() throws Exception {
        Path db = directory.resolve("schema4.db");
        seedVersion(db, 4, true);
        rejectFuture(db, "schema4-first");
        assertEquals(4, schemaVersion(db));
        assertEquals("keep-me", sentinel(db));
        rejectFuture(db, "schema4-second");
        assertEquals(4, schemaVersion(db));
        assertEquals("keep-me", sentinel(db));
    }

    private void rejectFuture(Path db, String workerName) {
        BoundedExecutor worker = new BoundedExecutor(workerName, 1, 32);
        SqliteShopRepository repository = repository(db, worker);
        try {
            ExecutionException error = assertThrows(
                    ExecutionException.class,
                    () -> repository.initialize().get(10, TimeUnit.SECONDS)
            );
            assertTrue(error.getCause().getMessage().contains("Could not initialize"));
            assertEquals(4, repository.diagnostics().schemaVersion());
            assertEquals("migration-failed", repository.diagnostics().migrationStatus());
        } finally {
            worker.shutdown(Duration.ofSeconds(5));
        }
    }

    private SqliteShopRepository repository(Path db, BoundedExecutor worker) {
        return new SqliteShopRepository(
                db.toFile(),
                new PluginConfig.Database(db.getFileName().toString(), 1, 5_000L),
                worker,
                Logger.getAnonymousLogger()
        );
    }

    private void seedVersion(Path db, int version, boolean sentinel) throws Exception {
        Class.forName("org.sqlite.JDBC");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE plexonshops_schema (id INTEGER PRIMARY KEY, schema_version INTEGER NOT NULL, updated_at INTEGER NOT NULL)");
            statement.execute("INSERT INTO plexonshops_schema VALUES (1, " + version + ", 123)");
            if (sentinel) {
                statement.execute("CREATE TABLE future_schema_sentinel (value TEXT NOT NULL)");
                statement.execute("INSERT INTO future_schema_sentinel VALUES ('keep-me')");
            }
        }
    }

    private int schemaVersion(Path db) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("SELECT schema_version FROM plexonshops_schema WHERE id = 1")) {
            assertTrue(row.next());
            return row.getInt(1);
        }
    }

    private String sentinel(Path db) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("SELECT value FROM future_schema_sentinel")) {
            assertTrue(row.next());
            return row.getString(1);
        }
    }
}
