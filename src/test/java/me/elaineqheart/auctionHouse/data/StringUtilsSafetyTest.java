package me.elaineqheart.auctionHouse.data;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;

class StringUtilsSafetyTest {

    @org.junit.jupiter.api.Test
    void rejectsNonFinitePrices() {
        org.junit.jupiter.api.Assertions.assertEquals(-1, StringUtils.parsePositiveNumber("NaN"));
        org.junit.jupiter.api.Assertions.assertEquals(-1, StringUtils.parsePositiveNumber("Infinity"));
        org.junit.jupiter.api.Assertions.assertEquals(-1, StringUtils.parsePositiveNumber("1e309"));
    }

    @Test
    void parsesBillionPriceSuffix() {
        org.junit.jupiter.api.Assertions.assertEquals(10_000_000_000D,
                StringUtils.parsePositiveNumber("10b"));
        org.junit.jupiter.api.Assertions.assertEquals(1_500_000_000D,
                StringUtils.parsePositiveNumber("1.5B"));
    }

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
