package me.elaineqheart.auctionHouse.GUI;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiniMessageLoreSafetyTest {

    @Test
    void multiPriceBidLoreIsReplacedBeforeMiniMessageParsing() throws IOException {
        String itemManager = Files.readString(Path.of(
                "src/main/java/me/elaineqheart/auctionHouse/data/ram/ItemManager.java"));

        assertFalse(itemManager.contains("M.applyPriceReplacements"));
        assertTrue(itemManager.contains("M.getLoreComponents(\"items.top-bid.lore\", amount, newBid)"));
        assertTrue(itemManager.contains(
                "M.getLoreComponents(\"items.submit-another-bid.lore\", amount, previousBid, amount-previousBid)"));
    }
}
