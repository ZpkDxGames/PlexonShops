package com.plexon.shops.services;

import com.plexon.shops.config.PluginConfig;
import com.plexon.shops.event.ShopEventPublisher;
import com.plexon.shops.models.Category;
import com.plexon.shops.models.DirectoryFilter;
import com.plexon.shops.models.LabeledItem;
import com.plexon.shops.models.Shop;
import com.plexon.shops.models.ShopLocation;
import com.plexon.shops.models.ShopStatus;
import com.plexon.shops.models.Visitor;
import com.plexon.shops.models.VisitorStats;
import com.plexon.shops.storage.ShopRepository;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Domain operations with ordered asynchronous durability and post-commit public events. */
public final class ShopService {
    private static final Comparator<Shop> DIRECTORY_ORDER = Comparator
            .comparing((Shop shop) -> shop.status() == ShopStatus.OPEN ? 0 : 1)
            .thenComparing(Comparator.comparingDouble(Shop::averageRating).reversed())
            .thenComparing(Shop::name, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(Shop::id);
    private static final long DIRECTORY_SNAPSHOT_TTL_SECONDS = 60L;

    private final ShopCache cache;
    private final ShopRepository repository;
    private final Supplier<PluginConfig> config;
    private final Logger logger;
    private final ShopEventPublisher events;
    private final Object mutationLock = new Object();
    private final Map<UUID, CompletableFuture<Void>> mutationChains = new HashMap<>();
    private final Object creationLock = new Object();
    private final Set<UUID> ownersCreating = new HashSet<>();
    private final Object directoryLock = new Object();
    private final Map<DirectoryFilter, DirectorySnapshot> directorySnapshots = new EnumMap<>(DirectoryFilter.class);
    private final Object visitLock = new Object();
    private final Map<UUID, VisitAccumulator> pendingVisitPersistence = new HashMap<>();
    private final Map<UUID, CompletableFuture<Void>> activeVisitFlushes = new HashMap<>();
    private long directoryGeneration;
    private long completedVisitFlushes;
    private long failedVisitFlushes;

    public ShopService(
            ShopCache cache,
            ShopRepository repository,
            Supplier<PluginConfig> config,
            Logger logger,
            ShopEventPublisher events
    ) {
        this.cache = cache;
        this.repository = repository;
        this.config = config;
        this.logger = logger;
        this.events = events;
    }

    public void load(List<Shop> shops) {
        synchronized (directoryLock) {
            cache.replaceAll(shops);
            invalidateDirectoryLocked();
        }
    }

    public Optional<Shop> find(UUID shopId) {
        return cache.find(shopId);
    }

    public List<Shop> ownedBy(UUID ownerUuid) {
        return cache.ownedBy(ownerUuid);
    }

    public int ownedCount(UUID ownerUuid) {
        return cache.ownedCount(ownerUuid);
    }

    /**
     * Compatibility directory view. GUI callers should prefer {@link #directoryPage(DirectoryFilter, int, int)}
     * so page navigation never rebuilds or materializes the entire marketplace.
     */
    public List<Shop> directory(DirectoryFilter filter) {
        synchronized (directoryLock) {
            DirectorySnapshot snapshot = directorySnapshotLocked(filter);
            List<Shop> result = new ArrayList<>(snapshot.shopIds().size());
            for (UUID shopId : snapshot.shopIds()) {
                cache.find(shopId).ifPresent(result::add);
            }
            return List.copyOf(result);
        }
    }

    /** Returns only the directory page that a GUI will render. */
    public DirectoryPage directoryPage(DirectoryFilter filter, int requestedPage, int pageSize) {
        int safePageSize = Math.max(1, pageSize);
        synchronized (directoryLock) {
            DirectorySnapshot snapshot = directorySnapshotLocked(filter);
            int total = snapshot.shopIds().size();
            int pages = Math.max(1, (total + safePageSize - 1) / safePageSize);
            int page = Math.clamp(requestedPage, 0, pages - 1);
            int from = Math.min(total, page * safePageSize);
            int to = Math.min(total, from + safePageSize);
            List<Shop> visible = new ArrayList<>(to - from);
            for (int index = from; index < to; index++) {
                cache.find(snapshot.shopIds().get(index)).ifPresent(visible::add);
            }
            return new DirectoryPage(page, pages, total, List.copyOf(visible));
        }
    }

    public long inactiveCount() {
        int purgeDays = config.get().inactivityPurgeDays();
        if (purgeDays <= 0) {
            return 0L;
        }
        long cutoff = Instant.now().minus(purgeDays, ChronoUnit.DAYS).getEpochSecond();
        return cache.count(shop -> !shop.isRecentlyActive(cutoff));
    }

    public int totalCount() {
        return cache.size();
    }

    public int openCount() {
        return cache.openCount();
    }

    public long directoryGeneration() {
        synchronized (directoryLock) {
            return directoryGeneration;
        }
    }

    public int directoryCacheEntries() {
        synchronized (directoryLock) {
            return directorySnapshots.size();
        }
    }

    public int ownerOrderCacheEntries() {
        return cache.ownerOrderCacheEntries();
    }

    public int activeMutationChains() {
        synchronized (mutationLock) {
            return mutationChains.size();
        }
    }

    public int ownerCreationsInFlight() {
        synchronized (creationLock) {
            return ownersCreating.size();
        }
    }

    public VisitPersistenceMetrics visitPersistenceMetrics() {
        synchronized (visitLock) {
            int pendingUniqueVisitors = pendingVisitPersistence.values().stream()
                    .mapToInt(VisitAccumulator::uniqueVisitorCount)
                    .sum();
            return new VisitPersistenceMetrics(
                    pendingVisitPersistence.size(),
                    pendingUniqueVisitors,
                    activeVisitFlushes.size(),
                    completedVisitFlushes,
                    failedVisitFlushes
            );
        }
    }

    public int shopLimit(Player player) {
        PluginConfig.Limits limits = config.get().limits();
        if (player.hasPermission("plexonshops.subshops.unlimited")
                || player.hasPermission("plexonshops.limit.unlimited")) {
            return Integer.MAX_VALUE;
        }
        int legacyTotal = PermissionLimitResolver.resolve(
                candidate -> player.hasPermission("plexonshops.limit." + candidate),
                limits.maxShopsPerPlayer(),
                limits.maximumPermissionLimit()
        );
        int additional = PermissionLimitResolver.resolve(
                candidate -> player.hasPermission("plexonshops.subshops." + candidate),
                0,
                limits.maximumPermissionLimit()
        );
        return Math.max(legacyTotal, Math.addExact(1, additional));
    }

    public ShopCapacity capacity(Player player) {
        int owned = cache.ownedCount(player.getUniqueId());
        int totalLimit = shopLimit(player);
        int subShops = Math.max(0, owned - 1);
        int subShopLimit = totalLimit == Integer.MAX_VALUE ? Integer.MAX_VALUE : Math.max(0, totalLimit - 1);
        return new ShopCapacity(owned, totalLimit, subShops, subShopLimit);
    }

    public CompletableFuture<Shop> create(UUID ownerUuid, String ownerName, ShopLocation location) {
        PluginConfig.Defaults defaults = config.get().defaults();
        long now = Instant.now().getEpochSecond();
        Shop shop = new Shop(
                UUID.randomUUID(),
                ownerUuid,
                ownerName,
                ownerName + "'s Shop",
                location,
                Set.of(defaults.category()),
                defaults.status(),
                Map.of(),
                VisitorStats.empty(),
                "",
                defaults.description(),
                List.of(),
                defaults.teleportFee(),
                now,
                now,
                now
        );

        synchronized (creationLock) {
            if (!ownersCreating.add(ownerUuid)) {
                return CompletableFuture.failedFuture(new IllegalStateException("Shop creation already in progress"));
            }
        }

        CompletableFuture<Shop> result;
        try {
            result = repository.save(shop).thenApply(ignored -> {
                putDirectoryShop(shop);
                events.publishCreated(ownerUuid, shop);
                return shop;
            });
        } catch (RuntimeException error) {
            releaseOwnerCreation(ownerUuid);
            return CompletableFuture.failedFuture(error);
        }
        return result.whenComplete((ignored, error) -> releaseOwnerCreation(ownerUuid));
    }

    public CompletableFuture<Shop> setStatus(UUID shopId, ShopStatus status) {
        return mutateCore(shopId, shop -> shop.withStatus(status, now()));
    }

    public CompletableFuture<Shop> setCategories(UUID shopId, Set<Category> categories) {
        int maximum = config.get().limits().maxCategoriesPerShop();
        if (categories.isEmpty() || categories.size() > maximum) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid category count"));
        }
        return mutateCore(shopId, shop -> shop.withCategories(categories, now()));
    }

