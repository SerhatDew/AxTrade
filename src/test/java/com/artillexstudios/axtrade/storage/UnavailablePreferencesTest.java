package com.artillexstudios.axtrade.storage;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnavailablePreferencesTest {
    @Test
    void unavailableStorageFailsClosedForRequestChecks() {
        UUID playerId = UUID.randomUUID();
        PreferencesService service = UnavailablePreferences.INSTANCE;

        assertFalse(service.isReady(playerId));
        assertTrue(service.isDisabled(playerId));
        assertThrows(CompletionException.class, () -> service.load(playerId).join());
        assertThrows(CompletionException.class, () -> service.toggle(playerId).join());
    }
}
