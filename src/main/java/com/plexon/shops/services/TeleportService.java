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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Level;

/** Cancellable warmups coordinated by one shared task, followed by Paper asynchronous teleports. */
public final class TeleportService {
    private final JavaPlugin plugin;
    private final ShopService shops;
    private final DiscoveryService discovery;
    private final ShopAvailabilityResolver availability;
    private final VaultEconomyHook economy;
    private final Supplier<PluginConfig> config;
    private final Supplier<MessageService> messages;
    private final Map<UUID, PendingTeleport> pending = new ConcurrentHashMap<>();
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    private final TeleportAttemptRegistry<InFlightTeleport> inFlight = new TeleportAttemptRegistry<>();
    private BukkitTask progressCoordinator;
    private int coordinatorIntervalTicks;

    public TeleportService(
            JavaPlugin plugin,
            ShopService shops,
            DiscoveryService discovery,
            ShopAvailabilityResolver availability,
            VaultEconomyHook economy,
            Supplier<PluginConfig> config,
            Supplier<MessageService> messages
    ) {
        this.plugin = plugin;
        this.shops = shops;
        this.discovery = discovery;
        this.availability = availability;
        this.economy = economy;
        this.config = config;
        this.messages = messages;
    }

    /** O(1) listener fast gate used before reading movement/damage configuration. */
    public boolean hasPending(UUID playerUuid) {
        return pending.containsKey(playerUuid);
    }

    public int pendingCount() {
        return pending.size() + inFlight.size();
    }

    public int inFlightCount() {
        return inFlight.size();
    }

    public boolean coordinatorRunning() {
        return progressCoordinator != null;
    }

    public void request(Player player, Shop requestedShop) {
        PluginConfig settings = config.get();
        if (!settings.teleport().enabled()) {
            messages.get().send(player, "teleport-disabled");
            return;
        }
        UUID playerUuid = player.getUniqueId();
        if (pending.containsKey(playerUuid) || inFlight.contains(playerUuid)) {
            messages.get().send(player, "teleport-in-progress");
            return;
        }
        Shop shop = shops.find(requestedShop.id()).orElse(null);
        if (shop == null) {
            messages.get().send(player, "shop-not-found");
            return;
        }
        ShopAvailabilityResolver.Resolution resolved = availability.resolve(shop);
        if (!resolved.teleportable()) {
            sendUnavailable(player, shop, resolved);
            return;
        }
        if (!bypassesCooldown(player, shop, settings.teleport())) {
            long remainingNanos = cooldowns.getOrDefault(player.getUniqueId(), 0L) - System.nanoTime();
            if (remainingNanos > 0L) {
                messages.get().send(player, "teleport-cooldown",
                        Placeholder.unparsed("seconds", Long.toString((remainingNanos + 999_999_999L) / 1_000_000_000L)));
                return;
            }
            cooldowns.remove(player.getUniqueId());
        }

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
        pending.put(player.getUniqueId(), new PendingTeleport(
                shop.id(),
                shop.name(),
                start.getWorld() == null ? null : start.getWorld().getUID(),
                start.getX(),
                start.getY(),
                start.getZ(),
                startedAt,
                durationNanos,
                bossBar
        ));
        ensureProgressCoordinator(settings.teleport().bossBar().updateIntervalTicks());
        messages.get().send(player, "teleport-start",
                messages.get().storedTag("shop", shop.name()),
                Placeholder.unparsed("seconds", Integer.toString(warmup)));
    }

    public void handleMovement(Player player, Location to) {
        PendingTeleport active = pending.get(player.getUniqueId());
        if (active == null) {
            return;
        }
        if (!config.get().teleport().cancelOnMovement() || to.getWorld() == null) {
            return;
        }
        if (!to.getWorld().getUID().equals(active.worldUuid()) || squaredDistance(to, active) > 1.0E-6D) {
            cancelWarmup(player.getUniqueId(), "teleport-cancelled");
        }
    }

    public void handleDamage(Player player) {
        if (!pending.containsKey(player.getUniqueId())) {
            return;
        }
        if (config.get().teleport().cancelOnDamage()) {
            cancelWarmup(player.getUniqueId(), "teleport-cancelled-damage");
        }
    }

