package com.plexon.shops.models;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ShopTest {
    @Test
    void updatesRatingsAndVisitorCountsImmutably() {
        Shop original = shop();
        UUID visitor = UUID.randomUUID();

        Shop updated = original.withRating(visitor, 4, 200L).withVisit(visitor, 201L).withVisit(visitor, 202L);

        assertEquals(0, original.ratings().size());
        assertEquals(4.0D, updated.averageRating());
        assertEquals("★★★★☆", updated.starBar());
        assertEquals(2L, updated.visitors().totalVisits());
        assertEquals(1, updated.visitors().uniqueCount());
    }

    @Test
    void defensivelyCopiesCollections() {
        Shop shop = shop();

        assertThrows(UnsupportedOperationException.class, () -> shop.description().add("mutate"));
        assertThrows(UnsupportedOperationException.class, () -> shop.categories().add(Category.TOOLS));
    }

    public static Shop shop() {
        UUID owner = UUID.randomUUID();
        long now = 100L;
        return new Shop(
                UUID.randomUUID(),
                owner,
                "Tonim",
                "Plexon Market",
                new ShopLocation(UUID.randomUUID(), "world", 0, 64, 0, 0, 0),
                Set.of(Category.BLOCKS),
                ShopStatus.OPEN,
                Map.of(),
                VisitorStats.empty(),
                "",
                List.of("<gray>Quality blocks.</gray>"),
                List.of(),
                25.0D,
                now,
                now,
                now
        );
    }
}
