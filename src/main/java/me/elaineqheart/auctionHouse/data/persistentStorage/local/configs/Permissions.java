package me.elaineqheart.auctionHouse.data.persistentStorage.local.configs;

import me.elaineqheart.auctionHouse.data.persistentStorage.database.MySQLMetaStore;
import me.elaineqheart.auctionHouse.data.persistentStorage.database.RedisMetaCache;
import me.elaineqheart.auctionHouse.data.persistentStorage.database.RedisSyncManager;
import me.elaineqheart.auctionHouse.data.persistentStorage.local.SettingManager;
import me.elaineqheart.auctionHouse.data.persistentStorage.local.data.Config;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.Map;

/**
 * Per-permission overrides for {@code auction-slots}, {@code bin-auction-duration}
 * and {@code bid-auction-duration}.
 *
 * <p>Reads are answered from the in-memory cache maintained by
 * {@link RedisMetaCache}; writes go through MySQL and are broadcast so
 * every node in the cluster picks them up. Falls back to the per-server
 * YAML when {@code database.persistence} is {@code JSON}.</p>
 */
public class Permissions extends Config {

    public int getAuctionSlots(Player player) {
        int slots = SettingManager.defaultMaxAuctions;
        Map<String, Long> section = SettingManager.useMetaPersistence()
                ? RedisMetaCache.getPermissions("auction-slots")
                : legacy("auction-slots");
        for (Map.Entry<String, Long> entry : section.entrySet()) {
            if (player.hasPermission(entry.getKey())) {
                int newSlots = entry.getValue().intValue();
                if (newSlots > slots) slots = newSlots;
            }
        }
        return slots;
    }

    public long getAuctionDuration(Player player, boolean BID) {
        long duration = BID ? SettingManager.BIDAuctionDuration : SettingManager.BINAuctionDuration;
        String key = BID ? "bid-auction-duration" : "bin-auction-duration";
        Map<String, Long> section = SettingManager.useMetaPersistence()
                ? RedisMetaCache.getPermissions(key)
                : legacy(key);
        for (Map.Entry<String, Long> entry : section.entrySet()) {
            if (player.hasPermission(entry.getKey())) {
                long newDuration = entry.getValue();
                if (newDuration > duration) duration = newDuration;
            }
        }
        return duration;
    }

    /** Admin command surface. */
    public void setPermission(String permType, String node, long value) {
        if (SettingManager.useMetaPersistence()) {
            MySQLMetaStore.upsertPermission(permType, node, value);
            RedisMetaCache.applyPermissionUpsert(permType, node, value);
            if (SettingManager.useMetaRedisCache()) {
                RedisSyncManager.publishPermissionUpsert(permType, node, value);
            }
            return;
        }
        ConfigurationSection sec = getCustomFile().getConfigurationSection(permType);
        if (sec == null) sec = getCustomFile().createSection(permType);
        sec.set(node, value);
        save();
    }

    public void unsetPermission(String permType, String node) {
        if (SettingManager.useMetaPersistence()) {
            MySQLMetaStore.deletePermission(permType, node);
            RedisMetaCache.applyPermissionDelete(permType, node);
            if (SettingManager.useMetaRedisCache()) {
                RedisSyncManager.publishPermissionDelete(permType, node);
            }
            return;
        }
        ConfigurationSection sec = getCustomFile().getConfigurationSection(permType);
        if (sec != null) sec.set(node, null);
        save();
    }

    private Map<String, Long> legacy(String permType) {
        Map<String, Long> out = new java.util.HashMap<>();
        ConfigurationSection section = getCustomFile().getConfigurationSection(permType);
        if (section == null) return out;
        for (String key : section.getKeys(true)) {
            if (!section.isLong(key)) continue;
            out.put(key, section.getLong(key));
        }
        return out;
    }
}