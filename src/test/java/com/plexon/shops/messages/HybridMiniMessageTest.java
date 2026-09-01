package com.plexon.shops.messages;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HybridMiniMessageTest {
    @Test
    void convertsLegacyAndBungeeHexWithoutDamagingMiniMessage() {
        assertEquals("<green>Shop", HybridMiniMessage.convert("&aShop"));
        assertEquals("<#A1B2C3>Shop", HybridMiniMessage.convert("&x&A&1&B&2&C&3Shop"));
        assertEquals("<gradient:#8CE6FF:#5BA8FF>Shop</gradient>",
                HybridMiniMessage.convert("<gradient:#8CE6FF:#5BA8FF>Shop</gradient>"));
    }

    @Test
    void acceptsNullAsEmptyText() {
        assertEquals("", HybridMiniMessage.convert(null));
    }
}
