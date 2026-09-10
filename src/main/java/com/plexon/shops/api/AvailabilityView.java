package com.plexon.shops.api;

/** Immutable public availability snapshot. Evaluate from the Paper server thread. */
public record AvailabilityView(String state, boolean teleportable, String reason) {
    public AvailabilityView {
        state = state == null ? "unavailable" : state;
        reason = reason == null ? "unknown" : reason;
    }
}
