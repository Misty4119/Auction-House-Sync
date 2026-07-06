package me.elaineqheart.auctionHouse.data.persistentStorage;

import me.elaineqheart.auctionHouse.AuctionHouse;
import me.elaineqheart.auctionHouse.data.persistentStorage.database.MySQLManager;
import me.elaineqheart.auctionHouse.data.persistentStorage.database.MySQLNoteStorage;
import me.elaineqheart.auctionHouse.data.persistentStorage.database.RedisManager;
import me.elaineqheart.auctionHouse.data.persistentStorage.database.RedisNoteStorage;
import me.elaineqheart.auctionHouse.data.persistentStorage.database.RedisSyncManager;
import me.elaineqheart.auctionHouse.data.persistentStorage.local.SettingManager;
import me.elaineqheart.auctionHouse.data.persistentStorage.local.data.ConfigManager;
import me.elaineqheart.auctionHouse.data.persistentStorage.local.data.JsonNoteStorage;
import me.elaineqheart.auctionHouse.data.ram.AuctionHouseStorage;
import me.elaineqheart.auctionHouse.data.ram.Bid;
import me.elaineqheart.auctionHouse.data.ram.ItemManager;
import me.elaineqheart.auctionHouse.data.ram.ItemNote;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.util.*;
import java.util.logging.Logger;

/**
 * Storage facade.
 *
 * <p>This is the only class that the rest of the plugin talks to about
 * persistence. It hides the choice of backend:</p>
 * <ul>
 *   <li><b>persistence = JSON</b> (default if nothing configured): legacy
 *       per-server JSON file behaviour. The cluster sees per-server state
 *       only — there is no cross-server convergence.</li>
 *   <li><b>persistence = MYSQL</b>: every write goes to MySQL (HikariCP
 *       pool). Redis (when enabled) is used as a fast in-memory cache and
 *       publishes events to the other servers through pub/sub. On startup
 *       we hydrate RAM from MySQL; thereafter all reads come from RAM and
 *       all writes are mirrored to MySQL + Redis.</li>
 * </ul>
 *
 * <p>Reads are backed by the in-memory {@link AuctionHouseStorage}; the
 * database layer is consulted only at startup (and in the JSON fallback).
 * This keeps the gameplay code path identical regardless of backend.</p>
 */
public class ItemNoteStorage {

    private static final HashMap<UUID, ItemStack> items = new HashMap<>();

    public static ItemStack getItem(UUID itemNoteID) { return items.get(itemNoteID); }
    public static void addItem(UUID itemNoteID, ItemStack item) {
        if (itemNoteID == null || item == null) return;
        items.put(itemNoteID, item);
    }
    public static void removeItem(UUID itemNoteID) { items.remove(itemNoteID); }

    // ------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------

    public static void createNote(Player p, ItemStack item, double price, boolean isBIDAuction) {
        ItemNote itemNote = new ItemNote(p, item, price, isBIDAuction);
        ConfigManager.transactionLogger.logSetUpAuction(
                p.getDisplayName(),
                itemNote.getItemName(),
                price,
                item.getAmount(),
                isBIDAuction);

        persistUpsert(itemNote);
    }

    public static void saveNotes() throws IOException {
        if (SettingManager.isMysqlPersistence()) {
            // Single source of truth is MySQL; per-write upserts happen already.
            return;
        }
        JsonNoteStorage.saveNotes();
    }

    public static void loadNotes() throws IOException {
        if (ConfigManager.backwardsCompatibility()) JsonNoteStorage.backwardsCompatibility();
        if (SettingManager.isMysqlPersistence()) {
            // Hydrate from MySQL, but only on first ready; subsequent loads are no-ops.
            loadFromMysql();
        } else {
            JsonNoteStorage.loadNotes();
        }
        Logger log = AuctionHouse.getInstance() == null ? null : AuctionHouse.getInstance().getLogger();
        if (log != null) {
            log.info("[AuctionHouse] loadNotes() finished — RAM has "
                    + AuctionHouseStorage.getAll().size() + " items.");
        }
    }

