package com.plexon.shops.gui.view;

import java.util.List;
import java.util.UUID;

/** Immutable profile snapshot used by the Phase 3 player directory renderer. */
public record ShopProfileViewModel(
        UUID shopId,
        long shopRevision,
        String name,
        String owner,
        List<String> description,
        String categories,
        String status,
        String statusDetail,
        String rating,
        int ratingCount,
        int viewerRating,
        List<String> showcaseLabels,
        int showcaseCount,
        String location,
        String teleportFee,
        boolean teleportable,
        boolean featured,
        boolean favorite,
        boolean ownerView
) {
    public ShopProfileViewModel {
        description = List.copyOf(description);
        showcaseLabels = List.copyOf(showcaseLabels);
    }

    public String showcaseSummary() {
        return showcaseLabels.isEmpty() ? "No showcased items yet" : String.join(" • ", showcaseLabels);
    }

    /** Player-visible text only; intentionally excludes UUIDs and internal revisions. */
    public List<String> visibleText() {
        return List.of(name, owner, categories, status, statusDetail, rating, showcaseSummary(), location, teleportFee);
    }
}
