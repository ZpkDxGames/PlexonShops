package com.plexon.shops.gui.view;

import java.util.function.DoubleFunction;

/** Pure presentation snapshot for the paid-visit confirmation surface. */
public record TeleportPreviewViewModel(
        String fee,
        String balance,
        String status,
        String detail,
        boolean paid,
        boolean ready
) {
    public static TeleportPreviewViewModel create(
            boolean destinationAvailable,
            boolean economyEnabled,
            boolean feeBypass,
            boolean economyAvailable,
            boolean failOpenWithoutVault,
            double configuredFee,
            double balance,
            DoubleFunction<String> formatter
    ) {
        if (!destinationAvailable) {
            return new TeleportPreviewViewModel(formatter.apply(configuredFee), unavailableBalance(balance, formatter),
                    "UNAVAILABLE", "The shop destination is not available.", configuredFee > 0.0D, false);
        }
        if (!economyEnabled || feeBypass || configuredFee <= 0.0D) {
            return new TeleportPreviewViewModel("FREE", unavailableBalance(balance, formatter),
                    "READY", "No teleport fee will be charged.", false, true);
        }
        if (!economyAvailable) {
            if (failOpenWithoutVault) {
                return new TeleportPreviewViewModel("FREE", "Unavailable",
                        "READY", "The economy provider is unavailable; current policy allows a free visit.", false, true);
            }
            return new TeleportPreviewViewModel(formatter.apply(configuredFee), "Unavailable",
                    "PAYMENT UNAVAILABLE", "The economy service cannot process this visit right now.", true, false);
        }
        String formattedBalance = formatter.apply(balance);
        if (!Double.isFinite(balance) || balance < configuredFee) {
            return new TeleportPreviewViewModel(formatter.apply(configuredFee), formattedBalance,
                    "INSUFFICIENT BALANCE", "You need more money before visiting this shop.", true, false);
        }
        return new TeleportPreviewViewModel(formatter.apply(configuredFee), formattedBalance,
                "READY", "The fee is charged only by the existing teleport service when you confirm.", true, true);
    }

    private static String unavailableBalance(double balance, DoubleFunction<String> formatter) {
        return Double.isFinite(balance) ? formatter.apply(balance) : "Unavailable";
    }
}
