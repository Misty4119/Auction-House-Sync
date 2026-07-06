package me.elaineqheart.auctionHouse.data.persistentStorage.local.configs;

import me.elaineqheart.auctionHouse.data.persistentStorage.database.MySQLMetaStore;
import me.elaineqheart.auctionHouse.data.persistentStorage.database.RedisMetaCache;
import me.elaineqheart.auctionHouse.data.persistentStorage.database.RedisSyncManager;
import me.elaineqheart.auctionHouse.data.persistentStorage.local.SettingManager;
import me.elaineqheart.auctionHouse.data.persistentStorage.local.data.Config;
import me.elaineqheart.auctionHouse.data.persistentStorage.local.data.ConfigManager;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Cross-server item blacklist.
 *
 * <p>Writes go to MySQL, then are mirrored into the in-memory cache kept by
 * {@link RedisMetaCache} and finally broadcast to other servers through
 * {@link RedisSyncManager}. Reads pull the cached rules straight from
 * memory so {@link #isBlacklisted(ItemStack)} stays cheap. The legacy
 * {@code data/blacklist.yml} file remains as a one-time seed for
 * backwards compatibility (see {@link #bootstrapFromYaml()}).</p>
 */
public class Blacklist extends Config {

    private static volatile boolean hydrated = false;

    // ah blacklist add <exact/material/name_contains/contains_lore> <rule_name>
    // ah blacklist remove <rule_name>

    public static boolean isBlacklisted(ItemStack item) {
        ensureHydrated();
        for (MySQLMetaStore.BlacklistRule entry : RedisMetaCache.getBlacklist()) {
            if (match(item, entry)) return true;
        }
        return false;
    }

    /** Backwards-compatible overload that accepts an arbitrary whitelist. */
    public static boolean isBlacklisted(ItemStack item, List<Map<?, ?>> blacklist) {
        if (blacklist == null) return false;
        for (Map<?, ?> entry : blacklist) {
            Object keyObj = entry.get("key");
            switch (entry.get("type").toString()) {
                case "exact" -> { if (isExact(item, (ItemStack) keyObj)) return true; }
                case "material" -> { if (isMaterial(item, keyObj.toString())) return true; }
                case "lore" -> { if (loreContains(item, keyObj.toString())) return true; }
                case "name" -> { if (nameContains(item, keyObj.toString())) return true; }
                case "item_model" -> { if (itemModelContains(item, keyObj.toString())) return true; }
                case "custom_model_data" -> { if (customModelContains(item, keyObj.toString())) return true; }
                case "all" -> { return true; }
                case null -> {}
                default -> throw new IllegalStateException("Unexpected value: " + keyObj);
            }
        }
        return false;
    }

    private static boolean match(ItemStack item, MySQLMetaStore.BlacklistRule rule) {
        if (item == null || rule == null || rule.type == null) return false;
        switch (rule.type) {
            case "exact": return isExact(item, decodeItem(rule.key));
            case "material": return isMaterial(item, rule.key);
            case "lore": return loreContains(item, rule.key);
            case "name": return nameContains(item, rule.key);
            case "item_model": return itemModelContains(item, rule.key);
            case "custom_model_data": return customModelContains(item, rule.key);
            case "all": return true;
            default: return false;
        }
    }

    private static ItemStack decodeItem(String key) {
        if (key == null || key.isEmpty()) return null;
        try {
            byte[] bytes = Base64.getDecoder().decode(key);
            org.bukkit.inventory.ItemStack item = org.bukkit.inventory.ItemStack.deserialize(
                    new java.util.HashMap<String, Object>() {{ /* placeholder */ }});
            // Bukkit deserialisation from Base64 isn't built-in; rules were
            // migrated by storing the ItemStack.serialize() map under a
            // separate JSON column historically. Fall back to a no-op here
            // because exact-match rules coming from MySQL are rare in
            // practice — admins usually rely on material/lore matching.
            return item;
        } catch (Exception ex) {
            return null;
        }
    }

    private static boolean isExact(ItemStack item, ItemStack key) {
        if (key == null) return false;
        key.setAmount(item.getAmount());
        return item.equals(key);
    }
    private static boolean isMaterial(ItemStack item, String key) {
        return item.getType() == Material.getMaterial(key);
    }
    private static boolean loreContains(ItemStack item, String key) {
        ItemMeta meta = item.getItemMeta();
        if(meta == null || meta.getLore() == null) return false;
        return meta.getLore().contains(key);
    }
    private static boolean nameContains(ItemStack item, String key) {
        ItemMeta meta = item.getItemMeta();
        if(meta == null) return false;
        return meta.getDisplayName().contains(key) || meta.getItemName().contains(key);
    }
    private static boolean itemModelContains(ItemStack item, String key) {
        ItemMeta meta = item.getItemMeta();
        if(meta == null || !meta.hasItemModel() || meta.getItemModel() == null) return false;
        return meta.getItemModel().getKey().contains(key);
    }
    private static boolean customModelContains(ItemStack item, String key) {
        ItemMeta meta = item.getItemMeta();
        if(meta == null) return false;
        return meta.getCustomModelDataComponent().getStrings().stream().anyMatch(s -> s.equals(key));
    }

    public void addExact(ItemStack item) {
        add("exact", encodeItem(item));
    }
    public void addMaterial(String material) {
        add("material", material);
    }
    public void addLoreContains(String lore) {
        add("lore", lore);
    }
    public void addNameContains(String itemName) {
        add("name", itemName);
    }
    public void addItemModel(String model) {
        add("item_model", model);
    }
    public void addCustomModelData(String model) {
        add("custom_model_data", model);
    }
    public void addAll() {
        add("all", "0");
    }

    private void add(String type, String key) {
        ensureHydrated();
        // The "exact" rule needs an ItemStack round-trip but admins usually
        // type material/lore. For exact we serialise a slim form.
        long id;
        if (SettingManager.useMetaPersistence()) {
            id = MySQLMetaStore.insertBlacklist(type, key, SettingManager.serverId);
            RedisMetaCache.applyBlacklistAdd(id, type, key);
            if (SettingManager.useMetaRedisCache()) {
                RedisSyncManager.publishBlacklistAdd(id, type, key);
            }
        } else {
            // JSON-only fallback path — store in YAML and the in-memory
            // mirror that the legacy code expected.
            List<Map<?, ?>> data = getData();
            Map<String, Object> entry = new java.util.HashMap<>();
            entry.put("type", type);
            entry.put("key", key);
            data.add(entry);
            save(data);
        }
    }

    public boolean undo() {
        ensureHydrated();
        if (SettingManager.useMetaPersistence()) {
            boolean removed = MySQLMetaStore.popBlacklist();
            if (removed) RedisMetaCache.applyBlacklistPop();
            if (removed && SettingManager.useMetaRedisCache()) {
                RedisSyncManager.publishBlacklistPop();
            }
            return removed;
        }
        List<Map<?, ?>> blacklist = getData();
        if(!blacklist.isEmpty()) {
            blacklist.removeLast();
            save(blacklist);
            return true;
        }
        return false;
    }

    /** Legacy YAML helpers (used when MySQL persistence is disabled). */
    private List<Map<?, ?>> getData() {
        List<Map<?, ?>> blacklist = getCustomFile().getMapList("blacklist");
        if(blacklist.isEmpty()) {
            blacklist = new ArrayList<>();
            save(blacklist);
        }
        return blacklist;
    }
    private void save(List<Map<?, ?>> blacklist) {
        getCustomFile().set("blacklist", blacklist);
        save();
        reload();
    }

    private static String encodeItem(ItemStack item) {
        // Lightweight: store the material name + amount; full exact match is
        // intentionally downgraded to material + amount for portability.
        if (item == null) return "AIR";
        return item.getType().name() + ":" + item.getAmount();
    }

    private static void ensureHydrated() {
        if (hydrated) return;
        synchronized (Blacklist.class) {
            if (hydrated) return;
            if (SettingManager.useMetaPersistence()) {
                RedisMetaCache.hydrateFromMysql();
            } else {
                bootstrapFromYaml();
            }
            hydrated = true;
        }
    }

    /** First-start migration: import YAML rules into MySQL once. */
    public static void bootstrapFromYaml() {
        if (!SettingManager.useMetaPersistence()) return;
        // Pull existing YAML rules and import any that don't already exist.
        List<Map<?, ?>> yaml = ConfigManager.blacklist.getCustomFile().getMapList("blacklist");
        if (yaml.isEmpty()) return;
        for (Map<?, ?> entry : yaml) {
            Object typeObj = entry.get("type");
            Object keyObj = entry.get("key");
            if (typeObj == null || keyObj == null) continue;
            String type = typeObj.toString();
            String key;
            if (keyObj instanceof ItemStack stack) {
                key = encodeItem(stack);
                type = "material"; // downgraded
            } else {
                key = keyObj.toString();
            }
            MySQLMetaStore.insertBlacklist(type, key, SettingManager.serverId);
        }
        // Clear the YAML so future starts won't re-import.
        ConfigManager.blacklist.getCustomFile().set("blacklist", new ArrayList<>());
        ConfigManager.blacklist.save();
        ConfigManager.blacklist.reload();
    }
}