package me.elaineqheart.auctionHouse.data.persistentStorage.local.configs;

import me.elaineqheart.auctionHouse.AuctionHouse;
import me.elaineqheart.auctionHouse.data.persistentStorage.database.MySQLMetaStore;
import me.elaineqheart.auctionHouse.data.persistentStorage.database.RedisMetaCache;
import me.elaineqheart.auctionHouse.data.persistentStorage.database.RedisSyncManager;
import me.elaineqheart.auctionHouse.data.persistentStorage.local.SettingManager;
import me.elaineqheart.auctionHouse.data.persistentStorage.local.data.Config;
import me.elaineqheart.auctionHouse.world.displays.DisplayListener;
import me.elaineqheart.auctionHouse.world.displays.DisplayNote;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Item;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Cross-server world-display registry.
 *
 * <p>Each display records the location and the UUIDs of the four entities
 * that make it up (BlockDisplay, Interaction, Item, TextDisplay). The data
 * is stored in MySQL ({@code ah_displays}) and mirrored in memory by
 * {@link RedisMetaCache}, with {@link RedisSyncManager} broadcasting changes
 * so displays placed on one server can be rebuilt on others when those
 * servers boot.</p>
 */
public class Displays extends Config {

    public void addDisplay(int id, DisplayNote note) {
        if (SettingManager.useMetaPersistence()) {
            MySQLMetaStore.DisplayRow row = toRow(id, note);
            MySQLMetaStore.upsertDisplay(id, row);
            RedisMetaCache.applyDisplayUpsert(id, row);
            if (SettingManager.useMetaRedisCache()) {
                RedisSyncManager.publishDisplayUpsert(row);
            }
            return;
        }
        ConfigurationSection sec = getYmlData();
        sec.set(id + ".location", note.location);
        sec.set(id + ".rank", note.rank);
        sec.set(id + ".sortType", note.sortType);
        sec.set(id + ".glassUUID", note.glassUUID == null ? null : note.glassUUID.toString());
        sec.set(id + ".interactionUUID", note.interactionUUID == null ? null : note.interactionUUID.toString());
        sec.set(id + ".itemUUID", note.itemUUID == null ? null : note.itemUUID.toString());
        sec.set(id + ".textUUID", note.textUUID == null ? null : note.textUUID.toString());
        save();
    }

    public void removeDisplay(int id) {
        if (SettingManager.useMetaPersistence()) {
            MySQLMetaStore.deleteDisplay(id);
            RedisMetaCache.applyDisplayDelete(id);
            if (SettingManager.useMetaRedisCache()) {
                RedisSyncManager.publishDisplayDelete(id);
            }
            return;
        }
        getYmlData().set(String.valueOf(id), null);
        save();
    }

    public void updateDisplay(int id, DisplayNote note) {
        addDisplay(id, note);
    }

    public HashMap<Integer, DisplayNote> getNotes() {
        HashMap<Integer, DisplayNote> out = new HashMap<>();
        if (SettingManager.useMetaPersistence()) {
            for (Map.Entry<Integer, MySQLMetaStore.DisplayRow> e : RedisMetaCache.getAllDisplays().entrySet()) {
                int id = e.getKey();
                MySQLMetaStore.DisplayRow row = e.getValue();
                DisplayNote note = fromRow(row);
                if (note != null && note.location.getWorld() == null) {
                    removeDisplay(id);
                    continue;
                }
                if (note != null) out.put(id, note);
            }
            return out;
        }
        ConfigurationSection sec = getYmlData();
        for (String key : sec.getKeys(false)) {
            int id = Integer.parseInt(key);
            DisplayNote note = getNote(key);
            if (note != null && note.location.getWorld() == null) {
                removeDisplay(id);
                save();
                continue;
            }
            notes_put(out, id, note);
        }
        return out;
    }

    private static void notes_put(HashMap<Integer, DisplayNote> map, int id, DisplayNote note) {
        if (note != null) map.put(id, note);
    }

