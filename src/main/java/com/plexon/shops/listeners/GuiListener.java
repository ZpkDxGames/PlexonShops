package com.plexon.shops.listeners;

import com.plexon.shops.gui.GuiManager;
import com.plexon.shops.gui.PlexonGuiHolder;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/** Protects GUI contents and delegates screen actions. */
public final class GuiListener implements Listener {
    private final GuiManager guis;

    public GuiListener(GuiManager guis) {
        this.guis = guis;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof PlexonGuiHolder holder)) {
            return;
        }
        event.setCancelled(true);
        guis.handleClick(event, holder);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof PlexonGuiHolder) {
            event.setCancelled(true);
        }
    }
}
