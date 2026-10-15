package com.artillexstudios.axtrade.storage;

import com.artillexstudios.axapi.config.Config;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;

class StorageConfigTest {
    @Test
    void unknownBackendDoesNotSilentlyFallBackToYaml() {
        Config config = mock(Config.class);
        when(config.getString("storage.backend", "yaml")).thenReturn("redis");

        assertThrows(IllegalArgumentException.class, () -> StorageConfig.read(config));
    }
    @Test
    void postgresDialectDefaultsPortTo5432() {
        Config config = mock(Config.class);
        when(config.getString("storage.backend", "yaml")).thenReturn("sql");
        when(config.getString("storage.sql.dialect", "mysql")).thenReturn("postgres");
        when(config.getInt(eq("storage.sql.port"), anyInt())).thenAnswer(inv -> inv.getArgument(1));

        StorageConfig storageConfig = StorageConfig.read(config);
        org.junit.jupiter.api.Assertions.assertEquals(5432, storageConfig.port());
    }

}
