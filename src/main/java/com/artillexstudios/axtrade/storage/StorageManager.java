package com.artillexstudios.axtrade.storage;

import com.artillexstudios.axapi.AxPlugin;
import com.artillexstudios.axapi.config.Config;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class StorageManager implements PreferencesService {
    private final AxPlugin plugin;
    private final Config toggled;
    private final StorageConfig config;
    private final ExecutorService executor;
    private final ScheduledExecutorService scheduler;
    private final ConcurrentHashMap<UUID, Boolean> values = new ConcurrentHashMap<>();
    private final Set<UUID> ready = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<UUID, Long> generations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> applied = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Object> locks = new ConcurrentHashMap<>();
    private volatile SqlStore sql;
    private volatile RedisPreferenceSync redis;
    private volatile boolean closed;

    private StorageManager(AxPlugin plugin, Config toggled, StorageConfig config, Map<UUID, Boolean> yamlValues) {
        this.plugin = plugin;
        this.toggled = toggled;
        this.config = config;
        this.values.putAll(yamlValues);
        this.executor = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(256), runnable -> {
                    Thread thread = new Thread(runnable, "AxTrade-storage");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "AxTrade-storage-reconcile");
            thread.setDaemon(true);
            return thread;
        });
    }

    public static CompletableFuture<StorageManager> openAsync(AxPlugin plugin, Config config, Config toggled) {
        StorageConfig storageConfig = StorageConfig.read(config);
        StorageManager manager = new StorageManager(plugin, toggled, storageConfig, readYamlValues(toggled));
        CompletableFuture<StorageManager> result = new CompletableFuture<>();
        manager.executor.execute(() -> {
            try {
                manager.openBackends();
                result.complete(manager);
            } catch (Throwable throwable) {
                manager.close();
                result.completeExceptionally(throwable);
            }
        });
        return result;
    }

    private static Map<UUID, Boolean> readYamlValues(Config toggled) {
        Map<UUID, Boolean> result = new ConcurrentHashMap<>();
        var section = toggled.getSection("toggled");
        if (section == null) return result;
        for (String route : section.getRoutesAsStrings(false)) {
            try {
                UUID playerId = UUID.fromString(route);
                if (toggled.getBoolean("toggled." + route, false)) result.put(playerId, true);
            } catch (IllegalArgumentException ignored) {
                // Keep unrelated or malformed legacy keys intact, but do not trust them as player IDs.
            }
        }
        return result;
    }

    private void openBackends() throws Exception {
        if (config.redisEnabled() && config.backend() != StorageConfig.Backend.SQL) {
            throw new IllegalStateException("storage.redis.enabled requires storage.backend: sql");
        }
        if (config.backend() == StorageConfig.Backend.SQL) {
            sql = SqlStore.open(plugin, config);
            sql.migrateYaml(values);
            if (config.redisEnabled()) {
                redis = new RedisPreferenceSync(config, scheduler, this::refresh,
                        message -> plugin.getLogger().warning(message));
                redis.start();
            }
        }
    }

    @Override
    public boolean isReady(UUID playerId) {
        return !closed && ready.contains(playerId);
    }

    @Override
    public boolean isDisabled(UUID playerId) {
        if (!isReady(playerId)) return true;
        return values.getOrDefault(playerId, true);
    }

    @Override
    public CompletableFuture<Boolean> load(UUID playerId) {
        if (closed) return failed(new IllegalStateException("AxTrade storage is closed"));
        if (config.backend() == StorageConfig.Backend.YAML) {
            synchronized (lock(playerId)) {
                values.putIfAbsent(playerId, false);
                ready.add(playerId);
            }
            return CompletableFuture.completedFuture(true);
        }
        synchronized (lock(playerId)) {
            ready.remove(playerId);
        }
        long generation = nextGeneration(playerId);
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        submit(() -> {
            try {
                Boolean disabled = sql.readPreference(playerId);
                synchronized (lock(playerId)) {
                    if (!closed) {
                        if (generation >= applied.getOrDefault(playerId, 0L)) {
                            applied.put(playerId, generation);
                            values.put(playerId, disabled);
                        }
                        ready.add(playerId);
                    }
                }
                result.complete(true);
            } catch (Exception ex) {
                result.completeExceptionally(ex);
            }
        }, result);
        return result;
    }

    @Override
    public CompletableFuture<Boolean> toggle(UUID playerId) {
        if (!isReady(playerId)) return failed(new IllegalStateException("Player preference is not ready"));
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        submit(() -> {
            synchronized (lock(playerId)) {
                if (closed || !isReady(playerId)) {
                    result.completeExceptionally(new IllegalStateException("AxTrade storage is closed"));
                    return;
                }
                long generation = nextGeneration(playerId);
                try {
                    boolean disabled;
                    if (config.backend() == StorageConfig.Backend.SQL) {
                        disabled = sql.togglePreference(playerId);
                        applied.put(playerId, generation);
                        values.put(playerId, disabled);
                        RedisPreferenceSync sync = redis;
                        if (sync != null) sync.publish(playerId);
                    } else {
                        disabled = !values.getOrDefault(playerId, false);
                        saveYaml(playerId, disabled);
                        values.put(playerId, disabled);
                    }
                    result.complete(disabled);
                } catch (Exception ex) {
                    result.completeExceptionally(ex.getCause() == null ? ex : ex.getCause());
                }
            }
        }, result);
        return result;
    }

    private Object lock(UUID playerId) {
        return locks.computeIfAbsent(playerId, ignored -> new Object());
    }

    private long nextGeneration(UUID playerId) {
        return generations.compute(playerId, (ignored, current) -> (current == null ? 0L : current) + 1L);
    }

    @Override
    public void unload(UUID playerId) {
        if (closed) return;
        synchronized (lock(playerId)) {
            ready.remove(playerId);
            generations.remove(playerId);
            applied.remove(playerId);
            if (config.backend() == StorageConfig.Backend.SQL) {
                values.remove(playerId);
            }
            locks.remove(playerId);
        }
    }

    private void saveYaml(UUID playerId, boolean disabled) throws java.io.IOException {
        synchronized (toggled) {
            String path = "toggled." + playerId;
            boolean previous = toggled.getBoolean(path, false);
            if (disabled) toggled.set(path, true);
            else toggled.getBackingDocument().remove(path);
            try {
                if (!toggled.getBackingDocument().save()) {
                    throw new java.io.IOException("Player preference document has no backing file");
                }
            } catch (java.io.IOException failure) {
                if (previous) toggled.set(path, true);
                else toggled.getBackingDocument().remove(path);
                throw failure;
            }
        }
    }

    private void submit(Runnable runnable, CompletableFuture<?> result) {
        try {
            executor.execute(runnable);
        } catch (RejectedExecutionException rejected) {
            result.completeExceptionally(new IllegalStateException("AxTrade storage queue is full", rejected));
        }
    }

    private void refresh(UUID playerId) {
        if (closed || sql == null) return;
        CompletableFuture<Void> ignored = new CompletableFuture<>();
        ignored.whenComplete((unused, error) -> {
            if (error != null) plugin.getLogger().warning("Could not reconcile preference state from SQL: " + error.getMessage());
        });
        submit(() -> {
            try {
                if (playerId == null) {
                    java.util.List<UUID> ids = new java.util.ArrayList<>(ready);
                    for (int start = 0; start < ids.size(); start += 512) {
                        Set<UUID> loaded = new java.util.HashSet<>(ids.subList(start, Math.min(start + 512, ids.size())));
                        Map<UUID, Long> snapshot = new java.util.HashMap<>();
                        for (UUID id : loaded) snapshot.put(id, nextGeneration(id));
                        for (Map.Entry<UUID, Boolean> entry : sql.readPreferences(loaded).entrySet()) {
                            applyRefresh(entry.getKey(), entry.getValue(), snapshot.getOrDefault(entry.getKey(), -1L));
                        }
                    }
                } else {
                    long generation = nextGeneration(playerId);
                    applyRefresh(playerId, sql.readPreference(playerId), generation);
                }
            } catch (Exception ex) {
                plugin.getLogger().warning("Could not reconcile preference state from SQL: " + ex.getMessage());
            }
        }, ignored);
    }
    private void applyRefresh(UUID playerId, boolean disabled, long generation) {
        synchronized (lock(playerId)) {
            if ((ready.contains(playerId) || generations.containsKey(playerId))
                    && generation >= applied.getOrDefault(playerId, 0L)) {
                applied.put(playerId, generation);
                values.put(playerId, disabled);
            }
        }
    }

    public void recordHistory(String entry) {
        if (sql == null || closed) return;
        CompletableFuture<Void> ignored = new CompletableFuture<>();
        ignored.whenComplete((unused, error) -> {
            if (error != null) plugin.getLogger().warning("Could not queue trade history entry: " + error.getMessage());
        });
        submit(() -> {
            try {
                sql.writeHistory(entry);
            } catch (Exception ex) {
                plugin.getLogger().warning("Could not persist trade history entry: " + ex.getMessage());
            }
        }, ignored);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        RedisPreferenceSync sync = redis;
        if (sync != null) sync.close();
        scheduler.shutdownNow();
        executor.shutdown();
        if (!Thread.currentThread().getName().startsWith("AxTrade-storage")) {
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
            }
        }
        SqlStore store = sql;
        if (store != null) store.close();
        ready.clear();
        generations.clear();
        applied.clear();
        locks.clear();
    }
    private static <T> CompletableFuture<T> failed(Throwable throwable) {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(throwable);
        return future;
    }
}
