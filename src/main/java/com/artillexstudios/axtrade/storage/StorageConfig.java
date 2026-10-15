package com.artillexstudios.axtrade.storage;

import com.artillexstudios.axapi.config.Config;

record StorageConfig(
        Backend backend,
        String dialect,
        String jdbcUrl,
        String host,
        int port,
        String database,
        String username,
        String password,
        int poolSize,
        long connectionTimeoutMs,
        long validationTimeoutMs,
        boolean redisEnabled,
        String redisHost,
        int redisPort,
        String redisUsername,
        String redisPassword,
        int redisDatabase,
        boolean redisTls,
        String redisNamespace,
        String redisServerId,
        int redisConnectTimeoutMs,
        int redisSocketTimeoutMs
) {
    enum Backend { YAML, SQL }

    static StorageConfig read(Config config) {
        String configuredBackend = config.getString("storage.backend", "yaml");
        Backend backend;
        if ("sql".equalsIgnoreCase(configuredBackend)) backend = Backend.SQL;
        else if ("yaml".equalsIgnoreCase(configuredBackend)) backend = Backend.YAML;
        else throw new IllegalArgumentException("storage.backend must be yaml or sql");
        String dialect = config.getString("storage.sql.dialect", "mysql").toLowerCase();
        return new StorageConfig(
                backend,
                dialect,
                config.getString("storage.sql.url", ""),
                config.getString("storage.sql.host", "localhost"),
                config.getInt("storage.sql.port", (dialect.equals("postgresql") || dialect.equals("postgres")) ? 5432 : 3306),
                config.getString("storage.sql.database", "axtrade"),
                config.getString("storage.sql.username", "axtrade"),
                config.getString("storage.sql.password", ""),
                Math.max(1, config.getInt("storage.sql.pool-size", 4)),
                Math.max(250L, config.getLong("storage.sql.connection-timeout-ms", 3000L)),
                Math.max(250L, config.getLong("storage.sql.validation-timeout-ms", 1000L)),
                config.getBoolean("storage.redis.enabled", false),
                config.getString("storage.redis.host", "localhost"),
                config.getInt("storage.redis.port", 6379),
                config.getString("storage.redis.username", ""),
                config.getString("storage.redis.password", ""),
                config.getInt("storage.redis.database", 0),
                config.getBoolean("storage.redis.tls", false),
                config.getString("storage.redis.namespace", "axtrade"),
                config.getString("storage.redis.server-id", "server"),
                Math.max(250, config.getInt("storage.redis.connect-timeout-ms", 3000)),
                Math.max(250, config.getInt("storage.redis.socket-timeout-ms", 3000))
        );
    }
}
