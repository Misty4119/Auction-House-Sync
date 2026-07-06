package me.elaineqheart.auctionHouse.data.persistentStorage.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import me.elaineqheart.auctionHouse.AuctionHouse;
import me.elaineqheart.auctionHouse.data.persistentStorage.local.SettingManager;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Logger;

/**
 * Owns the HikariCP connection pool to MySQL.
 *
 * <p>This is the persistence layer of the plugin: notes are read from and
 * written through this pool. A cluster of Minecraft servers pointed at the
 * same MySQL instance see the same rows, so even without Redis they would
 * eventually converge. With Redis added on top the live in-memory state is
 * also kept in sync across the cluster via pub/sub.</p>
 */
public final class MySQLManager {

    private static volatile HikariDataSource dataSource;
    private static volatile boolean available = false;

    private MySQLManager() {}

    /**
     * Initialise the pool and ensure required tables exist. Safe to call only
     * when {@link SettingManager#isMysqlPersistence()} returns true.
     */
    public static synchronized void init() {
        if (available) return;
        try {
            HikariConfig cfg = buildConfig();
            dataSource = new HikariDataSource(cfg);
            // Flip the availability flag BEFORE we touch the pool from inside
            // this class (e.g. ensureSchema below calls getConnection), so that
            // isAvailable() returns true and we don't shoot ourselves in the foot.
            available = dataSource != null && !dataSource.isClosed();

            ensureSchema();
            // New tables backing the meta data that used to live on disk.
            MySQLMetaStore.ensureSchema();

            AuctionHouse.getInstance().getLogger().info(
                    "[AuctionHouse] MySQL pool initialised at " +
                            cfg.getJdbcUrl().replaceFirst(":[^:@/?]+@", ":***@")
            );
        } catch (Throwable t) {
            // Make sure we don't half-leave a pool around that callers would
            // later believe is usable.
            HikariDataSource ds = dataSource;
            dataSource = null;
            available = false;
            if (ds != null) {
                try { ds.close(); } catch (Throwable ignored) {}
            }
            Logger.getLogger("AuctionHouse").severe("MySQL init failed: " + t.getMessage());
            throw new RuntimeException("Failed to initialise MySQL pool", t);
        }
    }

    public static synchronized void shutdown() {
        HikariDataSource ds = dataSource;
        dataSource = null;
        available = false;
        if (ds != null && !ds.isClosed()) {
            try { ds.close(); } catch (Throwable ignored) {}
        }
    }

    public static boolean isAvailable() {
        HikariDataSource ds = dataSource;
        return available && ds != null && !ds.isClosed();
    }

    /**
     * Borrow a connection from the pool; the caller must close it.
     *
     * <p>Callers might want to fetch a connection during plugin startup (e.g.
     * for migrations) before {@link #init()} finishes. We therefore only
     * complain when the pool is genuinely unusable (null or closed), not when
     * {@link #available()} simply hasn't been flipped yet.</p>
     */
    public static Connection getConnection() throws SQLException {
        HikariDataSource ds = dataSource;
        if (ds == null) {
            throw new SQLException("MySQL pool is not initialised (init() not called yet)");
        }
        if (ds.isClosed()) {
            throw new SQLException("MySQL pool has been closed");
        }
        return ds.getConnection();
    }

    private static HikariConfig buildConfig() {
        HikariConfig cfg = new HikariConfig();
        cfg.setPoolName("AuctionHouseHikariPool");

        String jdbcUrl = buildJdbcUrl();
        cfg.setJdbcUrl(jdbcUrl);
        cfg.setUsername(SettingManager.mysqlUsername);
        cfg.setPassword(SettingManager.mysqlPassword);

        cfg.setMaximumPoolSize(SettingManager.mysqlPoolSize);
        cfg.setMinimumIdle(SettingManager.mysqlMinIdle);
        cfg.setMaxLifetime(SettingManager.mysqlMaxLifetimeMs);
        cfg.setConnectionTimeout(SettingManager.mysqlConnectionTimeoutMs);
        cfg.setIdleTimeout(Math.max(60_000L, SettingManager.mysqlMaxLifetimeMs / 2));
        cfg.setDriverClassName("com.mysql.cj.jdbc.Driver");

        cfg.addDataSourceProperty("cachePrepStmts", "true");
        cfg.addDataSourceProperty("prepStmtCacheSize", "250");
        cfg.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        cfg.addDataSourceProperty("useServerPrepStmts", "true");
        cfg.addDataSourceProperty("rewriteBatchedStatements", "true");

        cfg.setConnectionTestQuery("SELECT 1");
        return cfg;
    }

    private static String buildJdbcUrl() {
        StringBuilder sb = new StringBuilder("jdbc:mysql://")
                .append(SettingManager.mysqlHost).append(":")
                .append(SettingManager.mysqlPort).append("/")
                .append(SettingManager.mysqlDatabase);
        String params = SettingManager.mysqlExtraParams;
        sb.append('?');
        sb.append(params == null || params.isBlank() ? "useSSL=false" : params);
        if (SettingManager.mysqlUseSsl) {
            sb.append("&useSSL=true&requireSSL=true");
        }
        return sb.toString();
    }

    private static void ensureSchema() throws SQLException {
        try (Connection conn = getConnection(); Statement st = conn.createStatement()) {
            // auctions: one row per ItemNote. Pickled item column stores the Bukkit item.
            // bid_history: separate table because it's a 1-N relation.
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS ah_notes (
                    note_id              CHAR(36)     NOT NULL PRIMARY KEY,
                    item_name            VARCHAR(255) NOT NULL,
                    player_uuid          CHAR(36)     NOT NULL,
                    player_name          VARCHAR(64)  NOT NULL,
                    buyer_uuid           CHAR(36)              DEFAULT NULL,
                    buyer_name           VARCHAR(64)           DEFAULT NULL,
                    price                DOUBLE        NOT NULL DEFAULT 0,
                    current_amount       INT           NOT NULL DEFAULT 0,
                    is_bid_auction       TINYINT(1)    NOT NULL DEFAULT 0,
                    is_sold              TINYINT(1)    NOT NULL DEFAULT 0,
                    partially_sold_left  INT           NOT NULL DEFAULT 0,
                    admin_message        TEXT                   DEFAULT NULL,
                    auction_time         BIGINT        NOT NULL DEFAULT 0,
                    date_created         BIGINT        NOT NULL DEFAULT 0,
                    item_data            LONGTEXT      NOT NULL,
                    INDEX idx_player (player_uuid),
                    INDEX idx_buyer  (buyer_uuid),
                    INDEX idx_sold   (is_sold),
                    INDEX idx_price  (price),
                    INDEX idx_date   (date_created)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS ah_bids (
                    bid_id     BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    note_id    CHAR(36)     NOT NULL,
                    player_uuid CHAR(36)    NOT NULL,
                    player_name VARCHAR(64) NOT NULL,
                    bid_price  DOUBLE       NOT NULL,
                    bid_time   BIGINT       NOT NULL,
                    INDEX idx_note (note_id),
                    INDEX idx_player (player_uuid)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        }
    }
}
