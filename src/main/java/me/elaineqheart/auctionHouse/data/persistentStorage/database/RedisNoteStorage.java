package me.elaineqheart.auctionHouse.data.persistentStorage.database;

import com.google.gson.Gson;
import me.elaineqheart.auctionHouse.AuctionHouse;
import me.elaineqheart.auctionHouse.data.persistentStorage.local.SettingManager;
import me.elaineqheart.auctionHouse.data.ram.ItemNote;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;

import java.util.*;

/**
 * Fast-path key/value cache for {@link ItemNote} state. Backed by Redis 8.x.
 *
 * <p>The plugin uses Redis as a secondary cache on top of MySQL: writes go
 * through MySQL first (via {@link MySQLNoteStorage}) and then update Redis
 * via a small JSON snapshot so the other servers in the cluster can pull the
 * latest row into their own in-memory state without hitting MySQL.</p>
 *
 * <p>Because the snapshot is small (we use Base64-encoded item data,
 * already produced by {@link me.elaineqheart.auctionHouse.data.persistentStorage.ItemStackConverter}),
 * we keep it as a single key per note ({@code <prefix>note:<uuid>}) and
 * additionally maintain sorted-set indexes for fast scans:</p>
 * <ul>
 *     <li>{@code auction:byPrice}  &mdash; price -> noteId</li>
 *     <li>{@code auction:byDate}   &mdash; created epoch ms -> noteId</li>
 *     <li>{@code auction:byName}   &mdash; lowercased itemName + ":" + noteId</li>
 *     <li>{@code auction:player:<uuid>} &mdash; per-seller SET of noteIds</li>
 * </ul>
 */
public final class RedisNoteStorage {

    private static final Gson GSON = new Gson();

    private RedisNoteStorage() {}

    /** Persist a note to Redis. Always runs on the writer (origin) node. */
    public static void upsertNote(ItemNote note) {
        if (!RedisManager.isAvailable()) return;
        if (note == null) return;
        try (Jedis j = RedisManager.getResource()) {
            String noteKey = SettingManager.key("note:" + note.getNoteID());
            Map<String, String> hash = toHash(note);
            j.hset(noteKey, hash);

            // Live indexes. We keep them populated for every note (including
            // sold-but-claimable ones) so a fresh server joining the cluster
            // can rebuild its RAM mirror by reading either MySQL OR Redis
            // without losing state.
            try { j.zadd(SettingManager.key("byPrice"), note.getPrice(), note.getNoteID().toString()); }
            catch (Exception ignored) {}
            try {
                long dateMs = note.getDateCreated() == null ? System.currentTimeMillis()
                        : note.getDateCreated().getTime();
                j.zadd(SettingManager.key("byDate"), dateMs, note.getNoteID().toString());
            } catch (Exception ignored) {}
            try {
                String n = note.getItemName() == null ? "unknown" : note.getItemName();
                j.zadd(SettingManager.key("byName"), 0, n.toLowerCase(Locale.ROOT) + ":" + note.getNoteID());
            } catch (Exception ignored) {}
            if (note.getPlayerUUID() != null) {
                try {
                    j.sadd(SettingManager.key("player:" + note.getPlayerUUID()),
                            note.getNoteID().toString());
                } catch (Exception ignored) {}
            }
        } catch (Exception ex) {
            AuctionHouse.getInstance().getLogger().warning(
                    "Redis upsertNote failed (cache will heal on next access): " + ex.getMessage());
        }
    }

    public static void deleteNote(UUID noteId) {
        if (!RedisManager.isAvailable() || noteId == null) return;
        try (Jedis j = RedisManager.getResource()) {
            // Pull first to keep indexes clean.
            String noteKey = SettingManager.key("note:" + noteId);
            String itemName = j.hget(noteKey, "itemName");
            String playerId = j.hget(noteKey, "playerUUID");

            Pipeline p = j.pipelined();
            p.del(noteKey);
            p.zrem(SettingManager.key("byPrice"), noteId.toString());
            p.zrem(SettingManager.key("byDate"),  noteId.toString());
            if (itemName != null) {
                p.zrem(SettingManager.key("byName"),
                        itemName.toLowerCase(Locale.ROOT) + ":" + noteId);
            }
            if (playerId != null && !playerId.isEmpty()) {
                p.srem(SettingManager.key("player:" + playerId), noteId.toString());
            }
            // Always drop the bids list. Cheaper than a separate EXISTS check.
            p.del(SettingManager.key("bids:" + noteId));
            p.sync();
        } catch (Exception ex) {
            AuctionHouse.getInstance().getLogger().warning("Redis deleteNote failed: " + ex.getMessage());
        }
    }

