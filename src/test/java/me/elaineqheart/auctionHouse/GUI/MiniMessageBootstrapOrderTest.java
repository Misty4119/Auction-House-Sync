package me.elaineqheart.auctionHouse.GUI;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MiniMessageBootstrapOrderTest {

    @Test
    void enablesMiniMessageBeforeCompatibilityReloadCanCreateGuiItems() throws IOException {
        String settings = Files.readString(Path.of(
                "src/main/java/me/elaineqheart/auctionHouse/data/persistentStorage/local/SettingManager.java"));

        int miniMessageEnabled = settings.indexOf(
                "useAdventureAPIMessages = c.getBoolean(\"use-adventure-text-minimessages\", true);");
        int compatibilityMigration = settings.indexOf("if (ConfigManager.backwardsCompatibility())");
        int layoutReload = settings.indexOf("ConfigManager.layout.reload();");

        assertTrue(miniMessageEnabled >= 0, "MiniMessage must be initialised from config.yml");
        assertTrue(compatibilityMigration >= 0, "The compatibility migration must remain covered");
        assertTrue(layoutReload >= 0, "The migration reloads the layout and creates GUI item caches");
        assertTrue(miniMessageEnabled < compatibilityMigration,
                "MiniMessage must be enabled before a compatibility reload can create GUI items");
    }
}
