package com.plexon.shops.models;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.Optional;
import java.util.UUID;

/** Persistable Paper location that resolves worlds only on the server thread. */
public record ShopLocation(
        UUID worldUuid,
        String worldName,
        double x,
        double y,
        double z,
        float yaw,
        float pitch
) {
    public ShopLocation {
        worldName = worldName == null ? "" : worldName;
    }

    public static ShopLocation from(Location location) {
        World world = location.getWorld();
        if (world == null) {
            throw new IllegalArgumentException("Location does not reference a loaded world");
        }
        return new ShopLocation(
                world.getUID(),
                world.getName(),
                location.getX(),
                location.getY(),
                location.getZ(),
                location.getYaw(),
                location.getPitch()
        );
    }

    public Optional<Location> resolve() {
        World world = worldUuid == null ? null : Bukkit.getWorld(worldUuid);
        if (world == null && !worldName.isBlank()) {
            world = Bukkit.getWorld(worldName);
        }
        return world == null ? Optional.empty() : Optional.of(new Location(world, x, y, z, yaw, pitch));
    }
}
