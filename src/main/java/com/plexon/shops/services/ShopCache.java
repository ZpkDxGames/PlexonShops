package com.plexon.shops.services;

import com.plexon.shops.models.Shop;
import com.plexon.shops.models.ShopStatus;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/** Thread-safe cache of immutable shop aggregates. */
public final class ShopCache {
    private static final Comparator<Shop> CREATED_ORDER = Comparator
            .comparingLong(Shop::createdAtEpochSecond)
            .thenComparing(Shop::id);

    private final ConcurrentMap<UUID, Shop> shops = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Set<UUID>> ownerIndex = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, List<UUID>> ownerCreatedOrder = new ConcurrentHashMap<>();
    private final AtomicInteger openShops = new AtomicInteger();

    public void replaceAll(Collection<Shop> loaded) {
        shops.clear();
        ownerIndex.clear();
        ownerCreatedOrder.clear();
        openShops.set(0);
        loaded.forEach(this::put);
    }

    public Optional<Shop> find(UUID id) {
        return Optional.ofNullable(shops.get(id));
    }

    /**
     * Returns a stable unsorted snapshot. Callers that only scan/filter must use this instead of {@link #all()}.
     */
    public List<Shop> unsortedSnapshot() {
        return List.copyOf(shops.values());
    }

    /** Created-order compatibility view for API/management callers that genuinely need stable ordering. */
    public List<Shop> all() {
        return shops.values().stream().sorted(CREATED_ORDER).toList();
    }

    /**
     * Returns this owner's shops in stable creation order. The ID order is cached and only invalidated by
     * create/delete/owner-transfer operations, so ordinary edits do not trigger another sort.
     */
    public List<Shop> ownedBy(UUID ownerUuid) {
        List<UUID> ids = ownerIdsInCreatedOrder(ownerUuid);
        if (ids.isEmpty()) {
            return List.of();
        }
        List<Shop> result = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            Shop shop = shops.get(id);
            if (shop != null) {
                result.add(shop);
            }
        }
        return List.copyOf(result);
    }

    /** O(1) owner capacity lookup without allocating or sorting an owned-shop list. */
    public int ownedCount(UUID ownerUuid) {
        Set<UUID> ids = ownerIndex.get(ownerUuid);
        return ids == null ? 0 : ids.size();
    }

    /** Uses the cached created-order IDs to answer primary/sub-shop rendering without rebuilding the owner list. */
    public boolean isPrimary(UUID ownerUuid, UUID shopId) {
        List<UUID> ids = ownerIdsInCreatedOrder(ownerUuid);
        return !ids.isEmpty() && ids.getFirst().equals(shopId);
    }

    public int ownerOrderCacheEntries() {
        return ownerCreatedOrder.size();
    }

    /** O(1) open-shop metric maintained with cache mutations. */
    public int openCount() {
        return openShops.get();
    }

    /** Allocation-free unsorted scan for infrequent derived metrics such as inactivity. */
    public long count(Predicate<Shop> predicate) {
        return shops.values().stream().filter(predicate).count();
    }

    public void put(Shop shop) {
        Shop previous = shops.put(shop.id(), shop);
        updateOpenCount(previous, shop);
        if (previous == null) {
            ownerIndex.computeIfAbsent(shop.ownerUuid(), ignored -> ConcurrentHashMap.newKeySet()).add(shop.id());
            invalidateOwnerOrder(shop.ownerUuid());
            return;
        }
        if (!previous.ownerUuid().equals(shop.ownerUuid())) {
            removeOwnerReference(previous.ownerUuid(), previous.id());
            ownerIndex.computeIfAbsent(shop.ownerUuid(), ignored -> ConcurrentHashMap.newKeySet()).add(shop.id());
            invalidateOwnerOrder(shop.ownerUuid());
        }
    }

    public boolean replace(Shop expected, Shop replacement) {
        boolean replaced = shops.replace(expected.id(), expected, replacement);
        if (!replaced) {
            return false;
        }
        updateOpenCount(expected, replacement);
        if (!expected.ownerUuid().equals(replacement.ownerUuid())) {
            removeOwnerReference(expected.ownerUuid(), expected.id());
            ownerIndex.computeIfAbsent(replacement.ownerUuid(), ignored -> ConcurrentHashMap.newKeySet())
                    .add(replacement.id());
            invalidateOwnerOrder(replacement.ownerUuid());
        }
        return true;
    }

    public Optional<Shop> remove(UUID id) {
        Shop removed = shops.remove(id);
        if (removed != null) {
            updateOpenCount(removed, null);
            removeOwnerReference(removed.ownerUuid(), id);
        }
        return Optional.ofNullable(removed);
    }

    public int size() {
        return shops.size();
    }

    private List<UUID> ownerIdsInCreatedOrder(UUID ownerUuid) {
        Set<UUID> ids = ownerIndex.get(ownerUuid);
        if (ids == null || ids.isEmpty()) {
            ownerCreatedOrder.remove(ownerUuid);
            return List.of();
        }
        return ownerCreatedOrder.computeIfAbsent(ownerUuid, ignored -> ids.stream()
                .map(shops::get)
                .filter(java.util.Objects::nonNull)
                .sorted(CREATED_ORDER)
                .map(Shop::id)
                .toList());
    }

    private void updateOpenCount(Shop previous, Shop replacement) {
        boolean wasOpen = previous != null && previous.status() == ShopStatus.OPEN;
        boolean isOpen = replacement != null && replacement.status() == ShopStatus.OPEN;
        if (wasOpen == isOpen) {
            return;
        }
        if (isOpen) {
            openShops.incrementAndGet();
        } else {
            openShops.decrementAndGet();
        }
    }

    private void removeOwnerReference(UUID ownerUuid, UUID shopId) {
        ownerIndex.computeIfPresent(ownerUuid, (ignored, ids) -> {
            ids.remove(shopId);
            return ids.isEmpty() ? null : ids;
        });
        invalidateOwnerOrder(ownerUuid);
    }

    private void invalidateOwnerOrder(UUID ownerUuid) {
        ownerCreatedOrder.remove(ownerUuid);
    }
}
