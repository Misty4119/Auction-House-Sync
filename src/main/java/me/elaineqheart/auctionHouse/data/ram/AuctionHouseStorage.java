package me.elaineqheart.auctionHouse.data.ram;

import me.elaineqheart.auctionHouse.data.persistentStorage.ItemNoteStorage;
import me.elaineqheart.auctionHouse.data.persistentStorage.local.configs.Blacklist;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class AuctionHouseStorage {

    private static final ArrayList<UUID> itemNotes = new ArrayList<>();
    private static final ArrayList<UUID> sortedHighestPrice = new ArrayList<>();
    private static final ArrayList<UUID> sortedTimeLeft = new ArrayList<>();
    private static final ArrayList<UUID> sortedAlphabetical = new ArrayList<>();
    private static final HashMap<UUID, List<UUID>> sortedBids = new HashMap<>(); // player : itemNotes
    private static final HashMap<UUID, List<UUID>> sortedPlayers = new HashMap<>(); // itemNote : players
    private static final HashMap<UUID, ItemNote> notes = new HashMap<>();
    private static final HashMap<List<Map<?, ?>>, List<UUID>> categories = new HashMap<>();

    private static void addToLists(ItemNote note) {
        // Avoid duplicate entries when the same note is upserted twice (e.g.
        // a cross-server UPSERT arrives while we already hold a local copy).
        UUID id = note.getNoteID();
        boolean alreadyPresent = notes.containsKey(id);
        notes.put(id, note);
        if (!alreadyPresent) itemNotes.add(id);
        // Always drop the note from the sort indexes first, then re-add it
        // at the end if it is still active. This keeps the lists idempotent
        // even if a previous version of the same note was added with a
        // different price/date.
        sortedHighestPrice.remove(id);
        sortedTimeLeft.remove(id);
        sortedAlphabetical.remove(id);
        categories.forEach((maps, uuids) -> uuids.remove(id));
        if(note.isTheoreticallyOnAuction() && !note.isExpired()) {
            sortedHighestPrice.add(id);
            sortedTimeLeft.add(id);
            sortedAlphabetical.add(id);
            categories.forEach((maps, uuids) -> {
                if(!Blacklist.isBlacklisted(note.getItem(), maps)) uuids.add(id);
            });
        }
    }
    private static void removeFromLists(UUID noteID) {
        notes.remove(noteID);
        itemNotes.remove(noteID);
        sortedHighestPrice.remove(noteID);
        sortedTimeLeft.remove(noteID);
        sortedAlphabetical.remove(noteID);
        categories.forEach((maps, uuids) -> uuids.remove(noteID));
    }
    private static void addWhiteList(List<Map<?, ?>> whitelist) {
        categories.put(whitelist, itemNotes.stream()
                .filter(noteID -> !Blacklist.isBlacklisted(notes.get(noteID).getItem(), whitelist))
                .collect(Collectors.toList()));
    }

    public static void add(ItemNote note) {
        addToLists(note);
        updateSortedLists();
    }

    /**
     * Replace the entire in-memory mirror. Used by the MySQL hydration path
     * at startup. All categories are dropped because category membership is
     * derived from the (now-empty) whitelist cache, which the caller can
     * re-register afterwards if needed.
     */
    public static void set(ItemNote[] notes) {
        clear();
        if (notes == null) return;
        for (ItemNote note : notes) {
            if (note == null) continue;
            addToLists(note);
        }
        updateBids();
        updateSortedLists();
    }

    /**
     * List-based overload of {@link #set(ItemNote[])} — preferred because the
     * list version tolerates {@code null} elements without crashing.
     */
    public static void replaceAll(List<ItemNote> list) {
        clear();
        if (list == null) return;
        for (ItemNote note : list) {
            if (note == null) continue;
            addToLists(note);
        }
        updateBids();
        updateSortedLists();
    }

    /**
     * Drop every note from the in-memory mirror. Public so the purge path
     * can wipe RAM without going through the GUI.
     */
    public static void clear() {
        notes.clear();
        itemNotes.clear();
        sortedHighestPrice.clear();
        sortedTimeLeft.clear();
        sortedAlphabetical.clear();
        sortedBids.clear();
        sortedPlayers.clear();
        // Drop categories too — they will be re-built lazily by callers that
        // call addWhiteList(...) again.
        categories.clear();
    }

    public static void remove(ItemNote item) {
        removeFromLists(item.getNoteID());
        if(!item.isBIDAuction() || !item.hasBidHistory()) notes.remove(item.getNoteID());
    }

    public static boolean canCollectBid(ItemNote item, UUID player) {return !item.getClaimedPlayers().contains(player);}

    public static void removeBid(UUID player, UUID noteID) {
        if(sortedBids.containsKey(player)) sortedBids.get(player).remove(noteID);
        if(sortedPlayers.containsKey(noteID)) {
            sortedPlayers.get(noteID).remove(player);
            checkRemove(noteID);
        }
    }
    public static void checkRemove(UUID noteID) {
        if(!notes.get(noteID).isBIDAuction()) return;
        if(sortedPlayers.get(noteID).isEmpty() && notes.get(noteID).isSold()) {
            sortedPlayers.remove(noteID);
            sortedBids.remove(notes.get(noteID).getPlayerUUID());
            removeFromLists(noteID);
        }
    }

    public static List<ItemNote> getAll() {
        return itemNotes.stream().map(notes::get).toList(); //keep order
    }

    public static List<ItemNote> getSortedList(ItemNoteStorage.SortMode mode, AhConfiguration c){
        String search = c == null ? "" : c.getCurrentSearch();
        List<UUID> list;
        switch (mode) {
            case DATE -> list = sortedTimeLeft;
            case NAME -> list = sortedAlphabetical;
            case PRICE_ASC -> list = sortedHighestPrice;
            case PRICE_DESC -> list = sortedHighestPrice.reversed();
            default -> list = sortedTimeLeft;
        }
        Stream<ItemNote> base = list.stream()
                .map(notes::get)
                .filter(Objects::nonNull)
                .filter(note -> note.isTheoreticallyOnAuction() && !note.isExpired() && !note.isOnWaitingList());
        if (search != null && !search.isEmpty() && c != null && c.getPlayer() != null) {
            String lowered = search.toLowerCase(Locale.ROOT);
            base = base.filter(note -> {
                try {
                    return note.getSearchIndex(c.getPlayer()).stream()
                            .anyMatch(s -> s != null && s.contains(lowered));
                } catch (Throwable t) {
                    // Player locale may be missing — fall back to a permissive
                    // match so the GUI never breaks for offline-render paths.
                    return true;
                }
            });
        }
        if (c != null) {
            base = base.filter(note -> switch (c.getBinFilter()) {
                case ALL -> true;
                case AUCTIONS_ONLY -> note.isBIDAuction();
                case BIN_ONLY -> !note.isBIDAuction();
            });
        }
        return base.collect(Collectors.toList());
    }

    public static void applyWhitelist(List<ItemNote> notes, List<Map<?, ?>> whitelist) {
        if(!categories.containsKey(whitelist)) {addWhiteList(whitelist);}
        notes.removeIf(note -> categories.get(whitelist).contains(note.getNoteID()));
    }

    public static List<ItemNote> getMySortedDateCreated(UUID playerID){ //use only for online players
        return itemNotes.stream()
                .map(notes::get)
                .filter(note -> Objects.equals(note.getPlayerUUID(), playerID))
                .filter(note -> !(note.isBIDAuction() && note.isSold()))
                .toList(); // toList() makes it unmodifiable
    }

    public static int getNumberOfAuctions(UUID playerID) {
        return getMySortedDateCreated(playerID).size();
    }

    public static ItemNote getNote(UUID noteID) {
        return notes.get(noteID);
    }

    public static List<ItemNote> getMyBids(UUID playerID) {
        if(!sortedBids.containsKey(playerID)) return List.of();
        return sortedBids.get(playerID).stream()
                .map(notes::get)
                .filter(itemNote -> itemNote.canClaimBid(playerID))
                .toList();
    }

    public static void addBid(UUID playerID, UUID noteID) {
        sortedBids.computeIfAbsent(playerID, k -> new ArrayList<>());
        List<UUID> bids = sortedBids.get(playerID);
        if(!bids.contains(noteID)) bids.addFirst(noteID);
        sortedPlayers.computeIfAbsent(noteID, k -> new ArrayList<>());
        List<UUID> players = sortedPlayers.get(noteID);
        if(!players.contains(playerID)) players.addFirst(playerID);
    }

    private static void updateBids() {
        sortedBids.clear();
        sortedPlayers.clear();
        for(UUID noteID : itemNotes) {
            ItemNote note = notes.get(noteID);
            for(UUID playerID : note.getBidders()) {
                if(canCollectBid(note, playerID)) addBid(playerID, note.getNoteID());
            }
        }
    }

    private static void updateSortedLists(){
        //these lists of the auction items are sorted by price, creation data and the alphabet
        sortedAlphabetical.sort(Comparator.comparing(o -> notes.get(o).getItemName()));
        sortedHighestPrice.sort(Comparator.comparing(o -> notes.get(o).getPrice()));
        sortedTimeLeft.sort((Comparator.comparing(o -> notes.get(o).getTimeLeft())));
    }
}
