package com.plexon.shops.config;

import com.plexon.shops.models.Category;
import com.plexon.shops.models.ShopStatus;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Validated, immutable runtime settings. */
public record PluginConfig(
        Database database,
        Worker worker,
        Limits limits,
        Defaults defaults,
        Teleport teleport,
        Prompts prompts,
        int inactivityPurgeDays,
        Set<String> blacklistedWorlds,
        Map<String, GuiLayout> guis
) {
    public PluginConfig {
        blacklistedWorlds = Set.copyOf(blacklistedWorlds);
        guis = Map.copyOf(guis);
    }

    public static PluginConfig load(File file) {
        return from(YamlConfiguration.loadConfiguration(file));
    }

    public static PluginConfig from(YamlConfiguration yaml) {
        Database database = new Database(
                yaml.getString("database.file", "shops.db"),
                Math.clamp(yaml.getInt("database.maximum-pool-size", 2), 1, 8),
                Math.clamp(yaml.getLong("database.connection-timeout-millis", 5_000L), 250L, 60_000L)
        );
        Worker worker = new Worker(
                Math.clamp(yaml.getInt("worker.threads", 2), 1, 8),
                Math.clamp(yaml.getInt("worker.queue-capacity", 2_048), 64, 65_536),
                Math.clamp(yaml.getInt("worker.shutdown-timeout-seconds", 10), 1, 60)
        );
        Limits limits = new Limits(
                Math.clamp(yaml.getInt("limits.max-shops-per-player", 1), 1, 100),
                Math.clamp(yaml.getInt("limits.maximum-permission-limit", 100), 1, 1_000),
                Math.clamp(yaml.getInt("limits.max-categories-per-shop", 3), 1, Category.values().length),
                Math.clamp(yaml.getInt("limits.max-labeled-items-per-shop", 45), 0, 500),
                Math.clamp(yaml.getInt("limits.shop-name-length", 40), 3, 128),
                Math.clamp(yaml.getInt("limits.description-lines", 5), 1, 20),
                Math.clamp(yaml.getInt("limits.description-line-length", 80), 10, 256),
                Math.clamp(yaml.getInt("limits.label-length", 48), 3, 128)
        );
        ShopStatus status = parseStatus(yaml.getString("defaults.status", "CLOSED"));
        Category category = Category.parse(yaml.getString("defaults.category", "MISC")).orElse(Category.MISC);
        Defaults defaults = new Defaults(
                status,
                category,
                Math.max(0.0D, yaml.getDouble("defaults.teleport-fee", 25.0D)),
                List.copyOf(yaml.getStringList("defaults.description"))
        );
        Teleport teleport = new Teleport(
                yaml.getBoolean("teleport.enabled", true),
                Math.clamp(yaml.getInt("teleport.warmup-seconds", 3), 0, 300),
                yaml.getBoolean("teleport.cancel-on-movement", true),
                yaml.getBoolean("teleport.economy-enabled", true),
                yaml.getBoolean("teleport.fail-open-without-vault", false),
                yaml.getBoolean("teleport.owners-can-set-fee", true),
                Math.max(0.0D, yaml.getDouble("teleport.maximum-owner-fee", 5_000.0D))
        );
        Prompts prompts = new Prompts(
                yaml.getString("chat-prompts.cancel-word", "cancel"),
                Math.clamp(yaml.getInt("chat-prompts.timeout-seconds", 60), 10, 600)
        );
        Set<String> blacklisted = yaml.getStringList("blacklisted-worlds").stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        Map<String, GuiLayout> layouts = loadLayouts(yaml.getConfigurationSection("guis"));

        return new PluginConfig(
                database,
                worker,
                limits,
                defaults,
                teleport,
                prompts,
                Math.clamp(yaml.getInt("inactivity-purge-days", 45), 0, 3_650),
                blacklisted,
                layouts
        );
    }

    public GuiLayout gui(String key, int defaultSize) {
        return guis.getOrDefault(key, new GuiLayout(defaultSize, List.of(), Map.of()));
    }

    public boolean isWorldBlacklisted(String worldName) {
        return blacklistedWorlds.contains(worldName.toLowerCase(Locale.ROOT));
    }

    private static Map<String, GuiLayout> loadLayouts(ConfigurationSection root) {
        if (root == null) {
            return Map.of();
        }
        Map<String, GuiLayout> result = new HashMap<>();
        for (String guiKey : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(guiKey);
            if (section == null) {
                continue;
            }
            int size = section.getInt("size", 54);
            List<Integer> contentSlots = section.getIntegerList("content-slots");
            Map<String, Integer> slots = new LinkedHashMap<>();
            ConfigurationSection slotSection = section.getConfigurationSection("slots");
            if (slotSection != null) {
                for (String slotKey : slotSection.getKeys(false)) {
                    slots.put(slotKey, slotSection.getInt(slotKey));
                }
            }
            result.put(guiKey, new GuiLayout(size, contentSlots, slots));
        }
        return result;
    }

    private static ShopStatus parseStatus(String raw) {
        try {
            return ShopStatus.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return ShopStatus.CLOSED;
        }
    }

    public record Database(String file, int maximumPoolSize, long connectionTimeoutMillis) {
        public Database {
            if (file == null || file.isBlank() || file.contains("..") || new File(file).isAbsolute()) {
                file = "shops.db";
            }
        }
    }

    public record Worker(int threads, int queueCapacity, int shutdownTimeoutSeconds) {
    }

    public record Limits(
            int maxShopsPerPlayer,
            int maximumPermissionLimit,
            int maxCategoriesPerShop,
            int maxLabeledItemsPerShop,
            int shopNameLength,
            int descriptionLines,
            int descriptionLineLength,
            int labelLength
    ) {
    }

    public record Defaults(
            ShopStatus status,
            Category category,
            double teleportFee,
            List<String> description
    ) {
        public Defaults {
            description = List.copyOf(description);
        }
    }

    public record Teleport(
            boolean enabled,
            int warmupSeconds,
            boolean cancelOnMovement,
            boolean economyEnabled,
            boolean failOpenWithoutVault,
            boolean ownersCanSetFee,
            double maximumOwnerFee
    ) {
    }

    public record Prompts(String cancelWord, int timeoutSeconds) {
        public Prompts {
            cancelWord = cancelWord == null || cancelWord.isBlank() ? "cancel" : cancelWord.trim();
        }
    }
}