    public DisplayNote getNote(String id) {
        if (SettingManager.useMetaPersistence()) {
            try {
                int displayId = Integer.parseInt(id);
                MySQLMetaStore.DisplayRow row = RedisMetaCache.getDisplay(displayId);
                if (row == null) return null;
                return fromRow(row);
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        ConfigurationSection sec = getYmlData();
        if (sec.contains(id + ".location")) {
            DisplayNote note = new DisplayNote();
            note.location = sec.getLocation(id + ".location");
            note.rank = sec.getInt(id + ".rank");
            note.sortType = sec.getString(id + ".sortType");
            note.glassUUID = UUID.fromString(sec.getString(id + ".glassUUID"));
            note.interactionUUID = UUID.fromString(sec.getString(id + ".interactionUUID"));
            if (sec.contains(id + ".itemUUID")) {
                String itemUUID = sec.getString(id + ".itemUUID");
                note.itemUUID = itemUUID == null ? null : UUID.fromString(itemUUID);
            }
            if (sec.contains(id + ".textUUID")) {
                String textUUID = sec.getString(id + ".textUUID");
                note.textUUID = textUUID == null ? null : UUID.fromString(textUUID);
            }
            return note;
        }
        if (AuctionHouse.isFolia()) return null;

        Location loc = sec.getLocation(id);
        if (loc != null) {
            DisplayNote note = retrieveDataBackwardsCompatibility(loc, Integer.parseInt(id));
            if (note != null) return note;
            note = new DisplayNote();
            note.location = loc;
            return note;
        }
        return null;
    }

    public void backwardsCompatibility() {
        // Only meaningful for the YAML path; the MySQL path already
        // populates `ah_displays` from authoritative data.
        if (SettingManager.useMetaPersistence()) return;
        Set<Integer> oldSet = null;
        ConfigurationSection customFile = getCustomFile();
        try {
            oldSet = customFile.getKeys(false).stream()
                    .map(Integer::parseInt)
                    .collect(java.util.stream.Collectors.toSet());
        } catch (NumberFormatException ignored) {}

        if (customFile.getConfigurationSection("displays") == null) {
            customFile.createSection("displays");
        }
        if (oldSet != null) {
            for (Integer displayID : oldSet) {
                ConfigurationSection displaysSec = customFile.getConfigurationSection("displays");
                if (displaysSec != null) {
                    displaysSec.set(String.valueOf(displayID), customFile.get(String.valueOf(displayID)));
                }
                customFile.set(String.valueOf(displayID), null);
            }
        }
        save();
    }

    private DisplayNote retrieveDataBackwardsCompatibility(Location loc, int id) {
        DisplayNote data = new DisplayNote();
        data.location = loc;
        BlockDisplay glass = null;
        Interaction interaction = null;
        Item itemEntity = null;
        TextDisplay text = null;
        assert loc.getWorld() != null;

        for (Entity glassTest : loc.getWorld().getNearbyEntities(loc, 1, 1, 1)) {
            if (isDisplayGlass(glassTest)) glass = (BlockDisplay) glassTest;
        }
        if (glass == null) return null;
        data.glassUUID = glass.getUniqueId();
        data.sortType = getType(glass);
        data.rank = getRank(glass, data.sortType);

        for (Entity interactionTest : loc.getWorld().getNearbyEntities(loc.clone().add(0.2, 1, 0.2), 1, 1, 1)) {
            if (DisplayListener.isDisplayInteraction(interactionTest)) interaction = (Interaction) interactionTest;
        }
        if (interaction == null) return null;
        data.interactionUUID = interaction.getUniqueId();

        for (Entity itemTest : loc.getWorld().getNearbyEntities(loc.clone().add(0.5, 0.5, 0.5), 1, 1, 1)) {
            if (isDisplayItem(itemTest)) itemEntity = (Item) itemTest;
        }
        if (itemEntity != null) {
            data.itemUUID = itemEntity.getUniqueId();
        }

        for (Entity TextTest : loc.getWorld().getNearbyEntities(loc.clone().add(0.5, 1.9, 0.5), 1, 1, 1)) {
            if (isTextDisplay(TextTest)) text = (TextDisplay) TextTest;
        }
        if (text != null) {
            data.textUUID = text.getUniqueId();
        }
        addDisplay(id, data);
        AuctionHouse.getInstance().getLogger().info("Successfully transferred old display data to the new system at location: " + loc);
        return data;
    }

    private static boolean isDisplayItem(Entity entity) {
        if (entity instanceof Item item) {
            return item.getPersistentDataContainer().has(new NamespacedKey(AuctionHouse.getInstance(), "display_item"), PersistentDataType.BOOLEAN);
        }
        return false;
    }

    private static boolean isTextDisplay(Entity entity) {
        if (entity instanceof TextDisplay text) {
            return text.getPersistentDataContainer().has(new NamespacedKey(AuctionHouse.getInstance(), "display_text"), PersistentDataType.BOOLEAN);
        }
        return false;
    }

    private static String getType(BlockDisplay entity) {
        if (entity.getPersistentDataContainer().has(new NamespacedKey(AuctionHouse.getInstance(), "highest_price"), PersistentDataType.INTEGER)) return "highest_price";
        else if (entity.getPersistentDataContainer().has(new NamespacedKey(AuctionHouse.getInstance(), "ending_soon"), PersistentDataType.INTEGER)) return "ending_soon";
        return null;
    }
    private static int getRank(BlockDisplay entity, String sortType) {
        return entity.getPersistentDataContainer()
                .get(new NamespacedKey(AuctionHouse.getInstance(), sortType), PersistentDataType.INTEGER);
    }

    public static boolean isDisplayGlass(Entity entity) {
        if (entity instanceof BlockDisplay display) {
            return display.getPersistentDataContainer()
                    .has(new NamespacedKey(AuctionHouse.getInstance(), "highest_price"), PersistentDataType.INTEGER) ||
                    display.getPersistentDataContainer().has(new NamespacedKey(AuctionHouse.getInstance(), "ending_soon"), PersistentDataType.INTEGER);
        }
        return false;
    }

    // ---- row <-> DisplayNote conversions ----

    private static MySQLMetaStore.DisplayRow toRow(int id, DisplayNote note) {
        Location loc = note.location;
        return new MySQLMetaStore.DisplayRow(
                id,
                loc == null || loc.getWorld() == null ? "world" : loc.getWorld().getName(),
                loc == null ? 0 : loc.getBlockX() + 0.5,
                loc == null ? 0 : loc.getY(),
                loc == null ? 0 : loc.getBlockZ() + 0.5,
                loc == null ? 0 : loc.getYaw(),
                loc == null ? 0 : loc.getPitch(),
                note.rank,
                note.sortType,
                note.glassUUID == null ? null : note.glassUUID.toString(),
                note.interactionUUID == null ? null : note.interactionUUID.toString(),
                note.itemUUID == null ? null : note.itemUUID.toString(),
                note.textUUID == null ? null : note.textUUID.toString(),
                SettingManager.serverId);
    }

    private static DisplayNote fromRow(MySQLMetaStore.DisplayRow row) {
        DisplayNote note = new DisplayNote();
        org.bukkit.World world = Bukkit.getWorld(row.world);
        if (world == null) return null;
        note.location = new Location(world, row.x, row.y, row.z, row.yaw, row.pitch);
        note.rank = row.rank;
        note.sortType = row.sortType;
        if (row.glassUuid != null && !row.glassUuid.isEmpty()) note.glassUUID = UUID.fromString(row.glassUuid);
        if (row.interactionUuid != null && !row.interactionUuid.isEmpty()) note.interactionUUID = UUID.fromString(row.interactionUuid);
        if (row.itemUuid != null && !row.itemUuid.isEmpty()) note.itemUUID = UUID.fromString(row.itemUuid);
        if (row.textUuid != null && !row.textUuid.isEmpty()) note.textUUID = UUID.fromString(row.textUuid);
        return note;
    }

    // ---- YAML accessors for the JSON fallback path ----

    private ConfigurationSection getYmlData() {
        ConfigurationSection ymlData = getCustomFile().getConfigurationSection("displays");
        if (ymlData != null) return ymlData;
        getCustomFile().createSection("displays");
        save();
        return getCustomFile().getConfigurationSection("displays");
    }
}