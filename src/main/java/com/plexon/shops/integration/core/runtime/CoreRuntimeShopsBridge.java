package com.plexon.shops.integration.core.runtime;

import com.plexon.shops.config.PluginConfig;
import com.plexon.shops.integration.core.CoreBridge;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Selects ownership for player activity event acquisition without leaking PlexonCore
 * runtime types into always-loaded shop domain classes.
 *
 * <p>PlexonCore 2.0.0 does not expose public movement, damage, join, or quit
 * subscriptions. Therefore the safe 2.3.0 ownership is LOCAL for those event
 * families. This boundary exists so a future Core version can supply the contracts
 * without duplicating teleport cancellation rules.</p>
 */
public final class CoreRuntimeShopsBridge implements AutoCloseable {
    private final AtomicLong epoch = new AtomicLong(1L);
    private final Snapshot snapshot;

    private CoreRuntimeShopsBridge(Snapshot snapshot) {
        this.snapshot = snapshot;
    }

    public static CoreRuntimeShopsBridge resolve(CoreBridge core, PluginConfig.CoreRuntime settings) {
        Objects.requireNonNull(core, "core");
        settings = settings == null ? PluginConfig.CoreRuntime.defaults() : settings;

        boolean coreRequested = settings.mode() != PluginConfig.CoreRuntimeMode.LOCAL;
        boolean playerRuntimeAvailable = core.runtimeApi() && core.playerEventRuntimeAvailable();
        boolean coreUsable = coreRequested && playerRuntimeAvailable;

        if (coreUsable) {
            // No adapter is shipped until Core publishes the required public contracts.
            throw new IllegalStateException("Core reported player-event runtime availability but PlexonShops 2.3.0 has no compatible adapter");
        }

        boolean requiresFallback = settings.playerEvents().movement()
                || settings.playerEvents().damage()
                || settings.playerEvents().lifecycle();
        if (requiresFallback && !settings.allowLocalListeners()) {
            throw new IllegalStateException(
                    "PlexonCore does not expose the player-event contracts required by PlexonShops and local fallback is disabled");
        }

        boolean forcedCoreUnavailable = settings.mode() == PluginConfig.CoreRuntimeMode.CORE && !playerRuntimeAvailable;
        String detail;
        if (settings.mode() == PluginConfig.CoreRuntimeMode.LOCAL) {
            detail = "Player movement, damage and lifecycle forced to optimized local listeners";
        } else if (core.runtimeApi()) {
            detail = "Core Runtime detected; stable Core lacks player movement/damage/lifecycle subscriptions, using local parity listeners";
        } else if (core.compatible()) {
            detail = "Legacy Core detected; player movement, damage and lifecycle remain local";
        } else {
            detail = "Core unavailable/incompatible; player movement, damage and lifecycle remain local";
        }

        int fallbackFamilies = settings.trackFallbacks() && coreRequested
                ? enabledFamilies(settings.playerEvents())
                : 0;
        Snapshot snapshot = new Snapshot(
                settings.mode().name(),
                "LOCAL",
                "LOCAL",
                "LOCAL",
                "LOCAL",
                "LOCAL",
                "LOCAL",
                false,
                forcedCoreUnavailable,
                fallbackFamilies,
                detail
        );
        return new CoreRuntimeShopsBridge(snapshot);
    }

    private static int enabledFamilies(PluginConfig.PlayerEvents events) {
        int total = 0;
        if (events.movement()) total++;
        if (events.damage()) total++;
        if (events.lifecycle()) total += 2;
        return total;
    }

    public Snapshot snapshot() {
        return snapshot.withEpoch(epoch.get());
    }

    public boolean useLocalTeleportListener() {
        return "LOCAL".equals(snapshot.movement())
                || "LOCAL".equals(snapshot.damage())
                || "LOCAL".equals(snapshot.quit());
    }

    public boolean useLocalOwnerActivityListener() {
        return "LOCAL".equals(snapshot.join());
    }

    @Override
    public void close() {
        epoch.incrementAndGet();
    }

    public record Snapshot(
            String requestedMode,
            String movement,
            String damage,
            String quit,
            String join,
            String gui,
            String chatPrompt,
            boolean playerWatchAvailable,
            boolean degraded,
            int fallbackFamilies,
            String detail,
            long epoch
    ) {
        private Snapshot(
                String requestedMode,
                String movement,
                String damage,
                String quit,
                String join,
                String gui,
                String chatPrompt,
                boolean playerWatchAvailable,
                boolean degraded,
                int fallbackFamilies,
                String detail
        ) {
            this(requestedMode, movement, damage, quit, join, gui, chatPrompt,
                    playerWatchAvailable, degraded, fallbackFamilies, detail, 1L);
        }

        Snapshot withEpoch(long currentEpoch) {
            return new Snapshot(requestedMode, movement, damage, quit, join, gui, chatPrompt,
                    playerWatchAvailable, degraded, fallbackFamilies, detail, currentEpoch);
        }
    }
}
