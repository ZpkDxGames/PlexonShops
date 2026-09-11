package com.plexon.shops.services;

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
