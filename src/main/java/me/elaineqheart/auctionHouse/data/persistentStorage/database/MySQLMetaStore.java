package me.elaineqheart.auctionHouse.data.persistentStorage.database;

import me.elaineqheart.auctionHouse.AuctionHouse;
import me.elaineqheart.auctionHouse.data.persistentStorage.ItemStackConverter;
import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Persistence layer for every "non-auction-note" dataset:
 * blacklist rules, banned players, custom categories, per-player preferences,
 * world displays, per-permission overrides and the transaction log.
 *
 * <p>Each table is the single source of truth; Redis mirrors the same data so
 * reads stay fast and other servers in the cluster see updates through
 * {@link RedisSyncManager} pub/sub.</p>
 */
public final class MySQLMetaStore {

    private MySQLMetaStore() {}

    // ------------------------------------------------------------
    // Schema
    // ------------------------------------------------------------

    public static void ensureSchema() {
        try (Connection conn = MySQLManager.getConnection(); java.sql.Statement st = conn.createStatement()) {

            // ----- blacklist -----
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS ah_blacklist (
                    id           INT AUTO_INCREMENT PRIMARY KEY,
                    rule_type    VARCHAR(32)  NOT NULL,
                    rule_key     TEXT         NOT NULL,
                    created_at   BIGINT       NOT NULL,
                    server_id    VARCHAR(64)  NOT NULL DEFAULT 'global',
                    INDEX idx_type (rule_type)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);

            // ----- banned players -----
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS ah_banned_players (
                    player_uuid  CHAR(36)     PRIMARY KEY,
                    player_name  VARCHAR(64)  NOT NULL,
                    ban_end      BIGINT       NOT NULL,
                    reason       TEXT,
                    updated_at   BIGINT       NOT NULL
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);

            // ----- custom categories -----
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS ah_categories (
                    letter       VARCHAR(8)   PRIMARY KEY,
                    payload_json LONGTEXT     NOT NULL,
                    updated_at   BIGINT       NOT NULL
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);

            // ----- per-player preferences + AhConfiguration snapshot -----
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS ah_player_prefs (
                    player_uuid       CHAR(36)     PRIMARY KEY,
                    announcements     TINYINT(1)   NOT NULL DEFAULT 1,
                    configuration     LONGTEXT,
                    updated_at        BIGINT       NOT NULL
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);

            // ----- world displays -----
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS ah_displays (
                    display_id        INT          PRIMARY KEY,
                    world             VARCHAR(64)  NOT NULL,
                    x                 DOUBLE       NOT NULL,
                    y                 DOUBLE       NOT NULL,
                    z                 DOUBLE       NOT NULL,
                    yaw               FLOAT        NOT NULL DEFAULT 0,
                    pitch             FLOAT        NOT NULL DEFAULT 0,
                    rank              INT          NOT NULL,
                    sort_type         VARCHAR(32)  NOT NULL,
                    glass_uuid        CHAR(36),
                    interaction_uuid  CHAR(36),
                    item_uuid         CHAR(36),
                    text_uuid         CHAR(36),
                    server_id         VARCHAR(64)  NOT NULL,
                    updated_at        BIGINT       NOT NULL
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);

            // ----- permission overrides (auction-slots / auction-duration) -----
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS ah_permissions (
                    perm_type  VARCHAR(32)  NOT NULL,
                    perm_node  VARCHAR(128) NOT NULL,
                    perm_value BIGINT       NOT NULL,
                    updated_at BIGINT       NOT NULL,
                    PRIMARY KEY (perm_type, perm_node)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);

            // ----- transaction log (audit) -----
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS ah_transaction_log (
                    log_id     BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    created_at BIGINT       NOT NULL,
                    kind       VARCHAR(32)  NOT NULL,
                    player     VARCHAR(128) NOT NULL,
                    item       VARCHAR(255),
                    amount     INT          NOT NULL DEFAULT 0,
                    price      DOUBLE       NOT NULL DEFAULT 0,
                    is_bid     TINYINT(1)   NOT NULL DEFAULT 0,
                    note_uuid  CHAR(36),
                    INDEX idx_kind (kind),
                    INDEX idx_created (created_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        } catch (SQLException ex) {
            AuctionHouse.getInstance().getLogger().severe(
                    "Failed to ensure MySQL meta schema: " + ex.getMessage());
            throw new RuntimeException(ex);
        }
    }

    // ------------------------------------------------------------
    // Blacklist
    // ------------------------------------------------------------

    public static List<BlacklistRule> loadBlacklist() {
        List<BlacklistRule> out = new ArrayList<>();
        try (Connection c = MySQLManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id, rule_type, rule_key FROM ah_blacklist ORDER BY id ASC")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new BlacklistRule(
                            rs.getLong("id"),
                            rs.getString("rule_type"),
                            rs.getString("rule_key")));
                }
            }
        } catch (SQLException ex) {
            logError("loadBlacklist", ex);
        }
        return out;
    }

    public static long insertBlacklist(String type, String key, String serverId) {
        try (Connection c = MySQLManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO ah_blacklist (rule_type, rule_key, created_at, server_id) VALUES (?,?,?,?)",
                     java.sql.Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, type);
            ps.setString(2, key);
            ps.setLong(3, System.currentTimeMillis());
            ps.setString(4, serverId == null ? "global" : serverId);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) return keys.getLong(1);
            }
        } catch (SQLException ex) {
            logError("insertBlacklist", ex);
        }
        return -1L;
    }

    public static boolean popBlacklist() {
        // Equivalent to YAML "undo": remove the most-recent row by id.
        try (Connection c = MySQLManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM ah_blacklist ORDER BY id DESC LIMIT 1")) {
            return ps.executeUpdate() > 0;
        } catch (SQLException ex) {
            logError("popBlacklist", ex);
            return false;
        }
    }

    public static void clearBlacklist() {
        try (Connection c = MySQLManager.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM ah_blacklist")) {
            ps.executeUpdate();
        } catch (SQLException ex) {
            logError("clearBlacklist", ex);
        }
    }

    // ------------------------------------------------------------
    // Banned players
    // ------------------------------------------------------------

    public static void upsertBan(UUID uuid, String name, long banEndMs, String reason) {
        try (Connection c = MySQLManager.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                INSERT INTO ah_banned_players (player_uuid, player_name, ban_end, reason, updated_at)
                VALUES (?,?,?,?,?)
                ON DUPLICATE KEY UPDATE
                  player_name = VALUES(player_name),
                  ban_end = VALUES(ban_end),
                  reason = VALUES(reason),
                  updated_at = VALUES(updated_at)
                """)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            ps.setLong(3, banEndMs);
            ps.setString(4, reason);
            ps.setLong(5, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException ex) {
            logError("upsertBan", ex);
        }
    }

    public static void deleteBan(UUID uuid) {
        try (Connection c = MySQLManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM ah_banned_players WHERE player_uuid = ?")) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException ex) {
            logError("deleteBan", ex);
        }
    }

    public static BanEntry loadBan(UUID uuid) {
        try (Connection c = MySQLManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT player_uuid, player_name, ban_end, reason FROM ah_banned_players WHERE player_uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new BanEntry(
                            UUID.fromString(rs.getString("player_uuid")),
                            rs.getString("player_name"),
                            rs.getLong("ban_end"),
                            rs.getString("reason"));
                }
            }
        } catch (SQLException ex) {
            logError("loadBan", ex);
        }
        return null;
    }

    public static Map<UUID, BanEntry> loadAllBans() {
        Map<UUID, BanEntry> out = new HashMap<>();
        try (Connection c = MySQLManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT player_uuid, player_name, ban_end, reason FROM ah_banned_players")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID id = UUID.fromString(rs.getString("player_uuid"));
                    out.put(id, new BanEntry(id, rs.getString("player_name"),
                            rs.getLong("ban_end"), rs.getString("reason")));
                }
            }
        } catch (SQLException ex) {
            logError("loadAllBans", ex);
        }
        return out;
    }

    // ------------------------------------------------------------
    // Categories
    // ------------------------------------------------------------

    public static Map<String, String> loadCategories() {
        // letter -> raw JSON list of maps
        Map<String, String> out = new HashMap<>();
        try (Connection c = MySQLManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT letter, payload_json FROM ah_categories")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.put(rs.getString("letter"), rs.getString("payload_json"));
            }
        } catch (SQLException ex) {
            logError("loadCategories", ex);
        }
        return out;
    }

    public static void upsertCategory(String letter, String payloadJson) {
        try (Connection c = MySQLManager.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                INSERT INTO ah_categories (letter, payload_json, updated_at)
                VALUES (?,?,?)
                ON DUPLICATE KEY UPDATE
                  payload_json = VALUES(payload_json),
                  updated_at = VALUES(updated_at)
                """)) {
            ps.setString(1, letter);
            ps.setString(2, payloadJson);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException ex) {
            logError("upsertCategory", ex);
        }
    }

    public static void deleteCategory(String letter) {
        try (Connection c = MySQLManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM ah_categories WHERE letter = ?")) {
            ps.setString(1, letter);
            ps.executeUpdate();
        } catch (SQLException ex) {
            logError("deleteCategory", ex);
        }
    }

    /**
     * The plugin stores categories as an array of arbitrary strings; here we
     * serialise the entire list as a single JSON blob stored under a stable
     * sentinel row. This keeps the schema tiny while preserving order.
     */
    public static void replaceCategories(List<String> categories) {
        try (Connection c = MySQLManager.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement del = c.prepareStatement("DELETE FROM ah_categories WHERE letter LIKE '__CATEGORY__%'")) {
                del.executeUpdate();
            }
            try (PreparedStatement ins = c.prepareStatement("""
                INSERT INTO ah_categories (letter, payload_json, updated_at)
                VALUES (?,?,?)
                """)) {
                String json = new com.google.gson.Gson().toJson(categories);
                ins.setString(1, "__CATEGORY__LIST");
                ins.setString(2, json);
                ins.setLong(3, System.currentTimeMillis());
                ins.executeUpdate();
            }
            c.commit();
            c.setAutoCommit(true);
        } catch (SQLException ex) {
            logError("replaceCategories", ex);
        }
    }

    public static List<String> loadCategoryList() {
        try (Connection c = MySQLManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT payload_json FROM ah_categories WHERE letter = '__CATEGORY__LIST'")) {
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String json = rs.getString(1);
                    if (json == null || json.isEmpty()) return new ArrayList<>();
                    String[] parts = new com.google.gson.Gson().fromJson(json, String[].class);
                    return parts == null ? new ArrayList<>() : new ArrayList<>(java.util.Arrays.asList(parts));
                }
            }
        } catch (SQLException ex) {
            logError("loadCategoryList", ex);
        }
        return new ArrayList<>();
    }

    public static void upsertCategoryMaterial(String category, String material) {
        // Schema is permissive: one row per category+material; idempotent on
        // duplicate (PRIMARY KEY on the concatenation). Use a deterministic
        // letter key derived from the category name.
        try (Connection c = MySQLManager.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                INSERT INTO ah_categories (letter, payload_json, updated_at)
                VALUES (?,?,?)
                ON DUPLICATE KEY UPDATE
                  payload_json = VALUES(payload_json),
                  updated_at = VALUES(updated_at)
                """)) {
            ps.setString(1, "mat:" + category + ":" + material);
            ps.setString(2, "material");
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException ex) {
            logError("upsertCategoryMaterial", ex);
        }
    }

    public static void deleteCategoryMaterial(String category, String material) {
        try (Connection c = MySQLManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM ah_categories WHERE letter = ?")) {
            ps.setString(1, "mat:" + category + ":" + material);
            ps.executeUpdate();
        } catch (SQLException ex) {
            logError("deleteCategoryMaterial", ex);
        }
    }

    public static List<String> loadCategoryMaterials(String category) {
        List<String> out = new ArrayList<>();
        try (Connection c = MySQLManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT letter FROM ah_categories WHERE letter LIKE ?")) {
            ps.setString(1, "mat:" + category + ":%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String letter = rs.getString(1);
                    int lastColon = letter.lastIndexOf(':');
                    if (lastColon >= 0 && lastColon + 1 < letter.length()) {
                        out.add(letter.substring(lastColon + 1));
                    }
                }
            }
        } catch (SQLException ex) {
            logError("loadCategoryMaterials", ex);
        }
        return out;
    }

    // ------------------------------------------------------------
    // Player preferences
    // ------------------------------------------------------------

    public static PlayerPrefs loadPlayerPrefs(UUID uuid) {
        try (Connection c = MySQLManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT announcements, configuration FROM ah_player_prefs WHERE player_uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new PlayerPrefs(uuid, rs.getBoolean("announcements"), rs.getString("configuration"));
                }
            }
        } catch (SQLException ex) {
            logError("loadPlayerPrefs", ex);
        }
        return null;
    }

    public static Map<UUID, PlayerPrefs> loadAllPlayerPrefs() {
        Map<UUID, PlayerPrefs> out = new HashMap<>();
        try (Connection c = MySQLManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT player_uuid, announcements, configuration FROM ah_player_prefs")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID id = UUID.fromString(rs.getString("player_uuid"));
                    out.put(id, new PlayerPrefs(id, rs.getBoolean("announcements"), rs.getString("configuration")));
                }
            }
        } catch (SQLException ex) {
            logError("loadAllPlayerPrefs", ex);
        }
        return out;
    }

    public static void upsertAnnouncement(UUID uuid, boolean enabled) {
        try (Connection c = MySQLManager.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                INSERT INTO ah_player_prefs (player_uuid, announcements, configuration, updated_at)
                VALUES (?,?,NULL,?)
                ON DUPLICATE KEY UPDATE
                  announcements = VALUES(announcements),
                  updated_at = VALUES(updated_at)
                """)) {
            ps.setString(1, uuid.toString());
            ps.setBoolean(2, enabled);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException ex) {
            logError("upsertAnnouncement", ex);
        }
    }

    public static void upsertConfiguration(UUID uuid, String configurationJson) {
        try (Connection c = MySQLManager.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                INSERT INTO ah_player_prefs (player_uuid, announcements, configuration, updated_at)
                VALUES (?, 1, ?, ?)
                ON DUPLICATE KEY UPDATE
                  configuration = VALUES(configuration),
                  updated_at = VALUES(updated_at)
                """)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, configurationJson);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException ex) {
            logError("upsertConfiguration", ex);
        }
    }

    // ------------------------------------------------------------
    // Displays
    // ------------------------------------------------------------

    public static Map<Integer, DisplayRow> loadAllDisplays() {
        Map<Integer, DisplayRow> out = new HashMap<>();
        try (Connection c = MySQLManager.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                SELECT display_id, world, x, y, z, yaw, pitch, rank, sort_type,
                       glass_uuid, interaction_uuid, item_uuid, text_uuid, server_id
                FROM ah_displays
                """)) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getInt("display_id"), new DisplayRow(
                            rs.getInt("display_id"),
                            rs.getString("world"),
                            rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"),
                            rs.getFloat("yaw"), rs.getFloat("pitch"),
                            rs.getInt("rank"),
                            rs.getString("sort_type"),
                            rs.getString("glass_uuid"),
                            rs.getString("interaction_uuid"),
                            rs.getString("item_uuid"),
                            rs.getString("text_uuid"),
                            rs.getString("server_id")));
                }
            }
        } catch (SQLException ex) {
            logError("loadAllDisplays", ex);
        }
        return out;
    }

    public static void upsertDisplay(int id, DisplayRow row) {
        try (Connection c = MySQLManager.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                INSERT INTO ah_displays
                  (display_id, world, x, y, z, yaw, pitch, rank, sort_type,
                   glass_uuid, interaction_uuid, item_uuid, text_uuid, server_id, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE
                  world = VALUES(world),
                  x = VALUES(x), y = VALUES(y), z = VALUES(z),
                  yaw = VALUES(yaw), pitch = VALUES(pitch),
                  rank = VALUES(rank), sort_type = VALUES(sort_type),
                  glass_uuid = VALUES(glass_uuid),
                  interaction_uuid = VALUES(interaction_uuid),
                  item_uuid = VALUES(item_uuid),
                  text_uuid = VALUES(text_uuid),
                  server_id = VALUES(server_id),
                  updated_at = VALUES(updated_at)
                """)) {
            ps.setInt(1, id);
            ps.setString(2, row.world);
            ps.setDouble(3, row.x);
            ps.setDouble(4, row.y);
            ps.setDouble(5, row.z);
            ps.setFloat(6, row.yaw);
            ps.setFloat(7, row.pitch);
            ps.setInt(8, row.rank);
            ps.setString(9, row.sortType);
            ps.setString(10, row.glassUuid);
            ps.setString(11, row.interactionUuid);
            ps.setString(12, row.itemUuid);
            ps.setString(13, row.textUuid);
            ps.setString(14, row.serverId);
            ps.setLong(15, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException ex) {
            logError("upsertDisplay", ex);
        }
    }

    public static void deleteDisplay(int id) {
        try (Connection c = MySQLManager.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM ah_displays WHERE display_id = ?")) {
            ps.setInt(1, id);
            ps.executeUpdate();
        } catch (SQLException ex) {
            logError("deleteDisplay", ex);
        }
    }

    // ------------------------------------------------------------
    // Permissions
    // ------------------------------------------------------------

    public static Map<String, Long> loadPermissions(String permType) {
        Map<String, Long> out = new HashMap<>();
        try (Connection c = MySQLManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT perm_node, perm_value FROM ah_permissions WHERE perm_type = ?")) {
            ps.setString(1, permType);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.put(rs.getString("perm_node"), rs.getLong("perm_value"));
            }
        } catch (SQLException ex) {
            logError("loadPermissions", ex);
        }
        return out;
    }

    public static void upsertPermission(String permType, String node, long value) {
        try (Connection c = MySQLManager.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                INSERT INTO ah_permissions (perm_type, perm_node, perm_value, updated_at)
                VALUES (?,?,?,?)
                ON DUPLICATE KEY UPDATE
                  perm_value = VALUES(perm_value),
                  updated_at = VALUES(updated_at)
                """)) {
            ps.setString(1, permType);
            ps.setString(2, node);
            ps.setLong(3, value);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException ex) {
            logError("upsertPermission", ex);
        }
    }

    public static void deletePermission(String permType, String node) {
        try (Connection c = MySQLManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM ah_permissions WHERE perm_type = ? AND perm_node = ?")) {
            ps.setString(1, permType);
            ps.setString(2, node);
            ps.executeUpdate();
        } catch (SQLException ex) {
            logError("deletePermission", ex);
        }
    }

    // ------------------------------------------------------------
    // Transaction log
    // ------------------------------------------------------------

    public static void appendLog(String kind, String player, String itemName,
                                 int amount, double price, boolean isBid, UUID noteId) {
        try (Connection c = MySQLManager.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                INSERT INTO ah_transaction_log
                  (created_at, kind, player, item, amount, price, is_bid, note_uuid)
                VALUES (?,?,?,?,?,?,?,?)
                """)) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, kind);
            ps.setString(3, player);
            ps.setString(4, itemName);
            ps.setInt(5, amount);
            ps.setDouble(6, price);
            ps.setBoolean(7, isBid);
            ps.setString(8, noteId == null ? null : noteId.toString());
            ps.executeUpdate();
        } catch (SQLException ex) {
            logError("appendLog", ex);
        }
    }

    public static List<LogRow> loadRecentLogs(int limit) {
        List<LogRow> out = new ArrayList<>();
        try (Connection c = MySQLManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT created_at, kind, player, item, amount, price, is_bid, note_uuid " +
                             "FROM ah_transaction_log ORDER BY log_id DESC LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String noteStr = rs.getString("note_uuid");
                    out.add(new LogRow(
                            rs.getLong("created_at"),
                            rs.getString("kind"),
                            rs.getString("player"),
                            rs.getString("item"),
                            rs.getInt("amount"),
                            rs.getDouble("price"),
                            rs.getBoolean("is_bid"),
                            noteStr == null ? null : UUID.fromString(noteStr)));
                }
            }
        } catch (SQLException ex) {
            logError("loadRecentLogs", ex);
        }
        return out;
    }

    // ------------------------------------------------------------
    // DTOs
    // ------------------------------------------------------------

    public static final class BlacklistRule {
        public final long id;
        public final String type;
        public final String key;
        public BlacklistRule(long id, String type, String key) {
            this.id = id; this.type = type; this.key = key;
        }
    }

    public static final class BanEntry {
        public final UUID uuid;
        public final String name;
        public final long banEndMs;
        public final String reason;
        public BanEntry(UUID uuid, String name, long banEndMs, String reason) {
            this.uuid = uuid; this.name = name; this.banEndMs = banEndMs; this.reason = reason;
        }
    }

    public static final class PlayerPrefs {
        public final UUID uuid;
        public final boolean announcements;
        public final String configuration;
        public PlayerPrefs(UUID uuid, boolean announcements, String configuration) {
            this.uuid = uuid; this.announcements = announcements; this.configuration = configuration;
        }
    }

    public static final class DisplayRow {
        public final int id;
        public final String world;
        public final double x, y, z;
        public final float yaw, pitch;
        public final int rank;
        public final String sortType;
        public final String glassUuid;
        public final String interactionUuid;
        public final String itemUuid;
        public final String textUuid;
        public final String serverId;
        public DisplayRow(int id, String world, double x, double y, double z, float yaw, float pitch,
                         int rank, String sortType, String glassUuid, String interactionUuid,
                         String itemUuid, String textUuid, String serverId) {
            this.id = id; this.world = world; this.x = x; this.y = y; this.z = z;
            this.yaw = yaw; this.pitch = pitch; this.rank = rank; this.sortType = sortType;
            this.glassUuid = glassUuid; this.interactionUuid = interactionUuid;
            this.itemUuid = itemUuid; this.textUuid = textUuid; this.serverId = serverId;
        }
    }

    public static final class LogRow {
        public final long createdAt;
        public final String kind;
        public final String player;
        public final String item;
        public final int amount;
        public final double price;
        public final boolean isBid;
        public final UUID noteId;
        public LogRow(long createdAt, String kind, String player, String item, int amount,
                     double price, boolean isBid, UUID noteId) {
            this.createdAt = createdAt; this.kind = kind; this.player = player; this.item = item;
            this.amount = amount; this.price = price; this.isBid = isBid; this.noteId = noteId;
        }
    }

    private static void logError(String op, SQLException ex) {
        AuctionHouse.getInstance().getLogger().warning("MySQL meta " + op + " failed: " + ex.getMessage());
    }
}