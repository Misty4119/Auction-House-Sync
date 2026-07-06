package me.elaineqheart.auctionHouse.data.persistentStorage.local;

import me.elaineqheart.auctionHouse.AuctionHouse;
import me.elaineqheart.auctionHouse.data.persistentStorage.database.RedisManager;
import me.elaineqheart.auctionHouse.data.persistentStorage.local.configs.M;
import me.elaineqheart.auctionHouse.data.persistentStorage.local.data.ConfigManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;

import java.text.DecimalFormat;
import java.util.*;

public class SettingManager {

    public static double taxRate;
    public static long auctionSetupTime;
    public static DecimalFormat formatter;
    public static String formatTimeCharacters;
    public static int defaultMaxAuctions;
    public static boolean soldMessageEnabled;
    public static String permissionModerate;
    public static boolean partialSelling;

    // ---------- Multi-server database / cache ----------
    public enum StorageBackend { JSON, MYSQL }
    public enum CacheBackend   { JSON, REDIS }

    public static StorageBackend persistenceBackend   = StorageBackend.JSON;
    public static CacheBackend   cacheBackend         = CacheBackend.JSON;

    /**
     * Per-node identifier used by the cross-server sync layer to drop echo
     * events that originated on this very server. The default below is a
     * placeholder — every node in the cluster MUST override {@code server-id}
     * in {@code config.yml} with a unique value, otherwise events will be
     * filtered out and the cluster will diverge. {@link #loadDatabaseSettings}
     * warns loudly when the default is still in use.
     */
    public static String serverId = "ah-server-CHANGE-ME";

    // MySQL
    public static String mysqlHost = "127.0.0.1";
    public static int    mysqlPort = 3306;
    public static String mysqlDatabase = "auction_house";
    public static String mysqlUsername = "root";
    public static String mysqlPassword = "";
    public static int    mysqlPoolSize = 16;
    public static int    mysqlMinIdle = 4;
    public static long   mysqlMaxLifetimeMs = 1_800_000L;
    public static long   mysqlConnectionTimeoutMs = 5_000L;
    public static boolean mysqlUseSsl = false;
    public static String mysqlExtraParams = "useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true&useUnicode=true&characterEncoding=UTF-8";

    // Redis 8.0
    public static String  redisHost = "127.0.0.1";
    public static int     redisPort = 6379;
    public static String  redisUsername = "default";
    public static String  redisPassword = "";
    public static int     redisDatabase = 0;
    public static int     redisConnectionTimeoutMs = 3_000;
    public static int     redisSoTimeoutMs = 3_000;
    public static int     redisMaxTotal = 32;
    public static int     redisMaxIdle = 16;
    public static int     redisMinIdle = 4;
    public static boolean redisPubsubEnabled = true;
    public static String  redisKeyPrefix = "auction:";
    public static String  redisChannel = "auction:events";

    // ----------------------------------------------------

    public static int displayUpdateTicks;
    public static boolean autoCollect;
    public static boolean auctionAnnouncementsEnabled;
    public static boolean BINAuctions;
    public static long BINAuctionDuration; // in seconds, default is 48 hours
    public static boolean BIDAuctions;
    public static long BIDAuctionDuration;
    public static int lastBIDExtraTime;
    public static double bidIncreaseRatio;
    public static double minBINPrice;
    public static double minBIDPrice;
    public static double maxBINPrice;
    public static double maxBIDPrice;
    public static boolean useAdventureAPIMessages;
    public static String soundClick;
    public static String soundOpenEnderchest;
    public static String soundCloseEnderchest;
    public static String soundBreakWood;
    public static String soundExperience;
    public static String soundVillagerDeny;
    public static String soundOpenShulker;
    public static String soundCloseShulker;
    public static String soundNPCClick;
    public static String soundCloseBundle;
    public static String soundOpenBundle;

    // displays
    public static final Set<Material> displayMaterials = new HashSet<>();
    public static final Map<String, BlockData> displayGlassMap = new HashMap<>();
    public static final Map<String, BlockData> displayBaseMap = new HashMap<>();
    public static final Map<String, BlockData> displaySignMap = new HashMap<>();

    static {
        loadData();
    }

