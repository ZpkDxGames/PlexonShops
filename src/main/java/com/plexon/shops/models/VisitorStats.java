package com.plexon.shops.models;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Immutable total and unique visit counters. */
public record VisitorStats(long totalVisits, Map<UUID, Visitor> uniqueVisitors) {
    public VisitorStats {
        totalVisits = Math.max(0L, totalVisits);
        uniqueVisitors = Map.copyOf(uniqueVisitors);
    }

    public static VisitorStats empty() {
        return new VisitorStats(0L, Map.of());
    }

    public VisitorStats record(UUID playerUuid, long timestamp) {
        Map<UUID, Visitor> updated = new LinkedHashMap<>(uniqueVisitors);
        updated.putIfAbsent(playerUuid, new Visitor(playerUuid, timestamp));
        return new VisitorStats(totalVisits + 1L, updated);
    }

    public int uniqueCount() {
        return uniqueVisitors.size();
    }
}
