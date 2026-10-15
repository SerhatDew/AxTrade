package com.artillexstudios.axtrade.storage;

import com.artillexstudios.axapi.AxPlugin;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

final class SqlStore implements AutoCloseable {
    private final AxPlugin plugin;
    private final HikariDataSource dataSource;
    private final boolean postgres;

    private SqlStore(AxPlugin plugin, HikariDataSource dataSource, boolean postgres) {
        this.plugin = plugin;
        this.dataSource = dataSource;
        this.postgres = postgres;
    }

    static SqlStore open(AxPlugin plugin, StorageConfig config) throws Exception {
        boolean postgres = "postgres".equals(config.dialect()) || "postgresql".equals(config.dialect());
        boolean mariadb = "mariadb".equals(config.dialect());
        boolean mysql = "mysql".equals(config.dialect());
        if (!postgres && !mysql && !mariadb) {
            throw new IllegalArgumentException("storage.sql.dialect must be mysql, mariadb, or postgresql");
        }
        String driverClass = postgres ? "org.postgresql.Driver" : (mariadb ? "org.mariadb.jdbc.Driver" : "com.mysql.cj.jdbc.Driver");
        Class.forName(driverClass);
        String url = config.jdbcUrl();
        if (url == null || url.isBlank()) {
            String scheme = mariadb ? "mariadb" : (postgres ? "postgresql" : "mysql");
            url = "jdbc:" + scheme + "://" + config.host() + ":" + config.port() + "/" + config.database();
        }
        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(url);
        hikari.setDriverClassName(driverClass);
        hikari.setUsername(config.username());
        hikari.setPassword(config.password());
        hikari.setMaximumPoolSize(config.poolSize());
        hikari.setMinimumIdle(1);
        hikari.setConnectionTimeout(config.connectionTimeoutMs());
        hikari.setValidationTimeout(config.validationTimeoutMs());
        hikari.setPoolName("AxTrade-SQL");
        hikari.setInitializationFailTimeout(config.connectionTimeoutMs());
        HikariDataSource source = new HikariDataSource(hikari);
        SqlStore store = new SqlStore(plugin, source, postgres);
        try {
            store.initializeSchema();
        } catch (Exception ex) {
            source.close();
            throw ex;
        }
        return store;
    }

    private void initializeSchema() throws SQLException {
        String preferenceTable = "CREATE TABLE IF NOT EXISTS axtrade_preferences ("
                + "player_uuid VARCHAR(36) PRIMARY KEY, disabled BOOLEAN NOT NULL, updated_at BIGINT NOT NULL)";
        String historyTable = postgres
                ? "CREATE TABLE IF NOT EXISTS axtrade_history (id BIGSERIAL PRIMARY KEY, entry TEXT NOT NULL, created_at BIGINT NOT NULL)"
                : "CREATE TABLE IF NOT EXISTS axtrade_history (id BIGINT AUTO_INCREMENT PRIMARY KEY, entry TEXT NOT NULL, created_at BIGINT NOT NULL) DEFAULT CHARACTER SET utf8mb4";
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate(preferenceTable);
            statement.executeUpdate(historyTable);
            plugin.getLogger().info("SQL storage schema is ready");
        }
    }

    Boolean readPreference(UUID playerId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT disabled FROM axtrade_preferences WHERE player_uuid = ?")) {
            statement.setString(1, playerId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getBoolean(1) : Boolean.FALSE;
            }
        }
    }

    boolean togglePreference(UUID playerId) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                String toggle = postgres
                        ? "INSERT INTO axtrade_preferences (player_uuid, disabled, updated_at) VALUES (?, TRUE, ?) "
                        + "ON CONFLICT (player_uuid) DO UPDATE SET disabled = NOT axtrade_preferences.disabled, updated_at = EXCLUDED.updated_at"
                        : "INSERT INTO axtrade_preferences (player_uuid, disabled, updated_at) VALUES (?, TRUE, ?) "
                        + "ON DUPLICATE KEY UPDATE disabled = NOT disabled, updated_at = VALUES(updated_at)";
                try (PreparedStatement statement = connection.prepareStatement(toggle)) {
                    statement.setString(1, playerId.toString());
                    statement.setLong(2, System.currentTimeMillis());
                    statement.executeUpdate();
                }
                boolean disabled;
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT disabled FROM axtrade_preferences WHERE player_uuid = ?")) {
                    statement.setString(1, playerId.toString());
                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next()) throw new SQLException("Preference row disappeared during toggle");
                        disabled = result.getBoolean(1);
                    }
                }
                connection.commit();
                return disabled;
            } catch (SQLException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    void writePreference(UUID playerId, boolean disabled) throws SQLException {
        String sql = postgres
                ? "INSERT INTO axtrade_preferences (player_uuid, disabled, updated_at) VALUES (?, ?, ?) "
                + "ON CONFLICT (player_uuid) DO UPDATE SET disabled = EXCLUDED.disabled, updated_at = EXCLUDED.updated_at"
                : "INSERT INTO axtrade_preferences (player_uuid, disabled, updated_at) VALUES (?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE disabled = VALUES(disabled), updated_at = VALUES(updated_at)";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerId.toString());
            statement.setBoolean(2, disabled);
            statement.setLong(3, System.currentTimeMillis());
            statement.executeUpdate();
        }
    }


    java.util.Map<UUID, Boolean> readPreferences(java.util.Set<UUID> playerIds) throws SQLException {
        java.util.Map<UUID, Boolean> result = new java.util.HashMap<>();
        if (playerIds.isEmpty()) return result;
        String placeholders = String.join(", ", java.util.Collections.nCopies(playerIds.size(), "?"));
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT player_uuid, disabled FROM axtrade_preferences WHERE player_uuid IN (" + placeholders + ")")) {
            int index = 1;
            for (UUID playerId : playerIds) statement.setString(index++, playerId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.put(UUID.fromString(rows.getString(1)), rows.getBoolean(2));
                }
            }
        }
        return result;
    }
    void migrateYaml(Map<UUID, Boolean> preferences) throws SQLException {
        if (preferences.isEmpty()) return;
        String sql = postgres
                ? "INSERT INTO axtrade_preferences (player_uuid, disabled, updated_at) VALUES (?, ?, ?) ON CONFLICT (player_uuid) DO NOTHING"
                : "INSERT IGNORE INTO axtrade_preferences (player_uuid, disabled, updated_at) VALUES (?, ?, ?)";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            long now = System.currentTimeMillis();
            for (Map.Entry<UUID, Boolean> entry : preferences.entrySet()) {
                statement.setString(1, entry.getKey().toString());
                statement.setBoolean(2, entry.getValue());
                statement.setLong(3, now);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    void writeHistory(String entry) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO axtrade_history (entry, created_at) VALUES (?, ?)")) {
            statement.setString(1, entry);
            statement.setLong(2, System.currentTimeMillis());
            statement.executeUpdate();
        }
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
