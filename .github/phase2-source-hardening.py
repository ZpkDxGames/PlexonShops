from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    target = Path(path)
    text = target.read_text(encoding="utf-8")
    if text.count(old) != 1:
        raise SystemExit(f"Expected exactly one patch anchor in {path}: {old[:100]!r}; found {text.count(old)}")
    target.write_text(text.replace(old, new, 1), encoding="utf-8")


# 1) Future schema versions must fail closed before backup, Hikari, DDL, or marker writes.
replace_once(
    "src/main/java/com/plexon/shops/storage/SqliteShopRepository.java",
    """                previousVersion = preflightSchemaVersion();
                if (existingDatabase && previousVersion < SCHEMA_VERSION) {
""",
    """                previousVersion = preflightSchemaVersion();
                if (previousVersion > SCHEMA_VERSION) {
                    throw new StorageException(
                            "Database schema v" + previousVersion + " is newer than supported v" + SCHEMA_VERSION
                                    + "; refusing to start or rewrite schema metadata",
                            null
                    );
                }
                if (existingDatabase && previousVersion < SCHEMA_VERSION) {
""",
)

# 2) Teleport lifecycle: reject duplicate warmup/in-flight requests and bind callbacks to attempt identity.
replace_once(
    "src/main/java/com/plexon/shops/services/TeleportService.java",
    """    private final Map<UUID, PendingTeleport> pending = new ConcurrentHashMap<>();
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
""",
    """    private final Map<UUID, PendingTeleport> pending = new ConcurrentHashMap<>();
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    private final TeleportAttemptRegistry<InFlightTeleport> inFlight = new TeleportAttemptRegistry<>();
""",
)
replace_once(
    "src/main/java/com/plexon/shops/services/TeleportService.java",
    """    public int pendingCount() {
        return pending.size();
    }
""",
    """    public int pendingCount() {
        return pending.size() + inFlight.size();
    }

    public int inFlightCount() {
        return inFlight.size();
    }
""",
)
replace_once(
    "src/main/java/com/plexon/shops/services/TeleportService.java",
    """        if (!settings.teleport().enabled()) {
            messages.get().send(player, "teleport-disabled");
            return;
        }
        Shop shop = shops.find(requestedShop.id()).orElse(null);
""",
    """        if (!settings.teleport().enabled()) {
            messages.get().send(player, "teleport-disabled");
            return;
        }
        UUID playerUuid = player.getUniqueId();
        if (pending.containsKey(playerUuid) || inFlight.contains(playerUuid)) {
            messages.get().send(player, "teleport-in-progress");
            return;
        }
        Shop shop = shops.find(requestedShop.id()).orElse(null);
""",
)
replace_once(
    "src/main/java/com/plexon/shops/services/TeleportService.java",
    """        cancel(player.getUniqueId(), false);
        int warmup = settings.teleport().warmupSeconds();
""",
    """        int warmup = settings.teleport().warmupSeconds();
""",
)
old_execute = """        boolean feeBypass = player.hasPermission("plexonshops.teleport.fee.bypass")
                || (settings.teleport().ownersBypassFee() && shop.ownerUuid().equals(player.getUniqueId()));
        VaultEconomyHook.ChargeResult charge = feeBypass
                ? VaultEconomyHook.ChargeResult.free()
                : economy.charge(player, shop.teleportFee());
        if (!charge.success()) {
            if (charge.failure() == VaultEconomyHook.ChargeFailure.INSUFFICIENT_FUNDS) {
                messages.get().send(player, "insufficient-funds",
                        Placeholder.unparsed("fee", economy.format(shop.teleportFee())));
            } else {
                messages.get().send(player, "economy-unavailable");
            }
            return;
        }

        player.teleportAsync(destination).whenComplete((success, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null || !Boolean.TRUE.equals(success)) {
                economy.refund(player, charge.chargedAmount());
                messages.get().send(player, "teleport-failed");
                return;
            }
            shops.recordVisit(shop.id(), player.getUniqueId()).exceptionally(recordError -> {
                plugin.getLogger().log(Level.WARNING, "Could not persist authoritative shop visit for " + shop.id(), recordError);
                return null;
            });
            discovery.recordVisit(player.getUniqueId(), shop.id()).exceptionally(recordError -> {
                plugin.getLogger().log(Level.WARNING, "Could not persist recent shop history for " + shop.id(), recordError);
                return null;
            });
            if (!bypassesCooldown(player, shop, settings.teleport()) && settings.teleport().cooldownSeconds() > 0) {
                cooldowns.put(player.getUniqueId(),
                        System.nanoTime() + settings.teleport().cooldownSeconds() * 1_000_000_000L);
            }
            playEffects(player, true, settings.teleport().effects());
            messages.get().send(player, "teleport-success", messages.get().storedTag("shop", shop.name()));
            player.sendActionBar(messages.get().get(
                    "teleport-arrival-actionbar",
                    messages.get().storedTag("shop", shop.name())
            ));
        }));
"""
new_execute = """        UUID playerUuid = player.getUniqueId();
        InFlightTeleport attempt = new InFlightTeleport(player);
        if (!inFlight.begin(playerUuid, attempt)) {
            messages.get().send(player, "teleport-in-progress");
            return;
        }

        boolean feeBypass = player.hasPermission("plexonshops.teleport.fee.bypass")
                || (settings.teleport().ownersBypassFee() && shop.ownerUuid().equals(playerUuid));
        VaultEconomyHook.ChargeResult charge = feeBypass
                ? VaultEconomyHook.ChargeResult.free()
                : economy.charge(player, shop.teleportFee());
        if (!charge.success()) {
            inFlight.complete(playerUuid, attempt);
            if (charge.failure() == VaultEconomyHook.ChargeFailure.INSUFFICIENT_FUNDS) {
                messages.get().send(player, "insufficient-funds",
                        Placeholder.unparsed("fee", economy.format(shop.teleportFee())));
            } else {
                messages.get().send(player, "economy-unavailable");
            }
            return;
        }
        attempt.chargedAmount(charge.chargedAmount());

        try {
            player.teleportAsync(destination).whenComplete((success, error) -> {
                if (!inFlight.isAuthoritative(playerUuid, attempt)) {
                    return;
                }
                Bukkit.getScheduler().runTask(plugin, () -> finishAttempt(
                        playerUuid, attempt, shop, settings, Boolean.TRUE.equals(success), error));
            });
        } catch (RuntimeException error) {
            if (inFlight.complete(playerUuid, attempt)) {
                economy.refund(player, attempt.chargedAmount());
                messages.get().send(player, "teleport-failed");
            }
        }
"""
replace_once("src/main/java/com/plexon/shops/services/TeleportService.java", old_execute, new_execute)
replace_once(
    "src/main/java/com/plexon/shops/services/TeleportService.java",
    """    private void ensureProgressCoordinator(int updateIntervalTicks) {
""",
    """    private void finishAttempt(
            UUID playerUuid,
            InFlightTeleport attempt,
            Shop shop,
            PluginConfig settings,
            boolean success,
            Throwable error
    ) {
        if (!inFlight.isAuthoritative(playerUuid, attempt)) {
            return;
        }
        try {
            if (error != null || !success) {
                economy.refund(attempt.player(), attempt.chargedAmount());
                messages.get().send(attempt.player(), "teleport-failed");
                return;
            }
            shops.recordVisit(shop.id(), playerUuid).exceptionally(recordError -> {
                plugin.getLogger().log(Level.WARNING, "Could not persist authoritative shop visit for " + shop.id(), recordError);
                return null;
            });
            discovery.recordVisit(playerUuid, shop.id()).exceptionally(recordError -> {
                plugin.getLogger().log(Level.WARNING, "Could not persist recent shop history for " + shop.id(), recordError);
                return null;
            });
            if (!bypassesCooldown(attempt.player(), shop, settings.teleport())
                    && settings.teleport().cooldownSeconds() > 0) {
                cooldowns.put(playerUuid,
                        System.nanoTime() + settings.teleport().cooldownSeconds() * 1_000_000_000L);
            }
            playEffects(attempt.player(), true, settings.teleport().effects());
            messages.get().send(attempt.player(), "teleport-success", messages.get().storedTag("shop", shop.name()));
            attempt.player().sendActionBar(messages.get().get(
                    "teleport-arrival-actionbar",
                    messages.get().storedTag("shop", shop.name())
            ));
        } finally {
            inFlight.complete(playerUuid, attempt);
        }
    }

    private void ensureProgressCoordinator(int updateIntervalTicks) {
""",
)
replace_once(
    "src/main/java/com/plexon/shops/services/TeleportService.java",
    """    public void cancelAll() {
        for (UUID playerUuid : List.copyOf(pending.keySet())) {
            cancel(playerUuid, (String) null);
        }
        cooldowns.clear();
        stopProgressCoordinator();
    }
""",
    """    public void cancelAll() {
        for (UUID playerUuid : List.copyOf(pending.keySet())) {
            cancel(playerUuid, (String) null);
        }
        for (InFlightTeleport attempt : inFlight.drain()) {
            economy.refund(attempt.player(), attempt.chargedAmount());
        }
        cooldowns.clear();
        stopProgressCoordinator();
    }
""",
)
replace_once(
    "src/main/java/com/plexon/shops/services/TeleportService.java",
    """    private void cancel(UUID playerUuid, String messageKey) {
        if (clearPending(playerUuid) == null || messageKey == null) {
            return;
        }
        Player player = Bukkit.getPlayer(playerUuid);
        if (player != null) {
            messages.get().send(player, messageKey);
        }
    }
""",
    """    private void cancel(UUID playerUuid, String messageKey) {
        boolean cancelled = clearPending(playerUuid) != null;
        InFlightTeleport attempt = inFlight.cancel(playerUuid);
        if (attempt != null) {
            economy.refund(attempt.player(), attempt.chargedAmount());
            cancelled = true;
        }
        if (!cancelled || messageKey == null) {
            return;
        }
        Player player = Bukkit.getPlayer(playerUuid);
        if (player != null) {
            messages.get().send(player, messageKey);
        }
    }
""",
)
replace_once(
    "src/main/java/com/plexon/shops/services/TeleportService.java",
    """    private record PendingTeleport(
""",
    """    private static final class InFlightTeleport {
        private final Player player;
        private double chargedAmount;

        private InFlightTeleport(Player player) {
            this.player = player;
        }

        private Player player() {
            return player;
        }

        private double chargedAmount() {
            return chargedAmount;
        }

        private void chargedAmount(double value) {
            chargedAmount = Math.max(0.0D, value);
        }
    }

    private record PendingTeleport(
""",
)

