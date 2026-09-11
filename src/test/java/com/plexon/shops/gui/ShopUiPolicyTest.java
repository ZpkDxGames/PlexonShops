package com.plexon.shops.gui;

import com.plexon.shops.gui.view.ShopUiPolicy;
import com.plexon.shops.models.Category;
import com.plexon.shops.models.DirectoryFilter;
import com.plexon.shops.models.LabeledItem;
import com.plexon.shops.models.Shop;
import com.plexon.shops.models.ShopLocation;
import com.plexon.shops.models.ShopStatus;
import com.plexon.shops.models.VisitorStats;
import com.plexon.shops.services.ShopAvailabilityResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ShopUiPolicyTest {
    @Test
    void humanReadableAvailabilityNeverLeaksReasonKeys() {
        assertEquals("OPEN", ShopUiPolicy.status(ShopAvailabilityResolver.State.OPEN, "open").label());
        assertEquals("CLOSED", ShopUiPolicy.status(ShopAvailabilityResolver.State.CLOSED, "closed").label());
        assertEquals("UNDER MAINTENANCE",
                ShopUiPolicy.status(ShopAvailabilityResolver.State.MAINTENANCE, "maintenance").label());
        ShopUiPolicy.StatusPresentation unavailable =
                ShopUiPolicy.status(ShopAvailabilityResolver.State.UNAVAILABLE, "world-unavailable");
        assertEquals("SHOP LOCATION UNAVAILABLE", unavailable.label());
        assertFalse(unavailable.explanation().contains("world-unavailable"));
    }

    @Test
    void categoryFilterMatchesExistingDirectoryMetadata() {
        Shop blocks = shop("Builder", Set.of(Category.BLOCKS), List.of());
        assertTrue(DirectoryFilter.BLOCKS.matches(blocks));
        assertFalse(DirectoryFilter.TOOLS.matches(blocks));
        assertEquals("Blocks", ShopUiPolicy.categoryLabel(Category.BLOCKS));
    }

    @Test
    void searchCoversNameOwnerCategoryAndShowcaseLabels() {
        Shop shop = shop("Miner's Haven", Set.of(Category.TOOLS), List.of(
                new LabeledItem(UUID.randomUUID(), "Diamond Pickaxes", "", 1L)
        ));
        List<Shop> source = List.of(shop);
        assertEquals(1, ShopUiPolicy.search(source, "miner", value -> value).size());
        assertEquals(1, ShopUiPolicy.search(source, "owner", value -> value).size());
        assertEquals(1, ShopUiPolicy.search(source, "tools", value -> value).size());
        assertEquals(1, ShopUiPolicy.search(source, "diamond", value -> value).size());
        assertTrue(ShopUiPolicy.search(source, "emerald", value -> value).isEmpty());
        assertTrue(ShopUiPolicy.search(source, "   ", value -> value).isEmpty());
    }

    @Test
    void paginationIsBoundedAndStable() {
        List<Integer> values = IntStream.range(0, 80).boxed().toList();
        ShopUiPolicy.PageSlice<Integer> first = ShopUiPolicy.page(values, 0, 36);
        ShopUiPolicy.PageSlice<Integer> last = ShopUiPolicy.page(values, 99, 36);
        assertEquals(3, first.pages());
        assertEquals(36, first.items().size());
        assertEquals(2, last.page());
        assertEquals(8, last.items().size());
        assertEquals(80, last.total());
    }

    private static Shop shop(String name, Set<Category> categories, List<LabeledItem> labels) {
        return new Shop(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "OwnerName",
                name,
                new ShopLocation(null, "Survival_World", 0.0D, 64.0D, 0.0D, 0.0F, 0.0F),
                categories,
                ShopStatus.OPEN,
                Map.of(),
                VisitorStats.empty(),
                "",
                List.of("A useful shop"),
                labels,
                25.0D,
                1L,
                1L,
                1L
        );
    }
}
