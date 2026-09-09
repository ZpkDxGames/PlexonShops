package com.plexon.shops.listeners;

import com.plexon.shops.services.TeleportService;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;

/** Optimized local fallback for movement, damage and disconnect teleport guards. */
public final class TeleportListener implements Listener {
    private final TeleportService teleports;

    public TeleportListener(TeleportService teleports) {
        this.teleports = teleports;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        teleports.recordMovementReceived();
        Player player = event.getPlayer();
        UUID playerUuid = player.getUniqueId();
        if (!teleports.hasPending(playerUuid)) {
            teleports.recordMovementFastReject();
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || samePosition(from, to)) {
            return;
        }
        teleports.handleMovementFacts(
                playerUuid,
                to.getWorld() == null ? null : to.getWorld().getUID(),
                to.getX(),
                to.getY(),
                to.getZ()
        );
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        teleports.recordDamageReceived();
        UUID playerUuid = player.getUniqueId();
        if (!teleports.hasPending(playerUuid)) {
            teleports.recordDamageFastReject();
            return;
        }
        teleports.handleDamageFacts(playerUuid);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        teleports.handleQuit(event.getPlayer().getUniqueId());
    }

    private boolean samePosition(Location from, Location to) {
        if (from.getWorld() != to.getWorld()) {
            return false;
        }
        return Double.compare(from.getX(), to.getX()) == 0
                && Double.compare(from.getY(), to.getY()) == 0
                && Double.compare(from.getZ(), to.getZ()) == 0;
    }
}
