package com.plexon.shops.api;

import com.plexon.shops.models.Category;
import com.plexon.shops.models.DirectoryFilter;
import com.plexon.shops.models.Shop;
import com.plexon.shops.services.ShopService;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public final class DefaultPlexonShopsAPI implements PlexonShopsAPI {
    private final ShopService shops;

    public DefaultPlexonShopsAPI(ShopService shops) {
        this.shops = shops;
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

    private static ShopView view(Shop shop) {
        return new ShopView(
                shop.id(),
                shop.ownerUuid(),
                shop.ownerName(),
                shop.name(),
                shop.categories().stream().map(Category::name).map(value -> value.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toUnmodifiableSet()),
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