Path("src/main/java/com/plexon/shops/services/TeleportAttemptRegistry.java").write_text(r'''package com.plexon.shops.services;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Identity-bound ownership for asynchronous teleport attempts. */
final class TeleportAttemptRegistry<T> {
    private final ConcurrentMap<UUID, T> attempts = new ConcurrentHashMap<>();

    boolean begin(UUID playerUuid, T attempt) {
        return attempts.putIfAbsent(playerUuid, attempt) == null;
    }

    boolean contains(UUID playerUuid) {
        return attempts.containsKey(playerUuid);
    }

    boolean isAuthoritative(UUID playerUuid, T attempt) {
        return attempts.get(playerUuid) == attempt;
    }

    boolean complete(UUID playerUuid, T attempt) {
        return attempts.remove(playerUuid, attempt);
    }

    T cancel(UUID playerUuid) {
        return attempts.remove(playerUuid);
    }

    List<T> drain() {
        List<T> drained = new ArrayList<>();
        attempts.forEach((playerUuid, attempt) -> {
            if (attempts.remove(playerUuid, attempt)) {
                drained.add(attempt);
            }
        });
        return List.copyOf(drained);
    }

    int size() {
        return attempts.size();
    }
}
''', encoding="utf-8")

replace_once(
    "src/main/resources/messages.yml",
    'teleport-disabled: "<red>Shop teleportation is disabled.</red>"\n',
    'teleport-disabled: "<red>Shop teleportation is disabled.</red>"\nteleport-in-progress: "<yellow>A shop teleport is already in progress.</yellow>"\n',
)

