package com.plexon.shops.event;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

public final class PlexonShopCreatedEvent extends PlayerEvent {
    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID shopId;
    private final String shopType;
    private final UUID ownerId;
    private final String primaryCategory;
    private final String eventId;
    private final String transactionId;

    public PlexonShopCreatedEvent(
            Player player,
            UUID shopId,
            String shopType,
            UUID ownerId,
            String primaryCategory,
            String eventId,
            String transactionId
    ) {
        super(Objects.requireNonNull(player, "player"));
        this.shopId = Objects.requireNonNull(shopId, "shopId");
        this.shopType = requireText(shopType, "shopType");
        this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
        this.primaryCategory = primaryCategory == null ? "" : primaryCategory;
        this.eventId = requireText(eventId, "eventId");
        this.transactionId = requireText(transactionId, "transactionId");
    }

    public Player player() { return getPlayer(); }
    public UUID shopId() { return shopId; }
    public UUID getShopId() { return shopId; }
    public String shopType() { return shopType; }
    public String getShopType() { return shopType; }
    public UUID ownerId() { return ownerId; }
    public UUID getOwnerId() { return ownerId; }
    public String primaryCategory() { return primaryCategory; }
    public String getPrimaryCategory() { return primaryCategory; }
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
