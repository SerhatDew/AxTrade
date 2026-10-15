package com.artillexstudios.axtrade.storage;

import com.artillexstudios.axapi.AxPlugin;
import com.artillexstudios.axapi.config.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StorageManagerTest {
    @TempDir
    Path directory;

    @Test
    void uppercaseLegacyPreferenceKeyIsUpdatedInPlace() throws Exception {
        UUID player = UUID.fromString("abcdefab-cdef-abcd-efab-cdefabcdefab");
        Path toggled = directory.resolve("toggled.yml");
        String legacyKey = player.toString().toUpperCase(java.util.Locale.ROOT);
        Files.writeString(toggled, "toggled:\n  " + legacyKey + ": true\n");

        StorageManager manager = open(toggled);
        try {
            manager.load(player).get(5, TimeUnit.SECONDS);
            assertTrue(manager.isDisabled(player));
            assertFalse(manager.toggle(player).get(5, TimeUnit.SECONDS));
        } finally {
            manager.close();
        }

        StorageManager restarted = open(toggled);
        try {
            restarted.load(player).get(5, TimeUnit.SECONDS);
            assertFalse(restarted.isDisabled(player));
        } finally {
            restarted.close();
        }
    }

    @Test
    void legacyDisabledPreferenceSurvivesToggleAndRestart() throws Exception {
        UUID player = UUID.randomUUID();
        Path toggled = directory.resolve("toggled.yml");
        Files.writeString(toggled, "toggled:\n  " + player + ": true\n");
        StorageManager first = open(toggled);
        try {
            assertFalse(first.isReady(player));
            assertTrue(first.isDisabled(player));
            first.load(player).get(5, TimeUnit.SECONDS);
            assertTrue(first.isDisabled(player));
            assertFalse(first.toggle(player).get(5, TimeUnit.SECONDS));
        } finally {
            first.close();
        }
        StorageManager restarted = open(toggled);
        try {
            restarted.load(player).get(5, TimeUnit.SECONDS);
            assertFalse(restarted.isDisabled(player));
        } finally {
            restarted.close();
        }
    }

    @Test
    void concurrentTogglesPreserveParityOnDisk() throws Exception {
        UUID player = UUID.randomUUID();
        Path toggled = directory.resolve("toggled.yml");
        Files.writeString(toggled, "toggled: {}\n");
        StorageManager manager = open(toggled);
        try {
            manager.load(player).get(5, TimeUnit.SECONDS);
            var updates = new ArrayList<CompletableFuture<Boolean>>();
            for (int i = 0; i < 21; i++) updates.add(manager.toggle(player));
            CompletableFuture.allOf(updates.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);
            assertTrue(manager.isDisabled(player));
            try (var input = Files.newInputStream(toggled)) {
                var document = com.artillexstudios.axapi.libs.boostedyaml.YamlDocument.create(input);
                assertTrue(document.getBoolean("toggled." + player, false));
            }
        } finally {
            manager.close();
        }
    }

    @Test
    void failedSaveDoesNotAcknowledgeOrChangeTheCachedPreference() throws Exception {
        UUID player = UUID.randomUUID();
        Path toggled = directory.resolve("toggled.yml");
        Files.writeString(toggled, "toggled: {}\n");
        StorageManager manager = open(toggled);
        try {
            manager.load(player).get(5, TimeUnit.SECONDS);
            Files.delete(toggled);
            Files.createDirectory(toggled);
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> manager.toggle(player).get(5, TimeUnit.SECONDS));
            assertFalse(manager.isDisabled(player));
        } finally {
            manager.close();
        }
    }

    @Test
    void staleLoadCannotRestoreReadinessAfterUnloadAndRejoin() throws Exception {
        SqlStore sql = mock(SqlStore.class);
        StorageManager manager = openSqlManager(sql);
        UUID player = UUID.randomUUID();
        java.util.concurrent.CountDownLatch staleQueryStarted = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch releaseStaleQuery = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch rejoinQueryStarted = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
        when(sql.readPreference(player)).thenAnswer(inv -> {
            if (reads.incrementAndGet() == 1) {
                staleQueryStarted.countDown();
                if (!releaseStaleQuery.await(5, TimeUnit.SECONDS)) throw new AssertionError("Latch timeout");
                return true;
            }
            rejoinQueryStarted.countDown();
            return false;
        });

        try {
            CompletableFuture<Boolean> staleLoad = manager.load(player);
            if (!staleQueryStarted.await(5, TimeUnit.SECONDS)) throw new AssertionError("Query not started");

            manager.unload(player);
            CompletableFuture<Boolean> rejoinLoad = manager.load(player);
            if (!rejoinQueryStarted.await(5, TimeUnit.SECONDS)) throw new AssertionError("Rejoin query not started");
            rejoinLoad.get(5, TimeUnit.SECONDS);
            assertFalse(manager.isDisabled(player));

            releaseStaleQuery.countDown();
            staleLoad.get(5, TimeUnit.SECONDS);

            assertTrue(manager.isReady(player));
            assertFalse(manager.isDisabled(player), "A load from the previous session must not overwrite rejoin state");
        } finally {
            releaseStaleQuery.countDown();
            manager.close();
        }
    }

    @Test
    void refreshTreatsMissingSqlRowsAsEnabled() throws Exception {
        SqlStore sql = mock(SqlStore.class);
        StorageManager manager = openSqlManager(sql);
        UUID player = UUID.randomUUID();
        when(sql.readPreference(player)).thenReturn(true);
        when(sql.readPreferences(org.mockito.ArgumentMatchers.anySet())).thenReturn(java.util.Map.of());

        try {
            manager.load(player).get(5, TimeUnit.SECONDS);
            assertTrue(manager.isDisabled(player));

            var refreshMethod = StorageManager.class.getDeclaredMethod("refresh", UUID.class);
            refreshMethod.setAccessible(true);
            ((CompletableFuture<?>) refreshMethod.invoke(manager, (UUID) null)).get(5, TimeUnit.SECONDS);

            assertFalse(manager.isDisabled(player),
                    "A missing SQL preference row has the same enabled default as a direct read");
        } finally {
            manager.close();
        }
    }

    @Test
    void staleLoadDoesNotOverwriteNewerRemoteRefresh() throws Exception {
        SqlStore sql = mock(SqlStore.class);
        StorageManager manager = openSqlManager(sql);
        UUID player = UUID.randomUUID();
        java.util.concurrent.CountDownLatch staleQueryStarted = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch releaseStaleQuery = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
        when(sql.readPreference(player)).thenAnswer(inv -> {
            int read = reads.incrementAndGet();
            if (read == 2) {
                staleQueryStarted.countDown();
                if (!releaseStaleQuery.await(5, TimeUnit.SECONDS)) throw new AssertionError("Latch timeout");
                return false;
            }
            return read >= 3;
        });

        try {
            manager.load(player).get(5, TimeUnit.SECONDS);
            CompletableFuture<Boolean> oldLoad = manager.load(player);
            if (!staleQueryStarted.await(5, TimeUnit.SECONDS)) throw new AssertionError("Query not started");

            var refreshMethod = StorageManager.class.getDeclaredMethod("refresh", UUID.class);
            refreshMethod.setAccessible(true);
            refreshMethod.invoke(manager, player);

            var executorField = StorageManager.class.getDeclaredField("executor");
            executorField.setAccessible(true);
            var executor = (java.util.concurrent.ExecutorService) executorField.get(manager);
            executor.submit(() -> {}).get(5, TimeUnit.SECONDS);

            assertTrue(manager.isDisabled(player));

            releaseStaleQuery.countDown();
            oldLoad.get(5, TimeUnit.SECONDS);

            assertTrue(manager.isDisabled(player), "Stale SQL read must not overwrite newer invalidation");
        } finally {
            releaseStaleQuery.countDown();
            manager.close();
        }
    }

    @Test
    void failedRejoinLoadResetsReadinessAndFailsClosed() throws Exception {
        SqlStore sql = mock(SqlStore.class);
        StorageManager manager = openSqlManager(sql);
        UUID player = UUID.randomUUID();
        when(sql.readPreference(player)).thenReturn(false);

        try {
            manager.load(player).get(5, TimeUnit.SECONDS);
            assertTrue(manager.isReady(player));
            assertFalse(manager.isDisabled(player));

            when(sql.readPreference(player)).thenThrow(new java.sql.SQLException("DB down"));
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> manager.load(player).get(5, TimeUnit.SECONDS));

            assertFalse(manager.isReady(player));
            assertTrue(manager.isDisabled(player));
        } finally {
            manager.close();
        }
    }

    @Test
    void unloadClearsReadinessAndCachedPreferences() throws Exception {
        SqlStore sql = mock(SqlStore.class);
        StorageManager manager = openSqlManager(sql);
        UUID player = UUID.randomUUID();
        when(sql.readPreference(player)).thenReturn(false);

        try {
            manager.load(player).get(5, TimeUnit.SECONDS);
            assertTrue(manager.isReady(player));
            assertFalse(manager.isDisabled(player));

            manager.unload(player);

            assertFalse(manager.isReady(player));
            assertTrue(manager.isDisabled(player));
        } finally {
            manager.close();
        }
    }

    private StorageManager openSqlManager(SqlStore sql) throws Exception {
        StorageConfig config = new StorageConfig(StorageConfig.Backend.SQL, "mysql", "", "localhost", 3306,
                "axtrade", "user", "", 4, 3000, 1000, false, "localhost", 6379, "", "", 0, false,
                "axtrade", "test", 3000, 3000);
        var constructor = StorageManager.class.getDeclaredConstructor(
                AxPlugin.class, Config.class, StorageConfig.class, java.util.Map.class);
        constructor.setAccessible(true);
        StorageManager manager = constructor.newInstance(null, null, config, java.util.Map.of());
        var sqlField = StorageManager.class.getDeclaredField("sql");
        sqlField.setAccessible(true);
        sqlField.set(manager, sql);
        return manager;
    }

    private StorageManager open(Path toggled) throws Exception {
        Path config = directory.resolve("config.yml");
        Files.writeString(config, "storage:\n  backend: yaml\n");
        return StorageManager.openAsync(mock(AxPlugin.class), readConfig(config),
                readConfig(toggled)).get(5, TimeUnit.SECONDS);
    }

    private Config readConfig(Path file) throws Exception {
        byte[] contents = Files.readAllBytes(file);
        Files.delete(file);
        Config config = new Config(file.toFile());
        // Own the input stream: the bundled YAML file loader leaves its stream open on Windows.
        try (var input = new java.io.ByteArrayInputStream(contents)) {
            config.getBackingDocument().reload(input);
        }
        config.getBackingDocument().save();
        return config;
    }
}
