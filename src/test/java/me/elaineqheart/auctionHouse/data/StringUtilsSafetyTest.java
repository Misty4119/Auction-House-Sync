package me.elaineqheart.auctionHouse.data;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;

class StringUtilsSafetyTest {

    @Test
    void itemNameResolutionDoesNotMutateAWorld() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/me/elaineqheart/auctionHouse/data/StringUtils.java"));

        assertFalse(source.contains("spawnEntity("),
                "Resolving an ItemStack name must not spawn an entity; this is unsafe on Folia region threads");
        assertFalse(source.contains("Bukkit.getWorlds("),
                "Resolving an ItemStack name must not access an unrelated world");
    }
}
