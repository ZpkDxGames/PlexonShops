package com.plexon.shops.models;

import java.util.UUID;

/** Serialized directory showcase item with an owner-provided label. */
public record LabeledItem(UUID id, String label, String itemData, long createdAtEpochSecond) {
    public LabeledItem {
        label = label == null ? "" : label;
        itemData = itemData == null ? "" : itemData;
    }
}
