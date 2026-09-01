package com.plexon.shops.services;

import com.plexon.shops.config.PluginConfig;
import com.plexon.shops.integrations.VaultEconomyHook;
import com.plexon.shops.messages.MessageService;
import com.plexon.shops.models.Shop;
import com.plexon.shops.models.ShopStatus;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Cancellable boss-bar warmups followed by Paper asynchronous teleports. */
public final class TeleportService {
    private final JavaPlugin plugin;
    private final ShopService shops;
    private final VaultEconomyHook economy;
    private final Supplier<PluginConfig> config;
    private final Supplier<MessageService> messages;
    private final Map<UUID, PendingTeleport> pending = new ConcurrentHashMap<>();
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();

    public TeleportService(
            JavaPlugin plugin,
            ShopService shops,
            VaultEconomyHook economy,
            Supplier<PluginConfig> config,
            Supplier<MessageService> messages
    ) {
        this.plugin = plugin;
        this.shops = shops;
        this.economy = economy;
        this.config = config;
        this.messages = messages;
    }

    public void request(Player player, Shop requestedShop) {
        PluginConfig settings = config.get();
        if (!settings.teleport().enabled()) {
            messages.get().send(player, "teleport-disabled");
            return;
        }
        Shop shop = shops.find(requestedShop.id()).orElse(null);
        if (shop == null) {
            messages.get().send(player, "shop-not-found");
            return;
        }
        if (shop.status() != ShopStatus.OPEN) {
            messages.get().send(player, "shop-closed", statusResolver(shop.status()));
            return;
        }
        Location destination = shop.location().resolve().orElse(null);
        if (destination == null || destination.getWorld() == null) {
            messages.get().send(player, "teleport-failed");
            return;
        }
        if (settings.isWorldBlacklisted(destination.getWorld().getName())) {
            messages.get().send(player, "invalid-world");
            return;
        }
        if (!bypassesCooldown(player, shop, settings.teleport())) {
            long remaining = cooldowns.getOrDefault(player.getUniqueId(), 0L) - System.currentTimeMillis();
            if (remaining > 0L) {
                messages.get().send(player, "teleport-cooldown",
                        Placeholder.unparsed("seconds", Long.toString((remaining + 999L) / 1_000L)));
                return;
            }
            cooldowns.remove(player.getUniqueId());
        }

        cancel(player.getUniqueId(), false);
        int warmup = settings.teleport().warmupSeconds();
        if (warmup <= 0) {
            execute(player, shop.id());
            return;
        }

        Location start = player.getLocation();
        BossBar bossBar = createBossBar(shop, warmup, settings.teleport().bossBar());
        if (bossBar != null) {
            player.showBossBar(bossBar);
        }
        playEffects(player, false, settings.teleport().effects());

        long startedAt = System.nanoTime();
        long durationNanos = warmup * 1_000_000_000L;
        BukkitTask completion = Bukkit.getScheduler().runTaskLater(
                plugin,
                () -> execute(player, shop.id()),
                warmup * 20L
        );
        BukkitTask progress = bossBar == null ? null : Bukkit.getScheduler().runTaskTimer(
                plugin,
                () -> updateBossBar(player.getUniqueId(), shop.id(), startedAt, durationNanos),
                1L,
                settings.teleport().bossBar().updateIntervalTicks()
        );
        pending.put(player.getUniqueId(), new PendingTeleport(
                start.getWorld() == null ? null : start.getWorld().getUID(),
                start.getX(),
                start.getY(),
                start.getZ(),
                completion,
                progress,
                bossBar
        ));
        messages.get().send(player, "teleport-start",
                messages.get().storedTag("shop", shop.name()),
                Placeholder.unparsed("seconds", Integer.toString(warmup)));
    }

