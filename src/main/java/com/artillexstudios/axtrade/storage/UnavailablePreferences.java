package com.artillexstudios.axtrade.storage;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class UnavailablePreferences implements PreferencesService {
    public static final UnavailablePreferences INSTANCE = new UnavailablePreferences();

    private UnavailablePreferences() {
    }

    @Override
    public boolean isReady(UUID playerId) {
        return false;
    }

    @Override
    public boolean isDisabled(UUID playerId) {
        return true;
    }

    @Override
    public CompletableFuture<Boolean> load(UUID playerId) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        future.completeExceptionally(new IllegalStateException("AxTrade storage is unavailable"));
        return future;
    }

    @Override
    public CompletableFuture<Boolean> toggle(UUID playerId) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        future.completeExceptionally(new IllegalStateException("AxTrade storage is unavailable"));
        return future;
    }

    @Override
    public void close() {
    }
}
