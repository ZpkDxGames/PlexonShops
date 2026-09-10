package com.plexon.shops.gui;

import com.plexon.shops.models.DirectoryFilter;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Typed inventory context for Phase 2 discovery screens. */
public final class DiscoveryGuiHolder implements InventoryHolder {
    private final DiscoveryGuiType type;
    private final UUID shopId;
    private final int page;
    private final DirectoryFilter filter;
    private final String query;
    private final Map<Integer, UUID> targets = new HashMap<>();
    private Inventory inventory;

    public DiscoveryGuiHolder(
            DiscoveryGuiType type,
            UUID shopId,
            int page,
            DirectoryFilter filter,
            String query
    ) {
        this.type = type;
        this.shopId = shopId;
        this.page = page;
        this.filter = filter == null ? DirectoryFilter.ALL : filter;
        this.query = query == null ? "" : query;
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

    public DiscoveryGuiType type() { return type; }
    public UUID shopId() { return shopId; }
    public int page() { return page; }
    public DirectoryFilter filter() { return filter; }
    public String query() { return query; }

    public void target(int slot, UUID id) { targets.put(slot, id); }
    public Optional<UUID> target(int slot) { return Optional.ofNullable(targets.get(slot)); }
}