    public static void upsertBids(UUID noteId, List<BidDto> bids) {
        if (!RedisManager.isAvailable() || noteId == null) return;
        try (Jedis j = RedisManager.getResource()) {
            String key = SettingManager.key("bids:" + noteId);
            // Drop and re-create — simple and works.
            j.del(key);
            if (bids == null) return;
            for (BidDto b : bids) {
                j.rpush(key, GSON.toJson(b));
            }
        } catch (Exception ex) {
            AuctionHouse.getInstance().getLogger().warning("Redis upsertBids failed: " + ex.getMessage());
        }
    }

    /** Returns ordered bid history, or empty list on miss. */
    public static List<BidDto> loadBids(UUID noteId) {
        if (!RedisManager.isAvailable() || noteId == null) return List.of();
        try (Jedis j = RedisManager.getResource()) {
            List<String> raw = j.lrange(SettingManager.key("bids:" + noteId), 0, -1);
            if (raw == null || raw.isEmpty()) return List.of();
            List<BidDto> out = new ArrayList<>(raw.size());
            for (String s : raw) out.add(GSON.fromJson(s, BidDto.class));
            return out;
        } catch (Exception ex) {
            AuctionHouse.getInstance().getLogger().warning("Redis loadBids failed: " + ex.getMessage());
            return List.of();
        }
    }

    /** Total number of notes in the live byPrice index. */
    public static long countAll() {
        if (!RedisManager.isAvailable()) return -1;
        try (Jedis j = RedisManager.getResource()) {
            return j.zcard(SettingManager.key("byPrice"));
        } catch (Exception ex) {
            return -1;
        }
    }

    public static int countByPlayer(UUID playerId) {
        if (!RedisManager.isAvailable() || playerId == null) return -1;
        try (Jedis j = RedisManager.getResource()) {
            long n = j.scard(SettingManager.key("player:" + playerId));
            return (int) n;
        } catch (Exception ex) {
            return -1;
        }
    }

    /** Returns note IDs in the requested slice of the appropriate index. */
    public static List<UUID> scanIds(String index, long start, long stop) {
        if (!RedisManager.isAvailable()) return List.of();
        try (Jedis j = RedisManager.getResource()) {
            List<String> raw = switch (index) {
                case "byPrice" -> j.zrange(SettingManager.key("byPrice"), start, stop);
                case "byDate"  -> j.zrange(SettingManager.key("byDate"),  start, stop);
                case "byName"  -> j.zrange(SettingManager.key("byName"),  start, stop).stream()
                        .map(s -> {
                            int idx = s.lastIndexOf(':');
                            return idx < 0 ? s : s.substring(idx + 1);
                        })
                        .toList();
                default -> List.<String>of();
            };
            List<UUID> out = new ArrayList<>(raw.size());
            for (String s : raw) {
                try {
                    out.add(UUID.fromString(stripPrefix(s, index)));
                } catch (Exception ignored) {}
            }
            return out;
        } catch (Exception ex) {
            return List.of();
        }
    }

    public static List<UUID> scanIdsReverse(String index, long start, long stop) {
        if (!RedisManager.isAvailable()) return List.of();
        try (Jedis j = RedisManager.getResource()) {
            List<String> raw;
            if ("byPrice".equals(index)) {
                raw = j.zrevrange(SettingManager.key("byPrice"), start, stop);
            } else {
                return List.of();
            }
            List<UUID> out = new ArrayList<>(raw.size());
            for (String s : raw) {
                try { out.add(UUID.fromString(s)); } catch (Exception ignored) {}
            }
            return out;
        } catch (Exception ex) {
            return List.of();
        }
    }

