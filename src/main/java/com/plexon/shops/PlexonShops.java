package com.plexon.shops;

import com.plexon.shops.api.DefaultPlexonShopsAPI;
import com.plexon.shops.api.PlexonShopsAPI;
import com.plexon.shops.commands.PlexonShopsCommand;
import com.plexon.shops.config.PluginConfig;
import com.plexon.shops.event.ShopEventPublisher;
import com.plexon.shops.gui.DiscoveryGuiManager;
import com.plexon.shops.gui.GuiManager;
import com.plexon.shops.integration.core.CoreBridge;
import com.plexon.shops.integration.core.CoreBridgeFactory;
import com.plexon.shops.integrations.PlexonShopsExpansion;
import com.plexon.shops.integrations.VaultEconomyHook;
import com.plexon.shops.listeners.DiscoveryGuiListener;
import com.plexon.shops.listeners.GuiListener;
import com.plexon.shops.listeners.OwnerActivityListener;
import com.plexon.shops.listeners.TeleportListener;
import com.plexon.shops.messages.MessageService;
import com.plexon.shops.models.Shop;
import com.plexon.shops.services.ChatPromptService;
import com.plexon.shops.services.ConfirmationService;
import com.plexon.shops.services.DiscoveryService;
import com.plexon.shops.services.ShopAvailabilityResolver;
import com.plexon.shops.services.ShopCache;
import com.plexon.shops.services.ShopService;
import com.plexon.shops.services.TeleportService;
import com.plexon.shops.storage.ShopRepository;
import com.plexon.shops.storage.SqliteShopRepository;
import com.plexon.shops.storage.StorageDiagnostics;
import com.plexon.shops.util.BoundedExecutor;
import com.plexon.shops.util.MainThread;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

/** Main Paper entry point for PlexonShops. */
public final class PlexonShops extends JavaPlugin {
    private final AtomicReference<PluginConfig> runtimeConfig = new AtomicReference<>();
    private final AtomicReference<MessageService> messageService = new AtomicReference<>();

    private BoundedExecutor worker;
    private ShopRepository repository;
    private ShopService shops;
    private DiscoveryService discovery;
    private ShopAvailabilityResolver availability;
    private ConfirmationService confirmations;
    private ChatPromptService prompts;
    private TeleportService teleports;
    private VaultEconomyHook economy;
    private GuiManager guis;
    private DiscoveryGuiManager discoveryGuis;
    private PlexonShopsExpansion expansion;
    private PlexonShopsAPI publicApi;
    private CoreBridge coreBridge;
    private volatile boolean ready;
    private volatile boolean failed;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource("messages.yml", false);

        coreBridge = CoreBridgeFactory.resolve(this);
        coreBridge.registerStarting();

