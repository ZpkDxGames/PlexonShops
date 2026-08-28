package com.plexon.shops.util;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Bridges asynchronous work back to Paper's server thread. */
public final class MainThread {
    private MainThread() {
    }

    public static <T> void whenComplete(
            Plugin plugin,
            CompletableFuture<T> future,
            Consumer<T> success,
            Consumer<Throwable> failure
    ) {
        future.whenComplete((value, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error == null) {
                success.accept(value);
            } else {
                failure.accept(unwrap(error));
            }
        }));
    }

    public static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
