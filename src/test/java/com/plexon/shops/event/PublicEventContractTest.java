package com.plexon.shops.event;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.event.player.PlayerEvent;
import org.junit.jupiter.api.Test;

class PublicEventContractTest {
    @Test
    void exactQuestsEventClassesAndAccessorsExist() throws Exception {
        Class<?> created = Class.forName("com.plexon.shops.event.PlexonShopCreatedEvent");
        Class<?> rated = Class.forName("com.plexon.shops.event.PlexonShopRatedEvent");
        Class<?> visited = Class.forName("com.plexon.shops.event.PlexonShopVisitedEvent");

        for (Class<?> type : new Class<?>[]{created, rated, visited}) {
            assertTrue(PlayerEvent.class.isAssignableFrom(type));
            assertNotNull(type.getMethod("getPlayer"));
            assertNotNull(type.getMethod("player"));
            assertNotNull(type.getMethod("shopId"));
            assertNotNull(type.getMethod("getShopId"));
            assertNotNull(type.getMethod("shopType"));
            assertNotNull(type.getMethod("getShopType"));
            assertNotNull(type.getMethod("eventId"));
            assertNotNull(type.getMethod("getEventId"));
            assertNotNull(type.getMethod("transactionId"));
        }

        assertNotNull(rated.getMethod("rating"));
        assertNotNull(rated.getMethod("getRating"));
    }
}
