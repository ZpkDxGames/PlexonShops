package com.plexon.shops.storage;

import com.plexon.shops.models.Rating;
import com.plexon.shops.models.Shop;
import com.plexon.shops.models.Visitor;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Asynchronous persistence contract. No method may perform caller-thread I/O. */
public interface ShopRepository {
    CompletableFuture<Void> initialize();

    CompletableFuture<List<Shop>> loadAll();

    CompletableFuture<Void> save(Shop shop);

    CompletableFuture<Void> saveCore(Shop shop);

    /** Updates activity metadata for all shops owned by one player in a single storage operation. */
    CompletableFuture<Void> touchOwner(UUID ownerUuid, String ownerName, long timestampEpochSecond);

    CompletableFuture<Void> saveRating(UUID shopId, Rating rating);

    /**
     * Persists the latest total visit count and any newly observed unique visitors in one transaction.
     */
    CompletableFuture<Void> recordVisits(Shop shop, List<Visitor> newUniqueVisitors);

    default CompletableFuture<Void> recordVisit(Shop shop, Visitor visitor) {
        return recordVisits(shop, List.of(visitor));
    }

    CompletableFuture<Void> syncLabeledItems(Shop shop);

    CompletableFuture<Void> delete(UUID shopId);

    CompletableFuture<Void> close();
}
