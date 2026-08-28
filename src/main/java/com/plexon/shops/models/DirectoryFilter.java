package com.plexon.shops.models;

import org.bukkit.Material;

import java.util.Locale;

/** Filters supported by the global directory. */
public enum DirectoryFilter {
    ALL(Material.NETHER_STAR),
    BLOCKS(Material.BRICKS),
    TOOLS(Material.DIAMOND_PICKAXE),
    COMBAT(Material.DIAMOND_SWORD),
    REDSTONE(Material.REDSTONE),
    FARMING(Material.WHEAT),
    MISC(Material.ENDER_CHEST),
    LABELED(Material.NAME_TAG);

    private final Material icon;

    DirectoryFilter(Material icon) {
        this.icon = icon;
    }

    public Material icon() {
        return icon;
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public boolean matches(Shop shop) {
        return switch (this) {
            case ALL -> true;
            case LABELED -> !shop.labeledItems().isEmpty();
            default -> shop.categories().contains(Category.valueOf(name()));
        };
    }
}
