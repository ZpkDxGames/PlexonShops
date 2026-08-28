package com.plexon.shops.gui;

import com.plexon.shops.models.DirectoryFilter;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Screen context kept on the InventoryHolder instead of unsafe title matching. */
public final class PlexonGuiHolder implements InventoryHolder {
    private final GuiType type;
    private final UUID shopId;
    private final int page;
    private final DirectoryFilter filter;
    private final Map<Integer, UUID> targets = new HashMap<>();
    private Inventory inventory;
    private ItemStack pendingIcon;

    public PlexonGuiHolder(GuiType type, UUID shopId, int page, DirectoryFilter filter) {
        this.type = type;
        this.shopId = shopId;
        this.page = page;
        this.filter = filter == null ? DirectoryFilter.ALL : filter;
    }

    public void attach(Inventory inventory) {
        if (this.inventory != null) {
            throw new IllegalStateException("Inventory holder is already attached");
        }
        this.inventory = inventory;
    }

    @Override
    public @NotNull Inventory getInventory() {
        if (inventory == null) {
            throw new IllegalStateException("Inventory holder has not been attached");
        }
        return inventory;
    }

    public GuiType type() {
        return type;
    }

    public UUID shopId() {
        return shopId;
    }

    public int page() {
        return page;
    }

    public DirectoryFilter filter() {
        return filter;
    }

    public void target(int slot, UUID id) {
        targets.put(slot, id);
    }

    public Optional<UUID> target(int slot) {
        return Optional.ofNullable(targets.get(slot));
    }

    public ItemStack pendingIcon() {
        return pendingIcon == null ? null : pendingIcon.clone();
    }

    public void pendingIcon(ItemStack item) {
        pendingIcon = item == null ? null : item.clone();
    }
}
