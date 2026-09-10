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

    /** Main-thread-only because live world availability is part of the result. */
    default Optional<AvailabilityView> availability(UUID shopId) {
        return Optional.empty();
    }

    default boolean isFeatured(UUID shopId) {
        return false;
    }

    /** Cache-only; returns false while the player's discovery state has not been loaded. */
    default boolean isFavorite(UUID playerId, UUID shopId) {
        return false;
    }

    /** Cache-only discovery view. */
    default List<ShopView> favorites(UUID playerId) {
        return List.of();
    }

    /** Cache-only bounded successful-visit history. */
    default List<ShopView> recentlyVisited(UUID playerId) {
        return List.of();
    }
}
