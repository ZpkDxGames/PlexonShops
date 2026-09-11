package com.plexon.shops.gui.view;

import java.util.List;
import java.util.UUID;

/** Immutable per-card snapshot; rendering reads no persistence state. */
public record ShopCardViewModel(
        UUID shopId,
        long shopRevision,
        String name,
        String owner,
        String categories,
        String rating,
        int ratingCount,
        List<String> showcaseLabels,
        String status,
        String statusDetail,
        String location,
        String teleportFee,
        boolean teleportable,
        boolean featured,
        boolean favorite
) {
    public ShopCardViewModel {
        showcaseLabels = List.copyOf(showcaseLabels);
    }

    public String showcaseSummary() {
        if (showcaseLabels.isEmpty()) {
            return "No showcased items yet";
        }
        return String.join(" • ", showcaseLabels);
    }

    /** Player-visible text only; intentionally excludes UUIDs and internal revisions. */
    public List<String> visibleText() {
        return List.of(name, owner, categories, rating, showcaseSummary(), status, statusDetail, location, teleportFee);
    }
}
