package me.elaineqheart.auctionHouse.data.persistentStorage.local;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ClusterConfigurationTest {

    @Test
    void sharedMysqlRedisModeFailsClosedWhenRealtimeRequirementsAreInvalid() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/me/elaineqheart/auctionHouse/data/persistentStorage/local/SettingManager.java"));
        String plugin = Files.readString(Path.of(
                "src/main/java/me/elaineqheart/auctionHouse/AuctionHouse.java"));

        assertTrue(source.contains("realtimeClusterConfigurationError()"));
        assertTrue(source.contains("server-id must be a unique, non-placeholder value"));
        assertTrue(source.contains("sync-secret must be a shared non-placeholder secret"));
        assertTrue(plugin.contains("Refusing to start shared MySQL/Redis mode"));
        assertTrue(plugin.contains("Redis is unavailable; refusing to start"));
        assertTrue(plugin.contains("Redis subscriber did not start; refusing to run"));
    }
}
