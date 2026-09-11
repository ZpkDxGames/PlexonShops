package com.plexon.shops.gui;

import com.plexon.shops.gui.view.TeleportPreviewViewModel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TeleportPreviewViewModelTest {
    @Test
    void freeVisitNeedsNoPaidConfirmation() {
        TeleportPreviewViewModel model = TeleportPreviewViewModel.create(
                true, true, false, true, false, 0.0D, 100.0D, amount -> "$" + amount);
        assertEquals("FREE", model.fee());
        assertFalse(model.paid());
        assertTrue(model.ready());
    }

    @Test
    void paidVisitShowsAuthoritativeFeeAndBalanceSnapshot() {
        TeleportPreviewViewModel model = TeleportPreviewViewModel.create(
                true, true, false, true, false, 25.0D, 100.0D, amount -> "$" + amount);
        assertEquals("$25.0", model.fee());
        assertEquals("$100.0", model.balance());
        assertEquals("READY", model.status());
        assertTrue(model.paid());
        assertTrue(model.ready());
    }

    @Test
    void insufficientBalanceIsVisibleBeforeSubmission() {
        TeleportPreviewViewModel model = TeleportPreviewViewModel.create(
                true, true, false, true, false, 125.0D, 100.0D, amount -> "$" + amount);
        assertEquals("INSUFFICIENT BALANCE", model.status());
        assertTrue(model.paid());
        assertFalse(model.ready());
    }

    @Test
    void missingVaultFollowsExistingFailOpenPolicy() {
        TeleportPreviewViewModel closed = TeleportPreviewViewModel.create(
                true, true, false, false, false, 25.0D, Double.NaN, amount -> "$" + amount);
        TeleportPreviewViewModel open = TeleportPreviewViewModel.create(
                true, true, false, false, true, 25.0D, Double.NaN, amount -> "$" + amount);
        assertEquals("PAYMENT UNAVAILABLE", closed.status());
        assertFalse(closed.ready());
        assertEquals("FREE", open.fee());
        assertTrue(open.ready());
        assertFalse(open.paid());
    }

    @Test
    void unavailableDestinationCannotBecomePayable() {
        TeleportPreviewViewModel model = TeleportPreviewViewModel.create(
                false, true, false, true, false, 25.0D, 100.0D, amount -> "$" + amount);
        assertEquals("UNAVAILABLE", model.status());
        assertFalse(model.ready());
    }
}