    public static Set<UUID> playerNoteIds(UUID playerId) {
        if (!RedisManager.isAvailable() || playerId == null) return Set.of();
        try (Jedis j = RedisManager.getResource()) {
            Set<String> ids = j.smembers(SettingManager.key("player:" + playerId));
            Set<UUID> out = new HashSet<>();
            if (ids == null) return out;
            for (String s : ids) {
                try { out.add(UUID.fromString(s)); } catch (Exception ignored) {}
            }
            return out;
        } catch (Exception ex) {
            return Set.of();
        }
    }

    /**
     * Read note row as a flat map. Items are still Base64 — deserialisation into
     * ItemStack happens in the facade so we don't pay for Bukkit server-thread
     * coupling here.
     */
    public static Map<String, String> loadHash(UUID noteId) {
        if (!RedisManager.isAvailable() || noteId == null) return Map.of();
        try (Jedis j = RedisManager.getResource()) {
            return j.hgetAll(SettingManager.key("note:" + noteId));
        } catch (Exception ex) {
            return Map.of();
        }
    }

    /** Replace the contents of one note from a hash that originated elsewhere (e.g. from MySQL). */
    public static void replaceHashFromMap(UUID noteId, Map<String, String> snapshot) {
        if (!RedisManager.isAvailable() || noteId == null) return;
        try (Jedis j = RedisManager.getResource()) {
            String noteKey = SettingManager.key("note:" + noteId);
            j.del(noteKey);
            if (snapshot != null && !snapshot.isEmpty()) {
                j.hset(noteKey, snapshot);
                String price = snapshot.get("price");
                String itemName = snapshot.get("itemName");
                String playerId = snapshot.get("playerUUID");
                String date = snapshot.get("dateCreated");
                if (price != null && !price.isEmpty()) {
                    try { j.zadd(SettingManager.key("byPrice"),
                            Double.parseDouble(price), noteId.toString()); }
                    catch (Exception ignored) {}
                }
                if (date != null && !date.isEmpty()) {
                    try { j.zadd(SettingManager.key("byDate"),
                            Long.parseLong(date), noteId.toString()); }
                    catch (Exception ignored) {}
                }
                if (itemName != null && !itemName.isEmpty()) {
                    j.zadd(SettingManager.key("byName"), 0,
                            itemName.toLowerCase(Locale.ROOT) + ":" + noteId);
                }
                if (playerId != null && !playerId.isEmpty()) {
                    j.sadd(SettingManager.key("player:" + playerId), noteId.toString());
                }
            }
        } catch (Exception ex) {
            AuctionHouse.getInstance().getLogger().warning(
                    "Redis replaceHashFromMap failed: " + ex.getMessage());
        }
    }

    /**
     * Wipe ALL auction-related Redis keys under the current {@code key-prefix}.
     * Used by {@link ItemNoteStorage#purge()} so we don't accidentally drop
     * keys owned by another application that happens to share the Redis DB.
     */
    public static void purgeAll() {
        if (!RedisManager.isAvailable()) return;
        try (Jedis j = RedisManager.getResource()) {
            // SCAN is preferable to KEYS for large datasets, but for the
            // purge path the user explicitly asked us to drop everything
            // so a key enumeration is fine.
            Set<String> keys = j.keys(SettingManager.redisKeyPrefix + "*");
            if (keys != null && !keys.isEmpty()) {
                j.del(keys.toArray(new String[0]));
            }
        } catch (Exception ignored) {}
    }