    public CompletableFuture<Shop> setDisplayIcon(UUID shopId, String encodedItem) {
        return mutateCore(shopId, shop -> shop.withDisplayIcon(encodedItem, now()));
    }

    public CompletableFuture<Shop> rename(UUID shopId, String name) {
        return mutateCore(shopId, shop -> shop.withName(name, now()));
    }

    public CompletableFuture<Shop> setDescription(UUID shopId, List<String> description) {
        return mutateCore(shopId, shop -> shop.withDescription(description, now()));
    }

    public CompletableFuture<Shop> setLocation(UUID shopId, ShopLocation location) {
        return mutateCore(shopId, shop -> shop.withLocation(location, now()));
    }

    public CompletableFuture<Shop> setTeleportFee(UUID shopId, double fee) {
        return mutateCore(shopId, shop -> shop.withTeleportFee(fee, now()));
    }

    public CompletableFuture<Shop> addLabeledItem(UUID shopId, LabeledItem item) {
        int maximum = config.get().limits().maxLabeledItemsPerShop();
        return mutate(shopId, shop -> {
            if (shop.labeledItems().size() >= maximum) {
                throw new IllegalStateException("Labeled item limit reached");
            }
            return shop.withLabeledItem(item, now());
        }, repository::syncLabeledItems);
    }

