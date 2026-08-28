package com.plexon.shops.config;

import java.util.List;
import java.util.Map;

/** Immutable inventory size and slot mapping loaded from config.yml. */
public record GuiLayout(int size, List<Integer> contentSlots, Map<String, Integer> slots) {
    public GuiLayout {
        int normalizedSize = normalizeSize(size);
        contentSlots = contentSlots.stream()
                .filter(slot -> slot >= 0 && slot < normalizedSize)
                .distinct()
                .toList();
        slots = Map.copyOf(slots);
        size = normalizedSize;
    }

    public int slot(String key, int fallback) {
        int candidate = slots.getOrDefault(key, fallback);
        return candidate >= 0 && candidate < size ? candidate : Math.clamp(fallback, 0, size - 1);
    }

    private static int normalizeSize(int requested) {
        int clamped = Math.clamp(requested, 9, 54);
        return clamped - (clamped % 9);
    }
}
