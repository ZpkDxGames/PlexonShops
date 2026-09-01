package com.plexon.shops.listeners;

import com.plexon.shops.services.TeleportService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Movement and disconnect cancellation for pending shop teleports. */
public final class TeleportListener implements Listener {
    private final TeleportService teleports;

    public TeleportListener(TeleportService teleports) {
        this.teleports = teleports;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        teleports.handleMovement(event.getPlayer(), event.getTo());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof org.bukkit.entity.Player player) {
            teleports.handleDamage(player);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        teleports.cancel(event.getPlayer().getUniqueId(), false);
    }
}
