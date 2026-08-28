package com.plexon.shops.models;

import org.bukkit.Material;

/** Availability state shown in the directory. */
public enum ShopStatus {
    OPEN(Material.LIME_WOOL),
    CLOSED(Material.RED_WOOL),
    MAINTENANCE(Material.YELLOW_WOOL);

    private final Material icon;

    ShopStatus(Material icon) {
        this.icon = icon;
    }

    public Material icon() {
        return icon;
    }

    public ShopStatus next() {
        return switch (this) {
            case CLOSED -> OPEN;
            case OPEN -> MAINTENANCE;
            case MAINTENANCE -> CLOSED;
        };
    }
}
