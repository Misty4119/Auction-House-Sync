package me.elaineqheart.auctionHouse.GUI;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FoliaGuiThreadSafetyTest {

    @Test
    void guiManagerOpensInventoriesOnThePlayerEntityScheduler() throws IOException {
        String manager = Files.readString(Path.of(
                "src/main/java/me/elaineqheart/auctionHouse/GUI/GUIManager.java"));

        assertTrue(manager.contains("entitySpecificScheduler(player)"));
    }

    @Test
    void purchaseFlowNeverSchedulesPlayerGuiOnGlobalRegion() throws IOException {
        String purchase = Files.readString(Path.of(
                "src/main/java/me/elaineqheart/auctionHouse/GUI/impl/ConfirmBuyGUI.java"));

        assertFalse(purchase.contains("globalRegionalScheduler().run(() -> AuctionHouse.getGuiManager().openGUI"));
    }
}
