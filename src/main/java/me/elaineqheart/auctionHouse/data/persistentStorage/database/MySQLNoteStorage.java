package me.elaineqheart.auctionHouse.data.persistentStorage.database;

import me.elaineqheart.auctionHouse.AuctionHouse;
import me.elaineqheart.auctionHouse.data.persistentStorage.ItemStackConverter;
import me.elaineqheart.auctionHouse.data.ram.ItemManager;
import me.elaineqheart.auctionHouse.data.ram.ItemNote;
import org.bukkit.inventory.ItemStack;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/**
 * MySQL persistence implementation. The note's full state is stored as a row in
 * {@code ah_notes}; bid history is stored in {@code ah_bids}.
 *
 * <p>This class is intentionally simple: each public method opens one
 * connection via {@link MySQLManager#getConnection()} and returns primitives
 * or lightweight DTOs. The facade ({@code ItemNoteStorage}) is responsible for
 * translating JDBC rows back into the in-memory {@link ItemNote} instances
 * that the rest of the plugin uses.</p>
 */
public final class MySQLNoteStorage {

    private MySQLNoteStorage() {}

    public static void upsertNote(ItemNote note) {
        if (note == null) return;
        String sql = """
            INSERT INTO ah_notes
              (note_id, item_name, player_uuid, player_name, buyer_uuid, buyer_name,
               price, current_amount, is_bid_auction, is_sold, partially_sold_left,
               admin_message, auction_time, date_created, item_data)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON DUPLICATE KEY UPDATE
              item_name = VALUES(item_name),
              player_name = VALUES(player_name),
              buyer_uuid = VALUES(buyer_uuid),
              buyer_name = VALUES(buyer_name),
              price = VALUES(price),
              current_amount = VALUES(current_amount),
              is_bid_auction = VALUES(is_bid_auction),
              is_sold = VALUES(is_sold),
              partially_sold_left = VALUES(partially_sold_left),
              admin_message = VALUES(admin_message),
              auction_time = VALUES(auction_time),
              item_data = VALUES(item_data)
            """;

        try (Connection conn = MySQLManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ItemStack item = note.getItem();
            int currentAmount = item == null ? 0 : item.getAmount();

            // Defensive fallbacks. The ah_notes.item_name / player_name /
            // item_data columns are NOT NULL — historically the plugin
            // crashed with "Column 'item_name' cannot be null" when the
            // cached ItemStack or display-name was null on a freshly-
            // reconstructed note. We pre-validate here so a single bad row
            // does not abort the whole write path.
            String itemName   = safe(note.getItemName(), "Unknown");
            String playerName = safe(note.getPlayerName(), "Unknown");
            String itemData   = ItemStackConverter.encode(item);
            if (itemData == null) {
                itemData = ItemStackConverter.encode(ItemManager.createDirt());
            }

            ps.setString(1, note.getNoteID().toString());
            ps.setString(2, itemName);
            ps.setString(3, note.getPlayerUUID().toString());
            ps.setString(4, playerName);
            ps.setString(5, note.getBuyerUUID() == null ? null : note.getBuyerUUID().toString());
            ps.setString(6, note.getBuyerName());
            ps.setDouble(7, note.getPrice());
            ps.setInt(8, currentAmount);
            ps.setBoolean(9, note.isBIDAuction());
            ps.setBoolean(10, note.isSold());
            ps.setInt(11, note.getPartiallySoldAmountLeft());
            ps.setString(12, note.getAdminMessage());
            ps.setLong(13, note.getAuctionTimeSnapshot());
            ps.setLong(14, note.getDateCreated().getTime());
            ps.setString(15, itemData);
            ps.executeUpdate();

        } catch (SQLException ex) {
            logError("upsertNote", ex);
            throw new RuntimeException(ex);
        }
    }

    private static String safe(String value, String fallback) {
        if (value == null || value.isEmpty()) return fallback;
        return value;
    }

    public static void deleteNote(UUID noteId) {
        if (noteId == null) return;
        try (Connection conn = MySQLManager.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM ah_notes WHERE note_id = ?")) {
            ps.setString(1, noteId.toString());
            ps.executeUpdate();
        } catch (SQLException ex) {
            logError("deleteNote", ex);
            throw new RuntimeException(ex);
        }
        try (Connection conn = MySQLManager.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM ah_bids WHERE note_id = ?")) {
            ps.setString(1, noteId.toString());
            ps.executeUpdate();
        } catch (SQLException ex) {
            // ok to ignore if no bids existed
        }
    }

    public static NoteRow loadNote(UUID noteId) {
        try (Connection conn = MySQLManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM ah_notes WHERE note_id = ?")) {
            ps.setString(1, noteId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return NoteRow.fromResultSet(rs);
            }
        } catch (SQLException ex) {
            logError("loadNote", ex);
        }
        return null;
    }

