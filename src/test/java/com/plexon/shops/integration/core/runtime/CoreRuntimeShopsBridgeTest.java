package com.plexon.shops.integration.core.runtime;

import com.plexon.shops.config.PluginConfig;
import com.plexon.shops.integration.core.CoreBridge;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreRuntimeShopsBridgeTest {
    @Test
    void autoUsesLocalParityWhenCore2HasNoPlayerEventGateway() {
        CoreRuntimeShopsBridge bridge = CoreRuntimeShopsBridge.resolve(
                new StubCoreBridge(true, true, true, false, "CORE_RUNTIME"),
                PluginConfig.CoreRuntime.defaults()
        );
        CoreRuntimeShopsBridge.Snapshot snapshot = bridge.snapshot();
        assertEquals("LOCAL", snapshot.movement());
        assertEquals("LOCAL", snapshot.damage());
        assertEquals("LOCAL", snapshot.quit());
        assertEquals("LOCAL", snapshot.join());
        assertEquals(4, snapshot.fallbackFamilies());
        assertFalse(snapshot.degraded());
    }

    @Test
    void forcedCoreReportsDegradedWhenFallbackIsAllowed() {
        PluginConfig.CoreRuntime settings = new PluginConfig.CoreRuntime(
                PluginConfig.CoreRuntimeMode.CORE,
                new PluginConfig.PlayerEvents(true, true, true),
                true,
                true
        );
        CoreRuntimeShopsBridge bridge = CoreRuntimeShopsBridge.resolve(
                new StubCoreBridge(true, true, true, false, "CORE_RUNTIME"),
                settings
        );
        assertTrue(bridge.snapshot().degraded());
        assertEquals("LOCAL", bridge.snapshot().movement());
    }

    @Test
    void refusesUnsafeStartupWhenFallbackIsDisabled() {
        PluginConfig.CoreRuntime settings = new PluginConfig.CoreRuntime(
                PluginConfig.CoreRuntimeMode.CORE,
                new PluginConfig.PlayerEvents(true, true, true),
                false,
                true
        );
        assertThrows(IllegalStateException.class, () -> CoreRuntimeShopsBridge.resolve(
                new StubCoreBridge(true, true, true, false, "CORE_RUNTIME"),
                settings
        ));
    }

    @Test
    void closeAdvancesRuntimeEpoch() {
        CoreRuntimeShopsBridge bridge = CoreRuntimeShopsBridge.resolve(
                new StubCoreBridge(false, false, false, false, "STANDALONE"),
                PluginConfig.CoreRuntime.defaults()
        );
        long before = bridge.snapshot().epoch();
        bridge.close();
        assertEquals(before + 1L, bridge.snapshot().epoch());
    }

    private record StubCoreBridge(
            boolean installed,
            boolean available,
            boolean compatible,
            boolean playerEventRuntimeAvailable,
            String mode
    ) implements CoreBridge {
        @Override public boolean runtimeApi() { return "CORE_RUNTIME".equals(mode); }
        @Override public String pluginVersion() { return runtimeApi() ? "2.0.0" : "-"; }
        @Override public String apiVersion() { return runtimeApi() ? "2.0" : "-"; }
        @Override public String registrationState() { return installed ? "READY" : "NOT_INSTALLED"; }
        @Override public String detail() { return "test"; }
        @Override public void registerStarting() {}
        @Override public void markReady(String detail) {}
        @Override public void markDegraded(String detail) {}
        @Override public void markFailed(String detail) {}
        @Override public void unregister() {}
    }
}