# 3) Discovery loads publish only if both player and directory generations are still authoritative.
replace_once(
    "src/main/java/com/plexon/shops/services/DiscoveryService.java",
    "import java.util.concurrent.CompletableFuture;\n",
    "import java.util.concurrent.CompletableFuture;\nimport java.util.function.LongSupplier;\n",
)
replace_once(
    "src/main/java/com/plexon/shops/services/DiscoveryService.java",
    """    private final Object lock = new Object();
    private final LinkedHashMap<UUID, PlayerDiscoveryData> playerCache = new LinkedHashMap<>(32, 0.75F, true);
    private final Map<UUID, CompletableFuture<Void>> loads = new HashMap<>();
    private volatile Set<UUID> featured = Set.of();

    public DiscoveryService(ShopRepository repository, ShopService shops, Logger logger) {
        this.repository = repository;
        this.shops = shops;
        this.logger = logger;
    }
""",
    """    private final Object lock = new Object();
    private final LinkedHashMap<UUID, PlayerDiscoveryData> playerCache = new LinkedHashMap<>(32, 0.75F, true);
    private final Map<UUID, CompletableFuture<Void>> loads = new HashMap<>();
    private final Map<UUID, Long> playerGenerations = new HashMap<>();
    private final LongSupplier directoryGeneration;
    private long invalidationGeneration;
    private volatile Set<UUID> featured = Set.of();

    public DiscoveryService(ShopRepository repository, ShopService shops, Logger logger) {
        this(repository, shops, logger, shops::directoryGeneration);
    }

    DiscoveryService(ShopRepository repository, ShopService shops, Logger logger, LongSupplier directoryGeneration) {
        this.repository = repository;
        this.shops = shops;
        this.logger = logger;
        this.directoryGeneration = directoryGeneration;
    }
""",
)
old_preload = """    public CompletableFuture<Void> preload(UUID playerUuid) {
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
"""
new_preload = """    public CompletableFuture<Void> preload(UUID playerUuid) {
        long directoryRevision = directoryGeneration.getAsLong();
        synchronized (lock) {
            if (playerCache.containsKey(playerUuid)) {
                playerCache.get(playerUuid); // refresh access order
                return CompletableFuture.completedFuture(null);
            }
            CompletableFuture<Void> active = loads.get(playerUuid);
            if (active != null) {
                return active;
            }
            long playerRevision = playerGenerationLocked(playerUuid);
            long invalidationRevision = invalidationGeneration;
            CompletableFuture<Void> load = repository.loadDiscovery(playerUuid, RECENT_LIMIT)
                    .thenAccept(data -> {
                        long currentDirectoryRevision = directoryGeneration.getAsLong();
                        synchronized (lock) {
                            if (playerGenerationLocked(playerUuid) != playerRevision
                                    || invalidationGeneration != invalidationRevision
                                    || currentDirectoryRevision != directoryRevision) {
                                return;
                            }
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
                    if (!loads.containsKey(playerUuid) && !playerCache.containsKey(playerUuid)) {
                        playerGenerations.remove(playerUuid);
                    }
                }
                if (error != null) {
                    logger.log(Level.WARNING, "Could not load discovery state for " + playerUuid, error);
                }
            });
            return load;
        }
    }
"""
replace_once("src/main/java/com/plexon/shops/services/DiscoveryService.java", old_preload, new_preload)
replace_once(
    "src/main/java/com/plexon/shops/services/DiscoveryService.java",
    """            synchronized (lock) {
                PlayerDiscoveryData current = playerCache.getOrDefault(playerUuid, PlayerDiscoveryData.empty());
                favorite = !current.favorites().contains(shopId);
            }
""",
    """            synchronized (lock) {
                PlayerDiscoveryData current = playerCache.getOrDefault(playerUuid, PlayerDiscoveryData.empty());
                favorite = !current.favorites().contains(shopId);
                bumpPlayerGenerationLocked(playerUuid);
            }
""",
)
replace_once(
    "src/main/java/com/plexon/shops/services/DiscoveryService.java",
    """    public CompletableFuture<Void> recordVisit(UUID playerUuid, UUID shopId) {
        long now = Instant.now().getEpochSecond();
        return repository.recordRecentVisit(playerUuid, shopId, now, RECENT_LIMIT).thenRun(() -> {
""",
    """    public CompletableFuture<Void> recordVisit(UUID playerUuid, UUID shopId) {
        long now = Instant.now().getEpochSecond();
        synchronized (lock) {
            if (playerCache.containsKey(playerUuid) || loads.containsKey(playerUuid)) {
                bumpPlayerGenerationLocked(playerUuid);
            }
        }
        return repository.recordRecentVisit(playerUuid, shopId, now, RECENT_LIMIT).thenRun(() -> {
""",
)
replace_once(
    "src/main/java/com/plexon/shops/services/DiscoveryService.java",
    """    public void onShopDeleted(UUID shopId) {
        Set<UUID> nextFeatured = new HashSet<>(featured);
""",
    """    public void onShopDeleted(UUID shopId) {
        synchronized (lock) {
            invalidationGeneration++;
        }
        Set<UUID> nextFeatured = new HashSet<>(featured);
""",
)
replace_once(
    "src/main/java/com/plexon/shops/services/DiscoveryService.java",
    """    private void trimPlayerCacheLocked() {
        while (playerCache.size() > PLAYER_CACHE_LIMIT) {
            UUID eldest = playerCache.keySet().iterator().next();
            playerCache.remove(eldest);
        }
    }
}
""",
    """    public void unload(UUID playerUuid) {
        synchronized (lock) {
            bumpPlayerGenerationLocked(playerUuid);
            playerCache.remove(playerUuid);
            loads.remove(playerUuid);
        }
    }

    int playerGenerationEntries() {
        synchronized (lock) {
            return playerGenerations.size();
        }
    }

    private long playerGenerationLocked(UUID playerUuid) {
        return playerGenerations.getOrDefault(playerUuid, 0L);
    }

    private void bumpPlayerGenerationLocked(UUID playerUuid) {
        playerGenerations.put(playerUuid, playerGenerationLocked(playerUuid) + 1L);
    }

    private void trimPlayerCacheLocked() {
        while (playerCache.size() > PLAYER_CACHE_LIMIT) {
            UUID eldest = playerCache.keySet().iterator().next();
            playerCache.remove(eldest);
            if (!loads.containsKey(eldest)) {
                playerGenerations.remove(eldest);
            }
        }
    }
}
""",
)