    public static List<NoteRow> loadAll() {
        List<NoteRow> rows = new ArrayList<>();
        try (Connection conn = MySQLManager.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM ah_notes");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rows.add(NoteRow.fromResultSet(rs));
            }
        } catch (SQLException ex) {
            logError("loadAll", ex);
        }
        return rows;
    }

    public static List<UUID> loadAllNoteIds() {
        List<UUID> ids = new ArrayList<>();
        try (Connection conn = MySQLManager.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT note_id FROM ah_notes");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) ids.add(UUID.fromString(rs.getString(1)));
        } catch (SQLException ex) {
            logError("loadAllNoteIds", ex);
        }
        return ids;
    }

    public static void replaceBids(UUID noteId, List<BidRow> bids) {
        try (Connection conn = MySQLManager.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement del = conn.prepareStatement("DELETE FROM ah_bids WHERE note_id = ?")) {
                del.setString(1, noteId.toString());
                del.executeUpdate();
            }
            if (bids != null && !bids.isEmpty()) {
                try (PreparedStatement ins = conn.prepareStatement(
                        "INSERT INTO ah_bids (note_id, player_uuid, player_name, bid_price, bid_time) VALUES (?,?,?,?,?)")) {
                    for (BidRow b : bids) {
                        ins.setString(1, noteId.toString());
                        ins.setString(2, b.playerId == null ? null : b.playerId.toString());
                        ins.setString(3, b.playerName);
                        ins.setDouble(4, b.price);
                        ins.setLong(5, b.time);
                        ins.addBatch();
                    }
                    ins.executeBatch();
                }
            }
            conn.commit();
        } catch (SQLException ex) {
            logError("replaceBids", ex);
        }
    }

    public static List<BidRow> loadBids(UUID noteId) {
        List<BidRow> list = new ArrayList<>();
        try (Connection conn = MySQLManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT player_uuid, player_name, bid_price, bid_time FROM ah_bids WHERE note_id = ? ORDER BY bid_time ASC")) {
            ps.setString(1, noteId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String raw = rs.getString("player_uuid");
                    list.add(new BidRow(
                            raw == null ? null : UUID.fromString(raw),
                            rs.getString("player_name"),
                            rs.getDouble("bid_price"),
                            rs.getLong("bid_time")));
                }
            }
        } catch (SQLException ex) {
            logError("loadBids", ex);
        }
        return list;
    }

    public static int countByPlayer(UUID playerId) {
        try (Connection conn = MySQLManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM ah_notes WHERE player_uuid = ?")) {
            ps.setString(1, playerId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (SQLException ex) {
            logError("countByPlayer", ex);
        }
        return 0;
    }

    private static void logError(String op, SQLException ex) {
        AuctionHouse.getInstance().getLogger().warning("MySQL " + op + " failed: " + ex.getMessage());
    }

    /** Lightweight DTO for a note row. */
    public static final class NoteRow {
        public final UUID noteId;
        public final String itemName;
        public final UUID playerId;
        public final String playerName;
        public final UUID buyerId;
        public final String buyerName;
        public final double price;
        public final int currentAmount;
        public final boolean isBidAuction;
        public final boolean isSold;
        public final int partiallySoldLeft;
        public final String adminMessage;
        public final long auctionTime;
        public final long dateCreated;
        public final String itemData;

        private NoteRow(UUID noteId, String itemName, UUID playerId, String playerName,
                        UUID buyerId, String buyerName, double price, int currentAmount,
                        boolean isBidAuction, boolean isSold, int partiallySoldLeft,
                        String adminMessage, long auctionTime, long dateCreated, String itemData) {
            this.noteId = noteId;
            this.itemName = itemName;
            this.playerId = playerId;
            this.playerName = playerName;
            this.buyerId = buyerId;
            this.buyerName = buyerName;
            this.price = price;
            this.currentAmount = currentAmount;
            this.isBidAuction = isBidAuction;
            this.isSold = isSold;
            this.partiallySoldLeft = partiallySoldLeft;
            this.adminMessage = adminMessage;
            this.auctionTime = auctionTime;
            this.dateCreated = dateCreated;
            this.itemData = itemData;
        }

        static NoteRow fromResultSet(ResultSet rs) throws SQLException {
            String rawPlayer = rs.getString("player_uuid");
            String rawBuyer  = rs.getString("buyer_uuid");
            return new NoteRow(
                    UUID.fromString(rs.getString("note_id")),
                    rs.getString("item_name"),
                    rawPlayer == null ? null : UUID.fromString(rawPlayer),
                    rs.getString("player_name"),
                    rawBuyer == null ? null : UUID.fromString(rawBuyer),
                    rs.getString("buyer_name"),
                    rs.getDouble("price"),
                    rs.getInt("current_amount"),
                    rs.getBoolean("is_bid_auction"),
                    rs.getBoolean("is_sold"),
                    rs.getInt("partially_sold_left"),
                    rs.getString("admin_message"),
                    rs.getLong("auction_time"),
                    rs.getLong("date_created"),
                    rs.getString("item_data")
            );
        }

        public ItemNote intoNote() {
            ItemNote n = new ItemNote(noteId, playerName, playerId, buyerName, buyerId,
                    itemName, price, new Date(dateCreated),
                    ItemStackConverter.decode(itemData),
                    isBidAuction, isSold, partiallySoldLeft,
                    adminMessage, auctionTime);
            return n;
        }
    }

    public static final class BidRow {
        public final UUID playerId;
        public final String playerName;
        public final double price;
        public final long time;
        public BidRow(UUID playerId, String playerName, double price, long time) {
            this.playerId = playerId;
            this.playerName = playerName;
            this.price = price;
            this.time = time;
        }
    }
}
