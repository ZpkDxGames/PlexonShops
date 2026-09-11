package com.plexon.shops.integrations;

import com.plexon.shops.config.PluginConfig;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;
import java.util.function.Supplier;

/** Optional Vault bridge. Vault calls are intentionally made only on Paper's server thread. */
public final class VaultEconomyHook {
    private final JavaPlugin plugin;
    private final Supplier<PluginConfig> config;
    private volatile Economy economy;

    public VaultEconomyHook(JavaPlugin plugin, Supplier<PluginConfig> config) {
        this.plugin = plugin;
        this.config = config;
    }

    public boolean refresh() {
        economy = null;
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        RegisteredServiceProvider<Economy> registration = Bukkit.getServicesManager().getRegistration(Economy.class);
        economy = registration == null ? null : registration.getProvider();
        return economy != null;
    }

    public ChargeResult charge(Player player, double amount) {
        PluginConfig.Teleport settings = config.get().teleport();
        if (!settings.economyEnabled() || amount <= 0.0D) {
            return ChargeResult.free();
        }
        Economy current = economy;
        if (current == null) {
            return settings.failOpenWithoutVault()
                    ? ChargeResult.free()
                    : ChargeResult.failed(ChargeFailure.UNAVAILABLE);
        }
        if (!current.has(player, amount)) {
            return ChargeResult.failed(ChargeFailure.INSUFFICIENT_FUNDS);
        }
        EconomyResponse response = current.withdrawPlayer(player, amount);
        return response.transactionSuccess()
                ? new ChargeResult(true, amount, null)
                : ChargeResult.failed(ChargeFailure.PROVIDER_ERROR);
    }

    public void refund(Player player, double amount) {
        Economy current = economy;
        if (current == null || amount <= 0.0D) {
            return;
        }
        EconomyResponse response = current.depositPlayer(player, amount);
        if (!response.transactionSuccess()) {
            plugin.getLogger().warning("Vault could not refund " + player.getUniqueId() + ": " + response.errorMessage);
        }
    }

    /** Read-only balance snapshot for player-facing teleport previews; never mutates economy state. */
    public double balance(Player player) {
        Economy current = economy;
        return current == null ? Double.NaN : current.getBalance(player);
    }

    public String format(double amount) {
        Economy current = economy;
        return current == null ? String.format(Locale.ROOT, "%.2f", amount) : current.format(amount);
    }

    public boolean available() {
        return economy != null;
    }

    public enum ChargeFailure {
        UNAVAILABLE,
        INSUFFICIENT_FUNDS,
        PROVIDER_ERROR
    }

    public record ChargeResult(boolean success, double chargedAmount, ChargeFailure failure) {
        public static ChargeResult free() {
            return new ChargeResult(true, 0.0D, null);
        }

        public static ChargeResult failed(ChargeFailure failure) {
            return new ChargeResult(false, 0.0D, failure);
        }
    }
}
