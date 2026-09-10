package com.plexon.shops.services;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Actor/action/shop/revision-bound, expiring and single-use destructive confirmations. */
public final class ConfirmationService {
    private final ConcurrentMap<UUID, PendingConfirmation> pending = new ConcurrentHashMap<>();
    private final long ttlNanos;

    public ConfirmationService(Duration ttl) {
        Objects.requireNonNull(ttl, "ttl");
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("Confirmation TTL must be positive");
        }
        ttlNanos = ttl.toNanos();
    }

    public void stage(UUID actorId, String action, UUID shopId, long expectedRevision) {
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(shopId, "shopId");
        pending.put(actorId, new PendingConfirmation(
                action,
                shopId,
                expectedRevision,
                Math.addExact(System.nanoTime(), ttlNanos)
        ));
    }

    /** Consumes the actor's token before checking it, preventing retries and duplicate completion. */
    public boolean consume(UUID actorId, String action, UUID shopId, long currentRevision) {
        PendingConfirmation candidate = pending.remove(actorId);
        return candidate != null
                && System.nanoTime() <= candidate.expiresAtNanos()
                && candidate.action().equals(action)
                && candidate.shopId().equals(shopId)
                && candidate.expectedRevision() == currentRevision;
    }

    public void clear(UUID actorId) {
        pending.remove(actorId);
    }

    public void clearAll() {
        pending.clear();
    }

    public int pendingCount() {
        return pending.size();
    }

    private record PendingConfirmation(
            String action,
            UUID shopId,
            long expectedRevision,
            long expiresAtNanos
    ) {
    }
}
