package com.plexon.shops.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PlexonShopsAPI {
    Optional<ShopView> shop(UUID shopId);

    List<ShopView> shopsOwnedBy(UUID ownerId);

    List<ShopView> directory();

    int totalShops();

    int openShops();
}
