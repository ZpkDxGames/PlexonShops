package com.plexon.shops.services;

import com.plexon.shops.config.PluginConfig;
import com.plexon.shops.messages.MessageService;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** One-shot chat input used only after a player chooses a text-editing GUI action. */
public final class ChatPromptService implements Listener {
    private final JavaPlugin plugin;
    private final Supplier<PluginConfig> config;
    private final Supplier<MessageService> messages;
    private final Map<UUID, PendingPrompt> prompts = new ConcurrentHashMap<>();

    public ChatPromptService(
            JavaPlugin plugin,
            Supplier<PluginConfig> config,
            Supplier<MessageService> messages
    ) {
        this.plugin = plugin;
        this.config = config;
        this.messages = messages;
    }

    public void begin(Player player, String promptMessageKey, Consumer<String> response) {
        cancel(player.getUniqueId(), false);
        player.closeInventory();
        MessageService messageService = messages.get();
        messageService.send(player, promptMessageKey);
        messageService.send(player, "prompt-cancel-hint",
                Placeholder.unparsed("cancel", config.get().prompts().cancelWord()));

        int seconds = config.get().prompts().timeoutSeconds();
        AtomicReference<PendingPrompt> reference = new AtomicReference<>();
        BukkitTask timeout = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            PendingPrompt expected = reference.get();
            if (expected != null && prompts.remove(player.getUniqueId(), expected)) {
                messages.get().send(player, "prompt-timeout");
            }
        }, seconds * 20L);
        PendingPrompt pending = new PendingPrompt(response, timeout);
        reference.set(pending);
        prompts.put(player.getUniqueId(), pending);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        PendingPrompt pending = prompts.remove(event.getPlayer().getUniqueId());
        if (pending == null) {
            return;
        }
        event.setCancelled(true);
        String input = PlainTextComponentSerializer.plainText().serialize(event.message()).strip();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (pending.timeout() != null) {
                pending.timeout().cancel();
            }
            if (input.equalsIgnoreCase(config.get().prompts().cancelWord())) {
                messages.get().send(event.getPlayer(), "prompt-cancelled");
                return;
            }
            pending.response().accept(input);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cancel(event.getPlayer().getUniqueId(), false);
    }

    public void cancelAll() {
        prompts.keySet().forEach(uuid -> cancel(uuid, false));
    }

    private void cancel(UUID playerUuid, boolean notify) {
        PendingPrompt removed = prompts.remove(playerUuid);
        if (removed == null) {
            return;
        }
        if (removed.timeout() != null) {
            removed.timeout().cancel();
        }
        if (notify) {
            Player player = Bukkit.getPlayer(playerUuid);
            if (player != null) {
                messages.get().send(player, "prompt-cancelled");
            }
        }
    }

    private record PendingPrompt(Consumer<String> response, BukkitTask timeout) {
    }
}
