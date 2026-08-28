package com.plexon.shops.services;

import java.util.function.IntPredicate;

/** Resolves the highest numbered plexonshops.limit.N permission. */
public final class PermissionLimitResolver {
    private PermissionLimitResolver() {
    }

    public static int resolve(IntPredicate hasNumberedPermission, int defaultLimit, int scanCeiling) {
        for (int candidate = scanCeiling; candidate >= 1; candidate--) {
            if (hasNumberedPermission.test(candidate)) {
                return Math.max(defaultLimit, candidate);
            }
        }
        return defaultLimit;
    }
}
