package com.plexon.shops.listeners;

import com.plexon.shops.services.DiscoveryService;
import com.plexon.shops.services.ShopService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/** Refreshes owner activity and asynchronously primes cache-only discovery placeholders. */
public final class OwnerActivityListener implements Listener {
    private final ShopService shops;
    private final DiscoveryService discovery;

    public OwnerActivityListener(ShopService shops, DiscoveryService discovery) {
        this.shops = shops;
        this.discovery = discovery;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        shops.touchOwner(event.getPlayer().getUniqueId(), event.getPlayer().getName());
        discovery.preload(event.getPlayer().getUniqueId());
    }
}