    /**
     * One-time hydration at startup. Reads every note row from MySQL into the
     * shared {@link AuctionHouseStorage} so that every other subsystem (GUI,
     * displays, etc.) sees identical state.
     */
    private static void loadFromMysql() {
        if (!MySQLManager.isAvailable()) {
            AuctionHouse.getInstance().getLogger().warning(
                    "MySQL was selected as the backend but the pool is not available — " +
                            "falling back to an empty in-memory state.");
            return;
        }
        List<MySQLNoteStorage.NoteRow> rows = MySQLNoteStorage.loadAll();
        List<ItemNote> notes = new ArrayList<>(rows.size());
        for (MySQLNoteStorage.NoteRow row : rows) {
            try {
                ItemNote n = row.intoNote();
                // Load bid history — null playerUUID rows are tolerated so we
                // don't drop the whole note if a single bid is malformed.
                for (MySQLNoteStorage.BidRow b : MySQLNoteStorage.loadBids(n.getNoteID())) {
                    if (b.playerId == null) continue;
                    try {
                        n.appendBid(new Bid(b.playerId, b.playerName,
                                new Date(b.time), b.price));
                    } catch (Throwable ignored) {}
                }
                notes.add(n);
            } catch (Throwable t) {
                AuctionHouse.getInstance().getLogger().warning(
                        "Failed to decode note row " + row.noteId + ": " + t.getMessage());
            }
        }
        AuctionHouseStorage.replaceAll(notes);

        // Repopulate Redis from MySQL so cross-server subscribers see us.
        if (SettingManager.useRedisCache() && RedisManager.isAvailable()) {
            try {
                int written = RedisNoteStorage.rebuildFromRam(notes);
                for (ItemNote note : notes) {
                    try {
                        RedisNoteStorage.upsertBids(note.getNoteID(),
                                bidsToDtos(note.getBidHistoryList()));
                    } catch (Throwable ignored) {}
                }
                AuctionHouse.getInstance().getLogger().info(
                        "[AuctionHouse] Rebuilt " + written + " Redis index entries from MySQL.");
            } catch (Throwable t) {
                AuctionHouse.getInstance().getLogger().warning(
                        "Redis index rebuild failed (cache will heal lazily): " + t.getMessage());
            }
        }
        AuctionHouse.getInstance().getLogger().info(
                "[AuctionHouse] Loaded " + rows.size() + " notes from MySQL.");

        // Ask all known peers to re-broadcast their in-memory state. We do
        // this by publishing a BULK_REQUEST event from a server thread so
        // every other subscriber in the cluster will re-emit UPSERTs for
        // everything they hold. This is what makes "new server joins the
        // cluster" actually converge without manual reloads.
        if (SettingManager.useRedisCache() && SettingManager.redisPubsubEnabled
                && RedisManager.isAvailable()) {
            try {
                RedisSyncManager.publishBulkRequest();
            } catch (Throwable ignored) {}
        }
    }

    private static void persistUpsert(ItemNote note) {
        // Always update RAM first so the player sees immediate feedback.
        AuctionHouseStorage.add(note);

        // 1) MySQL is the durable source of truth — write it BEFORE we
        //    publish, otherwise a peer that acts on the pub/sub event might
        //    race ahead and pull from MySQL before this row exists.
        if (SettingManager.isMysqlPersistence()) {
            try { MySQLNoteStorage.upsertNote(note); }
            catch (Throwable t) {
                AuctionHouse.getInstance().getLogger().warning("MySQL upsert failed: " + t.getMessage());
            }
            // Sync current bid history too — auction house creation has none, but
            // bid mutations will call this again from addBid/updateField.
            try {
                MySQLNoteStorage.replaceBids(note.getNoteID(),
                        bidsToDbRows(note.getBidHistoryList()));
            } catch (Throwable ignored) {}
        }

        // 2) Update our local Redis cache so reads stay O(1) and so that
        //    the in-Redis index reflects reality.
        if (SettingManager.useRedisCache() && RedisManager.isAvailable()) {
            try { RedisNoteStorage.upsertNote(note); } catch (Throwable ignored) {}
            try {
                RedisNoteStorage.upsertBids(note.getNoteID(),
                        bidsToDtos(note.getBidHistoryList()));
            } catch (Throwable ignored) {}
        }

        // 3) Broadcast to other servers so they can update their RAM mirrors.
        if (SettingManager.useRedisCache() && SettingManager.redisPubsubEnabled
                && RedisManager.isAvailable()) {
            try {
                RedisSyncManager.publishUpsert(note, snapshot(note));
            } catch (Throwable ignored) {}
        }

        try { JsonNoteStorage.saveNotesIfJson(); } catch (Throwable ignored) {}
    }

