package com.plexon.shops.integrations;

import com.plexon.shops.models.Shop;
import com.plexon.shops.services.ShopService;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/** Internal PlaceholderAPI expansion registered only when PlaceholderAPI is present. */
public final class PlexonShopsExpansion extends PlaceholderExpansion {
    private final JavaPlugin plugin;
    private final ShopService shops;

    public PlexonShopsExpansion(JavaPlugin plugin, ShopService shops) {
        this.plugin = plugin;
        this.shops = shops;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "plexonshops";
    }

    @Override
    public @NotNull String getAuthor() {
        return String.join(", ", plugin.getPluginMeta().getAuthors());
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(@Nullable OfflinePlayer player, @NotNull String params) {
        return switch (params.toLowerCase(Locale.ROOT)) {
            case "total" -> Integer.toString(shops.totalCount());
            case "open" -> Integer.toString(shops.openCount());
            case "owned" -> player == null ? "0" : Integer.toString(shops.ownedBy(player.getUniqueId()).size());
            case "name" -> first(player).map(Shop::name).orElse("");
            case "status" -> first(player).map(shop -> shop.status().name()).orElse("NONE");
            case "rating" -> first(player).map(shop -> String.format(Locale.ROOT, "%.2f", shop.averageRating())).orElse("0.00");
            case "visitors" -> first(player).map(shop -> Long.toString(shop.visitors().totalVisits())).orElse("0");
            case "unique_visitors" -> first(player).map(shop -> Integer.toString(shop.visitors().uniqueCount())).orElse("0");
            default -> null;
        };
    }

    private java.util.Optional<Shop> first(OfflinePlayer player) {
        if (player == null) {
            return java.util.Optional.empty();
        }
        List<Shop> owned = shops.ownedBy(player.getUniqueId());
        return owned.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(owned.getFirst());
    }
}
