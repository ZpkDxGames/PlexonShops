package com.plexon.shops.util;

import org.bukkit.inventory.ItemStack;

import java.util.Base64;
import java.util.Optional;

/** Encodes Paper item stacks without touching the filesystem. Call from the server thread. */
public final class ItemStackCodec {
    private ItemStackCodec() {
    }

    public static String encode(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return "";
        }
        return Base64.getEncoder().encodeToString(item.clone().serializeAsBytes());
    }

    public static Optional<ItemStack> decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(ItemStack.deserializeBytes(Base64.getDecoder().decode(encoded)));
        } catch (IllegalArgumentException error) {
            return Optional.empty();
        }
    }
}
