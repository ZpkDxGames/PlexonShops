package com.plexon.shops.event;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

public final class PlexonShopRatedEvent extends PlayerEvent {
    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID shopId;
    private final String shopType;
    private final int rating;
    private final int previousRating;
    private final UUID ownerId;
    private final String eventId;
    private final String transactionId;

    public PlexonShopRatedEvent(
            Player player,
            UUID shopId,
            String shopType,
            int rating,
            int previousRating,
            UUID ownerId,
            String eventId,
            String transactionId
    ) {
        super(Objects.requireNonNull(player, "player"));
        this.shopId = Objects.requireNonNull(shopId, "shopId");
        this.shopType = requireText(shopType, "shopType");
        this.rating = rating;
        this.previousRating = previousRating;
        this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
        this.eventId = requireText(eventId, "eventId");
        this.transactionId = requireText(transactionId, "transactionId");
    }

    public Player player() { return getPlayer(); }
    public UUID shopId() { return shopId; }
    public UUID getShopId() { return shopId; }
    public String shopType() { return shopType; }
    public String getShopType() { return shopType; }
    public int rating() { return rating; }
    public int getRating() { return rating; }
    public int previousRating() { return previousRating; }
    public int getPreviousRating() { return previousRating; }
    public UUID ownerId() { return ownerId; }
    public UUID getOwnerId() { return ownerId; }
    public String eventId() { return eventId; }
    public String getEventId() { return eventId; }
    public String transactionId() { return transactionId; }
    public String getTransactionId() { return transactionId; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