    public static void loadData() {
        AuctionHouse.getInstance().reloadConfig();
        FileConfiguration c = AuctionHouse.getInstance().getConfig();

        if (ConfigManager.backwardsCompatibility())
            backwardsCompatibility();

        taxRate = c.getDouble("tax", 0.01);
        auctionSetupTime = c.getLong("auction-setup-time", 30);
        defaultMaxAuctions = c.getInt("default-max-auctions", 10);
        soldMessageEnabled = c.getBoolean("sold-message", true);
        formatter = new DecimalFormat(M.getFormatted("placeholders.format-numbers"));
        formatTimeCharacters = c.getString("format-time-characters", "dhms");
        permissionModerate = c.getString("admin-permission", "auctionhouse.moderator");
        partialSelling = c.getBoolean("partial-selling", false);

        loadDatabaseSettings(c);

        displayUpdateTicks = c.getInt("display-update", 80);
        autoCollect = c.getBoolean("auto-collect", false);
        auctionAnnouncementsEnabled = c.getBoolean("auction-announcements", true);
        BINAuctions = c.getBoolean("bin-auctions", true);
        BINAuctionDuration = c.getLong("bin-auction-duration", 172800);
        BIDAuctions = c.getBoolean("bid-auctions", true);
        BIDAuctionDuration = c.getLong("bid-auction-duration", 7200);
        lastBIDExtraTime = c.getInt("last-bid-extra-time", 60);
        bidIncreaseRatio = c.getDouble("bid-increase-percent", 25) / 100;
        minBINPrice = c.getDouble("min-bin", 1);
        minBIDPrice = c.getDouble("min-bid", 1);
        maxBINPrice = c.getDouble("max-bin", -1);
        maxBIDPrice = c.getDouble("max-bid", -1);
        useAdventureAPIMessages = c.getBoolean("use-adventure-text-minimessages", true);
        FileConfiguration layout = ConfigManager.layout.getCustomFile();
        soundClick = layout.getString("sounds.click", "ui.stonecutter.select_recipe");
        soundOpenEnderchest = layout.getString("sounds.open-enderchest", "block.ender_chest.open");
        soundCloseEnderchest = layout.getString("sounds.close-enderchest", "block.ender_chest.close");
        soundBreakWood = layout.getString("sounds.break-wood", "block.wood.break");
        soundExperience = layout.getString("sounds.experience", "entity.experience_orb.pickup");
        soundVillagerDeny = layout.getString("sounds.villager-deny", "entity.villager.no");
        soundOpenShulker = layout.getString("sounds.open-shulker", "block.shulker_box.open");
        soundCloseShulker = layout.getString("sounds.close-shulker", "block.shulker_box.close");
        soundNPCClick = layout.getString("sounds.npc-click", "ui.stonecutter.select_recipe");
        soundOpenBundle = layout.getString("sounds.open-bundle", "item.bundle.drop_contents");
        soundCloseBundle = layout.getString("sounds.close-bundle", "item.bundle.remove_one");
        loadDisplays(layout);
    }

