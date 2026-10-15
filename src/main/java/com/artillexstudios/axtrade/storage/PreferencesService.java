package com.artillexstudios.axtrade.storage;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Player preference persistence used by request checks and the toggle command. */
public interface PreferencesService {
    boolean isReady(UUID playerId);

    /** Fail closed while a player is not loaded or storage is shutting down. */
    boolean isDisabled(UUID playerId);

    /** Loads one player without allowing an older load to overwrite a newer toggle. */
    CompletableFuture<Boolean> load(UUID playerId);

    /** Persists the new value and completes only after durable storage succeeds. */
    CompletableFuture<Boolean> toggle(UUID playerId);

    void close();
}
