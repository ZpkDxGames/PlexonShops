package com.plexon.shops.services;

import com.plexon.shops.config.PluginConfig;
import com.plexon.shops.models.Shop;
import com.plexon.shops.models.ShopStatus;
import org.bukkit.Location;

import java.util.Objects;
import java.util.function.Supplier;

/** Single authoritative availability and destination resolver for GUI and teleport paths. */
public final class ShopAvailabilityResolver {
    private final Supplier<PluginConfig> config;

    public ShopAvailabilityResolver(Supplier<PluginConfig> config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    /** Must be evaluated on the Paper server thread because world resolution uses Bukkit state. */
    public Resolution resolve(Shop shop) {
        if (shop == null) {
            return unavailable(State.UNAVAILABLE, "shop-not-found");
        }
        if (shop.status() == ShopStatus.CLOSED) {
            return unavailable(State.CLOSED, "closed");
        }
        if (shop.status() == ShopStatus.MAINTENANCE) {
            return unavailable(State.MAINTENANCE, "maintenance");
        }
        if (shop.location() == null || !shop.location().hasFiniteCoordinates()) {
            return unavailable(State.UNAVAILABLE, "invalid-coordinates");
        }
        Location destination = shop.location().resolve().orElse(null);
        if (destination == null || destination.getWorld() == null) {
            return unavailable(State.UNAVAILABLE, "world-unavailable");
        }
        if (config.get().isWorldBlacklisted(destination.getWorld().getName())) {
            return unavailable(State.UNAVAILABLE, "world-blocked");
        }
        return new Resolution(State.OPEN, "open", destination);
    }

    private Resolution unavailable(State state, String reason) {
        return new Resolution(state, reason, null);
    }

    public enum State {
        OPEN,
        CLOSED,
        MAINTENANCE,
        UNAVAILABLE
    }

    /** Immutable main-thread resolution. A non-null destination exists only when teleportable. */
    public record Resolution(State state, String reason, Location destination) {
        public Resolution {
            Objects.requireNonNull(state, "state");
            reason = reason == null ? "unknown" : reason;
            destination = destination == null ? null : destination.clone();
        }

        @Override
        public Location destination() {
            return destination == null ? null : destination.clone();
        }

        public boolean teleportable() {
            return state == State.OPEN && destination != null;
        }
    }
}