    private static void loadDatabaseSettings(FileConfiguration c) {
        ConfigurationSection db = c.getConfigurationSection("database");
        if (db == null) {
            // No `database` section in config.yml at all — we can't fall back
            // to a JSON file (the YAML config file would become a silent split
            // brain across the cluster), so refuse to boot.
            AuctionHouse.getInstance().getLogger().severe(
                    "[AuctionHouse] config.yml is missing the 'database' section. " +
                            "Add a `database:` block (see default config.yml) and restart. " +
                            "Plugin will not start.");
            throw new IllegalStateException("Missing 'database' section in config.yml");
        }
        persistenceBackend = parseBackend(db.getString("persistence"), StorageBackend.MYSQL);
        cacheBackend       = parseCache(db.getString("cache"),       CacheBackend.REDIS);
        serverId = c.getString("server-id", serverId);

        // Loud warning so admins catch the duplicate-id bug early — two nodes
        // that share the same `server-id` will drop each other's pub/sub events.
        if (serverId == null || serverId.isBlank() || "ah-server-CHANGE-ME".equals(serverId)) {
            AuctionHouse.getInstance().getLogger().warning(
                    "[AuctionHouse] server-id is unset or still the placeholder default ('" + serverId + "'). " +
                            "Set a unique server-id in config.yml for EVERY node in the cluster, " +
                            "otherwise events will be filtered out and the cluster will not stay in sync.");
        }

        ConfigurationSection mysql = c.getConfigurationSection("database.mysql");
        if (mysql != null) {
            mysqlHost = mysql.getString("host", mysqlHost);
            mysqlPort = mysql.getInt("port", mysqlPort);
            mysqlDatabase = mysql.getString("database", mysqlDatabase);
            mysqlUsername = mysql.getString("username", mysqlUsername);
            mysqlPassword = mysql.getString("password", mysqlPassword);
            ConfigurationSection pool = mysql.getConfigurationSection("pool");
            if (pool != null) {
                mysqlPoolSize = pool.getInt("maximum-pool-size", mysqlPoolSize);
                mysqlMinIdle = pool.getInt("minimum-idle", mysqlMinIdle);
                mysqlMaxLifetimeMs = pool.getLong("max-lifetime", mysqlMaxLifetimeMs);
                mysqlConnectionTimeoutMs = pool.getLong("connection-timeout", mysqlConnectionTimeoutMs);
            }
            mysqlUseSsl = mysql.getBoolean("use-ssl", mysqlUseSsl);
            mysqlExtraParams = mysql.getString("parameters", mysqlExtraParams);
        }

        ConfigurationSection redis = c.getConfigurationSection("database.redis");
        if (redis != null) {
            redisHost = redis.getString("host", redisHost);
            redisPort = redis.getInt("port", redisPort);
            redisUsername = redis.getString("username", redisUsername);
            redisPassword = redis.getString("password", redisPassword);
            redisDatabase = redis.getInt("database", redisDatabase);
            redisConnectionTimeoutMs = redis.getInt("connection-timeout", redisConnectionTimeoutMs);
            redisSoTimeoutMs = redis.getInt("so-timeout", redisSoTimeoutMs);
            ConfigurationSection pool = redis.getConfigurationSection("pool");
            if (pool != null) {
                redisMaxTotal = pool.getInt("max-total", redisMaxTotal);
                redisMaxIdle = pool.getInt("max-idle", redisMaxIdle);
                redisMinIdle = pool.getInt("min-idle", redisMinIdle);
            }
            redisPubsubEnabled = redis.getBoolean("pubsub-enabled", redisPubsubEnabled);
            redisKeyPrefix = redis.getString("key-prefix", redisKeyPrefix);
            redisChannel = redis.getString("channel", redisChannel);
        }
    }

    private static StorageBackend parseBackend(String value, StorageBackend fallback) {
        if (value == null) return fallback;
        try {
            return StorageBackend.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            AuctionHouse.getInstance().getLogger().warning(
                    "Unknown persistence backend '" + value + "', falling back to " + fallback);
            return fallback;
        }
    }

    private static CacheBackend parseCache(String value, CacheBackend fallback) {
        if (value == null) return fallback;
        try {
            return CacheBackend.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            AuctionHouse.getInstance().getLogger().warning(
                    "Unknown cache backend '" + value + "', falling back to " + fallback);
            return fallback;
        }
    }

    /** Stable, key-prefixed names. Keeps namespaces clean per environment. */
    public static String key(String suffix) {
        if (suffix == null || suffix.isEmpty()) return redisKeyPrefix;
        return redisKeyPrefix + suffix;
    }

    /** Stable channel name for pub/sub fan-out (still prefixed). */
    public static String channel() { return redisChannel; }

    public static boolean useRedisCache() {
        return cacheBackend == CacheBackend.REDIS;
    }

    public static boolean isMysqlPersistence() {
        return persistenceBackend == StorageBackend.MYSQL;
    }

    /**
     * Meta data (blacklist, bans, categories, preferences, displays,
     * permissions, log) ALWAYS flows through MySQL + Redis once the plugin
     * is configured for any database backend. JSON-only fallback is still
     * supported for the local single-server case but is no longer the
     * recommended path. We expose this helper so individual config classes
     * can route through the central MySQL+Redis pipeline.
     */
    public static boolean useMetaPersistence() {
        return isMysqlPersistence();
    }

    public static boolean useMetaRedisCache() {
        return useRedisCache() && RedisManager.isAvailable();
    }

