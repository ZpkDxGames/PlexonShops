package com.plexon.shops.models;

import org.bukkit.Material;

import java.util.Locale;
import java.util.Optional;

/** Directory categories available to every shop. */
public enum Category {
    BLOCKS(Material.BRICKS),
    TOOLS(Material.DIAMOND_PICKAXE),
    COMBAT(Material.DIAMOND_SWORD),
    REDSTONE(Material.REDSTONE),
    FARMING(Material.WHEAT),
    MISC(Material.ENDER_CHEST);

    private final Material icon;

    Category(Material icon) {
        this.icon = icon;
    }

    public Material icon() {
        return icon;
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<Category> parse(String input) {
        if (input == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(input.strip().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException error) {
            return Optional.empty();
        }
    }
}