    public void handleMovement(Player player, Location to) {
        if (!config.get().teleport().cancelOnMovement()) {
            return;
        }
        PendingTeleport active = pending.get(player.getUniqueId());
        if (active == null || to.getWorld() == null) {
            return;
        }
        if (!to.getWorld().getUID().equals(active.worldUuid()) || squaredDistance(to, active) > 1.0E-6D) {
            cancel(player.getUniqueId(), "teleport-cancelled");
        }
    }

    public void handleDamage(Player player) {
        if (config.get().teleport().cancelOnDamage()) {
            cancel(player.getUniqueId(), "teleport-cancelled-damage");
        }
    }

    public void cancel(UUID playerUuid, boolean notify) {
        cancel(playerUuid, notify ? "teleport-cancelled" : null);
    }

    public void cancelAll() {
        pending.keySet().forEach(uuid -> cancel(uuid, (String) null));
        cooldowns.clear();
    }

    private void execute(Player player, UUID shopId) {
        clearPending(player.getUniqueId());
        if (!player.isOnline()) {
            return;
        }
        Shop shop = shops.find(shopId).orElse(null);
        if (shop == null) {
            messages.get().send(player, "shop-not-found");
            return;
        }
        if (shop.status() != ShopStatus.OPEN) {
            messages.get().send(player, "shop-closed", statusResolver(shop.status()));
            return;
        }
        PluginConfig settings = config.get();
        Location destination = shop.location().resolve().orElse(null);
        if (destination == null || destination.getWorld() == null
                || settings.isWorldBlacklisted(destination.getWorld().getName())) {
            messages.get().send(player, "teleport-failed");
            return;
        }

        boolean feeBypass = player.hasPermission("plexonshops.teleport.fee.bypass")
                || (settings.teleport().ownersBypassFee() && shop.ownerUuid().equals(player.getUniqueId()));
        VaultEconomyHook.ChargeResult charge = feeBypass
                ? VaultEconomyHook.ChargeResult.free()
                : economy.charge(player, shop.teleportFee());
        if (!charge.success()) {
            if (charge.failure() == VaultEconomyHook.ChargeFailure.INSUFFICIENT_FUNDS) {
                messages.get().send(player, "insufficient-funds",
                        Placeholder.unparsed("fee", economy.format(shop.teleportFee())));
            } else {
                messages.get().send(player, "economy-unavailable");
            }
            return;
        }

        player.teleportAsync(destination).whenComplete((success, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null || !Boolean.TRUE.equals(success)) {
                economy.refund(player, charge.chargedAmount());
                messages.get().send(player, "teleport-failed");
                return;
            }
            shops.recordVisit(shop.id(), player.getUniqueId());
            if (!bypassesCooldown(player, shop, settings.teleport()) && settings.teleport().cooldownSeconds() > 0) {
                cooldowns.put(player.getUniqueId(),
                        System.currentTimeMillis() + settings.teleport().cooldownSeconds() * 1_000L);
            }
            playEffects(player, true, settings.teleport().effects());
            messages.get().send(player, "teleport-success", messages.get().storedTag("shop", shop.name()));
            player.sendActionBar(messages.get().get(
                    "teleport-arrival-actionbar",
                    messages.get().storedTag("shop", shop.name())
            ));
        }));
    }

    private void updateBossBar(UUID playerUuid, UUID shopId, long startedAt, long durationNanos) {
        PendingTeleport active = pending.get(playerUuid);
        if (active == null || active.bossBar() == null) {
            return;
        }
        Player player = Bukkit.getPlayer(playerUuid);
        Shop shop = shops.find(shopId).orElse(null);
        if (player == null || shop == null) {
            cancel(playerUuid, (String) null);
            return;
        }
        long remainingNanos = Math.max(0L, durationNanos - (System.nanoTime() - startedAt));
        float progress = (float) Math.clamp(remainingNanos / (double) durationNanos, 0.0D, 1.0D);
        long seconds = Math.max(1L, (remainingNanos + 999_999_999L) / 1_000_000_000L);
        active.bossBar().progress(progress);
        active.bossBar().name(messages.get().get(
                "teleport-bossbar",
                messages.get().storedTag("shop", shop.name()),
                Placeholder.unparsed("seconds", Long.toString(seconds))
        ));
    }

    private BossBar createBossBar(Shop shop, int seconds, PluginConfig.TeleportBossBar settings) {
        if (!settings.enabled()) {
            return null;
        }
        BossBar.Color color;
        BossBar.Overlay overlay;
        try {
            color = BossBar.Color.valueOf(settings.color());
        } catch (IllegalArgumentException ignored) {
            color = BossBar.Color.BLUE;
        }
        try {
            overlay = BossBar.Overlay.valueOf(settings.overlay());
        } catch (IllegalArgumentException ignored) {
            overlay = BossBar.Overlay.PROGRESS;
        }
        return BossBar.bossBar(
                messages.get().get(
                        "teleport-bossbar",
                        messages.get().storedTag("shop", shop.name()),
                        Placeholder.unparsed("seconds", Integer.toString(seconds))
                ),
                1.0F,
                color,
                overlay
        );
    }

    private void playEffects(Player player, boolean arrival, PluginConfig.TeleportEffects settings) {
        if (!settings.enabled()) {
            return;
        }
        String soundKey = arrival ? settings.arrivalSound() : settings.warmupSound();
        float pitch = arrival ? settings.arrivalPitch() : settings.warmupPitch();
        try {
            player.playSound(Sound.sound(Key.key(soundKey), Sound.Source.PLAYER, settings.volume(), pitch));
        } catch (IllegalArgumentException ignored) {
            plugin.getLogger().warning("Ignoring invalid teleport sound key: " + soundKey);
        }
        int particles = arrival ? settings.portalParticles() : settings.portalParticles() / 2;
        if (particles > 0) {
            player.spawnParticle(Particle.PORTAL, player.getLocation().add(0.0D, 1.0D, 0.0D),
                    particles, 0.45D, 0.8D, 0.45D, 0.08D);
        }
    }

    private boolean bypassesCooldown(Player player, Shop shop, PluginConfig.Teleport settings) {
        return player.hasPermission("plexonshops.teleport.cooldown.bypass")
                || (settings.ownersBypassCooldown() && shop.ownerUuid().equals(player.getUniqueId()));
    }

    private void cancel(UUID playerUuid, String messageKey) {
        if (clearPending(playerUuid) == null || messageKey == null) {
            return;
        }
        Player player = Bukkit.getPlayer(playerUuid);
        if (player != null) {
            messages.get().send(player, messageKey);
        }
    }

    private PendingTeleport clearPending(UUID playerUuid) {
        PendingTeleport removed = pending.remove(playerUuid);
        if (removed == null) {
            return null;
        }
        removed.completionTask().cancel();
        if (removed.progressTask() != null) {
            removed.progressTask().cancel();
        }
        Player player = Bukkit.getPlayer(playerUuid);
        if (player != null && removed.bossBar() != null) {
            player.hideBossBar(removed.bossBar());
        }
        return removed;
    }

    private TagResolver statusResolver(ShopStatus status) {
        Component component = switch (status) {
            case OPEN -> messages.get().raw("<green>OPEN</green>");
            case CLOSED -> messages.get().raw("<red>CLOSED</red>");
            case MAINTENANCE -> messages.get().raw("<yellow>MAINTENANCE</yellow>");
        };
        return TagResolver.resolver("status", Tag.inserting(component));
    }

    private double squaredDistance(Location location, PendingTeleport origin) {
        double deltaX = location.getX() - origin.x();
        double deltaY = location.getY() - origin.y();
        double deltaZ = location.getZ() - origin.z();
        return deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ;
    }

    private record PendingTeleport(
            UUID worldUuid,
            double x,
            double y,
            double z,
            BukkitTask completionTask,
            BukkitTask progressTask,
            BossBar bossBar
    ) {
    }
}
