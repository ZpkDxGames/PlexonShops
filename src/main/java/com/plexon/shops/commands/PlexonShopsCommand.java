package com.plexon.shops.commands;

import com.plexon.shops.PlexonShops;
import com.plexon.shops.gui.GuiManager;
import com.plexon.shops.integration.core.runtime.CoreRuntimeShopsBridge;
import com.plexon.shops.messages.MessageService;
import com.plexon.shops.services.ShopService;
import com.plexon.shops.services.TeleportService;
import com.plexon.shops.util.BoundedExecutor;
import com.plexon.shops.util.MainThread;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/** Small command surface that routes player work into GUIs and admin diagnostics. */
public final class PlexonShopsCommand implements TabExecutor {
    private final PlexonShops plugin;
    private final Supplier<GuiManager> guis;
    private final Supplier<MessageService> messages;

    public PlexonShopsCommand(
            PlexonShops plugin,
            Supplier<GuiManager> guis,
            Supplier<MessageService> messages
    ) {
        this.plugin = plugin;
        this.guis = guis;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] args
    ) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            return reload(sender);
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("diagnostics")) {
            return diagnostics(sender);
        }
        if (!(sender instanceof Player player)) {
            messages.get().send(sender, "players-only");
            return true;
        }
        if (!player.hasPermission("plexonshops.use")) {
            messages.get().send(player, "no-permission");
            return true;
        }
        if (!plugin.isReady()) {
            messages.get().send(player, plugin.hasFailed() ? "disabled" : "loading");
            return true;
        }
        if (args.length == 0) {
            guis.get().openDirectory(player);
            return true;
        }
        if (args[0].equalsIgnoreCase("manage")) {
            if (!player.hasPermission("plexonshops.create")) {
                messages.get().send(player, "no-permission");
                return true;
            }
            guis.get().openOwnerManagement(player);
            return true;
        }
        player.sendMessage(messages.get().prefix().append(messages.get().raw(
                "<gray>Use <white>/pshops</white>, <white>/pshops manage</white>, "
                        + "<white>/pshops diagnostics</white>, or <white>/pshops reload</white>.</gray>")));
        return true;
    }

    private boolean reload(CommandSender sender) {
        if (!sender.hasPermission("plexonshops.admin") && !sender.hasPermission("plexonshops.reload")) {
            messages.get().send(sender, "no-permission");
            return true;
        }
        if (!plugin.isReady()) {
            messages.get().send(sender, plugin.hasFailed() ? "disabled" : "loading");
            return true;
        }
        MainThread.whenComplete(plugin, plugin.reloadRuntime(), ignored ->
                        messages.get().send(sender, "reloaded"),
                error -> {
                    plugin.getLogger().log(java.util.logging.Level.SEVERE, "Could not reload PlexonShops", error);
                    messages.get().send(sender, "reload-failed");
                });
        return true;
    }

    private boolean diagnostics(CommandSender sender) {
        if (!sender.hasPermission("plexonshops.admin")) {
            messages.get().send(sender, "no-permission");
            return true;
        }
        if (!plugin.isReady()) {
            messages.get().send(sender, plugin.hasFailed() ? "disabled" : "loading");
            return true;
        }

        PlexonShops.DiagnosticsSnapshot snapshot = plugin.diagnostics();
        ShopService.VisitPersistenceMetrics visits = snapshot.visitPersistence();
        BoundedExecutor.ExecutorMetrics executor = snapshot.executor();
        TeleportService.TeleportMetrics teleport = snapshot.teleportMetrics();
        CoreRuntimeShopsBridge.Snapshot ownership = snapshot.runtimeOwnership();
        sender.sendMessage("§6§lPlexonShops Diagnostics §8— §f" + snapshot.version());
        sender.sendMessage("§7Runtime: §f" + snapshot.paperVersion() + " §8| §7Java: §f" + snapshot.javaVersion());
        sender.sendMessage("§7Shops: §f" + snapshot.totalShops()
                + " §8| §7Open: §f" + snapshot.openShops()
                + " §8| §7Inactive: §f" + snapshot.inactiveShops());
        sender.sendMessage("§7Directory: §fgeneration " + snapshot.directoryGeneration()
                + " §8| §7cached filters: §f" + snapshot.directoryCacheEntries()
                + " §8| §7owner-order caches: §f" + snapshot.ownerOrderCacheEntries());
        sender.sendMessage("§7Teleports: §fpending " + snapshot.pendingTeleports()
                + " §8| §7cooldowns: §f" + snapshot.cooldowns()
                + " §8| §7coordinator: §f" + (snapshot.teleportCoordinatorRunning() ? "running" : "idle"));
        sender.sendMessage("§7Movement: §f" + teleport.movementEventsReceived() + " received §8| §f"
                + teleport.movementFastRejects() + " fast rejects §8| §f"
                + teleport.movementCancellations() + " cancellations");
        sender.sendMessage("§7Damage: §f" + teleport.damageEventsReceived() + " received §8| §f"
                + teleport.damageFastRejects() + " fast rejects §8| §f"
                + teleport.damageCancellations() + " cancellations §8| §7quit cancels: §f"
                + teleport.quitCancellations());
        sender.sendMessage("§7Player event ownership: §fmove=" + ownership.movement()
                + " §8| §fdamage=" + ownership.damage()
                + " §8| §fquit=" + ownership.quit()
                + " §8| §fjoin=" + ownership.join());
        sender.sendMessage("§7Local-only: §fGUI=" + ownership.gui()
                + " §8| §fchat=" + ownership.chatPrompt()
                + " §8| §7epoch: §f" + ownership.epoch()
                + " §8| §7fallback families: §f" + ownership.fallbackFamilies());
        sender.sendMessage("§7Mutations: §f" + snapshot.activeMutationChains()
                + " active chains §8| §7owner creation guards: §f" + snapshot.ownerCreationsInFlight());
        sender.sendMessage("§7Visits: §f" + visits.pendingShops() + " pending shops §8| §f"
                + visits.pendingUniqueVisitors() + " pending unique §8| §f" + visits.activeFlushes() + " active flushes");
        sender.sendMessage("§7Visit flushes: §f" + visits.completedFlushes() + " completed §8| §f"
                + visits.failedFlushes() + " failed");
        sender.sendMessage("§7DB worker: §f" + executor.queueDepth() + '/' + executor.queueCapacity()
                + " queued §8| §f" + executor.activeThreads() + " active §8| §f"
                + executor.rejectedOperations() + " rejected");
        sender.sendMessage(String.format(
                Locale.ROOT,
                "§7DB tasks: §f%d submitted §8| §f%d completed §8| §7P95: §f%.2f ms §8| §7oldest queued: §f%d ms",
                executor.submittedOperations(),
                executor.completedOperations(),
                executor.p95TaskLatencyMillis(),
                executor.oldestQueuedTaskAgeMillis()
        ));
        sender.sendMessage("§7Core: §fplugin " + snapshot.corePluginVersion()
                + " §8| §7API: §f" + snapshot.coreApiVersion()
                + " §8| §7mode: §f" + snapshot.coreMode()
                + " §8| §7module: §f" + snapshot.coreRegistrationState());
        sender.sendMessage("§7Core player watch: §f" + (ownership.playerWatchAvailable() ? "available" : "unavailable")
                + " §8| §7requested: §f" + ownership.requestedMode()
                + (ownership.degraded() ? " §8| §cDEGRADED" : ""));
        sender.sendMessage("§7Integrations: §fVault=" + status(snapshot.vaultAvailable())
                + " §8| §fPAPI=" + status(snapshot.placeholderApiAvailable())
                + " §8| §fPlexonRanks=" + status(snapshot.plexonRanksAvailable()));
        return true;
    }

    private String status(boolean available) {
        return available ? "ready" : "unavailable";
    }

    @Override
    public @Nullable List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            @NotNull String[] args
    ) {
        if (args.length != 1) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return List.of("manage", "diagnostics", "reload").stream()
                .filter(option -> option.startsWith(prefix))
                .filter(option -> !option.equals("reload")
                        || sender.hasPermission("plexonshops.admin")
                        || sender.hasPermission("plexonshops.reload"))
                .filter(option -> !option.equals("diagnostics") || sender.hasPermission("plexonshops.admin"))
                .toList();
    }
}
