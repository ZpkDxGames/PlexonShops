package com.plexon.shops.gui.view;

import com.plexon.shops.models.Category;
import com.plexon.shops.models.Shop;
import com.plexon.shops.services.ShopAvailabilityResolver;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/** Pure presentation policy for player-facing Phase 3 directory screens. */
public final class ShopUiPolicy {
    private ShopUiPolicy() {
    }

    public static StatusPresentation status(ShopAvailabilityResolver.State state, String reason) {
        return switch (state) {
            case OPEN -> new StatusPresentation("OPEN", "Ready to visit.", true);
            case CLOSED -> new StatusPresentation("CLOSED", "The owner has closed this shop.", false);
            case MAINTENANCE -> new StatusPresentation("UNDER MAINTENANCE", "This shop is temporarily unavailable.", false);
            case UNAVAILABLE -> new StatusPresentation(
                    "SHOP LOCATION UNAVAILABLE",
                    switch (String.valueOf(reason)) {
                        case "world-blocked" -> "This destination is not available from the directory.";
                        case "world-unavailable" -> "The destination world is not currently available.";
                        case "invalid-coordinates" -> "The owner needs to update this shop location.";
                        case "shop-not-found" -> "This shop is no longer available.";
                        default -> "This shop cannot be visited right now.";
                    },
                    false
            );
        };
    }

    public static String categoryLabel(Category category) {
        String value = category.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    public static String categorySummary(Set<Category> categories) {
        return categories.stream().map(ShopUiPolicy::categoryLabel).sorted().reduce((a, b) -> a + " • " + b).orElse("Misc");
    }

    /** Cache-only search over the already-loaded directory aggregate. */
    public static List<Shop> search(List<Shop> source, String query, Function<String, String> plainText) {
        String needle = normalize(query);
        if (needle.isBlank()) {
            return List.of();
        }
        return source.stream().filter(shop -> matches(shop, needle, plainText)).toList();
    }

    private static boolean matches(Shop shop, String needle, Function<String, String> plainText) {
        if (normalize(plainText.apply(shop.name())).contains(needle)
                || normalize(shop.ownerName()).contains(needle)) {
            return true;
        }
        if (shop.categories().stream().anyMatch(category ->
                normalize(category.key()).contains(needle) || normalize(categoryLabel(category)).contains(needle))) {
            return true;
        }
        return shop.labeledItems().stream().anyMatch(item -> normalize(plainText.apply(item.label())).contains(needle));
    }

    public static <T> PageSlice<T> page(List<T> values, int requestedPage, int pageSize) {
        int safePageSize = Math.max(1, pageSize);
        int pages = Math.max(1, (int) Math.ceil(values.size() / (double) safePageSize));
        int page = Math.clamp(requestedPage, 0, pages - 1);
        int from = Math.min(values.size(), page * safePageSize);
        int to = Math.min(values.size(), from + safePageSize);
        return new PageSlice<>(page, pages, values.size(), List.copyOf(values.subList(from, to)));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }

    public record StatusPresentation(String label, String explanation, boolean visitable) {
    }

    public record PageSlice<T>(int page, int pages, int total, List<T> items) {
        public PageSlice {
            items = List.copyOf(items);
        }
    }
}
