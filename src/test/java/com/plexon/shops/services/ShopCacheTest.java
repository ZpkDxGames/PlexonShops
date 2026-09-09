package com.plexon.shops.services;

import com.plexon.shops.models.Shop;
import com.plexon.shops.models.ShopStatus;
import com.plexon.shops.models.ShopTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopCacheTest {
    @Test
    void maintainsOwnerCountAndOpenCounterWithoutSortedLookups() {
        ShopCache cache = new ShopCache();
        Shop shop = ShopTest.shop();

        cache.put(shop);

        assertEquals(1, cache.size());
        assertEquals(1, cache.ownedCount(shop.ownerUuid()));
        assertEquals(1, cache.openCount());
        assertEquals(1, cache.unsortedSnapshot().size());

        Shop closed = shop.withStatus(ShopStatus.CLOSED, 101L);
        assertTrue(cache.replace(shop, closed));
        assertEquals(1, cache.ownedCount(shop.ownerUuid()));
        assertEquals(0, cache.openCount());

        cache.remove(shop.id());
        assertEquals(0, cache.size());
        assertEquals(0, cache.ownedCount(shop.ownerUuid()));
        assertEquals(0, cache.openCount());
    }

    @Test
    void replaceAllRebuildsCountersAndOwnerIndex() {
        Shop first = ShopTest.shop();
        Shop second = ShopTest.shop().withStatus(ShopStatus.CLOSED, 101L);
        ShopCache cache = new ShopCache();

        cache.replaceAll(java.util.List.of(first, second));

        assertEquals(2, cache.size());
        assertEquals(1, cache.openCount());
        assertEquals(1, cache.ownedCount(first.ownerUuid()));
        assertEquals(1, cache.ownedCount(second.ownerUuid()));
    }
}