        try {
            PluginConfig initialConfig = PluginConfig.load(new File(getDataFolder(), "config.yml"));
            MessageService initialMessages = loadMessages();
            runtimeConfig.set(initialConfig);
            messageService.set(initialMessages);

            worker = new BoundedExecutor(
                    "PlexonShops-IO",
                    initialConfig.worker().threads(),
                    initialConfig.worker().queueCapacity()
            );
            repository = new SqliteShopRepository(
                    new File(getDataFolder(), initialConfig.database().file()),
                    initialConfig.database(),
                    worker,
                    getLogger()
            );
            ShopCache cache = new ShopCache();
            ShopEventPublisher eventPublisher = new ShopEventPublisher(this);
            shops = new ShopService(cache, repository, runtimeConfig::get, getLogger(), eventPublisher);
            discovery = new DiscoveryService(repository, shops, getLogger());
            availability = new ShopAvailabilityResolver(runtimeConfig::get);
            confirmations = new ConfirmationService(Duration.ofSeconds(30));
            economy = new VaultEconomyHook(this, runtimeConfig::get);
            prompts = new ChatPromptService(this, runtimeConfig::get, messageService::get);
            teleports = new TeleportService(
                    this,
                    shops,
                    discovery,
                    availability,
                    economy,
                    runtimeConfig::get,
                    messageService::get
            );
            guis = new GuiManager(
                    this,
                    shops,
                    teleports,
                    prompts,
                    economy,
                    runtimeConfig::get,
                    messageService::get
            );
            discoveryGuis = new DiscoveryGuiManager(
                    this,
                    shops,
                    discovery,
                    availability,
                    teleports,
                    prompts,
                    economy,
                    guis,
                    runtimeConfig::get,
                    messageService::get
            );

            registerCommands();
            registerListeners();
            economy.refresh();
            bootstrapStorage();
        } catch (RuntimeException error) {
            failed = true;
            coreBridge.markFailed("PlexonShops initialization failed");
            getLogger().log(Level.SEVERE, "PlexonShops could not initialize", error);
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        ready = false;
        if (prompts != null) {
            prompts.cancelAll();
        }
        if (teleports != null) {
            teleports.cancelAll();
        }
        if (confirmations != null) {
            confirmations.clearAll();
        }
        if (expansion != null) {
            expansion.unregister();
            expansion = null;
        }
        if (publicApi != null) {
            Bukkit.getServicesManager().unregister(PlexonShopsAPI.class, publicApi);
            publicApi = null;
        }

        int timeoutSeconds = runtimeConfig.get() == null
                ? 10
                : runtimeConfig.get().worker().shutdownTimeoutSeconds();
        if (shops != null && worker != null) {
            try {
                shops.flushVisitAnalytics().get(timeoutSeconds, TimeUnit.SECONDS);
            } catch (Exception error) {
                getLogger().log(Level.WARNING, "Could not flush all visit analytics during shutdown", MainThread.unwrap(error));
            }
        }
        if (repository != null && worker != null) {
            try {
                repository.close().get(timeoutSeconds, TimeUnit.SECONDS);
            } catch (Exception error) {
                getLogger().log(Level.WARNING, "Timed out while closing the shop database", MainThread.unwrap(error));
            }
        }
        if (worker != null && !worker.shutdown(Duration.ofSeconds(timeoutSeconds))) {
            getLogger().warning("PlexonShops background worker did not stop cleanly.");
        }
        if (coreBridge != null) {
            coreBridge.unregister();
        }
    }

    public boolean isReady() {
        return ready;
    }

    public boolean hasFailed() {
        return failed;
    }

    /** Low-overhead operational snapshot used by /pshops diagnostics. */
    public DiagnosticsSnapshot diagnostics() {
        if (!ready || shops == null || teleports == null || worker == null || discovery == null || repository == null) {
            throw new IllegalStateException("PlexonShops is not ready");
        }
        BoundedExecutor.ExecutorMetrics executor = worker.metrics();
        ShopService.VisitPersistenceMetrics visits = shops.visitPersistenceMetrics();
        return new DiagnosticsSnapshot(
                getPluginMeta().getVersion(),
                Bukkit.getVersion(),
                System.getProperty("java.version", "unknown"),
                shops.totalCount(),
                shops.openCount(),
                shops.inactiveCount(),
                shops.directoryGeneration(),
                shops.directoryCacheEntries(),
                shops.ownerOrderCacheEntries(),
                shops.activeMutationChains(),
                shops.ownerCreationsInFlight(),
                teleports.pendingCount(),
                teleports.coordinatorRunning(),
                visits,
                executor,
                economy != null && economy.available(),
                Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI"),
                Bukkit.getPluginManager().isPluginEnabled("PlexonRanks"),
                coreBridge == null ? "unavailable" : String.valueOf(coreBridge.mode()),
                discovery.featuredIds().size(),
                discovery.playerCacheEntries(),
                confirmations == null ? 0 : confirmations.pendingCount(),
                repository.diagnostics()
        );
    }

    public CompletableFuture<Void> reloadRuntime() {
        PluginConfig active = runtimeConfig.get();
        return worker.supply(() -> {
            PluginConfig loaded = PluginConfig.load(new File(getDataFolder(), "config.yml"));
            MessageService loadedMessages = loadMessages();
            if (!loaded.database().equals(active.database())) {
                getLogger().warning("Database settings changed during reload; they take effect after a restart.");
            }
            if (!loaded.worker().equals(active.worker())) {
                getLogger().warning("Worker settings changed during reload; they take effect after a restart.");
            }
            PluginConfig runtime = new PluginConfig(
                    active.database(),
                    active.worker(),
                    loaded.limits(),
                    loaded.defaults(),
                    loaded.teleport(),
                    loaded.prompts(),
                    loaded.inactivityPurgeDays(),
                    loaded.blacklistedWorlds(),
                    loaded.guis()
            );
            return new ReloadBundle(runtime, loadedMessages);
        }).thenAccept(bundle -> {
            runtimeConfig.set(bundle.config());
            messageService.set(bundle.messages());
            Bukkit.getScheduler().runTask(this, () -> {
                economy.refresh();
                publishHealth();
            });
        });
    }

