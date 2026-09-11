package com.plexon.shops.gui;

import com.plexon.shops.models.DirectoryFilter;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Typed inventory context for Phase 3 directory screens. */
public final class DiscoveryGuiHolder implements InventoryHolder {
    private final DiscoveryGuiType type;
    private final UUID shopId;
    private final int page;
    private final DirectoryFilter filter;
    private final String query;
    private final DiscoveryGuiType returnType;
    private final int returnPage;
    private final DirectoryFilter returnFilter;
    private final String returnQuery;
    private final long shopRevision;
    private final Map<Integer, UUID> targets = new HashMap<>();
    private final AtomicBoolean submitted = new AtomicBoolean();
    private Inventory inventory;

    public DiscoveryGuiHolder(
            DiscoveryGuiType type,
            UUID shopId,
            int page,
            DirectoryFilter filter,
            String query
    ) {
        this(type, shopId, page, filter, query, DiscoveryGuiType.HUB, 0, DirectoryFilter.ALL, "", 0L);
    }

    public DiscoveryGuiHolder(
            DiscoveryGuiType type,
            UUID shopId,
            int page,
            DirectoryFilter filter,
            String query,
            DiscoveryGuiType returnType,
            int returnPage,
            DirectoryFilter returnFilter,
            String returnQuery,
            long shopRevision
    ) {
        this.type = type;
        this.shopId = shopId;
        this.page = Math.max(0, page);
        this.filter = filter == null ? DirectoryFilter.ALL : filter;
        this.query = query == null ? "" : query;
        this.returnType = returnType == null ? DiscoveryGuiType.HUB : returnType;
        this.returnPage = Math.max(0, returnPage);
        this.returnFilter = returnFilter == null ? DirectoryFilter.ALL : returnFilter;
        this.returnQuery = returnQuery == null ? "" : returnQuery;
        this.shopRevision = shopRevision;
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
    public DiscoveryGuiType returnType() { return returnType; }
    public int returnPage() { return returnPage; }
    public DirectoryFilter returnFilter() { return returnFilter; }
    public String returnQuery() { return returnQuery; }
    public long shopRevision() { return shopRevision; }

    public boolean matchesRevision(long currentRevision) {
        return shopRevision == 0L || shopRevision == currentRevision;
    }

    /** Single-use gate for paid/mutating actions from one rendered inventory. */
    public boolean trySubmit() {
        return submitted.compareAndSet(false, true);
    }

    public void target(int slot, UUID id) { targets.put(slot, id); }
    public Optional<UUID> target(int slot) { return Optional.ofNullable(targets.get(slot)); }
}