    private static BlockData parseBlockData(String input, Material defaultMaterial) {
        if (input == null || input.isEmpty())
            return Bukkit.createBlockData(defaultMaterial);
        try {
            if (input.contains(":")) {
                String[] parts = input.split(":", 2);
                Material mat = Material.matchMaterial(parts[0]);
                if (mat != null && mat.isBlock()) {
                    BlockData data = Bukkit.createBlockData(mat);
                    int value = Integer.parseInt(parts[1]);
                    switch (data) {
                        case org.bukkit.block.data.type.RespawnAnchor anchor ->
                                anchor.setCharges(Math.min(value, anchor.getMaximumCharges()));
                        case org.bukkit.block.data.Levelled levelled ->
                                levelled.setLevel(Math.min(value, levelled.getMaximumLevel()));
                        case org.bukkit.block.data.Ageable ageable ->
                                ageable.setAge(Math.min(value, ageable.getMaximumAge()));
                        case org.bukkit.block.data.Lightable lightable -> lightable.setLit(value > 0);
                        default -> {}
                    }
                    return data;
                }
            }
            Material mat = Material.matchMaterial(input);
            if (mat != null && mat.isBlock()) {
                return Bukkit.createBlockData(mat);
            }
            return Bukkit.createBlockData(input);
        } catch (Exception e) {
            AuctionHouse.getInstance().getLogger().warning("Invalid block data: " + input + ". Using default.");
            return Bukkit.createBlockData(defaultMaterial);
        }
    }

    private static void loadDisplays(FileConfiguration c) {
        displayMaterials.clear();
        displayGlassMap.clear();
        displayBaseMap.clear();
        displaySignMap.clear();

        if (c.getConfigurationSection("displays") != null) {
            for (String sortType : Objects.requireNonNull( c.getConfigurationSection("displays")).getKeys(false) ) {
                for (String rankOrDef : Objects.requireNonNull( c.getConfigurationSection("displays." + sortType)).getKeys(false) ) {
                    String baseKey = "displays." + sortType + "." + rankOrDef + ".";

                    BlockData glass = parseBlockData(c.getString(baseKey + "glass"), Material.GLASS);
                    displayGlassMap.put(sortType + "-" + rankOrDef, glass);

                    BlockData base = parseBlockData(c.getString(baseKey + "base"), Material.CHISELED_TUFF_BRICKS);
                    displayBaseMap.put(sortType + "-" + rankOrDef, base);
                    displayMaterials.add(base.getMaterial());

                    BlockData sign = parseBlockData(c.getString(baseKey + "sign"), Material.DARK_OAK_WALL_SIGN);
                    displaySignMap.put(sortType + "-" + rankOrDef, sign);
                    displayMaterials.add(sign.getMaterial());
                }
            }
        }
    }

    public static BlockData getDisplayGlass(String type, int rank) {
        return displayGlassMap.getOrDefault(type + "-" + rank,
                displayGlassMap.getOrDefault(type + "-default", Bukkit.createBlockData(Material.GLASS)));
    }

    public static BlockData getDisplayBase(String type, int rank) {
        return displayBaseMap.getOrDefault(type + "-" + rank,
                displayBaseMap.getOrDefault(type + "-default", Bukkit.createBlockData(Material.CHISELED_TUFF_BRICKS)));
    }

    public static BlockData getDisplaySign(String type, int rank) {
        return displaySignMap.getOrDefault(type + "-" + rank,
                displaySignMap.getOrDefault(type + "-default", Bukkit.createBlockData(Material.DARK_OAK_WALL_SIGN)));
    }

