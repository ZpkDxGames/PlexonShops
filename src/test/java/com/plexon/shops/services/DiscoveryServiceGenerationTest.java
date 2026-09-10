package com.plexon.shops.services;

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
