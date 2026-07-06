package me.elaineqheart.auctionHouse.data.persistentStorage.local.configs;

import me.elaineqheart.auctionHouse.data.StringUtils;
import me.elaineqheart.auctionHouse.data.persistentStorage.database.MySQLMetaStore;
import me.elaineqheart.auctionHouse.data.persistentStorage.database.RedisMetaCache;
import me.elaineqheart.auctionHouse.data.persistentStorage.database.RedisSyncManager;
import me.elaineqheart.auctionHouse.data.persistentStorage.local.SettingManager;
import me.elaineqheart.auctionHouse.data.persistentStorage.local.configs.M;
import me.elaineqheart.auctionHouse.data.persistentStorage.local.data.Config;
import org.bukkit.entity.Player;

import java.util.Date;
import java.util.Map;
import java.util.UUID;

/**
 * Cross-server banned-player list.
 *
 * <p>Authoritative storage lives in MySQL ({@code ah_banned_players}); Redis
 * keeps an in-memory map so {@link #checkIsBannedSendMessage(Player)} runs
 * without touching the database. Updates are broadcast through
 * {@link RedisSyncManager} so every server can react instantly.</p>
 *
 * <p>If {@code database.persistence} is left at its legacy {@code JSON}
 * default, the class transparently falls back to the per-server YAML file.</p>
 */
public class BannedPlayers extends Config {

    public void saveBannedPlayer(Player p, int durationInDays, String reason){
        long banEndDate = new Date().getTime() + ((long) durationInDays) * 24L * 60L * 60L * 1000L;
        if (SettingManager.useMetaPersistence()) {
            MySQLMetaStore.upsertBan(p.getUniqueId(), p.getName(), banEndDate, reason);
            RedisMetaCache.applyBanUpsert(p.getUniqueId(), p.getName(), banEndDate, reason);
            if (SettingManager.useMetaRedisCache()) {
                RedisSyncManager.publishBanUpsert(p.getUniqueId(), p.getName(), banEndDate, reason);
            }
            return;
        }
        // YAML fallback.
        String path = "BannedPlayers." + p.getUniqueId();
        getCustomFile().set(path + ".Date", new Date(banEndDate));
        getCustomFile().set(path + ".PlayerName", p.getName());
        getCustomFile().set(path + ".Reason", reason);
        save();
    }

    /**
     * Pardons a banned player by name. Returns the UUID of the pardoned
     * player on success, or {@code null} if no matching entry was found.
     *
     * <p>The MySQL path goes through {@link RedisMetaCache#getAllBans()},
     * so the list reflects every node in the cluster (not just whatever
     * YAML happened to be on the operator's machine). The YAML path is the
     * legacy fallback for {@code database.persistence=JSON} installs.</p>
     */
    public UUID pardonByName(String input) {
        if (input == null || input.isBlank()) return null;
        if (SettingManager.useMetaPersistence()) {
            for (MySQLMetaStore.BanEntry entry : RedisMetaCache.getAllBans().values()) {
                if (entry.name != null && entry.name.equals(input)) {
                    MySQLMetaStore.deleteBan(entry.uuid);
                    RedisMetaCache.applyBanDelete(entry.uuid);
                    if (SettingManager.useMetaRedisCache()) {
                        RedisSyncManager.publishBanDelete(entry.uuid);
                    }
                    return entry.uuid;
                }
            }
            return null;
        }
        org.bukkit.configuration.ConfigurationSection section = getCustomFile().getConfigurationSection("BannedPlayers");
        if (section == null) return null;
        for (String key : section.getKeys(false)) {
            String path = "BannedPlayers." + key + ".PlayerName";
            String playerName = getCustomFile().getString(path);
            if (playerName == null) continue;
            if (playerName.equals(input)) {
                getCustomFile().set("BannedPlayers." + key, null);
                save();
                try { return UUID.fromString(key); } catch (Exception ex) { return null; }
            }
        }
        return null;
    }

    //if the player is banned, send them a message
    public boolean checkIsBannedSendMessage(Player p){
        if (SettingManager.useMetaPersistence()) {
            MySQLMetaStore.BanEntry entry = RedisMetaCache.getBan(p.getUniqueId());
            if (entry == null) return false;
            long currentTime = new Date().getTime();
            if (currentTime > entry.banEndMs) {
                // Expired: lazily prune the row so we don't keep sending messages.
                MySQLMetaStore.deleteBan(entry.uuid);
                RedisMetaCache.applyBanDelete(entry.uuid);
                if (SettingManager.useMetaRedisCache()) {
                    RedisSyncManager.publishBanDelete(entry.uuid);
                }
                return false;
            }
            long banDuration = entry.banEndMs - currentTime;
            M.send(p, "command-feedback.banned.message",
                    "%time%", StringUtils.getTime(banDuration / 1000, true));
            M.send(p, "command-feedback.banned.reason", "%reason%", entry.reason);
            return true;
        }
        org.bukkit.configuration.file.FileConfiguration customFile = getCustomFile();
        String path = "BannedPlayers." + p.getUniqueId();
        if (customFile.get(path) == null) return false;
        Date banEndDate = (Date) customFile.get(path + ".Date");
        if (banEndDate == null) return false;
        long currentTime = new Date().getTime();
        if (currentTime > banEndDate.getTime()){
            customFile.set(path, null);
            save();
            return false;
        }
        long banDuration = banEndDate.getTime() - currentTime;
        M.send(p, "command-feedback.banned.message",
                "%time%", StringUtils.getTime(banDuration / 1000, true));
        M.send(p, "command-feedback.banned.reason",
                "%reason%", customFile.getString(path + ".Reason"));
        return true;
    }

    /** Used by Tab completion to list banned player names. */
    public java.util.Set<String> getBannedPlayerNames() {
        if (SettingManager.useMetaPersistence()) {
            java.util.Set<String> out = new java.util.HashSet<>();
            for (MySQLMetaStore.BanEntry entry : RedisMetaCache.getAllBans().values()) {
                if (entry.name != null) out.add(entry.name);
            }
            return out;
        }
        java.util.Set<String> out = new java.util.HashSet<>();
        org.bukkit.configuration.ConfigurationSection section = getCustomFile().getConfigurationSection("BannedPlayers");
        if (section == null) return out;
        for (String key : section.getKeys(false)) {
            String name = getCustomFile().getString("BannedPlayers." + key + ".PlayerName");
            if (name != null) out.add(name);
        }
        return out;
    }
}