package me.elaineqheart.auctionHouse.data.persistentStorage.database;

import com.google.gson.Gson;
import me.elaineqheart.auctionHouse.AuctionHouse;
import me.elaineqheart.auctionHouse.data.persistentStorage.ItemStackConverter;
import me.elaineqheart.auctionHouse.data.persistentStorage.ItemNoteStorage;
import me.elaineqheart.auctionHouse.data.persistentStorage.local.SettingManager;
import me.elaineqheart.auctionHouse.data.persistentStorage.local.configs.M;
import me.elaineqheart.auctionHouse.data.persistentStorage.local.data.ConfigManager;
import me.elaineqheart.auctionHouse.data.ram.AuctionHouseStorage;
import me.elaineqheart.auctionHouse.data.ram.Bid;
import me.elaineqheart.auctionHouse.data.ram.ItemNote;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import redis.clients.jedis.JedisPubSub;

import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Multi-server real-time synchronisation layer.
 *
 * <p>Each plugin instance publishes small JSON envelopes to a Redis pub/sub
 * channel. Every subscriber (including other servers pointed at the same
 * Redis) receives the envelope via a dedicated subscriber thread (required
 * because Jedis blocks the caller during {@code SUBSCRIBE}).</p>
 *
 * <p>This guarantees that every player's auction appears on every server
 * the instant it is created, with no polling and no race conditions:</p>
 * <ul>
 *     <li>Server A creates an auction → publishes {@link EventType#UPSERT}
 *         (with a snapshot hash).</li>
 *     <li>Server A also writes the row to MySQL.</li>
 *     <li>Servers B, C, D receive the broadcast → pull any data they don't
 *         already have → update their local RAM copies.</li>
 * </ul>
 *
 * <p>The MySQL row is the durable source of truth. A subscriber that joins
 * the cluster later catches up simply by {@code loadNotes()} + meta
 * hydration on enable.</p>
 *
 * <p>The same envelope format is used for every meta dataset (blacklist,
 * bans, categories, player preferences, displays, permissions, transaction
 * log). The {@code type} field discriminates between them and the
 * {@code payload} field carries an arbitrary JSON object (encoded as a
 * string so the envelope itself stays generic Gson).</p>
 */
public final class RedisSyncManager {

    private static final AtomicBoolean SUBSCRIBER_STARTED = new AtomicBoolean(false);
    private static SubscriberThread SUBSCRIBER;
    private static final Gson GSON = new Gson();

    private RedisSyncManager() {}

    public enum EventType {
        // ----- auctions -----
        UPSERT,
        DELETE,
        BID_REPLACE,
        /** A new node joined and wants everyone to re-broadcast their state. */
        BULK_REQUEST,
        // ----- meta data -----
        BLACKLIST_ADD,
        BLACKLIST_POP,
        BLACKLIST_REPLACE,
        BAN_UPSERT,
        BAN_DELETE,
        CATEGORY_UPSERT,
        PREFS_ANNOUNCEMENT,
        PREFS_CONFIGURATION,
        DISPLAY_UPSERT,
        DISPLAY_DELETE,
        PERMISSION_UPSERT,
        PERMISSION_DELETE,
        CATEGORIES_REPLACE,
        CATEGORY_MATERIAL_UPSERT,
        CATEGORY_MATERIAL_DELETE,
        LOG_APPEND,
        // ----- cross-server chat broadcast -----
        /** Broadcast a fully-formatted chat message to every online player on every server. */
        CHAT_BROADCAST,
        /** Broadcast a chat message targeted at one specific player across the cluster. */
        CHAT_PRIVATE
    }

    public static final class Event {
        public String type;             // discriminator (see EventType names)
        public String originServer;    // id of the publisher; ignored on the loopback
        public String noteId;          // UUID of the affected note (or null for bulk)
        public Map<String, String> hash;     // present for UPSERT
        public List<RedisNoteStorage.BidDto> bids; // present for BID_REPLACE
        /** Optional JSON-encoded payload for meta events. Always a String to
         *  stay compatible with the {@code <T> GSON.fromJson(String, Type)} API. */
        public String payload;

        public Event() {}

        public Event(String type, String noteId) {
            this.type = type;
            this.noteId = noteId;
            this.originServer = SettingManager.serverId;
        }
    }

    /**
     * Start the subscriber thread. Must only be called after the Jedis pool is up
     * and after {@link ItemNoteStorage#loadNotes()} has primed local RAM so the
     * loopback handler can no-op safely.
     */
    public static void start() {
        if (!SettingManager.useRedisCache()) return;
        if (!SettingManager.redisPubsubEnabled) return;
        if (!RedisManager.isAvailable()) return;
        if (!SUBSCRIBER_STARTED.compareAndSet(false, true)) return;

        SUBSCRIBER = new SubscriberThread();
        SUBSCRIBER.setDaemon(true);
        SUBSCRIBER.setName("AuctionHouse-RedisSub");
        SUBSCRIBER.start();
    }

    public static void shutdown() {
        if (SUBSCRIBER != null) SUBSCRIBER.requestStop();
        SUBSCRIBER_STARTED.set(false);
    }

    /** Broadcast a UPSERT (create or update) to other servers. */
    public static void publishUpsert(ItemNote note, Map<String, String> hash) {
        if (!SettingManager.useRedisCache()) return;
        if (!SettingManager.redisPubsubEnabled) return;
        Event e = new Event(EventType.UPSERT.name(), note.getNoteID().toString());
        e.hash = hash;
        publish(e);
    }

    /** Broadcast a DELETE to other servers. */
    public static void publishDelete(UUID noteId) {
        if (!SettingManager.useRedisCache()) return;
        if (!SettingManager.redisPubsubEnabled) return;
        publish(new Event(EventType.DELETE.name(), noteId.toString()));
    }

    /** Broadcast a bid-history replacement. */
    public static void publishBidReplace(UUID noteId, List<RedisNoteStorage.BidDto> bids) {
        if (!SettingManager.useRedisCache()) return;
        if (!SettingManager.redisPubsubEnabled) return;
        Event e = new Event(EventType.BID_REPLACE.name(), noteId.toString());
        e.bids = bids;
        publish(e);
    }

    /**
     * Broadcast a "please re-send your state" ping. Every other server in
     * the cluster will iterate its in-memory notes and re-emit UPSERTs.
     * Called on startup after {@code loadFromMysql()} so that a brand-new
     * node — or a node that has been offline for a while — converges with
     * the rest of the cluster without needing a manual reload.
     */
    public static void publishBulkRequest() {
        if (!SettingManager.useRedisCache()) return;
        if (!SettingManager.redisPubsubEnabled) return;
        publish(new Event(EventType.BULK_REQUEST.name(), null));
    }

    // ----- Meta event publishers -----

    public static void publishBlacklistAdd(long id, String ruleType, String key) {
        Event e = new Event(EventType.BLACKLIST_ADD.name(), null);
        e.payload = GSON.toJson(new String[]{String.valueOf(id), ruleType, key});
        publish(e);
    }
    public static void publishBlacklistPop() {
        Event e = new Event(EventType.BLACKLIST_POP.name(), null);
        publish(e);
    }
    public static void publishBlacklistReplace(List<MySQLMetaStore.BlacklistRule> list) {
        Event e = new Event(EventType.BLACKLIST_REPLACE.name(), null);
        e.payload = GSON.toJson(list);
        publish(e);
    }
    public static void publishBanUpsert(UUID uuid, String name, long banEndMs, String reason) {
        Event e = new Event(EventType.BAN_UPSERT.name(), null);
        e.payload = GSON.toJson(new String[]{uuid.toString(), name, String.valueOf(banEndMs), reason == null ? "" : reason});
        publish(e);
    }
    public static void publishBanDelete(UUID uuid) {
        Event e = new Event(EventType.BAN_DELETE.name(), null);
        e.payload = uuid.toString();
        publish(e);
    }
    public static void publishCategoryUpsert(String letter, List<Map<String, Object>> payload) {
        Event e = new Event(EventType.CATEGORY_UPSERT.name(), null);
        // Two-element payload: [letter, json]
        e.payload = GSON.toJson(new String[]{letter, GSON.toJson(payload)});
        publish(e);
    }
    public static void publishPrefsAnnouncement(UUID uuid, boolean enabled) {
        Event e = new Event(EventType.PREFS_ANNOUNCEMENT.name(), null);
        e.payload = GSON.toJson(new String[]{uuid.toString(), String.valueOf(enabled)});
        publish(e);
    }
    public static void publishPrefsConfiguration(UUID uuid, String configurationJson) {
        Event e = new Event(EventType.PREFS_CONFIGURATION.name(), null);
        e.payload = GSON.toJson(new String[]{uuid.toString(), configurationJson == null ? "" : configurationJson});
        publish(e);
    }
    public static void publishDisplayUpsert(MySQLMetaStore.DisplayRow row) {
        Event e = new Event(EventType.DISPLAY_UPSERT.name(), null);
        e.payload = GSON.toJson(row);
        publish(e);
    }
    public static void publishDisplayDelete(int id) {
        Event e = new Event(EventType.DISPLAY_DELETE.name(), null);
        e.payload = String.valueOf(id);
        publish(e);
    }
    public static void publishPermissionUpsert(String permType, String node, long value) {
        Event e = new Event(EventType.PERMISSION_UPSERT.name(), null);
        e.payload = GSON.toJson(new String[]{permType, node, String.valueOf(value)});
        publish(e);
    }
    public static void publishPermissionDelete(String permType, String node) {
        Event e = new Event(EventType.PERMISSION_DELETE.name(), null);
        e.payload = GSON.toJson(new String[]{permType, node});
        publish(e);
    }

    public static void publishCategoriesReplace(List<String> categories) {
        Event e = new Event(EventType.CATEGORIES_REPLACE.name(), null);
        e.payload = GSON.toJson(categories);
        publish(e);
    }

    public static void publishCategoryMaterialUpsert(String category, String material) {
        Event e = new Event(EventType.CATEGORY_MATERIAL_UPSERT.name(), null);
        e.payload = GSON.toJson(new String[]{category, material});
        publish(e);
    }

    public static void publishCategoryMaterialDelete(String category, String material) {
        Event e = new Event(EventType.CATEGORY_MATERIAL_DELETE.name(), null);
        e.payload = GSON.toJson(new String[]{category, material});
        publish(e);
    }

    public static void publishLogAppend(MySQLMetaStore.LogRow row) {
        Event e = new Event(EventType.LOG_APPEND.name(), null);
        e.payload = GSON.toJson(row);
        publish(e);
    }

    /**
     * Broadcast a chat line to <b>every online player</b> on every server in the
     * cluster. The original sender (whose UUID we exclude so we don't double-
     * message them on their own server) is read from {@link SettingManager#serverId}
     * via the event envelope.
     *
     * <p>The {@code messageKey} is a dotted path inside {@code messages.yml};
     * each server resolves it against its own configuration so localised
     * servers still see the wording they expect.</p>
     *
     * <p>If Redis is unavailable the call silently no-ops so the local server
     * can still complete its own iteration of the recipients (callers typically
     * invoke this method and then also do a local broadcast).</p>
     */
    public static void publishChatBroadcast(String messageKey,
                                            List<String> placeholderNames,
                                            List<String> placeholderValues,
                                            UUID excludePlayer) {
        if (!SettingManager.useRedisCache()) return;
        if (!SettingManager.redisPubsubEnabled) return;
        if (!RedisManager.isAvailable()) return;

        ChatPayload payload = new ChatPayload();
        payload.messageKey = messageKey;
        payload.placeholderNames = placeholderNames == null ? List.of() : placeholderNames;
        payload.placeholderValues = placeholderValues == null ? List.of() : placeholderValues;
        payload.targetUuid = excludePlayer == null ? null : excludePlayer.toString();
        payload.originServer = SettingManager.serverId;

        Event e = new Event(EventType.CHAT_BROADCAST.name(), null);
        e.payload = GSON.toJson(payload);
        e.originServer = SettingManager.serverId;
        publish(e);
    }

    /**
     * Send a chat message to one specific player across every server in the
     * cluster. Useful for "you were out-bid" style notifications.
     *
     * <p>Resolves to {@code null} on servers where the player is not online;
     * the lookup itself is a no-op, so this stays cheap.</p>
     */
    public static void publishChatPrivate(UUID targetPlayer, String messageKey,
                                          List<String> placeholderNames,
                                          List<String> placeholderValues) {
        if (!SettingManager.useRedisCache()) return;
        if (!SettingManager.redisPubsubEnabled) return;
        if (!RedisManager.isAvailable()) return;
        if (targetPlayer == null) return;

        ChatPayload payload = new ChatPayload();
        payload.messageKey = messageKey;
        payload.placeholderNames = placeholderNames == null ? List.of() : placeholderNames;
        payload.placeholderValues = placeholderValues == null ? List.of() : placeholderValues;
        payload.targetUuid = targetPlayer.toString();
        payload.originServer = SettingManager.serverId;

        Event e = new Event(EventType.CHAT_PRIVATE.name(), null);
        e.payload = GSON.toJson(payload);
        e.originServer = SettingManager.serverId;
        publish(e);
    }

    /** Payload describing a single cross-server chat line. */
    public static final class ChatPayload {
        public String messageKey;
        public List<String> placeholderNames;
        public List<String> placeholderValues;
        /** When set, the receiving server excludes this UUID from a broadcast. */
        public String targetUuid;
        public String originServer;
    }

    private static void publish(Event e) {
        if (!RedisManager.isAvailable()) return;
        e.originServer = SettingManager.serverId;
        String json = GSON.toJson(e);
        try (var jedis = RedisManager.getResource()) {
            jedis.publish(SettingManager.channel(), json);
        } catch (Exception ex) {
            AuctionHouse.getInstance().getLogger().fine("Redis publish failed: " + ex.getMessage());
        }
    }

    /** Process an incoming event from another server on the Bukkit thread. */
    private static void handleEvent(String channel, String body) {
        Event e;
        try {
            e = GSON.fromJson(body, Event.class);
        } catch (Exception ex) {
            return;
        }
        if (e == null || e.type == null) return;
        // Filter out echoes of our own writes.
        if (SettingManager.serverId.equals(e.originServer)) return;

        UUID id;
        try { id = e.noteId == null ? null : UUID.fromString(e.noteId); } catch (Exception ex) { return; }

        EventType type;
        try { type = EventType.valueOf(e.type); } catch (IllegalArgumentException ex) { return; }

        switch (type) {
            case UPSERT -> {
                if (id == null || e.hash == null || e.hash.isEmpty()) return;
                runOnServerThread(() -> applyRemoteUpsert(id, e.hash));
            }
            case DELETE -> {
                if (id == null) return;
                runOnServerThread(() -> applyRemoteDelete(id));
            }
            case BID_REPLACE -> {
                if (id == null) return;
                runOnServerThread(() -> applyRemoteBidReplace(id, e.bids));
            }
            case BULK_REQUEST -> {
                // Another server (or us, on startup) wants us to re-broadcast
                // our in-memory state. We do that on the server thread so we
                // don't read the storage map from a non-Bukkit thread.
                runOnServerThread(RedisSyncManager::rebroadcastAll);
            }
            // ----- meta handlers -----
            case BLACKLIST_ADD       -> runOnServerThread(() -> applyRemoteBlacklistAdd(e.payload));
            case BLACKLIST_POP       -> runOnServerThread(RedisMetaCache::applyBlacklistPop);
            case BLACKLIST_REPLACE   -> runOnServerThread(() -> applyRemoteBlacklistReplace(e.payload));
            case BAN_UPSERT          -> runOnServerThread(() -> applyRemoteBanUpsert(e.payload));
            case BAN_DELETE          -> runOnServerThread(() -> applyRemoteBanDelete(e.payload));
            case CATEGORY_UPSERT     -> runOnServerThread(() -> applyRemoteCategoryUpsert(e.payload));
            case PREFS_ANNOUNCEMENT  -> runOnServerThread(() -> applyRemotePrefsAnnouncement(e.payload));
            case PREFS_CONFIGURATION -> runOnServerThread(() -> applyRemotePrefsConfiguration(e.payload));
            case DISPLAY_UPSERT      -> runOnServerThread(() -> applyRemoteDisplayUpsert(e.payload));
            case DISPLAY_DELETE      -> runOnServerThread(() -> applyRemoteDisplayDelete(e.payload));
            case PERMISSION_UPSERT   -> runOnServerThread(() -> applyRemotePermissionUpsert(e.payload));
            case PERMISSION_DELETE   -> runOnServerThread(() -> applyRemotePermissionDelete(e.payload));
            case CATEGORIES_REPLACE -> runOnServerThread(() -> applyRemoteCategoriesReplace(e.payload));
            case CATEGORY_MATERIAL_UPSERT -> runOnServerThread(() -> applyRemoteCategoryMaterialUpsert(e.payload));
            case CATEGORY_MATERIAL_DELETE -> runOnServerThread(() -> applyRemoteCategoryMaterialDelete(e.payload));
            case LOG_APPEND         -> runOnServerThread(() -> applyRemoteLogAppend(e.payload));
            case CHAT_BROADCAST     -> runOnServerThread(() -> applyRemoteChatBroadcast(e.payload));
            case CHAT_PRIVATE       -> runOnServerThread(() -> applyRemoteChatPrivate(e.payload));
        }
    }

    /**
     * Schedule a callback on the Bukkit/Folia main thread. Tries, in order:
     * <ol>
     *   <li>MorePaperLib's global regional scheduler via
     *       {@code plugin.getScheduler().globalRegionalScheduler().runDelayed(..., 1)}
     *       (Folia-safe; 1-tick delay is required by that API).</li>
     *   <li>The classic {@code Bukkit.getScheduler().runTask(...)} (Spigot /
     *       pre-Folia Paper).</li>
     * </ol>
     * If both fail (rare; only happens if neither Paper nor Folia nor
     * MorePaperLib is on the classpath, or the plugin is shutting down) we
     * log once and drop the event. A fresh subscriber catches up via
     * {@code loadNotes()} on startup, and the next mutation on the origin
     * server re-broadcasts, so a single dropped event is not catastrophic.
     */
    private static void runOnServerThread(Runnable r) {
        AuctionHouse plugin = AuctionHouse.getInstance();
        if (plugin == null) return;

        // 1) MorePaperLib — always safe under Folia because it uses
        //    `runDelayed(..., 1)` internally. A 1-tick delay is harmless for
        //    the cross-server cache.
        try {
            plugin.getScheduler().globalRegionalScheduler().runDelayed(r, 1L);
            return;
        } catch (Throwable ignored) {
            // No MorePaperLib, or the scheduler refused — fall through.
        }

        // 2) Classic Bukkit scheduler (Spigot / older Paper). On Folia this
        //    throws `UnsupportedOperationException`, which is the very error
        //    we are trying to avoid, so we catch and warn.
        try {
            Bukkit.getScheduler().runTask(plugin, r);
        } catch (Throwable t) {
            plugin.getLogger().warning(
                    "Failed to schedule sync event on the server thread: " + t.getMessage());
        }
    }

    private static void applyRemoteUpsert(UUID noteId, Map<String, String> hash) {
        // If we don't have this note locally, build it from the hash.
        ItemNote local = AuctionHouseStorage.getNote(noteId);
        Map<String, String> snapshot = new LinkedHashMap<>(hash);

        if (local == null) {
            // First try the in-band hash; if it's missing required fields
            // (typically a BID_REPLACE that arrived before the corresponding
            // UPSERT) we won't be able to rebuild a full ItemNote. We then
            // try to hydrate from MySQL, which is the durable source of truth,
            // before giving up.
            ItemNote rebuilt = rehydrateFromHash(noteId, snapshot);
            if (rebuilt == null) {
                rebuilt = loadFromMysqlFallback(noteId);
            }
            if (rebuilt != null) {
                AuctionHouseStorage.add(rebuilt);
            }
            // Cache locally.
            try {
                if (RedisManager.isAvailable()) {
                    RedisNoteStorage.replaceHashFromMap(noteId, snapshot);
                }
            } catch (Exception ignored) {}
        } else {
            // We already have a local copy; just refresh fields that may have
            // changed. Cheap, idempotent, safe.
            applyHashToLocal(local, snapshot);
        }
    }

    /**
     * Best-effort: pull a note row from MySQL and rebuild a {@link ItemNote}
     * (with its bid history) when we received an event for a note we don't
     * have in memory and the in-band hash is incomplete. Returns {@code null}
     * if MySQL is unavailable or the row is missing.
     */
    private static ItemNote loadFromMysqlFallback(UUID noteId) {
        try {
            if (!SettingManager.isMysqlPersistence()) return null;
            if (!MySQLManager.isAvailable()) return null;
            MySQLNoteStorage.NoteRow row = MySQLNoteStorage.loadNote(noteId);
            if (row == null) return null;
            ItemNote n = row.intoNote();
            for (MySQLNoteStorage.BidRow b : MySQLNoteStorage.loadBids(noteId)) {
                if (b.playerId == null) continue;
                n.appendBid(new Bid(b.playerId, b.playerName, new Date(b.time), b.price));
            }
            return n;
        } catch (Throwable t) {
            AuctionHouse.getInstance().getLogger().fine(
                    "loadFromMysqlFallback failed for " + noteId + ": " + t.getMessage());
            return null;
        }
    }

    private static void applyRemoteDelete(UUID noteId) {
        ItemNote local = AuctionHouseStorage.getNote(noteId);
        if (local != null) {
            AuctionHouseStorage.remove(local);
        }
        try {
            if (RedisManager.isAvailable()) RedisNoteStorage.deleteNote(noteId);
        } catch (Exception ignored) {}
    }

    private static void applyRemoteBidReplace(UUID noteId, List<RedisNoteStorage.BidDto> bids) {
        ItemNote local = AuctionHouseStorage.getNote(noteId);
        if (local == null) return;
        local.resetBidHistory();
        if (bids != null) {
            for (RedisNoteStorage.BidDto b : bids) {
                if (b.playerId == null) continue;
                local.addBidFromDto(b);
            }
        }
        try {
            if (RedisManager.isAvailable()) {
                RedisNoteStorage.upsertBids(noteId, bids == null ? List.of() : bids);
            }
        } catch (Exception ignored) {}
    }

    private static void applyHashToLocal(ItemNote note, Map<String, String> hash) {
        try {
            if (hash.containsKey("price")) note.setPrice(Double.parseDouble(hash.get("price")));
            if (hash.containsKey("isSold")) note.setSold(Boolean.parseBoolean(hash.get("isSold")));
            if (hash.containsKey("partiallySoldAmountLeft"))
                note.setPartiallySoldAmountLeft(Integer.parseInt(hash.get("partiallySoldAmountLeft")));
            if (hash.containsKey("adminMessage"))
                note.setAdminMessage(emptyToNull(hash.get("adminMessage")));
            if (hash.containsKey("auctionTime"))
                note.setAuctionTime(Long.parseLong(hash.get("auctionTime")));
            // Re-encode the item if its data changed.
            if (hash.containsKey("itemData")) {
                String newPayload = hash.get("itemData");
                if (!Objects.equals(newPayload, note.getItemData())) {
                    ItemStack decoded = ItemStackConverter.decode(newPayload);
                    if (decoded != null) {
                        note.setItem(decoded);
                    }
                }
            }
            // The display name follows the item payload (setItem() above
            // re-decodes the ItemStack and its ItemMeta, which is where the
            // name comes from). No separate refresh step is needed here.
        } catch (Exception ignored) {}
    }

    private static ItemNote rehydrateFromHash(UUID noteId, Map<String, String> hash) {
        try {
            UUID playerId = UUID.fromString(hash.getOrDefault("playerUUID", ""));
            UUID buyerId  = hash.get("buyerUUID") == null || hash.get("buyerUUID").isEmpty()
                    ? null : UUID.fromString(hash.get("buyerUUID"));
            String playerName = hash.getOrDefault("playerName", "");
            String buyerName  = hash.getOrDefault("buyerName", null);
            if (buyerName != null && buyerName.isEmpty()) buyerName = null;
            String itemName = hash.getOrDefault("itemName", "Unknown");
            double price = Double.parseDouble(hash.getOrDefault("price", "0"));
            long dateMs = Long.parseLong(hash.getOrDefault("dateCreated", "0"));
            boolean isBid = Boolean.parseBoolean(hash.getOrDefault("isBidAuction", "false"));
            boolean isSold = Boolean.parseBoolean(hash.getOrDefault("isSold", "false"));
            int partial = Integer.parseInt(hash.getOrDefault("partiallySoldAmountLeft", "0"));
            String admin = hash.getOrDefault("adminMessage", null);
            if (admin != null && admin.isEmpty()) admin = null;
            long auctionTime = Long.parseLong(hash.getOrDefault("auctionTime", "0"));
            String payload = hash.get("itemData");

            ItemNote note = new ItemNote(noteId, playerName, playerId,
                    buyerName, buyerId, itemName, price,
                    new java.util.Date(dateMs),
                    ItemStackConverter.decode(payload),
                    isBid, isSold, partial, admin, auctionTime);
            return note;
        } catch (Exception ex) {
            AuctionHouse.getInstance().getLogger().warning(
                    "Failed to rehydrate remote note " + noteId + ": " + ex.getMessage());
            return null;
        }
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    /**
     * Re-broadcast every note currently in RAM. Triggered in response to a
     * {@link EventType#BULK_REQUEST} ping, or via direct invocation. We
     * also push the bid history so subscribers can rebuild it without a
     * second round trip.
     */
    private static void rebroadcastAll() {
        List<ItemNote> all;
        try {
            all = AuctionHouseStorage.getAll();
        } catch (Throwable t) {
            return;
        }
        for (ItemNote note : all) {
            if (note == null) continue;
            try {
                publishUpsert(note, snapshotOf(note));
                if (note.hasBidHistory()) {
                    publishBidReplace(note.getNoteID(), bidsToDtos(note.getBidHistoryList()));
                }
            } catch (Throwable ignored) {}
        }
    }

    /**
     * Same shape as {@link ItemNoteStorage#snapshot(ItemNote)}; duplicated
     * here so this class has no compile-time dependency on the facade.
     */
    private static Map<String, String> snapshotOf(ItemNote note) {
        Map<String, String> data = new LinkedHashMap<>();
        // Coerce every value to a non-null String so Jedis does not throw
        // "name cannot be null" on partially-populated notes.
        data.put("noteId", note.getNoteID() == null ? "" : note.getNoteID().toString());
        data.put("itemName", note.getItemName() == null ? "" : note.getItemName());
        data.put("playerName", note.getPlayerName() == null ? "" : note.getPlayerName());
        data.put("buyerName", note.getBuyerName() == null ? "" : note.getBuyerName());
        data.put("buyerUUID", note.getBuyerUUID() == null ? "" : note.getBuyerUUID().toString());
        data.put("playerUUID", note.getPlayerUUID() == null ? "" : note.getPlayerUUID().toString());
        data.put("price", String.valueOf(note.getPrice()));
        data.put("dateCreated", String.valueOf(note.getDateCreated() == null ? 0L : note.getDateCreated().getTime()));
        data.put("itemData", note.getItemData() == null ? "" : note.getItemData());
        data.put("isSold", String.valueOf(note.isSold()));
        data.put("partiallySoldAmountLeft", String.valueOf(note.getPartiallySoldAmountLeft()));
        data.put("adminMessage", note.getAdminMessage() == null ? "" : note.getAdminMessage());
        data.put("auctionTime", String.valueOf(note.getAuctionTimeSnapshot()));
        data.put("isBidAuction", String.valueOf(note.isBIDAuction()));
        try {
            data.put("amount", String.valueOf(note.getItem().getAmount()));
        } catch (Throwable ignored) {
            data.put("amount", "0");
        }
        return data;
    }

    private static List<RedisNoteStorage.BidDto> bidsToDtos(List<Bid> bids) {
        List<RedisNoteStorage.BidDto> out = new ArrayList<>();
        if (bids == null) return out;
        for (Bid b : bids) {
            if (b.getPlayerID() == null) continue;
            out.add(new RedisNoteStorage.BidDto(
                    b.getPlayerID().toString(),
                    b.getPlayerName(),
                    b.getPrice(),
                    b.getTimeMs()));
        }
        return out;
    }

    // ====================================================================
    // Meta event decoders
    // ====================================================================

    private static void applyRemoteBlacklistAdd(String payload) {
        if (payload == null) return;
        try {
            String[] parts = GSON.fromJson(payload, String[].class);
            if (parts == null || parts.length < 3) return;
            long id = Long.parseLong(parts[0]);
            RedisMetaCache.applyBlacklistAdd(id, parts[1], parts[2]);
        } catch (Exception ignored) {}
    }

    private static void applyRemoteBlacklistReplace(String payload) {
        if (payload == null) return;
        try {
            java.lang.reflect.Type t = new com.google.gson.reflect.TypeToken<List<MySQLMetaStore.BlacklistRule>>() {}.getType();
            List<MySQLMetaStore.BlacklistRule> list = GSON.fromJson(payload, t);
            if (list != null) RedisMetaCache.applyBlacklistReplace(list);
        } catch (Exception ignored) {}
    }

    private static void applyRemoteBanUpsert(String payload) {
        if (payload == null) return;
        try {
            String[] parts = GSON.fromJson(payload, String[].class);
            if (parts == null || parts.length < 4) return;
            UUID uuid = UUID.fromString(parts[0]);
            long endMs = Long.parseLong(parts[2]);
            RedisMetaCache.applyBanUpsert(uuid, parts[1], endMs, parts[3].isEmpty() ? null : parts[3]);
        } catch (Exception ignored) {}
    }

    private static void applyRemoteBanDelete(String payload) {
        if (payload == null) return;
        try { RedisMetaCache.applyBanDelete(UUID.fromString(payload)); } catch (Exception ignored) {}
    }

    private static void applyRemoteCategoryUpsert(String payload) {
        // Kept for backwards compatibility — old events used a
        // per-letter payload; new events use the bulk CATEGORIES_REPLACE.
        if (payload == null) return;
        try {
            String[] parts = GSON.fromJson(payload, String[].class);
            if (parts == null || parts.length < 2) return;
            // Older clients used a per-letter payload which we no longer
            // support; treat it as a no-op to avoid corrupting the cache.
        } catch (Exception ignored) {}
    }

    private static void applyRemotePrefsAnnouncement(String payload) {
        if (payload == null) return;
        try {
            String[] parts = GSON.fromJson(payload, String[].class);
            if (parts == null || parts.length < 2) return;
            UUID uuid = UUID.fromString(parts[0]);
            RedisMetaCache.applyPrefsAnnouncement(uuid, Boolean.parseBoolean(parts[1]));
        } catch (Exception ignored) {}
    }

    private static void applyRemotePrefsConfiguration(String payload) {
        if (payload == null) return;
        try {
            String[] parts = GSON.fromJson(payload, String[].class);
            if (parts == null || parts.length < 2) return;
            UUID uuid = UUID.fromString(parts[0]);
            RedisMetaCache.applyPrefsConfiguration(uuid, parts[1].isEmpty() ? null : parts[1]);
        } catch (Exception ignored) {}
    }

    private static void applyRemoteDisplayUpsert(String payload) {
        if (payload == null) return;
        try {
            MySQLMetaStore.DisplayRow row = GSON.fromJson(payload, MySQLMetaStore.DisplayRow.class);
            if (row != null) RedisMetaCache.applyDisplayUpsert(row.id, row);
        } catch (Exception ignored) {}
    }

    private static void applyRemoteDisplayDelete(String payload) {
        if (payload == null) return;
        try { RedisMetaCache.applyDisplayDelete(Integer.parseInt(payload)); } catch (Exception ignored) {}
    }

    private static void applyRemotePermissionUpsert(String payload) {
        if (payload == null) return;
        try {
            String[] parts = GSON.fromJson(payload, String[].class);
            if (parts == null || parts.length < 3) return;
            long value = Long.parseLong(parts[2]);
            RedisMetaCache.applyPermissionUpsert(parts[0], parts[1], value);
        } catch (Exception ignored) {}
    }

    private static void applyRemotePermissionDelete(String payload) {
        if (payload == null) return;
        try {
            String[] parts = GSON.fromJson(payload, String[].class);
            if (parts == null || parts.length < 2) return;
            RedisMetaCache.applyPermissionDelete(parts[0], parts[1]);
        } catch (Exception ignored) {}
    }

    private static void applyRemoteCategoriesReplace(String payload) {
        if (payload == null) return;
        try {
            java.lang.reflect.Type t = new com.google.gson.reflect.TypeToken<List<String>>() {}.getType();
            List<String> list = GSON.fromJson(payload, t);
            if (list != null) RedisMetaCache.applyCategories(list);
        } catch (Exception ignored) {}
    }

    private static void applyRemoteCategoryMaterialUpsert(String payload) {
        if (payload == null) return;
        try {
            String[] parts = GSON.fromJson(payload, String[].class);
            if (parts == null || parts.length < 2) return;
            RedisMetaCache.applyCategoryMaterialUpsert(parts[0], parts[1]);
        } catch (Exception ignored) {}
    }

    private static void applyRemoteCategoryMaterialDelete(String payload) {
        if (payload == null) return;
        try {
            String[] parts = GSON.fromJson(payload, String[].class);
            if (parts == null || parts.length < 2) return;
            RedisMetaCache.applyCategoryMaterialDelete(parts[0], parts[1]);
        } catch (Exception ignored) {}
    }

    private static void applyRemoteLogAppend(String payload) {
        // Logs are durable in MySQL; no in-memory mirror for them right now.
        // The event exists so other servers can immediately refresh their
        // transaction-log GUI without polling the database.
        if (payload == null) return;
        try { GSON.fromJson(payload, MySQLMetaStore.LogRow.class); } catch (Exception ignored) {}
    }

    // ====================================================================
    // Cross-server chat handlers
    // ====================================================================

    /**
     * Broadcast a chat message to every online player on this server. We never
     * re-broadcast (the publisher is already broadcasting from its own loop);
     * the {@code originServer} field on the envelope lets us skip deliveries
     * received from outside the cluster accidentally.
     */
    private static void applyRemoteChatBroadcast(String payload) {
        if (payload == null) return;
        ChatPayload p;
        try { p = GSON.fromJson(payload, ChatPayload.class); }
        catch (Exception ex) { return; }
        if (p == null || p.messageKey == null) return;

        UUID exclude = null;
        if (p.targetUuid != null && !p.targetUuid.isEmpty()) {
            try { exclude = UUID.fromString(p.targetUuid); } catch (Exception ignored) {}
        }

        // Resolve and send on the current (Bukkit) thread.
        String formatted = renderChatMessage(p);
        if (formatted == null) return;
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (exclude != null && online.getUniqueId().equals(exclude)) continue;
            if (!ConfigManager.playerPreferences.hasAnnouncementsEnabled(online.getUniqueId())) continue;
            online.sendMessage(formatted);
        }
    }

    /**
     * Send a chat message to one specific player if they happen to be online
     * on this server. {@code null} UUIDs (parse errors) are ignored.
     */
    private static void applyRemoteChatPrivate(String payload) {
        if (payload == null) return;
        ChatPayload p;
        try { p = GSON.fromJson(payload, ChatPayload.class); }
        catch (Exception ex) { return; }
        if (p == null || p.messageKey == null || p.targetUuid == null) return;
        UUID target;
        try { target = UUID.fromString(p.targetUuid); }
        catch (Exception ex) { return; }
        Player online = Bukkit.getPlayer(target);
        if (online == null) return;
        String formatted = renderChatMessage(p);
        if (formatted == null) return;
        online.sendMessage(formatted);
    }

    /**
     * Resolve the message key against the receiver's {@code messages.yml},
     * applying positional placeholders. The placeholder names use Bukkit's
     * {@code %name%} convention so all the helper formatters in
     * {@link M#getFormatted(String, String...)} work unchanged.
     */
    private static String renderChatMessage(ChatPayload p) {
        try {
            int n = Math.min(
                    p.placeholderNames == null ? 0 : p.placeholderNames.size(),
                    p.placeholderValues == null ? 0 : p.placeholderValues.size());
            String[] params = new String[n * 2];
            int idx = 0;
            for (int i = 0; i < n; i++) {
                params[idx++] = p.placeholderNames.get(i);
                params[idx++] = p.placeholderValues.get(i);
            }
            return M.getFormatted(p.messageKey, params);
        } catch (Throwable t) {
            AuctionHouse.getInstance().getLogger().fine(
                    "Failed to render cross-server chat '" + p.messageKey + "': " + t.getMessage());
            return null;
        }
    }

    /** Dedicated blocking subscriber; runs on its own daemon thread. */
    private static final class SubscriberThread extends Thread {
        private volatile JedisPubSub listener;
        private volatile boolean stop;

        void requestStop() {
            stop = true;
            JedisPubSub ls = listener;
            if (ls != null) {
                try { ls.unsubscribe(); } catch (Exception ignored) {}
            }
            this.interrupt();
        }

        @Override
        public void run() {
            while (!stop && RedisManager.isAvailable()) {
                try (var jedis = RedisManager.getResource()) {
                    listener = new JedisPubSub() {
                        @Override
                        public void onMessage(String channel, String message) {
                            try { handleEvent(channel, message); } catch (Throwable t) {
                                t.printStackTrace();
                            }
                        }
                    };
                    jedis.subscribe(listener, SettingManager.channel());
                } catch (Throwable t) {
                    if (stop) return;
                    AuctionHouse.getInstance().getLogger().warning(
                            "Redis subscriber error, retrying in 5s: " + t.getMessage());
                    try { Thread.sleep(5_000); } catch (InterruptedException ie) {
                        return;
                    }
                }
            }
        }
    }
}