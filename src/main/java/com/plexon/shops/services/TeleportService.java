package com.plexon.shops.services;

import com.plexon.shops.config.PluginConfig;
import com.plexon.shops.integrations.VaultEconomyHook;
import com.plexon.shops.messages.MessageService;
import com.plexon.shops.models.Shop;
import com.plexon.shops.models.ShopStatus;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Cancellable warmups followed by Paper asynchronous teleports. */
public final class TeleportService {
    private final JavaPlugin plugin;
    private final ShopService shops;
    private final VaultEconomyHook economy;
    private final Supplier<PluginConfig> config;
    private final Supplier<MessageService> messages;
    private final Map<UUID, PendingTeleport> pending = new ConcurrentHashMap<>();

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

        cancel(player.getUniqueId(), false);
        int warmup = settings.teleport().warmupSeconds();
        if (warmup <= 0) {
            execute(player, shop.id());
            return;
        }

        Location start = player.getLocation();
        BukkitTask task = Bukkit.getScheduler().runTaskLater(
                plugin,
                () -> execute(player, shop.id()),
                warmup * 20L
        );
        pending.put(player.getUniqueId(), new PendingTeleport(
                start.getWorld() == null ? null : start.getWorld().getUID(),
                start.getX(),
                start.getY(),
                start.getZ(),
                task
        ));
        messages.get().send(player, "teleport-start",
                Placeholder.unparsed("shop", shop.name()),
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
        if (!to.getWorld().getUID().equals(active.worldUuid())
                || squaredDistance(to, active) > 1.0E-6D) {
            cancel(player.getUniqueId(), true);
        }
    }

    public void cancel(UUID playerUuid, boolean notify) {
        PendingTeleport removed = pending.remove(playerUuid);
        if (removed == null) {
            return;
        }
        removed.task().cancel();
        if (notify) {
            Player player = Bukkit.getPlayer(playerUuid);
            if (player != null) {
                messages.get().send(player, "teleport-cancelled");
            }
        }
    }

    public void cancelAll() {
        pending.keySet().forEach(uuid -> cancel(uuid, false));
    }

    private void execute(Player player, UUID shopId) {
        PendingTeleport active = pending.remove(player.getUniqueId());
        if (active != null) {
            active.task().cancel();
        }
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
        Location destination = shop.location().resolve().orElse(null);
        if (destination == null || destination.getWorld() == null
                || config.get().isWorldBlacklisted(destination.getWorld().getName())) {
            messages.get().send(player, "teleport-failed");
            return;
        }

        VaultEconomyHook.ChargeResult charge = economy.charge(player, shop.teleportFee());
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
            messages.get().send(player, "teleport-success", Placeholder.unparsed("shop", shop.name()));
        }));
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
            BukkitTask task
    ) {
    }
}
