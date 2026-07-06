package me.elaineqheart.auctionHouse.data.persistentStorage.database;

import me.elaineqheart.auctionHouse.AuctionHouse;
import me.elaineqheart.auctionHouse.data.persistentStorage.local.SettingManager;
import me.elaineqheart.auctionHouse.data.ram.AuctionHouseStorage;
import me.elaineqheart.auctionHouse.data.ram.Bid;
import me.elaineqheart.auctionHouse.data.ram.ItemNote;

import java.util.ArrayList;
import java.util.List;

/**
 * Centralises the boot-time "rebuild Redis from MySQL" routine.
 *
 * <p>Why this class exists: when a server in the cluster starts up, both the
 * in-memory mirror {@link AuctionHouseStorage} AND the Redis cache start out
 * empty. Even after {@code loadNotes()} re-hydrates RAM from MySQL, the Redis
 * sorted-set indexes ({@code byPrice}, {@code byDate}, {@code byName},
 * {@code player:*}) are still missing — so any other server that does a
 * Redis-only lookup would see no items. We therefore re-populate Redis from
 * the freshly-loaded RAM as the last step of startup, and then ask peers to
 * re-broadcast their state for the items that changed while this server was
 * offline.</p>
 *
 * <p>This routine is idempotent: it is safe to call it on every startup and
 * after a {@code /ah reload}.</p>
 */
public final class RedisSnapshotService {

    private RedisSnapshotService() {}

    /**
     * Rebuild the Redis side from the current in-memory state. Returns the
     * number of notes written. Safe to call when Redis is unavailable — the
     * routine becomes a no-op.
     */
    public static int rebuildFromRam() {
        if (!SettingManager.useRedisCache()) return 0;
        if (!RedisManager.isAvailable()) return 0;

        List<ItemNote> all;
        try {
            all = AuctionHouseStorage.getAll();
        } catch (Throwable t) {
            AuctionHouse.getInstance().getLogger().warning(
                    "RedisSnapshotService.rebuildFromRam failed to read RAM: " + t.getMessage());
            return 0;
        }
        int written = RedisNoteStorage.rebuildFromRam(all);
        // Bids are kept as a separate list per note.
        for (ItemNote note : all) {
            if (note == null) continue;
            try {
                List<Bid> bids = note.getBidHistoryList();
                List<RedisNoteStorage.BidDto> dtos = new ArrayList<>(bids == null ? 0 : bids.size());
                if (bids != null) {
                    for (Bid b : bids) {
                        if (b == null || b.getPlayerID() == null) continue;
                        dtos.add(new RedisNoteStorage.BidDto(
                                b.getPlayerID().toString(),
                                b.getPlayerName(),
                                b.getPrice(),
                                b.getTimeMs()));
                    }
                }
                RedisNoteStorage.upsertBids(note.getNoteID(), dtos);
            } catch (Throwable ignored) {}
        }
        AuctionHouse.getInstance().getLogger().info(
                "[AuctionHouse] RedisSnapshotService rebuilt " + written + " cache entries.");
        return written;
    }
}