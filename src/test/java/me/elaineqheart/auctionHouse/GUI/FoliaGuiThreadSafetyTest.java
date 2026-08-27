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

    @Test
    void shutdownGuiCleanupNeverSchedulesWorkForPlayers() throws IOException {
        String manager = Files.readString(Path.of(
                "src/main/java/me/elaineqheart/auctionHouse/GUI/GUIManager.java"));
        String shutdownCleanup = methodBody(manager, "public void forceCloseAll()");

        assertFalse(shutdownCleanup.contains("runForPlayer("),
                "Plugin shutdown happens after Bukkit disables the plugin, so it cannot register a Folia task");
    }

    @Test
    void shutdownAnvilCleanupNeverSchedulesWorkForPlayers() throws IOException {
        String manager = Files.readString(Path.of(
                "src/main/java/me/elaineqheart/auctionHouse/GUI/other/AnvilGUIManager.java"));
        String shutdownCleanup = methodBody(manager, "public void forceCloseAll()");

        assertFalse(shutdownCleanup.contains("closeGUI("),
                "Anvil cleanup must not indirectly schedule a player task while the plugin is disabled");
    }

    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        int end = source.indexOf("\n    }", start) + "\n    }".length();
        return source.substring(start, end);
    }
}
