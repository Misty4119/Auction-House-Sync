package me.elaineqheart.auctionHouse;

import me.elaineqheart.auctionHouse.GUI.GUIListener;
import me.elaineqheart.auctionHouse.GUI.GUIManager;
import me.elaineqheart.auctionHouse.GUI.other.AnvilGUIManager;
import me.elaineqheart.auctionHouse.commands.DynamicCommandRegisterer;
import me.elaineqheart.auctionHouse.data.persistentStorage.ItemNoteStorage;
import me.elaineqheart.auctionHouse.data.persistentStorage.database.MySQLManager;
import me.elaineqheart.auctionHouse.data.persistentStorage.database.RedisManager;
import me.elaineqheart.auctionHouse.data.persistentStorage.database.RedisMetaCache;
import me.elaineqheart.auctionHouse.data.persistentStorage.database.RedisSnapshotService;
import me.elaineqheart.auctionHouse.data.persistentStorage.database.RedisSyncManager;
import me.elaineqheart.auctionHouse.data.persistentStorage.local.SettingManager;
import me.elaineqheart.auctionHouse.data.persistentStorage.local.data.ConfigManager;
import me.elaineqheart.auctionHouse.listeners.AhConfigurationListener;
import me.elaineqheart.auctionHouse.listeners.PlayerJoinCollectListener;
import me.elaineqheart.auctionHouse.pluginDependencies.AuctionHousePAPIExpansion;
import me.elaineqheart.auctionHouse.pluginDependencies.LocaleAPIExtension;
import me.elaineqheart.auctionHouse.world.displays.DisplayKillListener;
import me.elaineqheart.auctionHouse.world.displays.DisplayListener;
import me.elaineqheart.auctionHouse.world.displays.UpdateDisplay;
import me.elaineqheart.auctionHouse.world.npc.NPCListener;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import space.arim.morepaperlib.MorePaperLib;
import space.arim.morepaperlib.scheduling.GracefulScheduling;

import java.io.IOException;
import java.util.logging.Logger;

public final class AuctionHouse extends JavaPlugin {

    private static AuctionHouse instance;
    private static GUIManager guiManager;
    private static AnvilGUIManager anvilManager;
    public static GUIManager getGuiManager() {return guiManager;}
    public static AnvilGUIManager getAnvilManager() {return anvilManager;}
    private MorePaperLib morePaperLib;
    public static AuctionHouse getInstance() {return instance;}
    public GracefulScheduling getScheduler() {
        return morePaperLib.scheduling();
    }

    @Override
    public void onEnable() {
        long start = System.currentTimeMillis();
        Logger log = getLogger();

        LocaleAPIExtension.setup();
        instance = this;
        guiManager = new GUIManager();
        GUIListener guiListener = new GUIListener(guiManager);
        anvilManager = new AnvilGUIManager();
        Bukkit.getPluginManager().registerEvents(guiListener, this);
        Bukkit.getPluginManager().registerEvents(anvilManager, this);
        morePaperLib = new MorePaperLib(instance);

        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            Bukkit.getLogger().severe("No registered Vault provider found!");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        Bukkit.getPluginManager().registerEvents(new NPCListener(), this);
        Bukkit.getPluginManager().registerEvents(new DisplayListener(), this);
        Bukkit.getPluginManager().registerEvents(new PlayerJoinCollectListener(), this);
        Bukkit.getPluginManager().registerEvents(new AhConfigurationListener(), this);
        DisplayKillListener.register();

        ConfigManager.setupConfigs();

        // ----- Multi-server database bring-up -----
        log.info("[AuctionHouse] Persistence backend: " + SettingManager.persistenceBackend);
        log.info("[AuctionHouse] Cache backend:        " + SettingManager.cacheBackend);

        if (SettingManager.isMysqlPersistence()) {
            try {
                MySQLManager.init();
                log.info("[AuctionHouse] MySQL pool ready.");
            } catch (Throwable t) {
                log.severe("MySQL failed to initialise: " + t.getMessage() + ". Plugin will be disabled.");
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
        }

        if (SettingManager.useRedisCache()) {
            RedisManager.init();
            if (RedisManager.isAvailable()) {
                log.info("[AuctionHouse] Redis pool ready (server-id=" + SettingManager.serverId + ").");
            } else {
                log.warning("[AuctionHouse] Redis was configured but the pool did NOT become available. " +
                        "Cross-server sync will be disabled until the connection comes back.");
            }
        }
        // ------------------------------------------

        try {
            ItemNoteStorage.loadNotes();
        } catch (IOException e) {
            getLogger().severe("Failed to load Auction House item data");
            throw new RuntimeException(e);
        } catch (Throwable t) {
            // MySQL/Redis hiccups are recoverable — we already logged the cause
            // upstream, so we don't want to crash the whole plugin here.
            getLogger().severe("Unexpected error while loading notes: " + t.getMessage());
            t.printStackTrace();
        }

        // Hydrate the meta caches (blacklist, bans, categories, player
        // preferences, displays, permissions) from MySQL into the in-memory
        // mirror so reads are O(1) and writes converge via pub/sub. This
        // runs in addition to the per-config bootstrap that imports the
        // legacy YAML files on first boot.
        if (SettingManager.isMysqlPersistence()) {
            try {
                RedisMetaCache.hydrateFromMysql();
            } catch (Throwable t) {
                log.warning("Meta hydration failed: " + t.getMessage());
            }
        }

        // Now that RAM is hydrated we can safely start the subscriber — its
        // loopback filter on `serverId` guarantees we'll ignore our own writes,
        // but other servers' UPSERTs must still find a populated storage.
        // Rebuild the Redis side from RAM so the cross-server cache is
        // immediately consistent with what we just loaded from MySQL.
        if (SettingManager.useRedisCache() && RedisManager.isAvailable()) {
            try {
                RedisSnapshotService.rebuildFromRam();
            } catch (Throwable t) {
                log.warning("Redis snapshot rebuild failed: " + t.getMessage());
            }
        }

        if (SettingManager.useRedisCache() && RedisManager.isAvailable()) {
            RedisSyncManager.start();
            log.info("[AuctionHouse] Cross-server sync subscription enabled.");
        }

        DynamicCommandRegisterer.init();
        UpdateDisplay.init();

        // Register PlaceholderAPI expansion
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new AuctionHousePAPIExpansion().register();
            getLogger().info("PlaceholderAPI expansion registered.");
        }

        getLogger().info("AuctionHouse enabled in " + (System.currentTimeMillis() - start) + "ms");
    }

    @Override
    public void onDisable() {
        ConfigManager.playerPreferences.disable();
        if(guiManager != null) guiManager.forceCloseAll();
        if(anvilManager != null) anvilManager.forceCloseAll();

        // Tear down cross-server subscribers first so we don't process our own
        // teardown events on the way out.
        try { RedisSyncManager.shutdown(); } catch (Throwable ignored) {}
        try { RedisManager.shutdown(); } catch (Throwable ignored) {}
        try { MySQLManager.shutdown(); } catch (Throwable ignored) {}
    }


    public static boolean isFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

}
