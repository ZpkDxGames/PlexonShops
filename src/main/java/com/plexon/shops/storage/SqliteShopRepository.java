package com.plexon.shops.storage;

import com.plexon.shops.config.PluginConfig;
import com.plexon.shops.models.Category;
import com.plexon.shops.models.LabeledItem;
import com.plexon.shops.models.Rating;
import com.plexon.shops.models.Shop;
import com.plexon.shops.models.ShopLocation;
import com.plexon.shops.models.ShopStatus;
import com.plexon.shops.models.Visitor;
import com.plexon.shops.models.VisitorStats;
import com.plexon.shops.util.BoundedExecutor;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/** Transactional SQLite implementation. Every operation is dispatched to the bounded worker. */
public final class SqliteShopRepository implements ShopRepository {
    public static final int SCHEMA_VERSION = 3;

    private static final String UPSERT_SHOP = """
            INSERT INTO shops (
                id, owner_uuid, owner_name, shop_name, world_uuid, world_name,
                x, y, z, yaw, pitch, categories, status, total_visitors,
                display_icon, description, teleport_fee, last_owner_seen, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                owner_uuid = excluded.owner_uuid,
                owner_name = excluded.owner_name,
                shop_name = excluded.shop_name,
                world_uuid = excluded.world_uuid,
                world_name = excluded.world_name,
                x = excluded.x,
                y = excluded.y,
                z = excluded.z,
                yaw = excluded.yaw,
                pitch = excluded.pitch,
                categories = excluded.categories,
                status = excluded.status,
                total_visitors = excluded.total_visitors,
                display_icon = excluded.display_icon,
                description = excluded.description,
                teleport_fee = excluded.teleport_fee,
                last_owner_seen = excluded.last_owner_seen,
                updated_at = excluded.updated_at
            """;

    private final File databaseFile;
    private final PluginConfig.Database settings;
    private final BoundedExecutor executor;
    private final Logger logger;
    private volatile HikariDataSource dataSource;
    private volatile StorageDiagnostics diagnostics = new StorageDiagnostics(0, "not-initialized", "", 0);

    public SqliteShopRepository(
            File databaseFile,
            PluginConfig.Database settings,
            BoundedExecutor executor,
            Logger logger
    ) {
        this.databaseFile = databaseFile;
        this.settings = settings;
        this.executor = executor;
        this.logger = logger;
    }

    @Override
    public CompletableFuture<Void> initialize() {
        return executor.run(() -> {
            String backupFile = "";
            int previousVersion = 0;
            boolean existingDatabase = databaseFile.isFile() && databaseFile.length() > 0L;
            try {
                Files.createDirectories(databaseFile.toPath().toAbsolutePath().getParent());
                Class.forName("org.sqlite.JDBC");
                previousVersion = preflightSchemaVersion();
                if (existingDatabase && previousVersion < SCHEMA_VERSION) {
                    backupFile = backupBeforeMigration();
                }

                HikariConfig config = new HikariConfig();
                config.setPoolName("PlexonShops-SQLite");
                config.setJdbcUrl("jdbc:sqlite:" + databaseFile.getAbsolutePath());
                config.setMaximumPoolSize(settings.maximumPoolSize());
                config.setMinimumIdle(1);
                config.setConnectionTimeout(settings.connectionTimeoutMillis());
                config.setInitializationFailTimeout(settings.connectionTimeoutMillis());
                config.setMaxLifetime(0L);
                config.setIdleTimeout(0L);
                config.setConnectionInitSql("PRAGMA foreign_keys = ON");
                dataSource = new HikariDataSource(config);

                try (Connection connection = connection(); Statement statement = connection.createStatement()) {
                    statement.execute("PRAGMA journal_mode = WAL");
                    statement.execute("PRAGMA synchronous = NORMAL");
                    statement.execute("PRAGMA busy_timeout = 5000");
                    connection.setAutoCommit(false);
                    try {
                        createSchema(statement);
                        writeSchemaVersion(connection, SCHEMA_VERSION);
                        connection.commit();
                    } catch (SQLException error) {
                        rollback(connection, error);
                        throw error;
                    } finally {
                        connection.setAutoCommit(true);
                    }
                }
                String status;
                if (!existingDatabase) {
                    status = "initialized-v" + SCHEMA_VERSION;
                } else if (previousVersion >= SCHEMA_VERSION) {
                    status = "current-v" + SCHEMA_VERSION;
                } else if (previousVersion == 0) {
                    status = "migrated-v2.2.1-to-v3";
                } else {
                    status = "migrated-v" + previousVersion + "-to-v" + SCHEMA_VERSION;
                }
                diagnostics = new StorageDiagnostics(SCHEMA_VERSION, status, backupFile, diagnostics.corruptRows());
            } catch (Exception error) {
                closeNow();
                diagnostics = new StorageDiagnostics(previousVersion, "migration-failed", backupFile, diagnostics.corruptRows());
                throw new StorageException("Could not initialize the SQLite database", error);
            }
        });
    }

