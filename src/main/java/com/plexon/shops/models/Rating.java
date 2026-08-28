package com.plexon.shops.models;

import java.util.UUID;

/** One player's current one-to-five-star rating. */
public record Rating(UUID playerUuid, int stars, long updatedAtEpochSecond) {
    public Rating {
        if (stars < 1 || stars > 5) {
            throw new IllegalArgumentException("Rating must be between 1 and 5 stars");
        }
    }
}
