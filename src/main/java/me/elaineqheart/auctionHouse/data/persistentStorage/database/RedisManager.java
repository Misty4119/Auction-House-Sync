package me.elaineqheart.auctionHouse.data.persistentStorage.database;

import me.elaineqheart.auctionHouse.AuctionHouse;
import me.elaineqheart.auctionHouse.data.persistentStorage.local.SettingManager;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.time.Duration;

/**
 * Owns the {@link JedisPool} that the plugin uses to talk to Redis 8.0.
 *
 * <p>The connection settings come from {@link SettingManager}; calling
 * {@link #init()} once during plugin start-up is enough. The pool will
 * silently reconnect when Redis bounces.</p>
 */
public final class RedisManager {

    private static volatile JedisPool pool;
    private static volatile boolean available = false;

    private RedisManager() {}

    public static synchronized void init() {
        if (available) return;
        try {
            JedisPoolConfig poolConfig = new JedisPoolConfig();
            poolConfig.setMaxTotal(SettingManager.redisMaxTotal);
            poolConfig.setMaxIdle(SettingManager.redisMaxIdle);
            poolConfig.setMinIdle(SettingManager.redisMinIdle);
            poolConfig.setBlockWhenExhausted(true);
            // Jedis 6.x removed setMaxWaitMillis in favour of Duration-based
            // setter; we use it so callers don't block forever on a dead pool.
            try { poolConfig.setMaxWait(Duration.ofMillis(SettingManager.redisSoTimeoutMs)); }
            catch (Throwable ignored) {}
            poolConfig.setTestOnBorrow(false);
            poolConfig.setTestWhileIdle(true);
            poolConfig.setTimeBetweenEvictionRuns(Duration.ofSeconds(30));

            JedisClientConfig clientConfig = DefaultJedisClientConfig.builder()
                    .connectionTimeoutMillis(SettingManager.redisConnectionTimeoutMs)
                    .socketTimeoutMillis(SettingManager.redisSoTimeoutMs)
                    .user(SettingManager.redisUsername == null || SettingManager.redisUsername.isBlank()
                            ? null : SettingManager.redisUsername)
                    .password(SettingManager.redisPassword == null || SettingManager.redisPassword.isBlank()
                            ? null : SettingManager.redisPassword)
                    .database(SettingManager.redisDatabase)
                    .clientName("AuctionHouse:" + SettingManager.serverId)
                    .build();

            HostAndPort hp = new HostAndPort(SettingManager.redisHost, SettingManager.redisPort);
            pool = new JedisPool(poolConfig, hp, clientConfig);

            // Sanity ping — verifies connectivity at startup. We also retry
            // the connection a few times because the very first command after
            // a server restart occasionally races with Redis startup.
            int attempts = 0;
            while (true) {
                try (Jedis jedis = pool.getResource()) {
                    String pong = jedis.ping();
                    AuctionHouse.getInstance().getLogger().info(
                            "[AuctionHouse] Redis ping: " + pong);
                    break;
                } catch (Throwable t) {
                    attempts++;
                    if (attempts >= 3) throw t;
                    try { Thread.sleep(500L * attempts); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw t; }
                }
            }
            available = true;
        } catch (Throwable t) {
            available = false;
            AuctionHouse.getInstance().getLogger().warning(
                    "Redis init failed (continuing without cache): " + t.getMessage());
            if (pool != null) {
                try { pool.close(); } catch (Exception ignored) {}
            }
            pool = null;
        }
    }

    public static synchronized void shutdown() {
        if (pool != null && !pool.isClosed()) {
            try { pool.close(); } catch (Exception ignored) {}
        }
        pool = null;
        available = false;
    }

    public static boolean isAvailable() {
        return available && pool != null && !pool.isClosed();
    }

    /** Get a resource; caller MUST close it (try-with-resources). */
    public static Jedis getResource() {
        if (!isAvailable()) {
            throw new IllegalStateException("Redis pool is not initialised or has been closed");
        }
        return pool.getResource();
    }
}
