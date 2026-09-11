package com.plexon.shops.services;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class TeleportSettlementContractTest {
    @Test
    void quitCancelsOnlyPendingWarmup() throws Exception {
        String listener = Files.readString(Path.of(
                "src/main/java/com/plexon/shops/listeners/TeleportListener.java"));
        assertTrue(listener.contains("teleports.cancelPending(event.getPlayer().getUniqueId(), false)"));
        assertFalse(listener.contains("teleports.cancel(event.getPlayer().getUniqueId(), false)"));
    }

    @Test
    void pendingCancellationCannotRefundOrRevokeStartedAsyncTeleport() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/plexon/shops/services/TeleportService.java"));
        String method = section(source,
                "public void cancelPending(UUID playerUuid, boolean notify)",
                "public void cancelAll()");
        assertTrue(method.contains("cancelWarmup"));
        assertFalse(method.contains("inFlight.cancel"));
        assertFalse(method.contains("economy.refund"));
    }

    @Test
    void asyncCompletionRemainsAuthoritativeForSettlement() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/plexon/shops/services/TeleportService.java"));
        String execute = section(source,
                "private void execute(Player player, UUID shopId)",
                "private void finishAttempt(");
        assertTrue(execute.contains("player.teleportAsync(destination).whenComplete"));
        assertTrue(execute.contains("inFlight.isAuthoritative(playerUuid, attempt)"));
        assertTrue(execute.contains("finishAttempt("));

        String finish = section(source,
                "private void finishAttempt(",
                "private void ensureProgressCoordinator");
        assertTrue(finish.contains("if (error != null || !success)"));
        assertTrue(finish.contains("economy.refund(attempt.player(), attempt.chargedAmount())"));
        assertTrue(finish.contains("shops.recordVisit(shop.id(), playerUuid)"));
        assertTrue(finish.contains("inFlight.complete(playerUuid, attempt)"));
    }

    private static String section(String source, String startToken, String endToken) {
        int start = source.indexOf(startToken);
        int end = source.indexOf(endToken, start + startToken.length());
        assertTrue(start >= 0, "Missing start token: " + startToken);
        assertTrue(end > start, "Missing end token: " + endToken);
        return source.substring(start, end);
    }
}