replace_once(
    "src/main/java/com/plexon/shops/listeners/OwnerActivityListener.java",
    "import org.bukkit.event.player.PlayerJoinEvent;\n",
    "import org.bukkit.event.player.PlayerJoinEvent;\nimport org.bukkit.event.player.PlayerQuitEvent;\n",
)
replace_once(
    "src/main/java/com/plexon/shops/listeners/OwnerActivityListener.java",
    """    public void onJoin(PlayerJoinEvent event) {
        shops.touchOwner(event.getPlayer().getUniqueId(), event.getPlayer().getName());
        discovery.preload(event.getPlayer().getUniqueId());
    }
}
""",
    """    public void onJoin(PlayerJoinEvent event) {
        shops.touchOwner(event.getPlayer().getUniqueId(), event.getPlayer().getName());
        discovery.preload(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        discovery.unload(event.getPlayer().getUniqueId());
    }
}
""",
)

# Regression tests: attempt identity/idempotency.
Path("src/test/java/com/plexon/shops/services/TeleportAttemptRegistryTest.java").write_text(r'''package com.plexon.shops.services;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TeleportAttemptRegistryTest {
    @Test
    void rejectsSecondAttemptUntilAuthoritativeAttemptTerminates() {
        TeleportAttemptRegistry<Object> registry = new TeleportAttemptRegistry<>();
        UUID player = UUID.randomUUID();
        Object first = new Object();
        assertTrue(registry.begin(player, first));
        assertFalse(registry.begin(player, new Object()));
        assertTrue(registry.contains(player));
        assertTrue(registry.isAuthoritative(player, first));
    }

    @Test
    void staleCallbackCannotCompleteNewerAttempt() {
        TeleportAttemptRegistry<Object> registry = new TeleportAttemptRegistry<>();
        UUID player = UUID.randomUUID();
        Object oldAttempt = new Object();
        Object newAttempt = new Object();
        assertTrue(registry.begin(player, oldAttempt));
        assertSame(oldAttempt, registry.cancel(player));
        assertTrue(registry.begin(player, newAttempt));
        assertFalse(registry.complete(player, oldAttempt));
        assertTrue(registry.isAuthoritative(player, newAttempt));
        assertTrue(registry.complete(player, newAttempt));
    }

    @Test
    void terminalSideEffectsCanRunAtMostOnce() {
        TeleportAttemptRegistry<Object> registry = new TeleportAttemptRegistry<>();
        UUID player = UUID.randomUUID();
        Object attempt = new Object();
        AtomicInteger refunds = new AtomicInteger();
        AtomicInteger visits = new AtomicInteger();
        AtomicInteger cooldowns = new AtomicInteger();
        assertTrue(registry.begin(player, attempt));
        if (registry.complete(player, attempt)) {
            refunds.incrementAndGet();
        }
        if (registry.complete(player, attempt)) {
            refunds.incrementAndGet();
            visits.incrementAndGet();
            cooldowns.incrementAndGet();
        }
        assertEquals(1, refunds.get());
        assertEquals(0, visits.get());
        assertEquals(0, cooldowns.get());
    }

    @Test
    void duplicateAcceptanceCannotChargeOrInvokeTeleportTwice() {
        TeleportAttemptRegistry<Object> registry = new TeleportAttemptRegistry<>();
        UUID player = UUID.randomUUID();
        AtomicInteger charges = new AtomicInteger();
        AtomicInteger teleports = new AtomicInteger();
        Object first = new Object();
        if (registry.begin(player, first)) {
            charges.incrementAndGet();
            teleports.incrementAndGet();
        }
        if (registry.begin(player, new Object())) {
            charges.incrementAndGet();
            teleports.incrementAndGet();
        }
        assertEquals(1, charges.get());
        assertEquals(1, teleports.get());
    }

    @Test
    void cancellationOwnsCompensationOnlyOnce() {
        TeleportAttemptRegistry<Object> registry = new TeleportAttemptRegistry<>();
        UUID player = UUID.randomUUID();
        Object attempt = new Object();
        assertTrue(registry.begin(player, attempt));
        assertSame(attempt, registry.cancel(player));
        assertNull(registry.cancel(player));
        assertFalse(registry.complete(player, attempt));
    }

    @Test
    void shutdownDrainReleasesEveryAttemptExactlyOnce() {
        TeleportAttemptRegistry<Object> registry = new TeleportAttemptRegistry<>();
        assertTrue(registry.begin(UUID.randomUUID(), new Object()));
        assertTrue(registry.begin(UUID.randomUUID(), new Object()));
        List<Object> drained = registry.drain();
        assertEquals(2, drained.size());
        assertEquals(0, registry.size());
        assertTrue(registry.drain().isEmpty());
    }
}
''', encoding="utf-8")