    private void bootstrapStorage() {
        repository.initialize()
                .thenCompose(ignored -> repository.loadAll())
                .thenCompose(loaded -> discovery.initialize().thenApply(ignored -> loaded))
                .whenComplete((loaded, error) -> Bukkit.getScheduler().runTask(this, () -> {
                    if (error != null) {
                        failed = true;
                        coreBridge.markFailed("Database bootstrap failed");
                        getLogger().log(Level.SEVERE, "PlexonShops database bootstrap failed", MainThread.unwrap(error));
                        Bukkit.getPluginManager().disablePlugin(this);
                        return;
                    }
                    completeStartup(loaded);
                }));
    }

    private void completeStartup(List<Shop> loaded) {
        shops.load(loaded);
        registerPublicApi();
        registerPlaceholderExpansion();
        ready = true;
        publishHealth();
        Bukkit.getOnlinePlayers().forEach(player -> discovery.preload(player.getUniqueId()));
        StorageDiagnostics storage = repository.diagnostics();
        long inactive = shops.inactiveCount();
        getLogger().info("Loaded " + loaded.size() + " shops (" + inactive + " hidden by inactivity policy). Core mode: "
                + coreBridge.mode() + ". Schema: " + storage.schemaVersion() + " (" + storage.migrationStatus() + ").");
    }

    private void registerPublicApi() {
        publicApi = new DefaultPlexonShopsAPI(shops, discovery, availability);
        Bukkit.getServicesManager().register(PlexonShopsAPI.class, publicApi, this, ServicePriority.Normal);
    }

    private void publishHealth() {
        if (!ready) {
            return;
        }
        PluginConfig config = runtimeConfig.get();
        if (config.teleport().economyEnabled() && !economy.available()) {
            coreBridge.markDegraded("Shop engine ready; Vault economy provider unavailable");
            return;
        }
        StorageDiagnostics storage = repository.diagnostics();
        if (storage.schemaVersion() != SqliteShopRepository.SCHEMA_VERSION || storage.corruptRows() > 0) {
            coreBridge.markDegraded("Shop engine ready; persistence diagnostics require attention");
            return;
        }
        coreBridge.markReady("Shop engine, SQLite v3, discovery, public API and shop events ready");
    }

    private void registerCommands() {
        PluginCommand command = Objects.requireNonNull(getCommand("pshops"), "pshops command missing from plugin.yml");
        PlexonShopsCommand executor = new PlexonShopsCommand(
                this,
                () -> guis,
                () -> discoveryGuis,
                messageService::get
        );
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    private void registerListeners() {
        Bukkit.getPluginManager().registerEvents(prompts, this);
        Bukkit.getPluginManager().registerEvents(new GuiListener(
                guis, shops, confirmations, runtimeConfig::get, messageService::get), this);
        Bukkit.getPluginManager().registerEvents(new DiscoveryGuiListener(discoveryGuis), this);
        Bukkit.getPluginManager().registerEvents(new TeleportListener(teleports), this);
        Bukkit.getPluginManager().registerEvents(new OwnerActivityListener(shops, discovery), this);
    }

    private void registerPlaceholderExpansion() {
        if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            return;
        }
        expansion = new PlexonShopsExpansion(this, shops, discovery);
        if (!expansion.register()) {
            getLogger().warning("PlaceholderAPI rejected the PlexonShops expansion registration.");
            expansion = null;
        }
    }

    private MessageService loadMessages() {
        return MessageService.load(
                new File(getDataFolder(), "messages.yml"),
                getResource("messages.yml")
        );
    }

    public record DiagnosticsSnapshot(
            String version,
            String paperVersion,
            String javaVersion,
            int totalShops,
            int openShops,
            long inactiveShops,
            long directoryGeneration,
            int directoryCacheEntries,
            int ownerOrderCacheEntries,
            int activeMutationChains,
            int ownerCreationsInFlight,
            int pendingTeleports,
            boolean teleportCoordinatorRunning,
            ShopService.VisitPersistenceMetrics visitPersistence,
            BoundedExecutor.ExecutorMetrics executor,
            boolean vaultAvailable,
            boolean placeholderApiAvailable,
            boolean plexonRanksAvailable,
            String coreMode,
            int featuredShops,
            int discoveryPlayerCacheEntries,
            int pendingConfirmations,
            StorageDiagnostics storage
    ) {
    }

    private record ReloadBundle(PluginConfig config, MessageService messages) {
    }
}