    @Override
    public CompletableFuture<List<Shop>> loadAll() {
        return executor.supply(() -> {
            ensureInitialized();
            Map<UUID, LoadedShop> rows = new LinkedHashMap<>();
            int corruptRows = 0;
            try (Connection connection = connection()) {
                try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM shops ORDER BY created_at ASC");
                     ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        try {
                            LoadedShop row = readShop(result);
                            rows.put(row.id, row);
                        } catch (RuntimeException corruptRow) {
                            corruptRows++;
                            logger.log(Level.WARNING, "Ignoring a corrupt PlexonShops row without deleting it", corruptRow);
                        }
                    }
                }
                loadRatings(connection, rows);
                loadVisitors(connection, rows);
                loadLabels(connection, rows);
            } catch (SQLException error) {
                throw new StorageException("Could not load shops", error);
            }
            StorageDiagnostics current = diagnostics;
            diagnostics = new StorageDiagnostics(
                    current.schemaVersion(), current.migrationStatus(), current.backupFile(), corruptRows);
            return rows.values().stream().map(LoadedShop::toShop).toList();
        });
    }

    @Override
    public CompletableFuture<Void> save(Shop shop) {
        return executor.run(() -> {
            ensureInitialized();
            try (Connection connection = connection()) {
                connection.setAutoCommit(false);
                try {
                    upsertShop(connection, shop);
                    replaceRatings(connection, shop);
                    replaceVisitors(connection, shop);
                    replaceLabels(connection, shop);
                    connection.commit();
                } catch (SQLException error) {
                    rollback(connection, error);
                    throw error;
                } finally {
                    connection.setAutoCommit(true);
                }
            } catch (SQLException error) {
                throw new StorageException("Could not save shop " + shop.id(), error);
            }
        });
    }

    @Override
    public CompletableFuture<Void> saveCore(Shop shop) {
        return executor.run(() -> {
            ensureInitialized();
            try (Connection connection = connection()) {
                upsertShop(connection, shop);
            } catch (SQLException error) {
                throw new StorageException("Could not save shop core " + shop.id(), error);
            }
        });
    }

    @Override
    public CompletableFuture<Void> touchOwner(UUID ownerUuid, String ownerName, long timestampEpochSecond) {
        return executor.run(() -> {
            ensureInitialized();
            try (Connection connection = connection();
                 PreparedStatement statement = connection.prepareStatement("""
                         UPDATE shops
                         SET owner_name = ?, last_owner_seen = ?, updated_at = MAX(updated_at, ?)
                         WHERE owner_uuid = ?
                         """)) {
                statement.setString(1, ownerName);
                statement.setLong(2, timestampEpochSecond);
                statement.setLong(3, timestampEpochSecond);
                statement.setString(4, ownerUuid.toString());
                statement.executeUpdate();
            } catch (SQLException error) {
                throw new StorageException("Could not update owner activity for " + ownerUuid, error);
            }
        });
    }

    @Override
    public CompletableFuture<Void> saveRating(UUID shopId, Rating rating) {
        return executor.run(() -> {
            ensureInitialized();
            try (Connection connection = connection();
                 PreparedStatement statement = connection.prepareStatement("""
                         INSERT INTO ratings (shop_id, player_uuid, stars, updated_at) VALUES (?, ?, ?, ?)
                         ON CONFLICT(shop_id, player_uuid) DO UPDATE SET
                             stars = excluded.stars,
                             updated_at = excluded.updated_at
                         """)) {
                statement.setString(1, shopId.toString());
                statement.setString(2, rating.playerUuid().toString());
                statement.setInt(3, rating.stars());
                statement.setLong(4, rating.updatedAtEpochSecond());
                statement.executeUpdate();
            } catch (SQLException error) {
                throw new StorageException("Could not save rating for shop " + shopId, error);
            }
        });
    }

    @Override
    public CompletableFuture<Void> recordVisits(Shop shop, List<Visitor> newUniqueVisitors) {
        return executor.run(() -> {
            ensureInitialized();
            try (Connection connection = connection()) {
                connection.setAutoCommit(false);
                try {
                    try (PreparedStatement update = connection.prepareStatement(
                            "UPDATE shops SET total_visitors = MAX(total_visitors, ?), "
                                    + "updated_at = MAX(updated_at, ?) WHERE id = ?")) {
                        update.setLong(1, shop.visitors().totalVisits());
                        update.setLong(2, shop.updatedAtEpochSecond());
                        update.setString(3, shop.id().toString());
                        if (update.executeUpdate() != 1) {
                            throw new SQLException("Shop row does not exist: " + shop.id());
                        }
                    }
                    if (!newUniqueVisitors.isEmpty()) {
                        try (PreparedStatement insert = connection.prepareStatement("""
                                INSERT INTO visitors (shop_id, player_uuid, first_visited_at) VALUES (?, ?, ?)
                                ON CONFLICT(shop_id, player_uuid) DO NOTHING
                                """)) {
                            for (Visitor visitor : newUniqueVisitors) {
                                insert.setString(1, shop.id().toString());
                                insert.setString(2, visitor.playerUuid().toString());
                                insert.setLong(3, visitor.firstVisitedAtEpochSecond());
                                insert.addBatch();
                            }
                            insert.executeBatch();
                        }
                    }
                    connection.commit();
                } catch (SQLException error) {
                    rollback(connection, error);
                    throw error;
                } finally {
                    connection.setAutoCommit(true);
                }
            } catch (SQLException error) {
                throw new StorageException("Could not record visits for shop " + shop.id(), error);
            }
        });
    }

    @Override
    public CompletableFuture<Void> syncLabeledItems(Shop shop) {
        return executor.run(() -> {
            ensureInitialized();
            try (Connection connection = connection()) {
                connection.setAutoCommit(false);
                try {
                    try (PreparedStatement update = connection.prepareStatement(
                            "UPDATE shops SET updated_at = ? WHERE id = ?")) {
                        update.setLong(1, shop.updatedAtEpochSecond());
                        update.setString(2, shop.id().toString());
                        if (update.executeUpdate() != 1) {
                            throw new SQLException("Shop row does not exist: " + shop.id());
                        }
                    }
                    replaceLabels(connection, shop);
                    connection.commit();
                } catch (SQLException error) {
                    rollback(connection, error);
                    throw error;
                } finally {
                    connection.setAutoCommit(true);
                }
            } catch (SQLException error) {
                throw new StorageException("Could not synchronize labeled items for shop " + shop.id(), error);
            }
        });
    }

    @Override
    public CompletableFuture<Set<UUID>> loadFeatured() {
        return executor.supply(() -> {
            ensureInitialized();
            Set<UUID> result = new LinkedHashSet<>();
            try (Connection connection = connection();
                 PreparedStatement statement = connection.prepareStatement(
                         "SELECT shop_id FROM shop_features WHERE featured = 1 ORDER BY updated_at DESC, shop_id ASC");
                 ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(UUID.fromString(rows.getString("shop_id")));
                }
            } catch (SQLException | IllegalArgumentException error) {
                throw new StorageException("Could not load featured shops", error);
            }
            return Set.copyOf(result);
        });
    }

    @Override
    public CompletableFuture<PlayerDiscoveryData> loadDiscovery(UUID playerUuid, int recentLimit) {
        return executor.supply(() -> {
            ensureInitialized();
            int boundedLimit = Math.clamp(recentLimit, 1, 100);
            Set<UUID> favorites = new LinkedHashSet<>();
            List<UUID> recent = new ArrayList<>();
            try (Connection connection = connection()) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT shop_id FROM shop_favorites WHERE player_uuid = ? ORDER BY created_at DESC, shop_id ASC")) {
                    statement.setString(1, playerUuid.toString());
                    try (ResultSet rows = statement.executeQuery()) {
                        while (rows.next()) {
                            favorites.add(UUID.fromString(rows.getString("shop_id")));
                        }
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT shop_id FROM shop_recent_visits WHERE player_uuid = ? "
                                + "ORDER BY last_visited_at DESC, shop_id ASC LIMIT ?")) {
                    statement.setString(1, playerUuid.toString());
                    statement.setInt(2, boundedLimit);
                    try (ResultSet rows = statement.executeQuery()) {
                        while (rows.next()) {
                            recent.add(UUID.fromString(rows.getString("shop_id")));
                        }
                    }
                }
            } catch (SQLException | IllegalArgumentException error) {
                throw new StorageException("Could not load discovery state for " + playerUuid, error);
            }
            return new PlayerDiscoveryData(favorites, recent);
        });
    }

    @Override
    public CompletableFuture<Void> setFavorite(
            UUID playerUuid,
            UUID shopId,
            boolean favorite,
            long timestampEpochSecond
    ) {
        return executor.run(() -> {
            ensureInitialized();
            String sql = favorite
                    ? "INSERT INTO shop_favorites (player_uuid, shop_id, created_at) VALUES (?, ?, ?) "
                    + "ON CONFLICT(player_uuid, shop_id) DO UPDATE SET created_at = excluded.created_at"
                    : "DELETE FROM shop_favorites WHERE player_uuid = ? AND shop_id = ?";
            try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, playerUuid.toString());
                statement.setString(2, shopId.toString());
                if (favorite) {
                    statement.setLong(3, timestampEpochSecond);
                }
                statement.executeUpdate();
            } catch (SQLException error) {
                throw new StorageException("Could not update favorite for shop " + shopId, error);
            }
        });
    }

    @Override
    public CompletableFuture<Void> setFeatured(UUID shopId, boolean featured, long timestampEpochSecond) {
        return executor.run(() -> {
            ensureInitialized();
            String sql = featured
                    ? "INSERT INTO shop_features (shop_id, featured, updated_at) VALUES (?, 1, ?) "
                    + "ON CONFLICT(shop_id) DO UPDATE SET featured = 1, updated_at = excluded.updated_at"
                    : "DELETE FROM shop_features WHERE shop_id = ?";
            try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, shopId.toString());
                if (featured) {
                    statement.setLong(2, timestampEpochSecond);
                }
                statement.executeUpdate();
            } catch (SQLException error) {
                throw new StorageException("Could not update featured state for shop " + shopId, error);
            }
        });
    }

    @Override
    public CompletableFuture<Void> recordRecentVisit(
            UUID playerUuid,
            UUID shopId,
            long timestampEpochSecond,
            int recentLimit
    ) {
        return executor.run(() -> {
            ensureInitialized();
            int boundedLimit = Math.clamp(recentLimit, 1, 100);
            try (Connection connection = connection()) {
                connection.setAutoCommit(false);
                try {
                    try (PreparedStatement upsert = connection.prepareStatement("""
                            INSERT INTO shop_recent_visits (player_uuid, shop_id, last_visited_at) VALUES (?, ?, ?)
                            ON CONFLICT(player_uuid, shop_id) DO UPDATE SET
                                last_visited_at = excluded.last_visited_at
                            """)) {
                        upsert.setString(1, playerUuid.toString());
                        upsert.setString(2, shopId.toString());
                        upsert.setLong(3, timestampEpochSecond);
                        upsert.executeUpdate();
                    }
                    try (PreparedStatement trim = connection.prepareStatement("""
                            DELETE FROM shop_recent_visits
                            WHERE player_uuid = ? AND shop_id NOT IN (
                                SELECT shop_id FROM shop_recent_visits
                                WHERE player_uuid = ?
                                ORDER BY last_visited_at DESC, shop_id ASC
                                LIMIT ?
                            )
                            """)) {
                        trim.setString(1, playerUuid.toString());
                        trim.setString(2, playerUuid.toString());
                        trim.setInt(3, boundedLimit);
                        trim.executeUpdate();
                    }
                    connection.commit();
                } catch (SQLException error) {
                    rollback(connection, error);
                    throw error;
                } finally {
                    connection.setAutoCommit(true);
                }
            } catch (SQLException error) {
                throw new StorageException("Could not persist recent shop visit for " + playerUuid, error);
            }
        });
    }

    @Override
    public StorageDiagnostics diagnostics() {
        return diagnostics;
    }

    @Override
    public CompletableFuture<Void> delete(UUID shopId) {
        return executor.run(() -> {
            ensureInitialized();
            try (Connection connection = connection();
                 PreparedStatement statement = connection.prepareStatement("DELETE FROM shops WHERE id = ?")) {
                statement.setString(1, shopId.toString());
                statement.executeUpdate();
            } catch (SQLException error) {
                throw new StorageException("Could not delete shop " + shopId, error);
            }
        });
    }

    @Override
    public CompletableFuture<Void> close() {
        return executor.run(this::closeNow);
    }

    private void upsertShop(Connection connection, Shop shop) throws SQLException {
        ShopLocation location = shop.location();
        try (PreparedStatement statement = connection.prepareStatement(UPSERT_SHOP)) {
            int index = 1;
            statement.setString(index++, shop.id().toString());
            statement.setString(index++, shop.ownerUuid().toString());
            statement.setString(index++, shop.ownerName());
            statement.setString(index++, shop.name());
            statement.setString(index++, location.worldUuid().toString());
            statement.setString(index++, location.worldName());
            statement.setDouble(index++, location.x());
            statement.setDouble(index++, location.y());
            statement.setDouble(index++, location.z());
            statement.setFloat(index++, location.yaw());
            statement.setFloat(index++, location.pitch());
            statement.setString(index++, encodeCategories(shop.categories()));
            statement.setString(index++, shop.status().name());
            statement.setLong(index++, shop.visitors().totalVisits());
            statement.setString(index++, shop.displayIconData());
            statement.setString(index++, TextListCodec.encode(shop.description()));
            statement.setDouble(index++, shop.teleportFee());
            statement.setLong(index++, shop.lastOwnerSeenEpochSecond());
            statement.setLong(index++, shop.createdAtEpochSecond());
            statement.setLong(index, shop.updatedAtEpochSecond());
            statement.executeUpdate();
        }
    }

    private void replaceRatings(Connection connection, Shop shop) throws SQLException {
        deleteChildren(connection, "ratings", shop.id());
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO ratings (shop_id, player_uuid, stars, updated_at) VALUES (?, ?, ?, ?)")) {
            for (Rating rating : shop.ratings().values()) {
                statement.setString(1, shop.id().toString());
                statement.setString(2, rating.playerUuid().toString());
                statement.setInt(3, rating.stars());
                statement.setLong(4, rating.updatedAtEpochSecond());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void replaceVisitors(Connection connection, Shop shop) throws SQLException {
        deleteChildren(connection, "visitors", shop.id());
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO visitors (shop_id, player_uuid, first_visited_at) VALUES (?, ?, ?)")) {
            for (Visitor visitor : shop.visitors().uniqueVisitors().values()) {
                statement.setString(1, shop.id().toString());
                statement.setString(2, visitor.playerUuid().toString());
                statement.setLong(3, visitor.firstVisitedAtEpochSecond());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void replaceLabels(Connection connection, Shop shop) throws SQLException {
        deleteChildren(connection, "labeled_items", shop.id());
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO labeled_items (id, shop_id, label, item_data, created_at) VALUES (?, ?, ?, ?, ?)")) {
            for (LabeledItem item : shop.labeledItems()) {
                statement.setString(1, item.id().toString());
                statement.setString(2, shop.id().toString());
                statement.setString(3, item.label());
                statement.setString(4, item.itemData());
                statement.setLong(5, item.createdAtEpochSecond());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void deleteChildren(Connection connection, String table, UUID shopId) throws SQLException {
        String sql = switch (table) {
            case "ratings" -> "DELETE FROM ratings WHERE shop_id = ?";
            case "visitors" -> "DELETE FROM visitors WHERE shop_id = ?";
            case "labeled_items" -> "DELETE FROM labeled_items WHERE shop_id = ?";
            default -> throw new IllegalArgumentException("Unsupported child table");
        };
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, shopId.toString());
            statement.executeUpdate();
        }
    }

    private void loadRatings(Connection connection, Map<UUID, LoadedShop> shops) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM ratings");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                LoadedShop shop = shops.get(UUID.fromString(result.getString("shop_id")));
                if (shop != null) {
                    UUID playerUuid = UUID.fromString(result.getString("player_uuid"));
                    shop.ratings.put(playerUuid, new Rating(playerUuid, result.getInt("stars"), result.getLong("updated_at")));
                }
            }
        }
    }

    private void loadVisitors(Connection connection, Map<UUID, LoadedShop> shops) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM visitors");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                LoadedShop shop = shops.get(UUID.fromString(result.getString("shop_id")));
                if (shop != null) {
                    UUID playerUuid = UUID.fromString(result.getString("player_uuid"));
                    shop.visitors.put(playerUuid, new Visitor(playerUuid, result.getLong("first_visited_at")));
                }
            }
        }
    }

    private void loadLabels(Connection connection, Map<UUID, LoadedShop> shops) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM labeled_items ORDER BY created_at ASC");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                LoadedShop shop = shops.get(UUID.fromString(result.getString("shop_id")));
                if (shop != null) {
                    shop.labels.add(new LabeledItem(
                            UUID.fromString(result.getString("id")),
                            result.getString("label"),
                            result.getString("item_data"),
                            result.getLong("created_at")
                    ));
                }
            }
        }
    }

    private LoadedShop readShop(ResultSet result) throws SQLException {
        LoadedShop row = new LoadedShop();
        row.id = UUID.fromString(result.getString("id"));
        row.ownerUuid = UUID.fromString(result.getString("owner_uuid"));
        row.ownerName = result.getString("owner_name");
        row.name = result.getString("shop_name");
        row.location = new ShopLocation(
                UUID.fromString(result.getString("world_uuid")),
                result.getString("world_name"),
                result.getDouble("x"),
                result.getDouble("y"),
                result.getDouble("z"),
                result.getFloat("yaw"),
                result.getFloat("pitch")
        );
        row.categories = decodeCategories(result.getString("categories"));
        row.status = ShopStatus.valueOf(result.getString("status").toUpperCase(Locale.ROOT));
        row.totalVisitors = result.getLong("total_visitors");
        row.displayIcon = result.getString("display_icon");
        row.description = TextListCodec.decode(result.getString("description"));
        row.teleportFee = result.getDouble("teleport_fee");
        row.lastOwnerSeen = result.getLong("last_owner_seen");
        row.createdAt = result.getLong("created_at");
        row.updatedAt = result.getLong("updated_at");
        return row;
    }

    private String encodeCategories(Set<Category> categories) {
        return categories.stream().map(Enum::name).collect(Collectors.joining(","));
    }

    private Set<Category> decodeCategories(String encoded) {
        EnumSet<Category> categories = EnumSet.noneOf(Category.class);
        if (encoded != null) {
            for (String value : encoded.split(",")) {
                Category.parse(value).ifPresent(categories::add);
            }
        }
        return categories.isEmpty() ? EnumSet.of(Category.MISC) : categories;
    }

    private Connection connection() throws SQLException {
        ensureInitialized();
        Connection connection = dataSource.getConnection();
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = 5000");
        }
        return connection;
    }

    private void ensureInitialized() {
        if (dataSource == null || dataSource.isClosed()) {
            throw new IllegalStateException("SQLite repository is not initialized");
        }
    }

    private void closeNow() {
        HikariDataSource current = dataSource;
        dataSource = null;
        if (current != null && !current.isClosed()) {
            current.close();
        }
    }

    private void rollback(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackError) {
            original.addSuppressed(rollbackError);
        }
    }

    private int preflightSchemaVersion() throws SQLException {
        if (!databaseFile.isFile() || databaseFile.length() == 0L) {
            return 0;
        }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.getAbsolutePath());
             PreparedStatement exists = connection.prepareStatement(
                     "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'plexonshops_schema'");
             ResultSet row = exists.executeQuery()) {
            if (!row.next()) {
                return 0;
            }
        }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.getAbsolutePath());
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT schema_version FROM plexonshops_schema WHERE id = 1");
             ResultSet row = statement.executeQuery()) {
            return row.next() ? row.getInt(1) : 0;
        }
    }

    private String backupBeforeMigration() throws Exception {
        Path source = databaseFile.toPath().toAbsolutePath();
        String suffix = ".backup-v2.2.1-" + System.currentTimeMillis();
        Path backup = source.resolveSibling(source.getFileName() + suffix);
        Files.copy(source, backup, StandardCopyOption.COPY_ATTRIBUTES);
        copySidecar(source, backup, "-wal");
        copySidecar(source, backup, "-shm");
        return backup.getFileName().toString();
    }

    private void copySidecar(Path source, Path backup, String suffix) throws Exception {
        Path sidecar = Path.of(source.toString() + suffix);
        if (Files.isRegularFile(sidecar)) {
            Files.copy(sidecar, Path.of(backup.toString() + suffix), StandardCopyOption.COPY_ATTRIBUTES);
        }
    }

    private void writeSchemaVersion(Connection connection, int version) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO plexonshops_schema (id, schema_version, updated_at)
                VALUES (1, ?, CAST(strftime('%s','now') AS INTEGER))
                ON CONFLICT(id) DO UPDATE SET
                    schema_version = excluded.schema_version,
                    updated_at = excluded.updated_at
                """)) {
            statement.setInt(1, version);
            statement.executeUpdate();
        }
    }

    private void createSchema(Statement statement) throws SQLException {
        statement.execute("""
                CREATE TABLE IF NOT EXISTS shops (
                    id TEXT PRIMARY KEY,
                    owner_uuid TEXT NOT NULL,
                    owner_name TEXT NOT NULL,
                    shop_name TEXT NOT NULL,
                    world_uuid TEXT NOT NULL,
                    world_name TEXT NOT NULL,
                    x REAL NOT NULL,
                    y REAL NOT NULL,
                    z REAL NOT NULL,
                    yaw REAL NOT NULL,
                    pitch REAL NOT NULL,
                    categories TEXT NOT NULL,
                    status TEXT NOT NULL,
                    total_visitors INTEGER NOT NULL DEFAULT 0,
                    display_icon TEXT NOT NULL DEFAULT '',
                    description TEXT NOT NULL DEFAULT '',
                    teleport_fee REAL NOT NULL DEFAULT 0,
                    last_owner_seen INTEGER NOT NULL,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """);
        statement.execute("CREATE INDEX IF NOT EXISTS shops_owner_idx ON shops(owner_uuid)");
        statement.execute("""
                CREATE TABLE IF NOT EXISTS ratings (
                    shop_id TEXT NOT NULL,
                    player_uuid TEXT NOT NULL,
                    stars INTEGER NOT NULL CHECK(stars BETWEEN 1 AND 5),
                    updated_at INTEGER NOT NULL,
                    PRIMARY KEY (shop_id, player_uuid),
                    FOREIGN KEY (shop_id) REFERENCES shops(id) ON DELETE CASCADE
                )
                """);
        statement.execute("""
                CREATE TABLE IF NOT EXISTS visitors (
                    shop_id TEXT NOT NULL,
                    player_uuid TEXT NOT NULL,
                    first_visited_at INTEGER NOT NULL,
                    PRIMARY KEY (shop_id, player_uuid),
                    FOREIGN KEY (shop_id) REFERENCES shops(id) ON DELETE CASCADE
                )
                """);
        statement.execute("""
                CREATE TABLE IF NOT EXISTS labeled_items (
                    id TEXT PRIMARY KEY,
                    shop_id TEXT NOT NULL,
                    label TEXT NOT NULL,
                    item_data TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    FOREIGN KEY (shop_id) REFERENCES shops(id) ON DELETE CASCADE
                )
                """);
        statement.execute("CREATE INDEX IF NOT EXISTS labeled_items_shop_idx ON labeled_items(shop_id)");
        statement.execute("""
                CREATE TABLE IF NOT EXISTS plexonshops_schema (
                    id INTEGER PRIMARY KEY CHECK(id = 1),
                    schema_version INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """);
        statement.execute("""
                CREATE TABLE IF NOT EXISTS shop_features (
                    shop_id TEXT PRIMARY KEY,
                    featured INTEGER NOT NULL DEFAULT 1 CHECK(featured IN (0, 1)),
                    updated_at INTEGER NOT NULL,
                    FOREIGN KEY (shop_id) REFERENCES shops(id) ON DELETE CASCADE
                )
                """);
        statement.execute("""
                CREATE TABLE IF NOT EXISTS shop_favorites (
                    player_uuid TEXT NOT NULL,
                    shop_id TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    PRIMARY KEY (player_uuid, shop_id),
                    FOREIGN KEY (shop_id) REFERENCES shops(id) ON DELETE CASCADE
                )
                """);
        statement.execute("CREATE INDEX IF NOT EXISTS shop_favorites_player_idx ON shop_favorites(player_uuid)");
        statement.execute("""
                CREATE TABLE IF NOT EXISTS shop_recent_visits (
                    player_uuid TEXT NOT NULL,
                    shop_id TEXT NOT NULL,
                    last_visited_at INTEGER NOT NULL,
                    PRIMARY KEY (player_uuid, shop_id),
                    FOREIGN KEY (shop_id) REFERENCES shops(id) ON DELETE CASCADE
                )
                """);
        statement.execute("CREATE INDEX IF NOT EXISTS shop_recent_player_idx "
                + "ON shop_recent_visits(player_uuid, last_visited_at DESC)");
    }

    private static final class LoadedShop {
        private UUID id;
        private UUID ownerUuid;
        private String ownerName;
        private String name;
        private ShopLocation location;
        private Set<Category> categories;
        private ShopStatus status;
        private final Map<UUID, Rating> ratings = new LinkedHashMap<>();
        private final Map<UUID, Visitor> visitors = new LinkedHashMap<>();
        private long totalVisitors;
        private String displayIcon;
        private List<String> description = List.of();
        private final List<LabeledItem> labels = new ArrayList<>();
        private double teleportFee;
        private long lastOwnerSeen;
        private long createdAt;
        private long updatedAt;

        private Shop toShop() {
            return new Shop(id, ownerUuid, ownerName, name, location, categories, status, ratings,
                    new VisitorStats(totalVisitors, visitors), displayIcon, description, labels,
                    teleportFee, lastOwnerSeen, createdAt, updatedAt);
        }
    }
}