    private static void backwardsCompatibility() {
        FileConfiguration c = AuctionHouse.getInstance().getConfig();
        c.set("plugin-version", AuctionHouse.getInstance().getDescription().getVersion());
        FileConfiguration messageFile = M.get();
        if (c.contains("currency")) {
            messageFile.set("placeholders.currency-symbol", c.getString("currency"));
            c.set("currency", null);
            c.set("currency-symbol", "has been moved to messages.yml");
        }
        if (c.contains("currency-before-number")) {
            if (c.getBoolean("currency-before-number")) {
                messageFile.set("placeholders.price", "%currency-symbol%%number%");
            }
            c.set("currency-before-number", null);
        }
        if (c.contains("format-numbers")) {
            messageFile.set("placeholders.format-numbers", c.getString("format-numbers"));
            c.set("format-numbers", null);
        }
        if (c.contains("format-time-characters")) {
            messageFile.set("placeholders.format-time-characters", c.getString("format-time-characters"));
            c.set("format-time-characters", null);
        }
        if (c.contains("filler-item")) {
            Material material = Material.matchMaterial(c.getString("filler-item", "BLACK_STAINED_GLASS_PANE"));
            ItemStack fillerItem = material == null ? new ItemStack(Material.AIR) : new ItemStack(material);
            ConfigManager.layout.getCustomFile().set("filler-item", fillerItem);
            ConfigManager.layout.save();
            ConfigManager.layout.reload();
            c.set("filler-item", null);
        }
        if (c.contains("auction-duration")) {
            c.set("bin-auction-duration", c.get("auction-duration"));
            c.set("auction-duration", null);
        }
        if (ConfigManager.permissions.getCustomFile().contains("auction-duration")) {
            ConfigManager.permissions.getCustomFile().set("bin-auction-duration",
                    ConfigManager.permissions.getCustomFile().get("auction-duration"));
            ConfigManager.permissions.getCustomFile().set("auction-duration", null);
            ConfigManager.permissions.save();
            ConfigManager.permissions.reload();
        }
        if (Objects.equals(messageFile.getString("placeholders.currency-symbol"), " §ecoins")) {
            messageFile.set("placeholders.currency-symbol", " coins");
        }
        if (messageFile.contains("world.displays.sign-interaction")) {
            messageFile.set("world.displays.line-3", messageFile.get("world.displays.sign-interaction"));
            messageFile.set("world.displays.sign-interaction", null);
            String by = messageFile.getString("world.displays.by-player");
            if (by != null && !by.contains("%player%")) {
                messageFile.set("world.displays.by-player", messageFile.get("world.displays.by-player") + "%player%");
            }
        }
        if (messageFile.contains("commands.alias")) {
            String oldAlias = messageFile.getString("commands.alias");
            if (oldAlias != null) {
                messageFile.set("commands.aliases", List.of(oldAlias));
            }
            messageFile.set("commands.alias", null);
        }
        backwardsCompatibilityForPlaceholderAPI(messageFile);
        soundsBackwardsCompatibility();

        ConfigManager.messages.save();
        ConfigManager.messages.reload();
        AuctionHouse.getInstance().saveConfig();
        AuctionHouse.getInstance().reloadConfig();
    }

    private static void backwardsCompatibilityForPlaceholderAPI(FileConfiguration c) {
        String[] sellers = {"chat.purchase-auction", "chat.claim-auction", "items.auction.lore.default", "items.auction.lore.default-starting-bid",
                "items.auction.lore.default-bid", "items.admin-expire-item.lore", "items.admin-delete-item.lore"};
        String[] buyers = {"chat.sold-message.prefix", "chat.sold-message.auto-collect"};
        for (String seller : sellers) {
            c.set(seller, Objects.requireNonNull(c.getString(seller)).replace("%player%", "%seller%"));
        }
        for (String buyer : buyers) {
            c.set(buyer, Objects.requireNonNull(c.getString(buyer)).replace("%player%", "%buyer%"));
        }
        c.set("items.auction.lore.default-bid", Objects.requireNonNull(c.getString("items.auction.lore.default-bid"))
                .replace("%bidder%", "%buyer%"));
    }

    private static void soundsBackwardsCompatibility() {
        ConfigurationSection c = ConfigManager.layout.getCustomFile().getConfigurationSection("sounds");
        assert c != null;
        if (ConfigManager.oldVersion21()) {
            c.set("click", "ui.stonecutter.select_recipe");
            c.set("open-enderchest", "block.ender_chest.open");
            c.set("close-enderchest", "block.ender_chest.close");
            c.set("break-wood", "block.wood.break");
            c.set("experience", "entity.experience_orb.pickup");
            c.set("villager-deny", "entity.villager.no");
            c.set("open-shulker", "block.shulker_box.open");
            c.set("close-shulker", "block.shulker_box.close");
            c.set("npc-click", "ui.stonecutter.select_recipe");
            c.set("open-bundle", "item.bundle.drop_contents");
            c.set("close-bundle", "item.bundle.remove_one");
        } else {
            String mainSound = c.getString("click");
            assert mainSound != null;

            if (Character.isUpperCase(mainSound.charAt(0))) {
                for (String key : c.getKeys(false)) {
                    c.set(key, Sound.valueOf(c.getString(key)).getKey().getKey());
                }
            }
        }
        ConfigManager.layout.save();
        ConfigManager.layout.reload();
    }

}
