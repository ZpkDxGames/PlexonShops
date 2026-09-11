package com.plexon.shops.storage;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Immutable per-player discovery state loaded away from the server thread. */
public record PlayerDiscoveryData(Set<UUID> favorites, List<UUID> recentShopIds) {
    public PlayerDiscoveryData {
        favorites = Set.copyOf(favorites == null ? Set.of() : favorites);
        recentShopIds = List.copyOf(recentShopIds == null ? List.of() : recentShopIds);
    }

    public static PlayerDiscoveryData empty() {
        return new PlayerDiscoveryData(Set.of(), List.of());
    }
}