# Regression tests: stale asynchronous discovery publication.
Path("src/test/java/com/plexon/shops/services/DiscoveryServiceGenerationTest.java").write_text(r'''package com.plexon.shops.services;

import com.plexon.shops.storage.PlayerDiscoveryData;
import com.plexon.shops.storage.ShopRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class DiscoveryServiceGenerationTest {
    @Test
    void currentAsyncLoadPublishesCache() {
        CompletableFuture<PlayerDiscoveryData> load = new CompletableFuture<>();
        DiscoveryService service = service(new AtomicLong(), new ArrayDeque<>(List.of(load)));
        UUID player = UUID.randomUUID();
        UUID shop = UUID.randomUUID();
        CompletableFuture<Void> preload = service.preload(player);
        load.complete(new PlayerDiscoveryData(Set.of(shop), List.of()));
        preload.join();
        assertTrue(service.isLoaded(player));
        assertTrue(service.isFavorite(player, shop));
    }

    @Test
    void directoryMutationRejectsOlderAsyncResult() {
        AtomicLong directory = new AtomicLong(10L);
        CompletableFuture<PlayerDiscoveryData> load = new CompletableFuture<>();
        DiscoveryService service = service(directory, new ArrayDeque<>(List.of(load)));
        UUID player = UUID.randomUUID();
        CompletableFuture<Void> preload = service.preload(player);
        directory.incrementAndGet();
        load.complete(PlayerDiscoveryData.empty());
        preload.join();
        assertFalse(service.isLoaded(player));
    }

    @Test
    void deletionInvalidationRejectsOlderAsyncResult() {
        CompletableFuture<PlayerDiscoveryData> load = new CompletableFuture<>();
        DiscoveryService service = service(new AtomicLong(), new ArrayDeque<>(List.of(load)));
        UUID player = UUID.randomUUID();
        CompletableFuture<Void> preload = service.preload(player);
        service.onShopDeleted(UUID.randomUUID());
        load.complete(PlayerDiscoveryData.empty());
        preload.join();
        assertFalse(service.isLoaded(player));
    }

    @Test
    void unloadRejectsLateResult() {
        CompletableFuture<PlayerDiscoveryData> load = new CompletableFuture<>();
        DiscoveryService service = service(new AtomicLong(), new ArrayDeque<>(List.of(load)));
        UUID player = UUID.randomUUID();
        CompletableFuture<Void> preload = service.preload(player);
        service.unload(player);
        load.complete(PlayerDiscoveryData.empty());
        preload.join();
        assertFalse(service.isLoaded(player));
    }

    @Test
    void newerOverlappingGenerationWins() {
        CompletableFuture<PlayerDiscoveryData> first = new CompletableFuture<>();
        CompletableFuture<PlayerDiscoveryData> second = new CompletableFuture<>();
        Queue<CompletableFuture<PlayerDiscoveryData>> loads = new ArrayDeque<>();
        loads.add(first);
        loads.add(second);
        DiscoveryService service = service(new AtomicLong(), loads);
        UUID player = UUID.randomUUID();
        UUID newerFavorite = UUID.randomUUID();
        CompletableFuture<Void> firstPreload = service.preload(player);
        service.unload(player);
        CompletableFuture<Void> secondPreload = service.preload(player);
        second.complete(new PlayerDiscoveryData(Set.of(newerFavorite), List.of()));
        secondPreload.join();
        first.complete(PlayerDiscoveryData.empty());
        firstPreload.join();
        assertTrue(service.isLoaded(player));
        assertTrue(service.isFavorite(player, newerFavorite));
    }

    @Test
    void successfulVisitInvalidatesConcurrentPlayerLoad() {
        CompletableFuture<PlayerDiscoveryData> load = new CompletableFuture<>();
        DiscoveryService service = service(new AtomicLong(), new ArrayDeque<>(List.of(load)));
        UUID player = UUID.randomUUID();
        CompletableFuture<Void> preload = service.preload(player);
        service.recordVisit(player, UUID.randomUUID()).join();
        load.complete(PlayerDiscoveryData.empty());
        preload.join();
        assertFalse(service.isLoaded(player));
    }

    private DiscoveryService service(
            AtomicLong directoryGeneration,
            Queue<CompletableFuture<PlayerDiscoveryData>> loads
    ) {
        ShopRepository repository = (ShopRepository) Proxy.newProxyInstance(
                ShopRepository.class.getClassLoader(),
                new Class<?>[]{ShopRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "loadDiscovery" -> {
                        CompletableFuture<PlayerDiscoveryData> next = loads.poll();
                        if (next == null) throw new AssertionError("Unexpected discovery load");
                        yield next;
                    }
                    case "recordRecentVisit", "setFavorite", "setFeatured", "initialize", "close" ->
                            CompletableFuture.completedFuture(null);
                    case "loadFeatured" -> CompletableFuture.completedFuture(Set.of());
                    case "toString" -> "DiscoveryRepositoryStub";
                    default -> throw new UnsupportedOperationException(method.getName());
                }
        );
        return new DiscoveryService(repository, null, Logger.getAnonymousLogger(), directoryGeneration::get);
    }
}
''', encoding="utf-8")

