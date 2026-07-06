package me.elaineqheart.auctionHouse.data.persistentStorage.database;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import me.elaineqheart.auctionHouse.AuctionHouse;
import me.elaineqheart.auctionHouse.data.persistentStorage.local.SettingManager;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;

/**
 * In-memory + Redis cache for every "meta" dataset that used to be stored in
 * per-server YAML files: blacklist rules, banned players, custom categories,
 * per-player preferences, world displays, permission overrides and the
 * transaction log.
 *
 * <p>The plugin keeps an authoritative copy of each dataset in memory for
 * sub-millisecond reads, and writes <strong>always</strong> flow through
 * MySQL (the durable source of truth) and then through this cache. A pub/sub
 * event is broadcast after every write so peer servers can converge.</p>
 *
 * <p>This is intentionally a separate class from {@link RedisNoteStorage}:
 * notes have a high write rate and a different key shape (sorted-set
 * indexes), while meta data is much smaller and uses simpler hash/list keys.</p>
 */
public final class RedisMetaCache {

    private static final Gson GSON = new Gson();
    private static final Type LIST_OF_MAPS_TYPE = new TypeToken<List<Map<String, Object>>>() {}.getType();

    // ----- in-memory mirrors ---------------------------------------------------

    private static final Map<Long, MySQLMetaStore.BlacklistRule> BLACKLIST = new ConcurrentHashMap<>();
    private static final Map<UUID, MySQLMetaStore.BanEntry>    BANS      = new ConcurrentHashMap<>();
    /** Stable list of categories shown in the auction GUI. */
    private static final List<String> CATEGORIES_LIST = new ArrayList<>();
    /** Material membership per category. */
    private static final Map<String, Set<String>> CATEGORY_MATERIALS = new ConcurrentHashMap<>();
    private static final Map<UUID, MySQLMetaStore.PlayerPrefs> PREFS     = new ConcurrentHashMap<>();
    private static final Map<Integer, MySQLMetaStore.DisplayRow> DISPLAYS = new ConcurrentHashMap<>();
    private static final Map<String, Map<String, Long>> PERMISSIONS = new ConcurrentHashMap<>(); // permType -> (node -> value)

    private RedisMetaCache() {}

    // ------------------------------------------------------------
    // Hydration
    // ------------------------------------------------------------

    /** Pull every meta dataset from MySQL into memory and Redis. */
    public static void hydrateFromMysql() {
        if (!MySQLManager.isAvailable()) return;

        BLACKLIST.clear();
        for (MySQLMetaStore.BlacklistRule r : MySQLMetaStore.loadBlacklist()) BLACKLIST.put(r.id, r);

        BANS.clear();
        BANS.putAll(MySQLMetaStore.loadAllBans());

        CATEGORIES_LIST.clear();
        CATEGORIES_LIST.addAll(MySQLMetaStore.loadCategoryList());

        CATEGORY_MATERIALS.clear();
        for (String category : CATEGORIES_LIST) {
            Set<String> set = ConcurrentHashMap.newKeySet();
            set.addAll(MySQLMetaStore.loadCategoryMaterials(category));
            CATEGORY_MATERIALS.put(category, set);
        }

        PREFS.clear();
        PREFS.putAll(MySQLMetaStore.loadAllPlayerPrefs());

        DISPLAYS.clear();
        DISPLAYS.putAll(MySQLMetaStore.loadAllDisplays());

        PERMISSIONS.clear();
        PERMISSIONS.put("auction-slots",        MySQLMetaStore.loadPermissions("auction-slots"));
        PERMISSIONS.put("bin-auction-duration", MySQLMetaStore.loadPermissions("bin-auction-duration"));
        PERMISSIONS.put("bid-auction-duration", MySQLMetaStore.loadPermissions("bid-auction-duration"));

        if (SettingManager.useRedisCache() && RedisManager.isAvailable()) {
            // Push a full snapshot to Redis so peers can pull it later if needed.
            try (Jedis j = RedisManager.getResource()) {
                Pipeline p = j.pipelined();
                p.del(SettingManager.key("meta:bootstrapped"));
                p.set(SettingManager.key("meta:bootstrapped"), "1");
                p.sync();
            } catch (Exception ignored) {}
        }
        AuctionHouse.getInstance().getLogger().info(
                "[AuctionHouse] Meta caches hydrated: blacklist=" + BLACKLIST.size() +
                        ", bans=" + BANS.size() +
                        ", categories=" + CATEGORIES_LIST.size() +
                        ", prefs=" + PREFS.size() +
                        ", displays=" + DISPLAYS.size());
    }

    // ------------------------------------------------------------
    // Blacklist
    // ------------------------------------------------------------

    public static List<MySQLMetaStore.BlacklistRule> getBlacklist() {
        return List.copyOf(BLACKLIST.values());
    }

