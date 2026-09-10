package com.plexon.shops.storage;

import com.plexon.shops.models.Rating;
import com.plexon.shops.models.Shop;
import com.plexon.shops.models.Visitor;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Asynchronous persistence contract. No I/O method may block its caller thread. */
public interface ShopRepository {
    CompletableFuture<Void> initialize();

    CompletableFuture<List<Shop>> loadAll();

    CompletableFuture<Void> save(Shop shop);

    CompletableFuture<Void> saveCore(Shop shop);

    /** Updates activity metadata for all shops owned by one player in a single storage operation. */
    CompletableFuture<Void> touchOwner(UUID ownerUuid, String ownerName, long timestampEpochSecond);

    CompletableFuture<Void> saveRating(UUID shopId, Rating rating);

    /** Persists the latest total visit count and any newly observed unique visitors in one transaction. */
    CompletableFuture<Void> recordVisits(Shop shop, List<Visitor> newUniqueVisitors);

    default CompletableFuture<Void> recordVisit(Shop shop, Visitor visitor) {
        return recordVisits(shop, List.of(visitor));
    }

    CompletableFuture<Void> syncLabeledItems(Shop shop);

    CompletableFuture<Set<UUID>> loadFeatured();

    CompletableFuture<PlayerDiscoveryData> loadDiscovery(UUID playerUuid, int recentLimit);

    CompletableFuture<Void> setFavorite(UUID playerUuid, UUID shopId, boolean favorite, long timestampEpochSecond);

    CompletableFuture<Void> setFeatured(UUID shopId, boolean featured, long timestampEpochSecond);

    CompletableFuture<Void> recordRecentVisit(UUID playerUuid, UUID shopId, long timestampEpochSecond, int recentLimit);

    /** Returns a previously-computed snapshot and never touches SQLite. */
    StorageDiagnostics diagnostics();

    CompletableFuture<Void> delete(UUID shopId);

    CompletableFuture<Void> close();
}