# Regression tests: future schema non-destructive rejection and forward migration.
Path("src/test/java/com/plexon/shops/storage/SchemaCompatibilityTest.java").write_text(r'''package com.plexon.shops.storage;

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
''', encoding="utf-8")

Path("src/test/java/com/plexon/shops/services/ConfirmationServiceTest.java").write_text(r'''package com.plexon.shops.services;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ConfirmationServiceTest {
    @Test
    void confirmationIsActorActionShopAndRevisionBound() {
        ConfirmationService confirmations = new ConfirmationService(Duration.ofSeconds(30));
        UUID actor = UUID.randomUUID();
        UUID otherActor = UUID.randomUUID();
        UUID shop = UUID.randomUUID();
        UUID otherShop = UUID.randomUUID();

        confirmations.stage(actor, "delete", shop, 7L);
        assertFalse(confirmations.consume(otherActor, "delete", shop, 7L));
        assertTrue(confirmations.consume(actor, "delete", shop, 7L));

        confirmations.stage(actor, "delete", shop, 7L);
        assertFalse(confirmations.consume(actor, "close", shop, 7L));
        assertFalse(confirmations.consume(actor, "delete", shop, 7L));

        confirmations.stage(actor, "delete", shop, 7L);
        assertFalse(confirmations.consume(actor, "delete", otherShop, 7L));

        confirmations.stage(actor, "delete", shop, 7L);
        assertFalse(confirmations.consume(actor, "delete", shop, 8L));
    }

    @Test
    void confirmationIsSingleUseAndCanBeCleared() {
        ConfirmationService confirmations = new ConfirmationService(Duration.ofSeconds(30));
        UUID actor = UUID.randomUUID();
        UUID shop = UUID.randomUUID();
        confirmations.stage(actor, "delete", shop, 1L);
        assertTrue(confirmations.consume(actor, "delete", shop, 1L));
        assertFalse(confirmations.consume(actor, "delete", shop, 1L));
        confirmations.stage(actor, "delete", shop, 1L);
        confirmations.clear(actor);
        assertFalse(confirmations.consume(actor, "delete", shop, 1L));
    }
}
''', encoding="utf-8")

