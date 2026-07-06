package me.elaineqheart.auctionHouse.data.persistentStorage.database;

import me.elaineqheart.auctionHouse.AuctionHouse;
import me.elaineqheart.auctionHouse.data.persistentStorage.local.SettingManager;
import me.elaineqheart.auctionHouse.data.persistentStorage.local.configs.M;
import me.elaineqheart.auctionHouse.data.persistentStorage.local.data.ConfigManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Helper around {@link RedisSyncManager#publishChatBroadcast} and
 * {@link RedisSyncManager#publishChatPrivate} that hides the placeholder
 * encoding and ensures the local server still gets a delivery.
 *
 * <p>Cross-server semantics in this plugin are:
 * <ul>
 *   <li><b>Local-first</b>: every chat action must reach players on the same
 *       physical server as the actor. The Redis sub-loop explicitly drops
 *       packets whose {@code originServer} equals ours, so we always
 *       deliver the local copy here and only forward the cross-server copy
 *       via {@link RedisSyncManager}.</li>
 *   <li><b>Cluster-wide</b>: the same message is then published so every other
 *       node in the cluster receives a copy and pushes it to its own online
 *       players with the same per-player filter (announcements toggle,
 *       exclude actor, etc).</li>
 * </ul>
 *
 * <p>This class is intentionally stateless: callers can hold no reference to
 * it and we keep a single fan-out point so future tweaks (rate limits, mock
 * transports for tests) only need to change one place.</p>
 */
public final class CrossServerMessenger {

    private CrossServerMessenger() {}

    /**
     * Local-first broadcast of a {@code messages.yml} key to every online
     * player in the cluster (except the excluded player). Placeholders are
     * passed in pairs ({@code name, value, name, value, ...}).
     */
    public static void broadcastChat(String messageKey, UUID excludePlayer, String... placeholderPairs) {
        if (!SettingManager.auctionAnnouncementsEnabled) return;

        String formatted = M.getFormatted(messageKey, placeholderPairs);
        if (formatted == null) return;

        // 1) Local delivery.
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (excludePlayer != null && online.getUniqueId().equals(excludePlayer)) continue;
            if (!ConfigManager.playerPreferences.hasAnnouncementsEnabled(online.getUniqueId())) continue;
            online.sendMessage(formatted);
        }

        // 2) Cross-server delivery.
        try {
            List<String> names = new ArrayList<>();
            List<String> values = new ArrayList<>();
            for (int i = 0; i + 1 < placeholderPairs.length; i += 2) {
                names.add(placeholderPairs[i]);
                values.add(placeholderPairs[i + 1]);
            }
            RedisSyncManager.publishChatBroadcast(messageKey, names, values, excludePlayer);
        } catch (Throwable t) {
            AuctionHouse.getInstance().getLogger().fine(
                    "Cross-server broadcast failed (local players still got the message): " + t.getMessage());
        }
    }

    /**
     * Send a {@code messages.yml}-keyed message to one specific player no
     * matter which server they are on. Falls back to a local send if Redis
     * is unreachable.
     */
    public static void sendToPlayer(UUID target, String messageKey, String... placeholderPairs) {
        if (target == null) return;
        String formatted = M.getFormatted(messageKey, placeholderPairs);
        if (formatted == null) return;

        // 1) Local delivery (if the player is on this node).
        Player local = Bukkit.getPlayer(target);
        if (local != null && local.isOnline()) {
            local.sendMessage(formatted);
        }

        // 2) Cross-server delivery.
        try {
            List<String> names = new ArrayList<>();
            List<String> values = new ArrayList<>();
            for (int i = 0; i + 1 < placeholderPairs.length; i += 2) {
                names.add(placeholderPairs[i]);
                values.add(placeholderPairs[i + 1]);
            }
            RedisSyncManager.publishChatPrivate(target, messageKey, names, values);
        } catch (Throwable t) {
            AuctionHouse.getInstance().getLogger().fine(
                    "Cross-server private message failed: " + t.getMessage());
        }
    }
}
