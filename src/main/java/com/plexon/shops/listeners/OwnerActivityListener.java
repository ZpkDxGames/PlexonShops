package com.plexon.shops.listeners;

import com.plexon.shops.services.ShopService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/** Refreshes owner names and inactivity timestamps without blocking the join event. */
public final class OwnerActivityListener implements Listener {
    private final ShopService shops;

    public OwnerActivityListener(ShopService shops) {
        this.shops = shops;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        shops.touchOwner(event.getPlayer().getUniqueId(), event.getPlayer().getName());
    }
}