Path("src/test/java/com/plexon/shops/config/PluginConfigValidationTest.java").write_text(r'''package com.plexon.shops.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PluginConfigValidationTest {
    @Test
    void invalidCandidateIsRejectedWithoutMutatingKnownGoodSnapshot() {
        PluginConfig active = PluginConfig.from(new YamlConfiguration());
        int activeWarmup = active.teleport().warmupSeconds();
        YamlConfiguration invalid = new YamlConfiguration();
        invalid.set("teleport.warmup-seconds", 301);
        assertThrows(IllegalArgumentException.class, () -> PluginConfig.from(invalid));
        assertEquals(activeWarmup, active.teleport().warmupSeconds());
    }

    @Test
    void invalidGuiLayoutAndBossbarEnumsFailClosed() {
        YamlConfiguration invalidLayout = new YamlConfiguration();
        invalidLayout.set("guis.directory.size", 10);
        assertThrows(IllegalArgumentException.class, () -> PluginConfig.from(invalidLayout));

        YamlConfiguration invalidBossbar = new YamlConfiguration();
        invalidBossbar.set("teleport.bossbar.color", "NOT_A_COLOR");
        assertThrows(IllegalArgumentException.class, () -> PluginConfig.from(invalidBossbar));
    }
}
''', encoding="utf-8")