    public static void applyBlacklistAdd(long id, String type, String key) {
        BLACKLIST.put(id, new MySQLMetaStore.BlacklistRule(id, type, key));
        mirrorBlacklist();
    }

    public static boolean applyBlacklistPop() {
        long highest = BLACKLIST.keySet().stream().mapToLong(Long::longValue).max().orElse(-1L);
        if (highest < 0) return false;
        BLACKLIST.remove(highest);
        mirrorBlacklist();
        return true;
    }

    public static void applyBlacklistReplace(List<MySQLMetaStore.BlacklistRule> list) {
        BLACKLIST.clear();
        for (MySQLMetaStore.BlacklistRule r : list) BLACKLIST.put(r.id, r);
        mirrorBlacklist();
    }

    private static void mirrorBlacklist() {
        if (!SettingManager.useRedisCache() || !RedisManager.isAvailable()) return;
        try (Jedis j = RedisManager.getResource()) {
            String key = SettingManager.key("meta:blacklist");
            j.del(key);
            for (MySQLMetaStore.BlacklistRule r : BLACKLIST.values()) {
                j.hset(key, String.valueOf(r.id), r.type + "§" + r.key);
            }
        } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------
    // Bans
    // ------------------------------------------------------------

    public static MySQLMetaStore.BanEntry getBan(UUID uuid) {
        return BANS.get(uuid);
    }

    public static Map<UUID, MySQLMetaStore.BanEntry> getAllBans() {
        return Map.copyOf(BANS);
    }

    public static void applyBanUpsert(UUID uuid, String name, long banEndMs, String reason) {
        BANS.put(uuid, new MySQLMetaStore.BanEntry(uuid, name, banEndMs, reason));
        mirrorBans();
    }

    public static void applyBanDelete(UUID uuid) {
        BANS.remove(uuid);
        mirrorBans();
    }

    private static void mirrorBans() {
        if (!SettingManager.useRedisCache() || !RedisManager.isAvailable()) return;
        try (Jedis j = RedisManager.getResource()) {
            String key = SettingManager.key("meta:bans");
            j.del(key);
            for (MySQLMetaStore.BanEntry e : BANS.values()) {
                Map<String, String> entry = new HashMap<>();
                entry.put("name", e.name == null ? "" : e.name);
                entry.put("end", String.valueOf(e.banEndMs));
                entry.put("reason", e.reason == null ? "" : e.reason);
                j.hset(key, e.uuid.toString(), GSON.toJson(entry));
            }
        } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------
    // Categories
    // ------------------------------------------------------------

    /** Snapshot of the category list shown in the auction GUI. */
    public static List<String> getCategories() {
        return List.copyOf(CATEGORIES_LIST);
    }

    /** Replace the entire category list (called by {@code replaceCategories}). */
    public static void applyCategories(List<String> categories) {
        CATEGORIES_LIST.clear();
        CATEGORIES_LIST.addAll(categories);
        // Drop material sets for categories that no longer exist.
        CATEGORY_MATERIALS.keySet().retainAll(CATEGORIES_LIST);
        mirrorCategories();
    }

    public static List<String> getCategoryMaterials(String category) {
        Set<String> set = CATEGORY_MATERIALS.get(category);
        return set == null ? List.of() : List.copyOf(set);
    }

    public static void applyCategoryMaterialUpsert(String category, String material) {
        CATEGORY_MATERIALS.computeIfAbsent(category, k -> ConcurrentHashMap.newKeySet()).add(material);
        mirrorCategories();
    }

    public static void applyCategoryMaterialDelete(String category, String material) {
        Set<String> set = CATEGORY_MATERIALS.get(category);
        if (set != null) set.remove(material);
        mirrorCategories();
    }

    private static void mirrorCategories() {
        if (!SettingManager.useRedisCache() || !RedisManager.isAvailable()) return;
        try (Jedis j = RedisManager.getResource()) {
            String key = SettingManager.key("meta:categories");
            j.del(key);
            Map<String, String> blob = new HashMap<>();
            blob.put("list", GSON.toJson(CATEGORIES_LIST));
            Map<String, String> mats = new HashMap<>();
            for (Map.Entry<String, Set<String>> e : CATEGORY_MATERIALS.entrySet()) {
                mats.put(e.getKey(), GSON.toJson(e.getValue()));
            }
            blob.put("materials", GSON.toJson(mats));
            j.set(key, GSON.toJson(blob));
        } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------
    // Player preferences
    // ------------------------------------------------------------

    public static MySQLMetaStore.PlayerPrefs getPrefs(UUID uuid) {
        return PREFS.get(uuid);
    }

    public static boolean getAnnouncement(UUID uuid) {
        MySQLMetaStore.PlayerPrefs p = PREFS.get(uuid);
        if (p == null) return true; // default
        return p.announcements;
    }

    public static String getConfiguration(UUID uuid) {
        MySQLMetaStore.PlayerPrefs p = PREFS.get(uuid);
        return p == null ? null : p.configuration;
    }

    public static void applyPrefsAnnouncement(UUID uuid, boolean enabled) {
        MySQLMetaStore.PlayerPrefs prev = PREFS.get(uuid);
        PREFS.put(uuid, new MySQLMetaStore.PlayerPrefs(uuid, enabled,
                prev == null ? null : prev.configuration));
        mirrorPrefs(uuid);
    }

    public static void applyPrefsConfiguration(UUID uuid, String configurationJson) {
        MySQLMetaStore.PlayerPrefs prev = PREFS.get(uuid);
        boolean announcements = prev == null || prev.announcements;
        PREFS.put(uuid, new MySQLMetaStore.PlayerPrefs(uuid, announcements, configurationJson));
        mirrorPrefs(uuid);
    }

    private static void mirrorPrefs(UUID uuid) {
        if (!SettingManager.useRedisCache() || !RedisManager.isAvailable()) return;
        try (Jedis j = RedisManager.getResource()) {
            String key = SettingManager.key("meta:prefs");
            MySQLMetaStore.PlayerPrefs p = PREFS.get(uuid);
            if (p == null) {
                j.hdel(key, uuid.toString());
                return;
            }
            Map<String, String> entry = new HashMap<>();
            entry.put("announcements", String.valueOf(p.announcements));
            entry.put("configuration", p.configuration == null ? "" : p.configuration);
            j.hset(key, uuid.toString(), GSON.toJson(entry));
        } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------
    // Displays
    // ------------------------------------------------------------

    public static Map<Integer, MySQLMetaStore.DisplayRow> getAllDisplays() {
        return Map.copyOf(DISPLAYS);
    }

    public static MySQLMetaStore.DisplayRow getDisplay(int id) {
        return DISPLAYS.get(id);
    }

    public static void applyDisplayUpsert(int id, MySQLMetaStore.DisplayRow row) {
        DISPLAYS.put(id, row);
        mirrorDisplays();
    }

    public static void applyDisplayDelete(int id) {
        DISPLAYS.remove(id);
        mirrorDisplays();
    }

    private static void mirrorDisplays() {
        if (!SettingManager.useRedisCache() || !RedisManager.isAvailable()) return;
        try (Jedis j = RedisManager.getResource()) {
            String key = SettingManager.key("meta:displays");
            j.del(key);
            for (Map.Entry<Integer, MySQLMetaStore.DisplayRow> e : DISPLAYS.entrySet()) {
                MySQLMetaStore.DisplayRow r = e.getValue();
                Map<String, String> entry = new HashMap<>();
                entry.put("world", r.world);
                entry.put("x", String.valueOf(r.x));
                entry.put("y", String.valueOf(r.y));
                entry.put("z", String.valueOf(r.z));
                entry.put("yaw", String.valueOf(r.yaw));
                entry.put("pitch", String.valueOf(r.pitch));
                entry.put("rank", String.valueOf(r.rank));
                entry.put("sortType", r.sortType);
                entry.put("glassUuid", r.glassUuid == null ? "" : r.glassUuid);
                entry.put("interactionUuid", r.interactionUuid == null ? "" : r.interactionUuid);
                entry.put("itemUuid", r.itemUuid == null ? "" : r.itemUuid);
                entry.put("textUuid", r.textUuid == null ? "" : r.textUuid);
                entry.put("serverId", r.serverId == null ? "" : r.serverId);
                j.hset(key, String.valueOf(e.getKey()), GSON.toJson(entry));
            }
        } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------
    // Permissions
    // ------------------------------------------------------------

    public static Map<String, Long> getPermissions(String permType) {
        return PERMISSIONS.getOrDefault(permType, new HashMap<>());
    }

    public static void applyPermissionUpsert(String permType, String node, long value) {
        PERMISSIONS.computeIfAbsent(permType, k -> new HashMap<>()).put(node, value);
        mirrorPermissions();
    }

    public static void applyPermissionDelete(String permType, String node) {
        Map<String, Long> map = PERMISSIONS.get(permType);
        if (map != null) map.remove(node);
        mirrorPermissions();
    }

    private static void mirrorPermissions() {
        if (!SettingManager.useRedisCache() || !RedisManager.isAvailable()) return;
        try (Jedis j = RedisManager.getResource()) {
            String key = SettingManager.key("meta:permissions");
            j.del(key);
            for (Map.Entry<String, Map<String, Long>> e : PERMISSIONS.entrySet()) {
                Map<String, String> encoded = new HashMap<>();
                for (Map.Entry<String, Long> inner : e.getValue().entrySet()) {
                    encoded.put(inner.getKey(), String.valueOf(inner.getValue()));
                }
                j.hset(key, e.getKey(), GSON.toJson(encoded));
            }
        } catch (Exception ignored) {}
    }
}