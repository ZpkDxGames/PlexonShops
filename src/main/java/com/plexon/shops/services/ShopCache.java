package com.plexon.shops.services;

import com.plexon.shops.models.Shop;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Thread-safe cache of immutable shop aggregates. */
public final class ShopCache {
    private static final Comparator<Shop> CREATED_ORDER = Comparator
            .comparingLong(Shop::createdAtEpochSecond)
            .thenComparing(Shop::id);

    private final ConcurrentMap<UUID, Shop> shops = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Set<UUID>> ownerIndex = new ConcurrentHashMap<>();

    public void replaceAll(Collection<Shop> loaded) {
        shops.clear();
        ownerIndex.clear();
        loaded.forEach(this::put);
    }

    public Optional<Shop> find(UUID id) {
        return Optional.ofNullable(shops.get(id));
    }

    public List<Shop> all() {
        return shops.values().stream().sorted(CREATED_ORDER).toList();
    }

    public List<Shop> ownedBy(UUID ownerUuid) {
        Set<UUID> ids = ownerIndex.get(ownerUuid);
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<Shop> result = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            Shop shop = shops.get(id);
            if (shop != null) {
                result.add(shop);
            }
        }
        result.sort(CREATED_ORDER);
        return List.copyOf(result);
    }

    public void put(Shop shop) {
        Shop previous = shops.put(shop.id(), shop);
        if (previous != null && !previous.ownerUuid().equals(shop.ownerUuid())) {
            removeOwnerReference(previous.ownerUuid(), previous.id());
        }
        ownerIndex.computeIfAbsent(shop.ownerUuid(), ignored -> ConcurrentHashMap.newKeySet()).add(shop.id());
    }

    public boolean replace(Shop expected, Shop replacement) {
        boolean replaced = shops.replace(expected.id(), expected, replacement);
        if (replaced && !expected.ownerUuid().equals(replacement.ownerUuid())) {
            removeOwnerReference(expected.ownerUuid(), expected.id());
            ownerIndex.computeIfAbsent(replacement.ownerUuid(), ignored -> ConcurrentHashMap.newKeySet())
                    .add(replacement.id());
        }
        return replaced;
    }

    public Optional<Shop> remove(UUID id) {
        Shop removed = shops.remove(id);
        if (removed != null) {
            removeOwnerReference(removed.ownerUuid(), id);
        }
        return Optional.ofNullable(removed);
    }

    public int size() {
        return shops.size();
    }

    private void removeOwnerReference(UUID ownerUuid, UUID shopId) {
        ownerIndex.computeIfPresent(ownerUuid, (ignored, ids) -> {
            ids.remove(shopId);
            return ids.isEmpty() ? null : ids;
        });
    }
}
