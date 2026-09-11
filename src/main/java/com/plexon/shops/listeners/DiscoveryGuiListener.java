package com.plexon.shops.listeners;

import com.plexon.shops.gui.DiscoveryGuiHolder;
import com.plexon.shops.gui.DiscoveryGuiManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/** Protects Phase 2 discovery inventory contents and routes typed actions. */
public final class DiscoveryGuiListener implements Listener {
    private final DiscoveryGuiManager guis;

    public DiscoveryGuiListener(DiscoveryGuiManager guis) {
        this.guis = guis;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof DiscoveryGuiHolder holder)) {
            return;
        }
        event.setCancelled(true);
        guis.handleClick(event, holder);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof DiscoveryGuiHolder) {
            event.setCancelled(true);
        }
    }
}
