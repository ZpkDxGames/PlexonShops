package com.plexon.shops.gui;

import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;

final class DirectoryGuiArchitectureTest {
    @Test
    void rendererOwnsNoPersistenceRepository() {
        assertFalse(Arrays.stream(DiscoveryGuiManager.class.getDeclaredFields())
                .map(Field::getType)
                .map(Class::getName)
                .anyMatch(name -> name.contains("storage") || name.endsWith("Repository")));
    }

    @Test
    void rendererOwnsNoPerPlayerRepeatingTask() {
        assertFalse(Arrays.stream(DiscoveryGuiManager.class.getDeclaredFields())
                .map(Field::getType)
                .anyMatch(BukkitTask.class::isAssignableFrom));
    }
}