    /**
     * Iterate the byPrice index and rebuild every note hash from a snapshot.
     * Used at startup after {@link ItemNoteStorage#loadNotes()} hydrated RAM
     * so the cross-server subscriber can immediately serve cache hits without
     * round-tripping MySQL.
     */
    public static int rebuildFromRam(java.util.List<ItemNote> all) {
        if (!RedisManager.isAvailable()) return 0;
        if (all == null || all.isEmpty()) return 0;
        int written = 0;
        try (Jedis j = RedisManager.getResource()) {
            // Drop the four live indexes so we start from a clean slate.
            j.del(SettingManager.key("byPrice"));
            j.del(SettingManager.key("byDate"));
            j.del(SettingManager.key("byName"));
            // Player index has one SET per UUID; nuke it via SCAN so we don't
            // accidentally wipe keys owned by another application.
            java.util.Set<String> playerKeys = j.keys(SettingManager.key("player:*"));
            if (playerKeys != null && !playerKeys.isEmpty()) {
                j.del(playerKeys.toArray(new String[0]));
            }
            for (ItemNote note : all) {
                if (note == null) continue;
                try {
                    j.hset(SettingManager.key("note:" + note.getNoteID()), toHash(note));
                    j.zadd(SettingManager.key("byPrice"), note.getPrice(),
                            note.getNoteID().toString());
                    long dateMs = note.getDateCreated() == null
                            ? System.currentTimeMillis() : note.getDateCreated().getTime();
                    j.zadd(SettingManager.key("byDate"), dateMs, note.getNoteID().toString());
                    String n = note.getItemName() == null ? "unknown" : note.getItemName();
                    j.zadd(SettingManager.key("byName"), 0,
                            n.toLowerCase(Locale.ROOT) + ":" + note.getNoteID());
                    if (note.getPlayerUUID() != null) {
                        j.sadd(SettingManager.key("player:" + note.getPlayerUUID()),
                                note.getNoteID().toString());
                    }
                    written++;
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return written;
    }

    /** Public helper so the sync layer can serialise/deserialise bid entries. */
    public static String toJson(Object o) { return GSON.toJson(o); }
    public static <T> T fromJson(String s, Class<T> t) { return GSON.fromJson(s, t); }

    private static String stripPrefix(String entry, String index) {
        // For byName we already strip in scanIds(...). The byPrice/byDate members are
        // the bare noteId.
        if ("byName".equals(index)) {
            int idx = entry.lastIndexOf(':');
            return idx < 0 ? entry : entry.substring(idx + 1);
        }
        return entry;
    }

    private static Map<String, String> toHash(ItemNote note) {
        Map<String, String> data = new HashMap<>();
        data.put("noteId", note.getNoteID().toString());
        data.put("itemName", note.getItemName() == null ? "" : note.getItemName());
        data.put("playerName", note.getPlayerName() == null ? "" : note.getPlayerName());
        data.put("buyerName", note.getBuyerName() == null ? "" : note.getBuyerName());
        data.put("buyerUUID", note.getBuyerUUID() == null ? "" : note.getBuyerUUID().toString());
        data.put("playerUUID", note.getPlayerUUID().toString());
        data.put("price", String.valueOf(note.getPrice()));
        data.put("dateCreated", String.valueOf(note.getDateCreated().getTime()));
        data.put("itemData", note.getItemData() == null ? "" : note.getItemData());
        data.put("isSold", String.valueOf(note.isSold()));
        data.put("partiallySoldAmountLeft", String.valueOf(note.getPartiallySoldAmountLeft()));
        data.put("adminMessage", note.getAdminMessage() == null ? "" : note.getAdminMessage());
        data.put("auctionTime", String.valueOf(note.getAuctionTimeSnapshot()));
        data.put("isBidAuction", String.valueOf(note.isBIDAuction()));
        // amount is derived, but cache it so the read path doesn't need to decode the item.
        try {
            ItemStack item = note.getItem();
            data.put("amount", item == null ? "0" : String.valueOf(item.getAmount()));
        } catch (Throwable ignored) {
            data.put("amount", "0");
        }
        return data;
    }

    /** Light DTO so we can rebuild bid history across processes without Bukkit types. */
    public static final class BidDto {
        public String playerId;
        public String playerName;
        public double price;
        public long time;

        public BidDto() {}

        public BidDto(String playerId, String playerName, double price, long time) {
            this.playerId = playerId;
            this.playerName = playerName;
            this.price = price;
            this.time = time;
        }
    }
}