# Documentation closure for the hardening boundary.
Path("docs/PHASE2_HARDENING_3.0.0.md").write_text('''# PlexonShops 3.0.0 Phase 2 hardening\n\n## Teleport transaction lifecycle\n\nOne player owns at most one accepted shop teleport at a time. Warmup is mutually exclusive with another warmup or an in-flight teleport. When warmup completes, the same main-thread turn acquires an identity-bound in-flight reservation before any Vault charge. The asynchronous completion callback may mutate state only while its exact attempt object remains authoritative. Failure/cancel/logout/shutdown compensation removes ownership once and refunds a charged amount at most once. Successful completion publishes one visit, installs one cooldown, then releases ownership. Stale callbacks are ignored. No blocking wait is introduced around Paper `teleportAsync`.\n\n## Schema 3 compatibility\n\nPlexonShops reads `plexonshops_schema` before migration, backup, Hikari initialization, DDL, or schema-marker writes. Versions below 3 follow the forward migration/backup path. Version 3 starts normally. Any version above 3 fails closed and is never rewritten down to 3.\n\n## Discovery cache generations\n\nAsync player-discovery loads capture player, global invalidation, and shop-directory generations. Returned data is published only if all generations remain current. Shop mutations/reload are covered by the authoritative `ShopService.directoryGeneration`; deletion adds an explicit discovery invalidation; player unload and concurrent visit/favorite mutations advance the player generation. Stale results are discarded without retry loops.\n\n## Runtime boundary\n\nThese changes are automated source-safety evidence only. PlexonCraft runtime certification, Spark/MSPT comparison, and the soak test remain mandatory before stable `v3.0.0`.\n''', encoding="utf-8")

Path("docs/MIGRATION_2.2.1_TO_3.0.0.md").write_text('''# Migrating PlexonShops 2.2.1 to 3.0.0\n\nThe rollback baseline is `v2.2.1` (`81c77e936194de2d46fd40221ea57a1f65c07b34`). Back up the PlexonShops data directory before installing the 3.0 candidate. The 3.0 storage layer uses schema 3 and creates a migration backup for an older existing database before schema work. Existing shop UUIDs and owner UUIDs remain authoritative; the premium discovery metadata is additive.\n\nIf the database declares a schema newer than 3, the plugin intentionally fails startup without running migrations, DDL, or rewriting the schema marker. Use a PlexonShops build that supports that newer schema instead of attempting a downgrade.\n\nRollback testing must use the saved 2.2.1 data backup. Do not point 2.2.1 at a database after intentionally advancing it with a newer schema without restoring the corresponding backup first.\n''', encoding="utf-8")

Path("docs/RUNTIME_CERTIFICATION_3.0.0.md").write_text('''# PlexonShops 3.0.0 runtime certification\n\nStable `v3.0.0` is blocked until PlexonCraft validates: representative 2.2.1 migration; discovery/browser/categories/search/filter; shop profile and owner management; OPEN/CLOSED/UNAVAILABLE behavior; successful teleport; duplicate warmup/in-flight rejection; movement/damage/logout cancellation; shop close/destination invalidation during warmup; Vault/Theosis charge/refund; visit publication only after successful teleport; stale discovery loads; restart persistence; invalid reload rollback; PlaceholderAPI; public API/events; PlexonCore/cross-plugin behavior; Spark/MSPT comparison; at least 30 minutes of soak; and zero HIGH/CRITICAL defects.\n''', encoding="utf-8")

Path("src/test/java/com/plexon/shops/config/.gitkeep").unlink(missing_ok=True)
