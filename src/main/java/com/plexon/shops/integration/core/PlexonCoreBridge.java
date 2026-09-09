package com.plexon.shops.integration.core;

import com.zpkdxgames.plexoncore.api.PlexonCoreAPI;
import com.zpkdxgames.plexoncore.api.PlexonCoreAPI.CoreVersion;
import com.zpkdxgames.plexoncore.module.ModuleRegistry;
import com.zpkdxgames.plexoncore.module.ModuleRegistry.ModuleDescriptor;
import com.zpkdxgames.plexoncore.module.ModuleRegistry.ModuleState;
import com.zpkdxgames.plexoncore.module.ModuleRegistry.ModuleVersionRange;
import java.time.Instant;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

public final class PlexonCoreBridge implements CoreBridge {
    private static final Set<String> CAPABILITIES = Set.of(
            "shop-engine",
            "player-shops",
            "sub-shops",
            "shop-directory",
            "shop-api",
            "shop-created-event",
            "shop-visited-event",
            "shop-rated-event",
            "shop-ratings",
            "shop-visitors",
            "sqlite-persistence",
            "vault-teleport-fees",
            "teleport-warmup",
            "player-lifecycle-fallback");

    private final Plugin plugin;
    private final PlexonCoreAPI core;
    private final CoreVersion version;
    private final boolean runtimeApi;
    private final boolean compatible;
    private final String selectedRange;
    private boolean ownsRegistration;
    private String registrationState = "NOT_REGISTERED";
    private String detail = "PlexonCore API resolved";

    public PlexonCoreBridge(JavaPlugin plugin) {
        this(plugin, resolveApi());
    }

    PlexonCoreBridge(Plugin plugin, PlexonCoreAPI core) {
        this.plugin = plugin;
        this.core = core;
        this.version = core.version();
        this.runtimeApi = version.apiMajor() == 2;
        this.selectedRange = runtimeApi ? RUNTIME_API_RANGE : LEGACY_API_RANGE;
        this.compatible = ModuleVersionRange.parse(selectedRange).contains(version);
        if (!compatible) {
            detail = "Core API " + version.apiVersion() + " is outside supported ranges " + SUPPORTED_API_RANGE;
        }
    }

    private static PlexonCoreAPI resolveApi() {
        RegisteredServiceProvider<PlexonCoreAPI> registration =
                Bukkit.getServicesManager().getRegistration(PlexonCoreAPI.class);
        if (registration == null) {
            throw new IllegalStateException("PlexonCore API service is not registered");
        }
        return registration.getProvider();
    }

    @Override public boolean installed() { return true; }
    @Override public boolean available() { return compatible; }
    @Override public boolean compatible() { return compatible; }
    @Override public boolean runtimeApi() { return runtimeApi && compatible; }

    @Override
    public boolean playerEventRuntimeAvailable() {
        // Stable PlexonCore 2.0.0 exposes the shared block-event gateway, but no public
        // movement/damage/join/quit subscription contracts. Shops must retain its local
        // parity listeners until a future Core release publishes those contracts.
        return false;
    }

    @Override public String pluginVersion() { return version.pluginVersion(); }
    @Override public String apiVersion() { return version.apiVersion(); }

    @Override
    public String mode() {
        if (!compatible || !ownsRegistration) {
            return "STANDALONE";
        }
        return runtimeApi ? "CORE_RUNTIME" : "CORE_LEGACY";
    }

    @Override
    public String registrationState() {
        if (ownsRegistration) {
            return core.modules().find(MODULE_ID).map(descriptor -> descriptor.state().name()).orElse("NOT_REGISTERED");
        }
        return registrationState;
    }

    @Override
    public String detail() {
        if (ownsRegistration) {
            return core.modules().find(MODULE_ID).map(ModuleDescriptor::detail).orElse(detail);
        }
        return detail;
    }

    @Override
    public void registerStarting() {
        ModuleDescriptor descriptor = new ModuleDescriptor(
                MODULE_ID,
                "PlexonShops",
                plugin.getName(),
                plugin.getPluginMeta().getVersion(),
                plugin,
                ModuleVersionRange.parse(selectedRange),
                CAPABILITIES,
                ModuleState.STARTING,
                "Initializing PlexonShops",
                Instant.now());
        ModuleRegistry.RegistrationResult result = core.modules().register(descriptor);
        ModuleDescriptor registered = result.descriptor();
        ownsRegistration = registered != null && registered.plugin() == plugin;
        registrationState = registered == null ? "NOT_REGISTERED" : registered.state().name();
        detail = result.message();
        if (!result.success() && !ownsRegistration) {
            plugin.getLogger().warning("PlexonCore module registration rejected: " + result.message());
        } else if (!compatible) {
            plugin.getLogger().warning("PlexonCore API " + version.apiVersion()
                    + " is incompatible with supported ranges " + SUPPORTED_API_RANGE
                    + "; Shops will continue in standalone compatibility mode.");
        }
    }

    @Override public void markReady(String detail) { update(ModuleState.READY, detail); }
    @Override public void markDegraded(String detail) { update(ModuleState.DEGRADED, detail); }
    @Override public void markFailed(String detail) { update(ModuleState.FAILED, detail); }

    private void update(ModuleState state, String newDetail) {
        if (!compatible || !ownsRegistration) {
            return;
        }
        core.modules().updateState(MODULE_ID, state, newDetail);
        registrationState = state.name();
        detail = newDetail == null ? "" : newDetail;
    }

    @Override
    public void unregister() {
        if (!ownsRegistration) {
            return;
        }
        core.modules().find(MODULE_ID)
                .filter(descriptor -> descriptor.plugin() == plugin)
                .ifPresent(descriptor -> core.modules().unregister(MODULE_ID));
        ownsRegistration = false;
        registrationState = "UNREGISTERED";
    }
}
