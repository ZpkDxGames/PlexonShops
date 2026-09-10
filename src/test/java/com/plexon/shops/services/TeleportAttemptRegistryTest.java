package com.plexon.shops.services;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TeleportAttemptRegistryTest {
    @Test
    void rejectsSecondAttemptUntilAuthoritativeAttemptTerminates() {
        TeleportAttemptRegistry<Object> registry = new TeleportAttemptRegistry<>();
        UUID player = UUID.randomUUID();
        Object first = new Object();
        assertTrue(registry.begin(player, first));
        assertFalse(registry.begin(player, new Object()));
        assertTrue(registry.contains(player));
        assertTrue(registry.isAuthoritative(player, first));
    }

    @Test
    void staleCallbackCannotCompleteNewerAttempt() {
        TeleportAttemptRegistry<Object> registry = new TeleportAttemptRegistry<>();
        UUID player = UUID.randomUUID();
        Object oldAttempt = new Object();
        Object newAttempt = new Object();
        assertTrue(registry.begin(player, oldAttempt));
        assertSame(oldAttempt, registry.cancel(player));
        assertTrue(registry.begin(player, newAttempt));
        assertFalse(registry.complete(player, oldAttempt));
        assertTrue(registry.isAuthoritative(player, newAttempt));
        assertTrue(registry.complete(player, newAttempt));
    }

    @Test
    void terminalSideEffectsCanRunAtMostOnce() {
        TeleportAttemptRegistry<Object> registry = new TeleportAttemptRegistry<>();
        UUID player = UUID.randomUUID();
        Object attempt = new Object();
        AtomicInteger refunds = new AtomicInteger();
        AtomicInteger visits = new AtomicInteger();
        AtomicInteger cooldowns = new AtomicInteger();
        assertTrue(registry.begin(player, attempt));
        if (registry.complete(player, attempt)) {
            refunds.incrementAndGet();
        }
        if (registry.complete(player, attempt)) {
            refunds.incrementAndGet();
            visits.incrementAndGet();
            cooldowns.incrementAndGet();
        }
        assertEquals(1, refunds.get());
        assertEquals(0, visits.get());
        assertEquals(0, cooldowns.get());
    }

    @Test
    void duplicateAcceptanceCannotChargeOrInvokeTeleportTwice() {
        TeleportAttemptRegistry<Object> registry = new TeleportAttemptRegistry<>();
        UUID player = UUID.randomUUID();
        AtomicInteger charges = new AtomicInteger();
        AtomicInteger teleports = new AtomicInteger();
        Object first = new Object();
        if (registry.begin(player, first)) {
            charges.incrementAndGet();
            teleports.incrementAndGet();
        }
        if (registry.begin(player, new Object())) {
            charges.incrementAndGet();
            teleports.incrementAndGet();
        }
        assertEquals(1, charges.get());
        assertEquals(1, teleports.get());
    }

    @Test
    void cancellationOwnsCompensationOnlyOnce() {
        TeleportAttemptRegistry<Object> registry = new TeleportAttemptRegistry<>();
        UUID player = UUID.randomUUID();
        Object attempt = new Object();
        assertTrue(registry.begin(player, attempt));
        assertSame(attempt, registry.cancel(player));
        assertNull(registry.cancel(player));
        assertFalse(registry.complete(player, attempt));
    }

    @Test
    void shutdownDrainReleasesEveryAttemptExactlyOnce() {
        TeleportAttemptRegistry<Object> registry = new TeleportAttemptRegistry<>();
        assertTrue(registry.begin(UUID.randomUUID(), new Object()));
        assertTrue(registry.begin(UUID.randomUUID(), new Object()));
        List<Object> drained = registry.drain();
        assertEquals(2, drained.size());
        assertEquals(0, registry.size());
        assertTrue(registry.drain().isEmpty());
    }
}
