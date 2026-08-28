package com.plexon.shops.models;

import java.util.UUID;

/** First-visit record used for unique visitor accounting. */
public record Visitor(UUID playerUuid, long firstVisitedAtEpochSecond) {
}