    public CompletableFuture<Shop> removeLabeledItem(UUID shopId, UUID itemId) {
        return mutate(shopId, shop -> shop.withoutLabeledItem(itemId, now()), repository::syncLabeledItems);
    }

    public CompletableFuture<Shop> rate(UUID shopId, UUID playerUuid, int stars) {
        if (stars < 1 || stars > 5) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Stars must be between 1 and 5"));
        }
        synchronized (mutationLock) {
            CompletableFuture<Void> previous = mutationChains.getOrDefault(shopId, CompletableFuture.completedFuture(null));
            CompletableFuture<Shop> result = previous.thenCompose(ignored -> {
                Shop current = cache.find(shopId).orElse(null);
                if (current == null) {
                    return CompletableFuture.failedFuture(new IllegalArgumentException("Shop does not exist"));
                }
                if (current.ownerUuid().equals(playerUuid)) {
                    return CompletableFuture.failedFuture(new IllegalStateException("Owners cannot rate their own shop"));
                }
                int previousRating = current.ratingFrom(playerUuid);
                if (previousRating == stars) {
                    return CompletableFuture.completedFuture(current);
                }
                Shop updated = current.withRating(playerUuid, stars, now());
                return repository.saveRating(updated.id(), updated.ratings().get(playerUuid)).thenApply(nothing -> {
                    putDirectoryShop(updated);
                    events.publishRated(playerUuid, updated, previousRating);
                    return updated;
                });
            });
            trackTail(shopId, result);
            return result;
        }
    }

    /**
     * Applies the authoritative visit to memory and publishes the public event once, while persistence is
     * coalesced independently per shop. Busy traffic therefore cannot enqueue one SQLite transaction per teleport.
     */
    public CompletableFuture<Shop> recordVisit(UUID shopId, UUID playerUuid) {
        synchronized (mutationLock) {
            CompletableFuture<Void> previous = mutationChains.getOrDefault(shopId, CompletableFuture.completedFuture(null));
            CompletableFuture<Shop> result = previous.thenApply(ignored -> {
                Shop current = cache.find(shopId).orElse(null);
                if (current == null) {
                    throw new IllegalArgumentException("Shop does not exist");
                }
                boolean uniqueVisitor = !current.visitors().uniqueVisitors().containsKey(playerUuid);
                Shop updated = current.withVisit(playerUuid, now());
                cache.put(updated);
                Visitor visitor = uniqueVisitor ? updated.visitors().uniqueVisitors().get(playerUuid) : null;
                enqueueVisitPersistence(updated, visitor);
                events.publishVisited(playerUuid, updated, uniqueVisitor);
                return updated;
            });
            trackTail(shopId, result);
            return result;
        }
    }

    /**
     * Coalesces owner activity into one indexed SQL update while preserving per-shop mutation order.
     */
    public void touchOwner(UUID ownerUuid, String ownerName) {
        List<Shop> owned = cache.ownedBy(ownerUuid);
        if (owned.isEmpty()) {
            return;
        }
        long timestamp = now();
        List<UUID> shopIds = owned.stream().map(Shop::id).toList();

        synchronized (mutationLock) {
            CompletableFuture<?>[] predecessors = shopIds.stream()
                    .map(shopId -> mutationChains.getOrDefault(shopId, CompletableFuture.completedFuture(null)))
                    .toArray(CompletableFuture[]::new);
            CompletableFuture<Void> batch = CompletableFuture.allOf(predecessors)
                    .thenCompose(ignored -> repository.touchOwner(ownerUuid, ownerName, timestamp))
                    .thenRun(() -> applyOwnerTouch(ownerUuid, ownerName, timestamp));
            CompletableFuture<Void> tail = batch.handle((ignored, error) -> null);
            for (UUID shopId : shopIds) {
                mutationChains.put(shopId, tail);
            }
            tail.whenComplete((ignored, error) -> clearSharedTail(shopIds, tail));
            batch.exceptionally(error -> {
                logger.log(Level.WARNING, "Could not update shop owner activity for " + ownerUuid, error);
                return null;
            });
        }
    }

    public CompletableFuture<Void> flushVisitAnalytics() {
        List<CompletableFuture<Void>> active;
        synchronized (visitLock) {
            for (UUID shopId : List.copyOf(pendingVisitPersistence.keySet())) {
                startVisitFlushLocked(shopId);
            }
            if (activeVisitFlushes.isEmpty()) {
                return CompletableFuture.completedFuture(null);
            }
            active = List.copyOf(activeVisitFlushes.values());
        }
        return CompletableFuture.allOf(active.toArray(CompletableFuture[]::new))
                .thenCompose(ignored -> flushVisitAnalytics());
    }

    public CompletableFuture<Shop> delete(UUID shopId) {
        synchronized (mutationLock) {
            CompletableFuture<Void> previous = mutationChains.getOrDefault(shopId, CompletableFuture.completedFuture(null));
            CompletableFuture<Shop> result = previous.thenCompose(ignored -> {
                Shop existing = find(shopId).orElse(null);
                if (existing == null) {
                    return CompletableFuture.failedFuture(new IllegalArgumentException("Shop does not exist"));
                }
                return flushVisitAnalytics(shopId)
                        .exceptionally(error -> {
                            logger.log(Level.WARNING, "Discarding unflushed visit analytics before deleting shop " + shopId, error);
                            clearPendingVisitAnalytics(shopId);
                            return null;
                        })
                        .thenCompose(nothing -> repository.delete(shopId))
                        .thenApply(nothing -> {
                            clearPendingVisitAnalytics(shopId);
                            removeDirectoryShop(shopId);
                            return existing;
                        });
            });
            trackTail(shopId, result);
            return result;
        }
    }

    private CompletableFuture<Shop> mutateCore(UUID shopId, UnaryOperator<Shop> mutation) {
        return mutate(shopId, mutation, repository::saveCore);
    }

    private CompletableFuture<Shop> mutate(
            UUID shopId,
            UnaryOperator<Shop> mutation,
            Function<Shop, CompletableFuture<Void>> persistence
    ) {
        synchronized (mutationLock) {
            CompletableFuture<Void> previous = mutationChains.getOrDefault(shopId, CompletableFuture.completedFuture(null));
            CompletableFuture<Shop> result = previous.thenCompose(ignored -> {
                Shop current = cache.find(shopId).orElse(null);
                if (current == null) {
                    return CompletableFuture.failedFuture(new IllegalArgumentException("Shop does not exist"));
                }
                Shop updated;
                try {
                    updated = mutation.apply(current);
                } catch (RuntimeException error) {
                    return CompletableFuture.failedFuture(error);
                }
                return persistence.apply(updated).thenApply(nothing -> {
                    if (directoryRelevantChanged(current, updated)) {
                        putDirectoryShop(updated);
                    } else {
                        cache.put(updated);
                    }
                    return updated;
                });
            });
            trackTail(shopId, result);
            return result;
        }
    }

    private DirectorySnapshot directorySnapshotLocked(DirectoryFilter filter) {
        PluginConfig settings = config.get();
        int purgeDays = settings.inactivityPurgeDays();
        long now = Instant.now().getEpochSecond();
        DirectorySnapshot cached = directorySnapshots.get(filter);
        if (cached != null
                && cached.generation() == directoryGeneration
                && cached.purgeDays() == purgeDays
                && now < cached.expiresAtEpochSecond()) {
            return cached;
        }

        long cutoff = purgeDays <= 0
                ? 0L
                : Instant.ofEpochSecond(now).minus(purgeDays, ChronoUnit.DAYS).getEpochSecond();
        List<UUID> sortedIds = cache.unsortedSnapshot().stream()
                .filter(shop -> shop.isRecentlyActive(cutoff))
                .filter(filter::matches)
                .sorted(DIRECTORY_ORDER)
                .map(Shop::id)
                .toList();
        long expiresAt = purgeDays <= 0 ? Long.MAX_VALUE : now + DIRECTORY_SNAPSHOT_TTL_SECONDS;
        DirectorySnapshot rebuilt = new DirectorySnapshot(directoryGeneration, purgeDays, expiresAt, sortedIds);
        directorySnapshots.put(filter, rebuilt);
        return rebuilt;
    }

    private boolean directoryRelevantChanged(Shop previous, Shop updated) {
        return previous.status() != updated.status()
                || !previous.name().equals(updated.name())
                || !previous.categories().equals(updated.categories())
                || Double.compare(previous.averageRating(), updated.averageRating()) != 0
                || previous.labeledItems().isEmpty() != updated.labeledItems().isEmpty()
                || previous.lastOwnerSeenEpochSecond() != updated.lastOwnerSeenEpochSecond();
    }

    private void applyOwnerTouch(UUID ownerUuid, String ownerName, long timestamp) {
        synchronized (directoryLock) {
            List<Shop> currentOwned = cache.ownedBy(ownerUuid);
            if (currentOwned.isEmpty()) {
                return;
            }
            for (Shop current : currentOwned) {
                cache.put(current.withOwnerSeen(ownerName, timestamp));
            }
            invalidateDirectoryLocked();
        }
    }

    private void enqueueVisitPersistence(Shop shop, Visitor uniqueVisitor) {
        synchronized (visitLock) {
            pendingVisitPersistence.computeIfAbsent(shop.id(), ignored -> new VisitAccumulator())
                    .merge(shop, uniqueVisitor);
            if (!activeVisitFlushes.containsKey(shop.id())) {
                startVisitFlushLocked(shop.id());
            }
        }
    }

    private void startVisitFlushLocked(UUID shopId) {
        if (activeVisitFlushes.containsKey(shopId)) {
            return;
        }
        VisitAccumulator accumulator = pendingVisitPersistence.remove(shopId);
        if (accumulator == null || accumulator.latestShop == null) {
            return;
        }
        VisitBatch batch = accumulator.toBatch();
        CompletableFuture<Void> flush = repository.recordVisits(batch.shop(), batch.uniqueVisitors());
        activeVisitFlushes.put(shopId, flush);
        flush.whenComplete((ignored, error) -> completeVisitFlush(shopId, batch, flush, error));
    }

    private void completeVisitFlush(
            UUID shopId,
            VisitBatch batch,
            CompletableFuture<Void> flush,
            Throwable error
    ) {
        boolean startNext = false;
        synchronized (visitLock) {
            if (activeVisitFlushes.get(shopId) != flush) {
                return;
            }
            activeVisitFlushes.remove(shopId);
            if (error == null) {
                completedVisitFlushes++;
                startNext = pendingVisitPersistence.containsKey(shopId);
            } else {
                failedVisitFlushes++;
                pendingVisitPersistence.computeIfAbsent(shopId, ignored -> new VisitAccumulator()).merge(batch);
            }
            if (startNext) {
                startVisitFlushLocked(shopId);
            }
        }
        if (error != null) {
            logger.log(Level.WARNING, "Could not flush visitor analytics for shop " + shopId, error);
        }
    }

    private CompletableFuture<Void> flushVisitAnalytics(UUID shopId) {
        CompletableFuture<Void> active;
        synchronized (visitLock) {
            if (!activeVisitFlushes.containsKey(shopId)) {
                startVisitFlushLocked(shopId);
            }
            active = activeVisitFlushes.get(shopId);
            if (active == null) {
                return CompletableFuture.completedFuture(null);
            }
        }
        return active.thenCompose(ignored -> flushVisitAnalytics(shopId));
    }

    private void clearPendingVisitAnalytics(UUID shopId) {
        synchronized (visitLock) {
            pendingVisitPersistence.remove(shopId);
        }
    }

    private void putDirectoryShop(Shop shop) {
        synchronized (directoryLock) {
            cache.put(shop);
            invalidateDirectoryLocked();
        }
    }

    private void removeDirectoryShop(UUID shopId) {
        synchronized (directoryLock) {
            cache.remove(shopId);
            invalidateDirectoryLocked();
        }
    }

    private void invalidateDirectoryLocked() {
        directoryGeneration++;
        directorySnapshots.clear();
    }

    private void releaseOwnerCreation(UUID ownerUuid) {
        synchronized (creationLock) {
            ownersCreating.remove(ownerUuid);
        }
    }

    private void clearSharedTail(List<UUID> shopIds, CompletableFuture<Void> tail) {
        synchronized (mutationLock) {
            for (UUID shopId : shopIds) {
                if (mutationChains.get(shopId) == tail) {
                    mutationChains.remove(shopId);
                }
            }
        }
    }

    private void trackTail(UUID shopId, CompletableFuture<Shop> result) {
        CompletableFuture<Void> tail = result.handle((ignored, error) -> null);
        mutationChains.put(shopId, tail);
        tail.whenComplete((ignored, error) -> {
            synchronized (mutationLock) {
                if (mutationChains.get(shopId) == tail) {
                    mutationChains.remove(shopId);
                }
            }
        });
    }

    private long now() {
        return Instant.now().getEpochSecond();
    }

    public record DirectoryPage(int page, int pages, int total, List<Shop> items) {
    }

    public record VisitPersistenceMetrics(
            int pendingShops,
            int pendingUniqueVisitors,
            int activeFlushes,
            long completedFlushes,
            long failedFlushes
    ) {
    }

    private record DirectorySnapshot(
            long generation,
            int purgeDays,
            long expiresAtEpochSecond,
            List<UUID> shopIds
    ) {
    }

    private record VisitBatch(Shop shop, List<Visitor> uniqueVisitors) {
    }

    private static final class VisitAccumulator {
        private Shop latestShop;
        private final Map<UUID, Visitor> uniqueVisitors = new LinkedHashMap<>();

        private void merge(Shop shop, Visitor uniqueVisitor) {
            if (latestShop == null
                    || shop.visitors().totalVisits() >= latestShop.visitors().totalVisits()) {
                latestShop = shop;
            }
            if (uniqueVisitor != null) {
                uniqueVisitors.putIfAbsent(uniqueVisitor.playerUuid(), uniqueVisitor);
            }
        }

        private void merge(VisitBatch batch) {
            merge(batch.shop(), null);
            for (Visitor visitor : batch.uniqueVisitors()) {
                uniqueVisitors.putIfAbsent(visitor.playerUuid(), visitor);
            }
        }

        private int uniqueVisitorCount() {
            return uniqueVisitors.size();
        }

        private VisitBatch toBatch() {
            return new VisitBatch(latestShop, List.copyOf(uniqueVisitors.values()));
        }
    }

    public record ShopCapacity(int owned, int totalLimit, int subShops, int subShopLimit) {
        public boolean atLimit() {
            return totalLimit != Integer.MAX_VALUE && owned >= totalLimit;
        }

        public boolean nextIsSubShop() {
            return owned > 0;
        }
    }
}
