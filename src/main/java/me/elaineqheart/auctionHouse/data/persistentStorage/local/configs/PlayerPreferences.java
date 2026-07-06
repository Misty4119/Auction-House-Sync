package me.elaineqheart.auctionHouse.data.persistentStorage.local.configs;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import me.elaineqheart.auctionHouse.AuctionHouse;
import me.elaineqheart.auctionHouse.data.persistentStorage.database.MySQLMetaStore;
import me.elaineqheart.auctionHouse.data.persistentStorage.database.RedisMetaCache;
import me.elaineqheart.auctionHouse.data.persistentStorage.database.RedisSyncManager;
import me.elaineqheart.auctionHouse.data.persistentStorage.local.SettingManager;
import me.elaineqheart.auctionHouse.data.persistentStorage.local.data.Config;
import me.elaineqheart.auctionHouse.data.ram.AhConfiguration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

/**
 * Per-player preferences + cached {@link AhConfiguration} JSON blob.
 *
 * <p>Backed by MySQL ({@code ah_player_prefs}) and a Redis-backed in-memory
 * mirror so reads are instantaneous and updates flow to every server in the
 * cluster via {@link RedisSyncManager}. Falls back to the legacy YAML when
 * MySQL persistence is disabled.</p>
 */
public class PlayerPreferences extends Config {

    private final boolean defaultAnnounce = true;

    public boolean hasAnnouncementsEnabled(UUID player) {
        if (SettingManager.useMetaPersistence()) {
            return RedisMetaCache.getAnnouncement(player);
        }
        return getCustomFile().getBoolean("players." + player.toString() + ".announcements", defaultAnnounce);
    }
    public void setAnnouncementsEnabled(UUID player, boolean enabled) {
        if (SettingManager.useMetaPersistence()) {
            MySQLMetaStore.upsertAnnouncement(player, enabled);
            RedisMetaCache.applyPrefsAnnouncement(player, enabled);
            if (SettingManager.useMetaRedisCache()) {
                RedisSyncManager.publishPrefsAnnouncement(player, enabled);
            }
            return;
        }
        if (defaultAnnounce != enabled) getCustomFile().set("players." + player + ".announcements", enabled);
        else getCustomFile().set("players." + player + ".announcements", null);
        save();
    }
    public boolean toggleAnnouncements(Player player) {
        boolean current = hasAnnouncementsEnabled(player.getUniqueId());
        setAnnouncementsEnabled(player.getUniqueId(), !current);
        return !current;
    }

    public void saveInstance(UUID player, AhConfiguration c) {
        if (c == null) return;
        String json = getGson().toJson(c);
        if (SettingManager.useMetaPersistence()) {
            MySQLMetaStore.upsertConfiguration(player, json);
            RedisMetaCache.applyPrefsConfiguration(player, json);
            if (SettingManager.useMetaRedisCache()) {
                RedisSyncManager.publishPrefsConfiguration(player, json);
            }
            return;
        }
        if (getCustomFile() == null) return;
        getCustomFile().set("players." + player + ".configuration", json);
        save();
    }
    public void loadInstance(Player p) {
        if (SettingManager.useMetaPersistence()) {
            String json = RedisMetaCache.getConfiguration(p.getUniqueId());
            AhConfiguration.loadInstance(p, getGson().fromJson(json, AhConfiguration.class));
            return;
        }
        AhConfiguration.loadInstance(p, getGson().fromJson(
                getCustomFile().getString("players." + p.getUniqueId() + ".configuration"),
                AhConfiguration.class));
    }

    @Override
    public void setup() {
        for(Player p : Bukkit.getOnlinePlayers()) {
            loadInstance(p);
        }
    }
    public void disable() {
        for(Player p : Bukkit.getOnlinePlayers()) {
            saveInstance(p.getUniqueId(), AhConfiguration.getInstance(p));
        }
    }

    private Gson getGson() {
        return new GsonBuilder()
                .registerTypeAdapterFactory(new TypeAdapterFactory() {
                    @Override
                    public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
                        TypeAdapter<T> delegate = gson.getDelegateAdapter(this, type);
                        TypeAdapter<JsonElement> elementAdapter = gson.getAdapter(JsonElement.class);

                        return new TypeAdapter<>() {
                            @Override
                            public void write(JsonWriter out, T value) throws IOException {
                                JsonElement tree = delegate.toJsonTree(value);

                                if (tree.isJsonObject()) {
                                    JsonObject obj = tree.getAsJsonObject();
                                    obj.entrySet().removeIf(entry -> {
                                        JsonElement e = entry.getValue();
                                        if (!e.isJsonPrimitive()) return false;
                                        JsonPrimitive p = e.getAsJsonPrimitive();
                                        if (p.isBoolean()) {return !p.getAsBoolean();}
                                        if (p.isNumber()) {return p.getAsNumber().doubleValue() == 0;}
                                        if (p.isString()) {return p.getAsString().isEmpty();}
                                        return false;
                                    });
                                }

                                elementAdapter.write(out, tree);
                            }


                            @Override
                            public T read(JsonReader in) throws IOException {
                                return delegate.read(in);
                            }
                        };
                    }
                })
                .create();
    }
}