package com.plexon.shops.services;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PermissionLimitResolverTest {
    @Test
    void returnsHighestGrantedOverride() {
        Set<Integer> granted = Set.of(2, 5, 9);

        int resolved = PermissionLimitResolver.resolve(granted::contains, 1, 20);

        assertEquals(9, resolved);
    }

    @Test
    void neverLowersDefaultLimit() {
        int resolved = PermissionLimitResolver.resolve(candidate -> candidate == 2, 4, 20);

        assertEquals(4, resolved);
    }
}
