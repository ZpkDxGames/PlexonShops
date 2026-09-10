package com.plexon.shops.services;

import com.plexon.shops.models.DirectoryFilter;
import com.plexon.shops.models.Shop;
import com.plexon.shops.storage.PlayerDiscoveryData;
import com.plexon.shops.storage.ShopRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Bounded cache and asynchronous persistence facade for discovery metadata. */
public final class DiscoveryService {
    public static final int RECENT_LIMIT = 20;
    public static final int PLAYER_CACHE_LIMIT = 512;

    private final ShopRepository repository;
    private final ShopService shops;
    private final Logger logger;
    private final Object lock = new Object();
    private final LinkedHashMap<UUID, PlayerDiscoveryData> playerCache = new LinkedHashMap<>(32, 0.75F, true);
    private final Map<UUID, CompletableFuture<Void>> loads = new HashMap<>();
    private volatile Set<UUID> featured = Set.of();

    public DiscoveryService(ShopRepository repository, ShopService shops, Logger logger) {
        this.repository = repository;
        this.shops = shops;
        this.logger = logger;
    }

    public CompletableFuture<Void> initialize() {
        return repository.loadFeatured().thenAccept(ids -> featured = Set.copyOf(ids));
    }

    /** Lazy-loads one player's bounded discovery state. Concurrent requests share the same future. */
    public CompletableFuture<Void> preload(UUID playerUuid) {
        synchronized (lock) {
            if (playerCache.containsKey(playerUuid)) {
                playerCache.get(playerUuid); // refresh access order
                return CompletableFuture.completedFuture(null);
            }
            CompletableFuture<Void> active = loads.get(playerUuid);
            if (active != null) {
                return active;
            }
            CompletableFuture<Void> load = repository.loadDiscovery(playerUuid, RECENT_LIMIT)
                    .thenAccept(data -> {
                        synchronized (lock) {
                            playerCache.put(playerUuid, data);
                            trimPlayerCacheLocked();
                        }
                    });
            loads.put(playerUuid, load);
            load.whenComplete((ignored, error) -> {
                synchronized (lock) {
                    if (loads.get(playerUuid) == load) {
                        loads.remove(playerUuid);
                    }
                }
                if (error != null) {
                    logger.log(Level.WARNING, "Could not load discovery state for " + playerUuid, error);
                }
            });
            return load;
        }
    }

    public boolean isLoaded(UUID playerUuid) {
        synchronized (lock) {
            return playerCache.containsKey(playerUuid);
        }
    }

    public int playerCacheEntries() {
        synchronized (lock) {
            return playerCache.size();
        }
    }

    public boolean isFeatured(UUID shopId) {
        return featured.contains(shopId);
    }

    public Set<UUID> featuredIds() {
        return featured;
    }

    public List<Shop> featuredShops() {
        Set<UUID> snapshot = featured;
        return shops.directory(DirectoryFilter.ALL).stream()
                .filter(shop -> snapshot.contains(shop.id()))
                .toList();
    }

    public boolean isFavorite(UUID playerUuid, UUID shopId) {
        synchronized (lock) {
            PlayerDiscoveryData data = playerCache.get(playerUuid);
            return data != null && data.favorites().contains(shopId);
        }
    }

    public int favoriteCount(UUID playerUuid) {
        synchronized (lock) {
            PlayerDiscoveryData data = playerCache.get(playerUuid);
            return data == null ? 0 : data.favorites().size();
        }
    }

    public List<Shop> favorites(UUID playerUuid) {
        Set<UUID> ids;
        synchronized (lock) {
            PlayerDiscoveryData data = playerCache.get(playerUuid);
            ids = data == null ? Set.of() : data.favorites();
        }
        if (ids.isEmpty()) {
            return List.of();
        }
        return shops.directory(DirectoryFilter.ALL).stream()
                .filter(shop -> ids.contains(shop.id()))
                .toList();
    }

    public List<Shop> recent(UUID playerUuid) {
        List<UUID> ids;
        synchronized (lock) {
            PlayerDiscoveryData data = playerCache.get(playerUuid);
            ids = data == null ? List.of() : data.recentShopIds();
        }
        List<Shop> result = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            shops.find(id).ifPresent(result::add);
        }
        return List.copyOf(result);
    }

    public CompletableFuture<Boolean> toggleFavorite(UUID playerUuid, UUID shopId) {
        return preload(playerUuid).thenCompose(ignored -> {
            final boolean favorite;
            synchronized (lock) {
                PlayerDiscoveryData current = playerCache.getOrDefault(playerUuid, PlayerDiscoveryData.empty());
                favorite = !current.favorites().contains(shopId);
            }
            long now = Instant.now().getEpochSecond();
            return repository.setFavorite(playerUuid, shopId, favorite, now).thenApply(nothing -> {
                synchronized (lock) {
                    PlayerDiscoveryData current = playerCache.getOrDefault(playerUuid, PlayerDiscoveryData.empty());
                    Set<UUID> next = new HashSet<>(current.favorites());
                    if (favorite) {
                        next.add(shopId);
                    } else {
                        next.remove(shopId);
                    }
                    playerCache.put(playerUuid, new PlayerDiscoveryData(next, current.recentShopIds()));
                    trimPlayerCacheLocked();
                }
                return favorite;
            });
        });
    }

    public CompletableFuture<Boolean> setFeatured(UUID shopId, boolean value) {
        long now = Instant.now().getEpochSecond();
        return repository.setFeatured(shopId, value, now).thenApply(ignored -> {
            Set<UUID> next = new HashSet<>(featured);
            if (value) {
                next.add(shopId);
            } else {
                next.remove(shopId);
            }
            featured = Set.copyOf(next);
            return value;
        });
    }

    /** Called only after a successful teleport. Persistence remains bounded and asynchronous. */
    public CompletableFuture<Void> recordVisit(UUID playerUuid, UUID shopId) {
        long now = Instant.now().getEpochSecond();
        return repository.recordRecentVisit(playerUuid, shopId, now, RECENT_LIMIT).thenRun(() -> {
            synchronized (lock) {
                PlayerDiscoveryData current = playerCache.get(playerUuid);
                if (current == null) {
                    return;
                }
                List<UUID> recent = new ArrayList<>(current.recentShopIds());
                recent.remove(shopId);
                recent.addFirst(shopId);
                if (recent.size() > RECENT_LIMIT) {
                    recent = new ArrayList<>(recent.subList(0, RECENT_LIMIT));
                }
                playerCache.put(playerUuid, new PlayerDiscoveryData(current.favorites(), recent));
            }
        });
    }

    public void onShopDeleted(UUID shopId) {
        Set<UUID> nextFeatured = new HashSet<>(featured);
        nextFeatured.remove(shopId);
        featured = Set.copyOf(nextFeatured);
        synchronized (lock) {
            for (Map.Entry<UUID, PlayerDiscoveryData> entry : playerCache.entrySet()) {
                PlayerDiscoveryData current = entry.getValue();
                if (!current.favorites().contains(shopId) && !current.recentShopIds().contains(shopId)) {
                    continue;
                }
                Set<UUID> favorites = new HashSet<>(current.favorites());
                favorites.remove(shopId);
                List<UUID> recent = current.recentShopIds().stream().filter(id -> !id.equals(shopId)).toList();
                entry.setValue(new PlayerDiscoveryData(favorites, recent));
            }
        }
    }

    private void trimPlayerCacheLocked() {
        while (playerCache.size() > PLAYER_CACHE_LIMIT) {
            UUID eldest = playerCache.keySet().iterator().next();
            playerCache.remove(eldest);
        }
    }
}