    /**
     * Cancels only the still-pending warmup. Once Paper's teleportAsync has started,
     * its completion remains authoritative for success/failure and fee settlement.
     */
    public void cancelPending(UUID playerUuid, boolean notify) {
        cancelWarmup(playerUuid, notify ? "teleport-cancelled" : null);
    }

    public void cancelAll() {
        for (UUID playerUuid : List.copyOf(pending.keySet())) {
            cancelWarmup(playerUuid, null);
        }
        for (InFlightTeleport attempt : inFlight.drain()) {
            economy.refund(attempt.player(), attempt.chargedAmount());
        }
        cooldowns.clear();
        stopProgressCoordinator();
    }

    private void execute(Player player, UUID shopId) {
        if (!player.isOnline()) {
            return;
        }
        Shop shop = shops.find(shopId).orElse(null);
        if (shop == null) {
            messages.get().send(player, "shop-not-found");
            return;
        }
        ShopAvailabilityResolver.Resolution resolved = availability.resolve(shop);
        if (!resolved.teleportable()) {
            sendUnavailable(player, shop, resolved);
            return;
        }
        Location destination = resolved.destination();
        PluginConfig settings = config.get();

        UUID playerUuid = player.getUniqueId();
        InFlightTeleport attempt = new InFlightTeleport(player);
        if (!inFlight.begin(playerUuid, attempt)) {
            messages.get().send(player, "teleport-in-progress");
            return;
        }

        boolean feeBypass = player.hasPermission("plexonshops.teleport.fee.bypass")
                || (settings.teleport().ownersBypassFee() && shop.ownerUuid().equals(playerUuid));
        VaultEconomyHook.ChargeResult charge = feeBypass
                ? VaultEconomyHook.ChargeResult.free()
                : economy.charge(player, shop.teleportFee());
        if (!charge.success()) {
            inFlight.complete(playerUuid, attempt);
            if (charge.failure() == VaultEconomyHook.ChargeFailure.INSUFFICIENT_FUNDS) {
                messages.get().send(player, "insufficient-funds",
                        Placeholder.unparsed("fee", economy.format(shop.teleportFee())));
            } else {
                messages.get().send(player, "economy-unavailable");
            }
            return;
        }
        attempt.chargedAmount(charge.chargedAmount());

        try {
            player.teleportAsync(destination).whenComplete((success, error) -> {
                if (!inFlight.isAuthoritative(playerUuid, attempt)) {
                    return;
                }
                Bukkit.getScheduler().runTask(plugin, () -> finishAttempt(
                        playerUuid, attempt, shop, settings, Boolean.TRUE.equals(success), error));
            });
        } catch (RuntimeException error) {
            if (inFlight.complete(playerUuid, attempt)) {
                economy.refund(player, attempt.chargedAmount());
                if (player.isOnline()) {
                    messages.get().send(player, "teleport-failed");
                }
            }
        }
    }

    private void finishAttempt(
            UUID playerUuid,
            InFlightTeleport attempt,
            Shop shop,
            PluginConfig settings,
            boolean success,
            Throwable error
    ) {
        if (!inFlight.isAuthoritative(playerUuid, attempt)) {
            return;
        }
        try {
            if (error != null || !success) {
                economy.refund(attempt.player(), attempt.chargedAmount());
                if (attempt.player().isOnline()) {
                    messages.get().send(attempt.player(), "teleport-failed");
                }
                return;
            }
            shops.recordVisit(shop.id(), playerUuid).exceptionally(recordError -> {
                plugin.getLogger().log(Level.WARNING, "Could not persist authoritative shop visit for " + shop.id(), recordError);
                return null;
            });
            discovery.recordVisit(playerUuid, shop.id()).exceptionally(recordError -> {
                plugin.getLogger().log(Level.WARNING, "Could not persist recent shop history for " + shop.id(), recordError);
                return null;
            });
            if (!bypassesCooldown(attempt.player(), shop, settings.teleport())
                    && settings.teleport().cooldownSeconds() > 0) {
                cooldowns.put(playerUuid,
                        System.nanoTime() + settings.teleport().cooldownSeconds() * 1_000_000_000L);
            }
            if (attempt.player().isOnline()) {
                playEffects(attempt.player(), true, settings.teleport().effects());
                messages.get().send(attempt.player(), "teleport-success", messages.get().storedTag("shop", shop.name()));
                attempt.player().sendActionBar(messages.get().get(
                        "teleport-arrival-actionbar",
                        messages.get().storedTag("shop", shop.name())
                ));
            }
        } finally {
            inFlight.complete(playerUuid, attempt);
        }
    }

