package com.plexon.shops.services;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ConfirmationServiceTest {
    @Test
    void confirmationIsActorActionShopAndRevisionBound() {
        ConfirmationService confirmations = new ConfirmationService(Duration.ofSeconds(30));
        UUID actor = UUID.randomUUID();
        UUID otherActor = UUID.randomUUID();
        UUID shop = UUID.randomUUID();
        UUID otherShop = UUID.randomUUID();

        confirmations.stage(actor, "delete", shop, 7L);
        assertFalse(confirmations.consume(otherActor, "delete", shop, 7L));
        assertTrue(confirmations.consume(actor, "delete", shop, 7L));

        confirmations.stage(actor, "delete", shop, 7L);
        assertFalse(confirmations.consume(actor, "close", shop, 7L));
        assertFalse(confirmations.consume(actor, "delete", shop, 7L));

        confirmations.stage(actor, "delete", shop, 7L);
        assertFalse(confirmations.consume(actor, "delete", otherShop, 7L));

        confirmations.stage(actor, "delete", shop, 7L);
        assertFalse(confirmations.consume(actor, "delete", shop, 8L));
    }

    @Test
    void confirmationIsSingleUseAndCanBeCleared() {
        ConfirmationService confirmations = new ConfirmationService(Duration.ofSeconds(30));
        UUID actor = UUID.randomUUID();
        UUID shop = UUID.randomUUID();
        confirmations.stage(actor, "delete", shop, 1L);
        assertTrue(confirmations.consume(actor, "delete", shop, 1L));
        assertFalse(confirmations.consume(actor, "delete", shop, 1L));
        confirmations.stage(actor, "delete", shop, 1L);
        confirmations.clear(actor);
        assertFalse(confirmations.consume(actor, "delete", shop, 1L));
    }
}
