package com.plexon.shops.api;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

public record ShopView(
        UUID id,
        UUID ownerId,
        String ownerName,
        String name,
        Set<String> categories,
        String status,
        double averageRating,
        long totalVisits,
        int uniqueVisitors,
        double teleportFee,
        String worldName,
        long createdAtEpochSecond,
        long updatedAtEpochSecond
) {
    public ShopView {
        categories = Set.copyOf(new LinkedHashSet<>(categories == null ? Set.of() : categories));
        ownerName = ownerName == null ? "" : ownerName;
        name = name == null ? "" : name;
        status = status == null ? "" : status;
        worldName = worldName == null ? "" : worldName;
    }
}