    private void ensureProgressCoordinator(int updateIntervalTicks) {
        int interval = Math.max(1, updateIntervalTicks);
        if (progressCoordinator != null && coordinatorIntervalTicks == interval) {
            return;
        }
        stopProgressCoordinator();
        coordinatorIntervalTicks = interval;
        progressCoordinator = Bukkit.getScheduler().runTaskTimer(plugin, this::tickCoordinator, 1L, interval);
    }

    private void tickCoordinator() {
        long now = System.nanoTime();
        List<UUID> due = new ArrayList<>();
        for (Map.Entry<UUID, PendingTeleport> entry : pending.entrySet()) {
            UUID playerUuid = entry.getKey();
            PendingTeleport active = entry.getValue();
            Player player = Bukkit.getPlayer(playerUuid);
            if (player == null || !player.isOnline()) {
                cancelWarmup(playerUuid, null);
                continue;
            }
            long remainingNanos = active.durationNanos() - (now - active.startedAtNanos());
            if (remainingNanos <= 0L) {
                due.add(playerUuid);
                continue;
            }
            if (active.bossBar() != null) {
                float progress = (float) Math.clamp(
                        remainingNanos / (double) active.durationNanos(),
                        0.0D,
                        1.0D
                );
                long seconds = Math.max(1L, (remainingNanos + 999_999_999L) / 1_000_000_000L);
                active.bossBar().progress(progress);
                active.bossBar().name(messages.get().get(
                        "teleport-bossbar",
                        messages.get().storedTag("shop", active.shopDisplayName()),
                        Placeholder.unparsed("seconds", Long.toString(seconds))
                ));
            }
        }
        for (UUID playerUuid : due) {
            completeWarmup(playerUuid);
        }
        stopProgressCoordinatorIfIdle();
    }

    private void completeWarmup(UUID playerUuid) {
        PendingTeleport active = clearPending(playerUuid);
        if (active == null) {
            return;
        }
        Player player = Bukkit.getPlayer(playerUuid);
        if (player != null && player.isOnline()) {
            execute(player, active.shopId());
        }
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

    private void cancelWarmup(UUID playerUuid, String messageKey) {
        boolean cancelled = clearPending(playerUuid) != null;
        if (!cancelled || messageKey == null) {
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
        Player player = Bukkit.getPlayer(playerUuid);
        if (player != null && removed.bossBar() != null) {
            player.hideBossBar(removed.bossBar());
        }
        return removed;
    }

    private void stopProgressCoordinatorIfIdle() {
        if (progressCoordinator != null && pending.isEmpty()) {
            stopProgressCoordinator();
        }
    }

    private void stopProgressCoordinator() {
        if (progressCoordinator != null) {
            progressCoordinator.cancel();
            progressCoordinator = null;
        }
        coordinatorIntervalTicks = 0;
    }

    private void sendUnavailable(Player player, Shop shop, ShopAvailabilityResolver.Resolution resolved) {
        if (resolved.state() == ShopAvailabilityResolver.State.CLOSED
                || resolved.state() == ShopAvailabilityResolver.State.MAINTENANCE) {
            messages.get().send(player, "shop-closed", statusResolver(shop.status()));
        } else {
            messages.get().send(player, "teleport-failed");
        }
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

    private static final class InFlightTeleport {
        private final Player player;
        private double chargedAmount;

        private InFlightTeleport(Player player) {
            this.player = player;
        }

        private Player player() {
            return player;
        }

        private double chargedAmount() {
            return chargedAmount;
        }

        private void chargedAmount(double value) {
            chargedAmount = Math.max(0.0D, value);
        }
    }

    private record PendingTeleport(
            UUID shopId,
            String shopDisplayName,
            UUID worldUuid,
            double x,
            double y,
            double z,
            long startedAtNanos,
            long durationNanos,
            BossBar bossBar
    ) {
    }
}
