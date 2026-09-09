package com.plexon.shops.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreRuntimeConfigTest {
    @Test
    void defaultsToAutoWithLocalFallback() {
        PluginConfig config = PluginConfig.from(new YamlConfiguration());
        assertEquals(PluginConfig.CoreRuntimeMode.AUTO, config.coreRuntime().mode());
        assertTrue(config.coreRuntime().allowLocalListeners());
        assertTrue(config.coreRuntime().playerEvents().movement());
        assertTrue(config.coreRuntime().playerEvents().damage());
        assertTrue(config.coreRuntime().playerEvents().lifecycle());
    }

    @Test
    void parsesForcedLocalMode() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("core-runtime.mode", "local");
        PluginConfig config = PluginConfig.from(yaml);
        assertEquals(PluginConfig.CoreRuntimeMode.LOCAL, config.coreRuntime().mode());
    }
}
