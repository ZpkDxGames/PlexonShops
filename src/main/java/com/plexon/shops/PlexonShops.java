package com.plexon.shops;

import com.plexon.shops.commands.PlexonShopsCommand;
import com.plexon.shops.config.PluginConfig;
import com.plexon.shops.gui.GuiManager;
import com.plexon.shops.integrations.PlexonShopsExpansion;
import com.plexon.shops.integrations.VaultEconomyHook;
import com.plexon.shops.listeners.GuiListener;
import com.plexon.shops.listeners.OwnerActivityListener;
import com.plexon.shops.listeners.TeleportListener;
import com.plexon.shops.messages.MessageService;
import com.plexon.shops.models.Shop;
import com.plexon.shops.services.ChatPromptService;
import com.plexon.shops.services.ShopCache;
import com.plexon.shops.services.ShopService;
import com.plexon.shops.services.TeleportService;
import com.plexon.shops.storage.ShopRepository;
import com.plexon.shops.storage.SqliteShopRepository;
import com.plexon.shops.util.BoundedExecutor;
import com.plexon.shops.util.MainThread;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
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
    private ChatPromptService prompts;
    private TeleportService teleports;
    private VaultEconomyHook economy;
    private GuiManager guis;
    private PlexonShopsExpansion expansion;
    private volatile boolean ready;
    private volatile boolean failed;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource("messages.yml", false);

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
            shops = new ShopService(cache, repository, runtimeConfig::get, getLogger());
            economy = new VaultEconomyHook(this, runtimeConfig::get);
            prompts = new ChatPromptService(this, runtimeConfig::get, messageService::get);
            teleports = new TeleportService(
                    this,
                    shops,
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

            registerCommands();
            registerListeners();
            economy.refresh();
            bootstrapStorage();
        } catch (RuntimeException error) {
            failed = true;
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
        if (expansion != null) {
            expansion.unregister();
            expansion = null;
        }

        int timeoutSeconds = runtimeConfig.get() == null
                ? 10
                : runtimeConfig.get().worker().shutdownTimeoutSeconds();
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
    }

    public boolean isReady() {
        return ready;
    }

    public boolean hasFailed() {
        return failed;
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
            Bukkit.getScheduler().runTask(this, economy::refresh);
        });
    }

    private void bootstrapStorage() {
        repository.initialize()
                .thenCompose(ignored -> repository.loadAll())
                .whenComplete((loaded, error) -> Bukkit.getScheduler().runTask(this, () -> {
                    if (error != null) {
                        failed = true;
                        getLogger().log(Level.SEVERE, "PlexonShops database bootstrap failed", MainThread.unwrap(error));
                        Bukkit.getPluginManager().disablePlugin(this);
                        return;
                    }
                    completeStartup(loaded);
                }));
    }

    private void completeStartup(List<Shop> loaded) {
        shops.load(loaded);
        ready = true;
        registerPlaceholderExpansion();
        long inactive = shops.inactiveCount();
        getLogger().info("Loaded " + loaded.size() + " shops (" + inactive + " hidden by inactivity policy).");
    }

    private void registerCommands() {
        PluginCommand command = Objects.requireNonNull(getCommand("pshops"), "pshops command missing from plugin.yml");
        PlexonShopsCommand executor = new PlexonShopsCommand(this, () -> guis, messageService::get);
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    private void registerListeners() {
        Bukkit.getPluginManager().registerEvents(prompts, this);
        Bukkit.getPluginManager().registerEvents(new GuiListener(guis), this);
        Bukkit.getPluginManager().registerEvents(new TeleportListener(teleports), this);
        Bukkit.getPluginManager().registerEvents(new OwnerActivityListener(shops), this);
    }

    private void registerPlaceholderExpansion() {
        if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            return;
        }
        expansion = new PlexonShopsExpansion(this, shops);
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

    private record ReloadBundle(PluginConfig config, MessageService messages) {
    }
}