    private static void persistDelete(ItemNote note) {
        AuctionHouseStorage.remove(note);

        if (SettingManager.isMysqlPersistence()) {
            try { MySQLNoteStorage.deleteNote(note.getNoteID()); }
            catch (Throwable t) {
                AuctionHouse.getInstance().getLogger().warning("MySQL delete failed: " + t.getMessage());
            }
        }
        if (SettingManager.useRedisCache()) {
            try { RedisNoteStorage.deleteNote(note.getNoteID()); } catch (Throwable ignored) {}
            if (SettingManager.redisPubsubEnabled) {
                try { RedisSyncManager.publishDelete(note.getNoteID()); } catch (Throwable ignored) {}
            }
        }
        try { JsonNoteStorage.saveNotesIfJson(); } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------
    // Public mutation API.  Identical to original; just routes through the facade.
    // ------------------------------------------------------------

    private static void deleteNote(ItemNote note) {
        persistDelete(note);
        removeItem(note.getNoteID());
    }

    public static void deleteCancelNote(ItemNote note) {
        ConfigManager.transactionLogger.logCancelAuction(
                note.getPlayerName(), note.getItemName(), note.getCurrentPrice(),
                note.getCurrentAmount(), note.isBIDAuction());
        deleteNote(note);
    }

    public static void deleteExpiredNote(ItemNote note) {
        ConfigManager.transactionLogger.logExpiredAuction(
                note.getPlayerName(), note.getItemName(), note.getCurrentPrice(),
                note.getCurrentAmount(), note.isBIDAuction());
        deleteNote(note);
    }
    public static void deleteAdminExpiredNote(ItemNote note) {
        ConfigManager.transactionLogger.logAdminExpiredAuction(
                note.getPlayerName(), note.getItemName(), note.getCurrentPrice(),
                note.getCurrentAmount(), note.isBIDAuction());
        deleteNote(note);
    }
    public static void deleteAdminDeletedNote(ItemNote note) {
        ConfigManager.transactionLogger.logAdminDeletedAuction(
                note.getPlayerName(), note.getItemName(), note.getCurrentPrice(),
                note.getCurrentAmount(), note.isBIDAuction());
        deleteNote(note);
    }

    public static void setBuyerName(ItemNote note, String buyerName, UUID playerID) {
        note.setBuyerName(buyerName, playerID);
        commit(note);
    }
    public static void setSold(ItemNote note, boolean isSold) {
        note.setSold(isSold);
        commit(note);
    }
    public static void setAdminMessage(ItemNote note, String adminMessage) {
        note.setAdminMessage(adminMessage);
        commit(note);
    }
    public static void setItem(ItemNote note, ItemStack item) {
        note.setItem(item);
        commit(note);
    }
    public static void setAuctionTime(ItemNote note, long time) {
        note.setAuctionTime(time);
        commit(note);
    }
    public static void setPartiallySoldAmountLeft(ItemNote note, int amount) {
        note.setPartiallySoldAmountLeft(amount);
        commit(note);
    }
    public static void setPrice(ItemNote note, double amount) {
        note.setPrice(amount);
        commit(note);
    }

    public static void addBid(ItemNote note, Player player, double amount) {
        note.addBid(player, amount);
        commit(note);
        if (SettingManager.isMysqlPersistence()) {
            try { MySQLNoteStorage.replaceBids(note.getNoteID(), bidsToDbRows(note.getBidHistoryList())); }
            catch (Throwable ignored) {}
        }
        if (SettingManager.useRedisCache()) {
            try {
                RedisNoteStorage.upsertBids(note.getNoteID(), bidsToDtos(note.getBidHistoryList()));
                if (SettingManager.redisPubsubEnabled) {
                    RedisSyncManager.publishBidReplace(note.getNoteID(), bidsToDtos(note.getBidHistoryList()));
                }
            } catch (Throwable ignored) {}
        }
    }

    public static void removeBid(Player player, ItemNote note) {
        note.removeBid(player);
        AuctionHouseStorage.removeBid(player.getUniqueId(), note.getNoteID());
    }

    public static void purge() {
        for (ItemNote note : AuctionHouseStorage.getAll()) {
            ConfigManager.transactionLogger.logPurge(
                    note.getPlayerName(), note.getItemName(), note.getCurrentPrice(),
                    note.getCurrentAmount(), note.isBIDAuction());
        }
        // Snapshot IDs first — AuctionHouseStorage.getAll() reads from the
        // live map which we'll mutate below.
        List<UUID> ids = new ArrayList<>();
        for (ItemNote n : AuctionHouseStorage.getAll()) {
            if (n != null) ids.add(n.getNoteID());
        }
        if (SettingManager.isMysqlPersistence()) {
            // Drop everything by issuing per-note deletes. Truncate would be
            // cleaner but requires admin rights we don't want to assume.
            for (UUID id : ids) {
                try { MySQLNoteStorage.deleteNote(id); } catch (Throwable ignored) {}
            }
        }
        if (SettingManager.useRedisCache() && RedisManager.isAvailable()) {
            // Wipe only keys under our own key-prefix — never touch keys
            // owned by another application that shares the Redis DB.
            RedisNoteStorage.purgeAll();
        }
        // Reset the in-memory mirror last so the rest of the plugin sees an
        // empty AH as soon as the function returns.
        AuctionHouseStorage.clear();
        JsonNoteStorage.purge();
    }

    public static void reload() {
        items.clear();
    }

    // ------------------------------------------------------------
    // High-level mutations called by the GUIs
    // ------------------------------------------------------------

    public static boolean setSoldIfOnAuction(ItemNote note, Player p, int amount, double price) {
        setSold(note, true);
        setBuyerName(note, p.getDisplayName(), p.getUniqueId());
        if (price != note.getPrice()) {
            if (note.getPartiallySoldAmountLeft() == 0) {
                setPartiallySoldAmountLeft(note, note.getItem().getAmount() - amount);
            } else {
                setPartiallySoldAmountLeft(note, note.getPartiallySoldAmountLeft() - amount);
            }
        }
        saveNotesWithoutCheck();
        ConfigManager.transactionLogger.logTransaction(
                p.getDisplayName(), note.getPlayerName(), note.getItemName(),
                price, amount, note.isBIDAuction());
        return true;
    }

    public static boolean collectExpiredAuctionItem(ItemNote note) {
        deleteExpiredNote(note);
        saveNotesWithoutCheck();
        return true;
    }
    public static boolean collectAdminExpiredAuctionItem(ItemNote note) {
        deleteAdminExpiredNote(note);
        saveNotesWithoutCheck();
        return true;
    }
    public static boolean collectAdminDeletedAuctionItem(ItemNote note) {
        deleteAdminDeletedNote(note);
        saveNotesWithoutCheck();
        return true;
    }

    public static boolean collectSoldAuctionItem(ItemNote note, int itemAmount, double price) {
        if (note == null) return false;
        if (note.isBIDAuction() && note.isSold()) return false;
        if (note.getPartiallySoldAmountLeft() != 0) {
            setPrice(note, note.getPrice() - price);
            ItemStack temp = note.getItem();
            temp.setAmount(note.getItem().getAmount() - itemAmount);
            setItem(note, temp);
            if (note.getPartiallySoldAmountLeft() == note.getItem().getAmount()) {
                setPartiallySoldAmountLeft(note, 0);
                setSold(note, false);
                setBuyerName(note, null, null);
            }
        } else {
            if (!note.isBIDAuction()) deleteNote(note);
            else {
                note.setSold(true);
                AuctionHouseStorage.checkRemove(note.getNoteID());
            }
        }
        saveNotesWithoutCheck();
        return true;
    }

    public static boolean addBidIfOnAuction(ItemNote note, Player p, double price) {
        addBid(note, p, price);
        saveNotesWithoutCheck();
        return true;
    }

    public static boolean claimEndedAuctionItem(Player p, ItemNote note) {
        removeBid(p, note);
        saveNotesWithoutCheck();
        return true;
    }

    public static boolean adminConfirmDeleteItem(ItemNote note, String reason) {
        setAuctionTime(note, -1);
        setAdminMessage(note, reason);
        setItem(note, ItemManager.createDirt());
        saveNotesWithoutCheck();
        return true;
    }

    public static boolean adminConfirmExpireItem(ItemNote note, String reason) {
        setAuctionTime(note, -1);
        setAdminMessage(note, reason);
        saveNotesWithoutCheck();
        return true;
    }

    // ------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------

    private static void commit(ItemNote note) {
        // 1) MySQL first — durable source of truth.
        if (SettingManager.isMysqlPersistence()) {
            try { MySQLNoteStorage.upsertNote(note); }
            catch (Throwable t) {
                AuctionHouse.getInstance().getLogger().warning("MySQL commit failed: " + t.getMessage());
            }
        }
        // 2) Update the local Redis cache. Skip when the pool isn't ready so
        //    we don't spam reconnect errors during a transient outage.
        if (SettingManager.useRedisCache() && RedisManager.isAvailable()) {
            try {
                RedisNoteStorage.upsertNote(note);
            } catch (Throwable ignored) {}
        }
        // 3) Broadcast the new state to peer servers via pub/sub.
        if (SettingManager.useRedisCache() && SettingManager.redisPubsubEnabled
                && RedisManager.isAvailable()) {
            try {
                Map<String, String> snap = snapshot(note);
                RedisSyncManager.publishUpsert(note, snap);
            } catch (Throwable ignored) {}
        }
        try { JsonNoteStorage.saveNotesIfJson(); } catch (Throwable ignored) {}
    }

    private static Map<String, String> snapshot(ItemNote note) {
        Map<String, String> data = new HashMap<>();
        // Coerce every value to a non-null String so Jedis does not throw
        // "name cannot be null" when an upstream cache lookup produced a
        // partially-populated note (e.g. only a BID_REPLACE arrived before
        // the matching UPSERT).
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
        ItemStack item = note.getItem();
        data.put("amount", item == null ? "0" : String.valueOf(item.getAmount()));
        return data;
    }

    private static List<MySQLNoteStorage.BidRow> bidsToDbRows(List<Bid> bids) {
        List<MySQLNoteStorage.BidRow> rows = new ArrayList<>();
        if (bids == null) return rows;
        for (Bid b : bids) {
            rows.add(new MySQLNoteStorage.BidRow(b.getPlayerID(), b.getPlayerName(),
                    b.getPrice(), b.getTimeMs()));
        }
        return rows;
    }

    private static List<RedisNoteStorage.BidDto> bidsToDtos(List<Bid> bids) {
        List<RedisNoteStorage.BidDto> rows = new ArrayList<>();
        if (bids == null) return rows;
        for (Bid b : bids) {
            rows.add(new RedisNoteStorage.BidDto(
                    b.getPlayerID() == null ? null : b.getPlayerID().toString(),
                    b.getPlayerName(), b.getPrice(), b.getTimeMs()));
        }
        return rows;
    }

    private static void saveNotesWithoutCheck() {
        try { saveNotes(); }
        catch (IOException e) { throw new RuntimeException(e); }
    }

    public enum SortMode {
        NAME, PRICE_ASC, PRICE_DESC, DATE
    }
}
