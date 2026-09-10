package com.plexon.shops.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PluginConfigValidationTest {
    @Test
    void invalidCandidateIsRejectedWithoutMutatingKnownGoodSnapshot() {
        PluginConfig active = PluginConfig.from(new YamlConfiguration());
        int activeWarmup = active.teleport().warmupSeconds();
        YamlConfiguration invalid = new YamlConfiguration();
        invalid.set("teleport.warmup-seconds", 301);
        assertThrows(IllegalArgumentException.class, () -> PluginConfig.from(invalid));
        assertEquals(activeWarmup, active.teleport().warmupSeconds());
    }

    @Test
    void invalidGuiLayoutAndBossbarEnumsFailClosed() {
        YamlConfiguration invalidLayout = new YamlConfiguration();
        invalidLayout.set("guis.directory.size", 10);
        assertThrows(IllegalArgumentException.class, () -> PluginConfig.from(invalidLayout));

        YamlConfiguration invalidBossbar = new YamlConfiguration();
        invalidBossbar.set("teleport.bossbar.color", "NOT_A_COLOR");
        assertThrows(IllegalArgumentException.class, () -> PluginConfig.from(invalidBossbar));
    }
}
