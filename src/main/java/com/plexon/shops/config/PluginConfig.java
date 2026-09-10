package com.plexon.shops.config;

import com.plexon.shops.models.Category;
import com.plexon.shops.models.ShopStatus;
import net.kyori.adventure.key.Key;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Validated, immutable runtime settings. Invalid candidates are rejected before activation. */
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
    private static final Set<String> BOSSBAR_COLORS = Set.of(
            "PINK", "BLUE", "RED", "GREEN", "YELLOW", "PURPLE", "WHITE"
    );
    private static final Set<String> BOSSBAR_OVERLAYS = Set.of(
            "PROGRESS", "NOTCHED_6", "NOTCHED_10", "NOTCHED_12", "NOTCHED_20"
    );

    public PluginConfig {
        blacklistedWorlds = Set.copyOf(blacklistedWorlds);
        guis = Map.copyOf(guis);
    }

    public static PluginConfig load(File file) {
        return from(YamlConfiguration.loadConfiguration(file));
    }

    public static PluginConfig from(YamlConfiguration yaml) {
        String databaseFile = yaml.getString("database.file", "shops.db");
        validateDatabaseFile(databaseFile);
        Database database = new Database(
                databaseFile,
                boundedInt(yaml, "database.maximum-pool-size", 2, 1, 8),
                boundedLong(yaml, "database.connection-timeout-millis", 5_000L, 250L, 60_000L)
        );
        Worker worker = new Worker(
                boundedInt(yaml, "worker.threads", 2, 1, 8),
                boundedInt(yaml, "worker.queue-capacity", 2_048, 64, 65_536),
                boundedInt(yaml, "worker.shutdown-timeout-seconds", 10, 1, 60)
        );
        Limits limits = new Limits(
                boundedInt(yaml, "limits.max-shops-per-player", 1, 1, 100),
                boundedInt(yaml, "limits.maximum-permission-limit", 100, 1, 1_000),
                boundedInt(yaml, "limits.max-categories-per-shop", 3, 1, Category.values().length),
                boundedInt(yaml, "limits.max-labeled-items-per-shop", 45, 0, 500),
                boundedInt(yaml, "limits.shop-name-length", 40, 3, 128),
                boundedInt(yaml, "limits.description-lines", 5, 1, 20),
                boundedInt(yaml, "limits.description-line-length", 80, 10, 256),
                boundedInt(yaml, "limits.label-length", 48, 3, 128)
        );

        ShopStatus status = parseStatus(yaml.getString("defaults.status", "CLOSED"));
        Category category = Category.parse(yaml.getString("defaults.category", "MISC"))
                .orElseThrow(() -> invalid("defaults.category", "unknown category"));
        double defaultFee = nonNegativeFinite(yaml, "defaults.teleport-fee", 25.0D);
        List<String> description = List.copyOf(yaml.getStringList("defaults.description"));
        if (description.size() > limits.descriptionLines()) {
            throw invalid("defaults.description", "contains more lines than limits.description-lines");
        }
        for (String line : description) {
            if (line != null && line.length() > limits.descriptionLineLength() * 4) {
                throw invalid("defaults.description", "contains an excessively long encoded line");
            }
        }
        Defaults defaults = new Defaults(status, category, defaultFee, description);

        String bossbarColor = normalizedEnum(yaml.getString("teleport.bossbar.color", "BLUE"));
        if (!BOSSBAR_COLORS.contains(bossbarColor)) {
            throw invalid("teleport.bossbar.color", "unknown boss-bar color");
        }
        String bossbarOverlay = normalizedEnum(yaml.getString("teleport.bossbar.overlay", "PROGRESS"));
        if (!BOSSBAR_OVERLAYS.contains(bossbarOverlay)) {
            throw invalid("teleport.bossbar.overlay", "unknown boss-bar overlay");
        }
        String warmupSound = requiredKey(yaml, "teleport.effects.warmup-sound", "minecraft:block.beacon.power_select");
        String arrivalSound = requiredKey(yaml, "teleport.effects.arrival-sound", "minecraft:entity.enderman.teleport");
        double maximumOwnerFee = nonNegativeFinite(yaml, "teleport.maximum-owner-fee", 5_000.0D);
        if (defaultFee > maximumOwnerFee) {
            throw invalid("defaults.teleport-fee", "cannot exceed teleport.maximum-owner-fee");
        }
        Teleport teleport = new Teleport(
                yaml.getBoolean("teleport.enabled", true),
                boundedInt(yaml, "teleport.warmup-seconds", 3, 0, 300),
                yaml.getBoolean("teleport.cancel-on-movement", true),
                yaml.getBoolean("teleport.cancel-on-damage", true),
                boundedInt(yaml, "teleport.cooldown-seconds", 10, 0, 3_600),
                yaml.getBoolean("teleport.owners-bypass-cooldown", true),
                yaml.getBoolean("teleport.economy-enabled", true),
                yaml.getBoolean("teleport.fail-open-without-vault", false),
                yaml.getBoolean("teleport.owners-can-set-fee", true),
                yaml.getBoolean("teleport.owners-bypass-fee", true),
                maximumOwnerFee,
                new TeleportBossBar(
                        yaml.getBoolean("teleport.bossbar.enabled", true),
                        bossbarColor,
                        bossbarOverlay,
                        boundedInt(yaml, "teleport.bossbar.update-interval-ticks", 2, 1, 20)
                ),
                new TeleportEffects(
                        yaml.getBoolean("teleport.effects.enabled", true),
                        warmupSound,
                        arrivalSound,
                        boundedFloat(yaml, "teleport.effects.volume", 0.8D, 0.0D, 4.0D),
                        boundedFloat(yaml, "teleport.effects.warmup-pitch", 1.25D, 0.5D, 2.0D),
                        boundedFloat(yaml, "teleport.effects.arrival-pitch", 1.0D, 0.5D, 2.0D),
                        boundedInt(yaml, "teleport.effects.portal-particles", 32, 0, 256)
                )
        );

        String cancelWord = yaml.getString("chat-prompts.cancel-word", "cancel");
        if (cancelWord == null || cancelWord.isBlank()) {
            throw invalid("chat-prompts.cancel-word", "must not be blank");
        }
        Prompts prompts = new Prompts(
                cancelWord,
                boundedInt(yaml, "chat-prompts.timeout-seconds", 60, 10, 600)
        );
        Set<String> blacklisted = yaml.getStringList("blacklisted-worlds").stream()
                .map(String::strip)
                .filter(value -> !value.isBlank())
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
                boundedInt(yaml, "inactivity-purge-days", 45, 0, 3_650),
                blacklisted,
                layouts
        );
    }

    public GuiLayout gui(String key, int defaultSize) {
        return guis.getOrDefault(key, new GuiLayout(defaultSize, List.of(), Map.of()));
    }

    public boolean isWorldBlacklisted(String worldName) {
        return worldName != null && blacklistedWorlds.contains(worldName.toLowerCase(Locale.ROOT));
    }

    private static Map<String, GuiLayout> loadLayouts(ConfigurationSection root) {
        if (root == null) {
            return Map.of();
        }
        Map<String, GuiLayout> result = new HashMap<>();
        for (String guiKey : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(guiKey);
            if (section == null) {
                throw invalid("guis." + guiKey, "must be a section");
            }
            int size = section.getInt("size", 54);
            if (size < 9 || size > 54 || size % 9 != 0) {
                throw invalid("guis." + guiKey + ".size", "must be a multiple of 9 from 9 through 54");
            }
            List<Integer> contentSlots = List.copyOf(section.getIntegerList("content-slots"));
            Set<Integer> occupied = new HashSet<>();
            for (int slot : contentSlots) {
                validateSlot(guiKey, "content-slots", slot, size);
                if (!occupied.add(slot)) {
                    throw invalid("guis." + guiKey + ".content-slots", "contains duplicate slot " + slot);
                }
            }
            Map<String, Integer> slots = new LinkedHashMap<>();
            ConfigurationSection slotSection = section.getConfigurationSection("slots");
            if (slotSection != null) {
                for (String slotKey : slotSection.getKeys(false)) {
                    int slot = slotSection.getInt(slotKey);
                    validateSlot(guiKey, "slots." + slotKey, slot, size);
                    if (!occupied.add(slot)) {
                        throw invalid("guis." + guiKey + ".slots." + slotKey,
                                "overlaps another configured slot at " + slot);
                    }
                    slots.put(slotKey, slot);
                }
            }
            result.put(guiKey, new GuiLayout(size, contentSlots, slots));
        }
        return result;
    }

    private static void validateSlot(String guiKey, String key, int slot, int size) {
        if (slot < 0 || slot >= size) {
            throw invalid("guis." + guiKey + "." + key, "slot " + slot + " is outside inventory size " + size);
        }
    }

    private static ShopStatus parseStatus(String raw) {
        try {
            return ShopStatus.valueOf(String.valueOf(raw).strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw invalid("defaults.status", "unknown status");
        }
    }

    private static int boundedInt(YamlConfiguration yaml, String path, int fallback, int min, int max) {
        int value = yaml.getInt(path, fallback);
        if (value < min || value > max) {
            throw invalid(path, "must be between " + min + " and " + max);
        }
        return value;
    }

    private static long boundedLong(YamlConfiguration yaml, String path, long fallback, long min, long max) {
        long value = yaml.getLong(path, fallback);
        if (value < min || value > max) {
            throw invalid(path, "must be between " + min + " and " + max);
        }
        return value;
    }

    private static float boundedFloat(YamlConfiguration yaml, String path, double fallback, double min, double max) {
        double value = yaml.getDouble(path, fallback);
        if (!Double.isFinite(value) || value < min || value > max) {
            throw invalid(path, "must be finite and between " + min + " and " + max);
        }
        return (float) value;
    }

    private static double nonNegativeFinite(YamlConfiguration yaml, String path, double fallback) {
        double value = yaml.getDouble(path, fallback);
        if (!Double.isFinite(value) || value < 0.0D) {
            throw invalid(path, "must be a finite non-negative number");
        }
        return value;
    }

    private static String requiredKey(YamlConfiguration yaml, String path, String fallback) {
        String value = yaml.getString(path, fallback);
        String candidate = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        try {
            Key.key(candidate);
            return candidate;
        } catch (IllegalArgumentException error) {
            throw invalid(path, "must be a valid namespaced key");
        }
    }

    private static String normalizedEnum(String value) {
        return value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
    }

    private static void validateDatabaseFile(String file) {
        if (file == null || file.isBlank() || file.contains("..") || new File(file).isAbsolute()) {
            throw invalid("database.file", "must be a relative file path without parent traversal");
        }
    }

    private static IllegalArgumentException invalid(String path, String reason) {
        return new IllegalArgumentException("Invalid PlexonShops configuration at '" + path + "': " + reason);
    }

    public record Database(String file, int maximumPoolSize, long connectionTimeoutMillis) {
        public Database {
            validateDatabaseFile(file);
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
            boolean cancelOnDamage,
            int cooldownSeconds,
            boolean ownersBypassCooldown,
            boolean economyEnabled,
            boolean failOpenWithoutVault,
            boolean ownersCanSetFee,
            boolean ownersBypassFee,
            double maximumOwnerFee,
            TeleportBossBar bossBar,
            TeleportEffects effects
    ) {
        public Teleport {
            bossBar = bossBar == null ? new TeleportBossBar(true, "BLUE", "PROGRESS", 2) : bossBar;
            effects = effects == null
                    ? new TeleportEffects(true, "minecraft:block.beacon.power_select",
                    "minecraft:entity.enderman.teleport", 0.8F, 1.25F, 1.0F, 32)
                    : effects;
        }
    }

    public record TeleportBossBar(boolean enabled, String color, String overlay, int updateIntervalTicks) {
        public TeleportBossBar {
            color = normalizedEnum(color);
            overlay = normalizedEnum(overlay);
        }
    }

    public record TeleportEffects(
            boolean enabled,
            String warmupSound,
            String arrivalSound,
            float volume,
            float warmupPitch,
            float arrivalPitch,
            int portalParticles
    ) {
        public TeleportEffects {
            warmupSound = warmupSound == null ? "" : warmupSound.strip().toLowerCase(Locale.ROOT);
            arrivalSound = arrivalSound == null ? "" : arrivalSound.strip().toLowerCase(Locale.ROOT);
        }
    }

    public record Prompts(String cancelWord, int timeoutSeconds) {
        public Prompts {
            if (cancelWord == null || cancelWord.isBlank()) {
                throw invalid("chat-prompts.cancel-word", "must not be blank");
            }
            cancelWord = cancelWord.trim();
        }
    }
}
