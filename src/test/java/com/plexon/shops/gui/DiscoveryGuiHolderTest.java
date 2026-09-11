package com.plexon.shops.gui;

import com.plexon.shops.models.DirectoryFilter;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DiscoveryGuiHolderTest {
    @Test
    void preservesBackNavigationContext() {
        UUID shop = UUID.randomUUID();
        DiscoveryGuiHolder holder = new DiscoveryGuiHolder(
                DiscoveryGuiType.PROFILE,
                shop,
                0,
                DirectoryFilter.ALL,
                "",
                DiscoveryGuiType.SEARCH_RESULTS,
                2,
                DirectoryFilter.ALL,
                "diamond",
                42L
        );
        assertEquals(DiscoveryGuiType.SEARCH_RESULTS, holder.returnType());
        assertEquals(2, holder.returnPage());
        assertEquals("diamond", holder.returnQuery());
        assertTrue(holder.matchesRevision(42L));
        assertFalse(holder.matchesRevision(43L));
    }

    @Test
    void duplicatePaidOrMutatingSubmissionIsAcceptedOnce() {
        DiscoveryGuiHolder holder = new DiscoveryGuiHolder(
                DiscoveryGuiType.TELEPORT_PREVIEW,
                UUID.randomUUID(),
                0,
                DirectoryFilter.ALL,
                ""
        );
        assertTrue(holder.trySubmit());
        assertFalse(holder.trySubmit());
        assertFalse(holder.trySubmit());
    }
}
