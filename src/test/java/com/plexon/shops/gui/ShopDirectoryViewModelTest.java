package com.plexon.shops.gui;

import com.plexon.shops.gui.view.ShopCardViewModel;
import com.plexon.shops.gui.view.ShopDirectoryViewModel;
import com.plexon.shops.gui.view.ShopProfileViewModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ShopDirectoryViewModelTest {
    @Test
    void directoryPaginationUsesOneImmutablePageSnapshot() {
        List<ShopCardViewModel> cards = IntStream.range(0, 40).mapToObj(this::card).toList();
        ShopDirectoryViewModel model = ShopDirectoryViewModel.of("Favorites", "", cards, 1, 36);
        assertEquals(1, model.page());
        assertEquals(2, model.pages());
        assertEquals(40, model.total());
        assertEquals(4, model.shops().size());
    }

    @Test
    void cardAndProfileVisibleTextExcludeInternalIdentity() {
        UUID id = UUID.randomUUID();
        ShopCardViewModel card = new ShopCardViewModel(
                id, 991L, "Miner's Haven", "Owner", "Tools", "★★★★☆ 4.0", 12,
                List.of("Diamond", "Iron"), "OPEN", "Ready to visit.", "120 blocks", "$25", true, false, false);
        ShopProfileViewModel profile = new ShopProfileViewModel(
                id, 991L, "Miner's Haven", "Owner", List.of("Description"), "Tools", "OPEN",
                "Ready to visit.", "★★★★☆ 4.0", 12, 4, List.of("Diamond"), 1,
                "120 blocks", "$25", true, false, false, false);
        String internal = id.toString();
        assertTrue(card.visibleText().stream().noneMatch(line -> line.contains(internal) || line.contains("991")));
        assertTrue(profile.visibleText().stream().noneMatch(line -> line.contains(internal) || line.contains("991")));
    }

    @Test
    void showcaseSummaryIsExplicitlyNonCommerceData() {
        ShopCardViewModel noItems = card(1);
        ShopCardViewModel items = new ShopCardViewModel(
                UUID.randomUUID(), 1L, "Shop", "Owner", "Misc", "☆☆☆☆☆ 0.0", 0,
                List.of("Diamond", "Emerald"), "OPEN", "Ready", "World", "FREE", true, false, false);
        assertEquals("No showcased items yet", noItems.showcaseSummary());
        assertEquals("Diamond • Emerald", items.showcaseSummary());
        assertFalse(items.visibleText().stream().anyMatch(line -> line.toLowerCase().contains("buy")));
    }

    private ShopCardViewModel card(int index) {
        return new ShopCardViewModel(
                UUID.randomUUID(), index, "Shop " + index, "Owner", "Misc", "☆☆☆☆☆ 0.0", 0,
                List.of(), "OPEN", "Ready to visit.", "World", "FREE", true, false, false);
    }
}
