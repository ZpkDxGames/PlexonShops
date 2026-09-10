package com.plexon.shops.api;

import com.plexon.shops.models.Category;
import com.plexon.shops.models.DirectoryFilter;
import com.plexon.shops.models.Shop;
import com.plexon.shops.services.DiscoveryService;
import com.plexon.shops.services.ShopAvailabilityResolver;
import com.plexon.shops.services.ShopService;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public final class DefaultPlexonShopsAPI implements PlexonShopsAPI {
    private final ShopService shops;
    private final DiscoveryService discovery;
    private final ShopAvailabilityResolver availability;

    public DefaultPlexonShopsAPI(
            ShopService shops,
            DiscoveryService discovery,
            ShopAvailabilityResolver availability
    ) {
        this.shops = shops;
        this.discovery = discovery;
        this.availability = availability;
    }

    @Override
    public Optional<ShopView> shop(UUID shopId) {
        return shops.find(shopId).map(DefaultPlexonShopsAPI::view);
    }

    @Override
    public List<ShopView> shopsOwnedBy(UUID ownerId) {
        return shops.ownedBy(ownerId).stream().map(DefaultPlexonShopsAPI::view).toList();
    }

    @Override
    public List<ShopView> directory() {
        return shops.directory(DirectoryFilter.ALL).stream().map(DefaultPlexonShopsAPI::view).toList();
    }

    @Override public int totalShops() { return shops.totalCount(); }
    @Override public int openShops() { return shops.openCount(); }

    @Override
    public Optional<AvailabilityView> availability(UUID shopId) {
        return shops.find(shopId).map(shop -> {
            ShopAvailabilityResolver.Resolution resolved = availability.resolve(shop);
            return new AvailabilityView(
                    resolved.state().name().toLowerCase(Locale.ROOT),
                    resolved.teleportable(),
                    resolved.reason());
        });
    }

    @Override public boolean isFeatured(UUID shopId) { return discovery.isFeatured(shopId); }
    @Override public boolean isFavorite(UUID playerId, UUID shopId) { return discovery.isFavorite(playerId, shopId); }

    @Override
    public List<ShopView> favorites(UUID playerId) {
        return discovery.favorites(playerId).stream().map(DefaultPlexonShopsAPI::view).toList();
    }

    @Override
    public List<ShopView> recentlyVisited(UUID playerId) {
        return discovery.recent(playerId).stream().map(DefaultPlexonShopsAPI::view).toList();
    }

    private static ShopView view(Shop shop) {
        return new ShopView(
                shop.id(),
                shop.ownerUuid(),
                shop.ownerName(),
                shop.name(),
                shop.categories().stream().map(Category::name).map(value -> value.toLowerCase(Locale.ROOT))
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()),
                shop.status().name().toLowerCase(Locale.ROOT),
                shop.averageRating(),
                shop.visitors().totalVisits(),
                shop.visitors().uniqueCount(),
                shop.teleportFee(),
                shop.location().worldName(),
                shop.createdAtEpochSecond(),
                shop.updatedAtEpochSecond());
    }
}
