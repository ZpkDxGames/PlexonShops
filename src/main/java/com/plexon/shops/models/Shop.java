package com.plexon.shops.models;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Immutable shop aggregate kept in the concurrent runtime cache. */
public record Shop(
        UUID id,
        UUID ownerUuid,
        String ownerName,
        String name,
        ShopLocation location,
        Set<Category> categories,
        ShopStatus status,
        Map<UUID, Rating> ratings,
        VisitorStats visitors,
        String displayIconData,
        List<String> description,
        List<LabeledItem> labeledItems,
        double teleportFee,
        long lastOwnerSeenEpochSecond,
        long createdAtEpochSecond,
        long updatedAtEpochSecond
) {
    public Shop {
        ownerName = ownerName == null || ownerName.isBlank() ? ownerUuid.toString() : ownerName;
        name = name == null || name.isBlank() ? ownerName + "'s Shop" : name;
        EnumSet<Category> categoryCopy = categories == null || categories.isEmpty()
                ? EnumSet.of(Category.MISC)
                : EnumSet.copyOf(categories);
        categories = Collections.unmodifiableSet(categoryCopy);
        ratings = Map.copyOf(ratings == null ? Map.of() : ratings);
        visitors = visitors == null ? VisitorStats.empty() : visitors;
        displayIconData = displayIconData == null ? "" : displayIconData;
        description = List.copyOf(description == null ? List.of() : description);
        labeledItems = List.copyOf(labeledItems == null ? List.of() : labeledItems);
        teleportFee = Math.max(0.0D, teleportFee);
    }

    public Category primaryCategory() {
        return categories.iterator().next();
    }

    public double averageRating() {
        return ratings.values().stream().mapToInt(Rating::stars).average().orElse(0.0D);
    }

    public int roundedRating() {
        return (int) Math.round(averageRating());
    }

    public String starBar() {
        int filled = roundedRating();
        return "★".repeat(filled) + "☆".repeat(5 - filled);
    }

    public int ratingFrom(UUID playerUuid) {
        Rating rating = ratings.get(playerUuid);
        return rating == null ? 0 : rating.stars();
    }

    public boolean isRecentlyActive(long cutoffEpochSecond) {
        return cutoffEpochSecond <= 0L || lastOwnerSeenEpochSecond >= cutoffEpochSecond;
    }

    public Shop withStatus(ShopStatus value, long now) {
        return copy(name, location, categories, value, ratings, visitors, displayIconData, description,
                labeledItems, teleportFee, ownerName, lastOwnerSeenEpochSecond, now);
    }

    public Shop withCategories(Set<Category> value, long now) {
        return copy(name, location, value, status, ratings, visitors, displayIconData, description,
                labeledItems, teleportFee, ownerName, lastOwnerSeenEpochSecond, now);
    }

    public Shop withDisplayIcon(String value, long now) {
        return copy(name, location, categories, status, ratings, visitors, value, description,
                labeledItems, teleportFee, ownerName, lastOwnerSeenEpochSecond, now);
    }

    public Shop withName(String value, long now) {
        return copy(value, location, categories, status, ratings, visitors, displayIconData, description,
                labeledItems, teleportFee, ownerName, lastOwnerSeenEpochSecond, now);
    }

    public Shop withDescription(List<String> value, long now) {
        return copy(name, location, categories, status, ratings, visitors, displayIconData, value,
                labeledItems, teleportFee, ownerName, lastOwnerSeenEpochSecond, now);
    }

    public Shop withLocation(ShopLocation value, long now) {
        return copy(name, value, categories, status, ratings, visitors, displayIconData, description,
                labeledItems, teleportFee, ownerName, lastOwnerSeenEpochSecond, now);
    }

    public Shop withTeleportFee(double value, long now) {
        return copy(name, location, categories, status, ratings, visitors, displayIconData, description,
                labeledItems, value, ownerName, lastOwnerSeenEpochSecond, now);
    }

    public Shop withRating(UUID playerUuid, int stars, long now) {
        Map<UUID, Rating> updated = new LinkedHashMap<>(ratings);
        updated.put(playerUuid, new Rating(playerUuid, stars, now));
        return copy(name, location, categories, status, updated, visitors, displayIconData, description,
                labeledItems, teleportFee, ownerName, lastOwnerSeenEpochSecond, now);
    }

    public Shop withVisit(UUID playerUuid, long now) {
        return copy(name, location, categories, status, ratings, visitors.record(playerUuid, now),
                displayIconData, description, labeledItems, teleportFee, ownerName,
                lastOwnerSeenEpochSecond, now);
    }

    public Shop withLabeledItem(LabeledItem item, long now) {
        List<LabeledItem> updated = new ArrayList<>(labeledItems);
        updated.add(item);
        return copy(name, location, categories, status, ratings, visitors, displayIconData, description,
                updated, teleportFee, ownerName, lastOwnerSeenEpochSecond, now);
    }

    public Shop withoutLabeledItem(UUID itemId, long now) {
        List<LabeledItem> updated = labeledItems.stream().filter(item -> !item.id().equals(itemId)).toList();
        return copy(name, location, categories, status, ratings, visitors, displayIconData, description,
                updated, teleportFee, ownerName, lastOwnerSeenEpochSecond, now);
    }

    public Shop withOwnerSeen(String currentName, long now) {
        return copy(name, location, categories, status, ratings, visitors, displayIconData, description,
                labeledItems, teleportFee, currentName, now, now);
    }

    private Shop copy(
            String nextName,
            ShopLocation nextLocation,
            Set<Category> nextCategories,
            ShopStatus nextStatus,
            Map<UUID, Rating> nextRatings,
            VisitorStats nextVisitors,
            String nextIcon,
            List<String> nextDescription,
            List<LabeledItem> nextLabels,
            double nextFee,
            String nextOwnerName,
            long nextOwnerSeen,
            long nextUpdatedAt
    ) {
        return new Shop(id, ownerUuid, nextOwnerName, nextName, nextLocation, nextCategories, nextStatus,
                nextRatings, nextVisitors, nextIcon, nextDescription, nextLabels, nextFee, nextOwnerSeen,
                createdAtEpochSecond, nextUpdatedAt);
    }
}
