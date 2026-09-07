package com.plexon.shops.event;

import com.plexon.shops.models.Shop;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.plugin.java.JavaPlugin;

public final class ShopEventPublisher {
    private static final String SHOP_TYPE = "player";

    private final JavaPlugin plugin;

    public ShopEventPublisher(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void publishCreated(UUID actorId, Shop shop) {
        publish(actorId, "created", (player, eventId, transactionId) -> new PlexonShopCreatedEvent(
                player,
                shop.id(),
                SHOP_TYPE,
                shop.ownerUuid(),
                shop.primaryCategory().name().toLowerCase(java.util.Locale.ROOT),
                eventId,
                transactionId));
    }

    public void publishRated(UUID actorId, Shop shop, int previousRating) {
        publish(actorId, "rated", (player, eventId, transactionId) -> new PlexonShopRatedEvent(
                player,
                shop.id(),
                SHOP_TYPE,
                shop.ratingFrom(actorId),
                previousRating,
                shop.ownerUuid(),
                eventId,
                transactionId));
    }

    public void publishVisited(UUID actorId, Shop shop, boolean uniqueVisitor) {
        publish(actorId, "visited", (player, eventId, transactionId) -> new PlexonShopVisitedEvent(
                player,
                shop.id(),
                SHOP_TYPE,
                shop.ownerUuid(),
                shop.location().worldName(),
                uniqueVisitor,
                shop.visitors().totalVisits(),
                eventId,
                transactionId));
    }

    private void publish(UUID actorId, String suffix, EventFactory factory) {
        Runnable dispatch = () -> {
            Player player = Bukkit.getPlayer(actorId);
            if (player == null || !player.isOnline()) {
                return;
            }
            String transactionId = UUID.randomUUID().toString();
            String eventId = transactionId + ":" + suffix;
            Event event = factory.create(player, eventId, transactionId);
            Bukkit.getPluginManager().callEvent(event);
        };
        if (Bukkit.isPrimaryThread()) {
            dispatch.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, dispatch);
        }
    }

    @FunctionalInterface
    private interface EventFactory {
        Event create(Player player, String eventId, String transactionId);
    }
}
