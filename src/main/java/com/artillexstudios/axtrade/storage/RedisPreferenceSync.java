package com.artillexstudios.axtrade.storage;

import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

final class RedisPreferenceSync implements AutoCloseable {
    private final StorageConfig config;
    private final String channel;
    private final Consumer<UUID> invalidation;
    private final Consumer<String> logger;
    private final ExecutorService executor;
    private final ScheduledExecutorService scheduler;
    private volatile boolean closed;
    private volatile JedisPubSub subscription;
    private volatile Jedis subscriberConnection;
    private volatile Thread subscriberThread;
    private boolean outageLogged;

    RedisPreferenceSync(StorageConfig config, ExecutorService executor, ScheduledExecutorService scheduler,
                        Consumer<UUID> invalidation, Consumer<String> logger) {
        this.config = config;
        this.channel = config.redisNamespace() + ":preferences";
        this.invalidation = invalidation;
        this.logger = logger;
        this.executor = executor;
        this.scheduler = scheduler;
        ping();
    }

    private DefaultJedisClientConfig clientConfig() {
        DefaultJedisClientConfig.Builder builder = DefaultJedisClientConfig.builder()
                .connectionTimeoutMillis(config.redisConnectTimeoutMs())
                .socketTimeoutMillis(config.redisSocketTimeoutMs())
                .database(config.redisDatabase())
                .ssl(config.redisTls());
        if (config.redisUsername() != null && !config.redisUsername().isBlank()) {
            builder.user(config.redisUsername());
        }
        if (config.redisPassword() != null && !config.redisPassword().isBlank()) {
            builder.password(config.redisPassword());
        }
        return builder.build();
    }

    private Jedis open() {
        return new Jedis(new HostAndPort(config.redisHost(), config.redisPort()), clientConfig());
    }

    private void ping() {
        try (Jedis jedis = open()) {
            if (!"PONG".equalsIgnoreCase(jedis.ping())) {
                throw new IllegalStateException("Redis ping did not return PONG");
            }
        }
    }

    void start() {
        subscriberThread = new Thread(this::subscribeLoop, "AxTrade-redis-subscribe");
        subscriberThread.setDaemon(true);
        subscriberThread.start();
        // Reconciliation is deliberately periodic: a missed pub/sub message never leaves a loaded preference stale.
        scheduler.scheduleAtFixedRate(() -> {
            if (!closed) invalidation.accept(null);
        }, 30, 30, TimeUnit.SECONDS);
    }

    void publish(UUID playerId) {
        try {
            executor.execute(() -> {
                if (closed) return;
                try (Jedis jedis = open()) {
                    jedis.publish(channel, config.redisNamespace() + "|" + config.redisServerId() + "|" + playerId);
                } catch (RuntimeException ex) {
                    logOutage(ex);
                }
            });
        } catch (RuntimeException rejected) {
            logOutage(rejected);
        }
    }

    private synchronized void logOutage(RuntimeException ex) {
        if (!outageLogged) {
            outageLogged = true;
            logger.accept("Redis preference synchronization is temporarily unavailable: " + ex.getMessage());
        }
    }

    private void subscribeLoop() {
        while (!closed) {
            JedisPubSub listener = new JedisPubSub() {
                @Override
                public void onMessage(String subscribedChannel, String message) {
                    String[] parts = message.split("\\|", 3);
                    if (parts.length != 3 || !config.redisNamespace().equals(parts[0])) return;
                    try {
                        invalidation.accept(UUID.fromString(parts[2]));
                    } catch (IllegalArgumentException ignored) {
                        // Ignore malformed messages; SQL polling remains authoritative.
                    }
                }
            };
            subscription = listener;
            try (Jedis jedis = open()) {
                subscriberConnection = jedis;
                jedis.subscribe(listener, channel);
            } catch (RuntimeException ex) {
                if (!closed) logOutage(ex);
                if (!closed) {
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            } finally {
                subscriberConnection = null;
                subscription = null;
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        Jedis connection = subscriberConnection;
        if (connection != null) {
            try {
                connection.close();
            } catch (RuntimeException ignored) {
                // Closing a broken socket is best effort.
            }
        }
        Thread thread = subscriberThread;
        if (thread != null) thread.interrupt();
    }
}
