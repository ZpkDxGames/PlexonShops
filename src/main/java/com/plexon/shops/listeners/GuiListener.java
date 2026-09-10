package com.plexon.shops.listeners;

import com.plexon.shops.config.PluginConfig;
import com.plexon.shops.gui.GuiManager;
import com.plexon.shops.gui.GuiType;
import com.plexon.shops.gui.PlexonGuiHolder;
import com.plexon.shops.messages.MessageService;
import com.plexon.shops.models.Shop;
import com.plexon.shops.services.ConfirmationService;
import com.plexon.shops.services.ShopService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

import java.util.function.Supplier;

/** Protects mature GUI contents and enforces staged destructive confirmation before delegation. */
public final class GuiListener implements Listener {
    private static final String DELETE_ACTION = "delete-shop";

    private final GuiManager guis;
    private final ShopService shops;
    private final ConfirmationService confirmations;
    private final Supplier<PluginConfig> config;
    private final Supplier<MessageService> messages;

    public GuiListener(
            GuiManager guis,
            ShopService shops,
            ConfirmationService confirmations,
            Supplier<PluginConfig> config,
            Supplier<MessageService> messages
    ) {
        this.guis = guis;
        this.shops = shops;
        this.confirmations = confirmations;
        this.config = config;
        this.messages = messages;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof PlexonGuiHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || holder.type() != GuiType.DELETE_CONFIRM) {
            guis.handleClick(event, holder);
            return;
        }
        int slot = event.getRawSlot();
        int confirmSlot = config.get().gui("delete-confirm", 27).slot("confirm", 11);
        int cancelSlot = config.get().gui("delete-confirm", 27).slot("cancel", 15);
        if (slot == cancelSlot) {
            confirmations.clear(player.getUniqueId());
            guis.handleClick(event, holder);
            return;
        }
        if (slot != confirmSlot) {
            return;
        }
        Shop shop = shops.find(holder.shopId()).orElse(null);
        if (shop == null) {
            guis.handleClick(event, holder);
            return;
        }
        if (confirmations.consume(player.getUniqueId(), DELETE_ACTION, shop.id(), shop.updatedAtEpochSecond())) {
            guis.handleClick(event, holder);
            return;
        }
        confirmations.stage(player.getUniqueId(), DELETE_ACTION, shop.id(), shop.updatedAtEpochSecond());
        messages.get().send(player, "confirmation-staged");
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof PlexonGuiHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof PlexonGuiHolder holder
                && holder.type() == GuiType.DELETE_CONFIRM
                && event.getPlayer() instanceof Player player) {
            confirmations.clear(player.getUniqueId());
        }
    }
}
