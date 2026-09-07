package com.plexon.shops.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ShopViewTest {
    @Test
    void categoriesAreDefensivelyCopiedAndImmutable() {
        Set<String> source = new LinkedHashSet<>();
        source.add("misc");
        ShopView view = new ShopView(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Owner",
                "Shop",
                source,
                "open",
                4.5D,
                12L,
                5,
                10.0D,
                "world",
                1L,
                2L);

        source.add("blocks");
        assertEquals(Set.of("misc"), view.categories());
        assertThrows(UnsupportedOperationException.class, () -> view.categories().add("tools"));
    }
}
