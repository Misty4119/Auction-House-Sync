package me.elaineqheart.auctionHouse.data.ram;

import de.unpixelt.locale.Translate;
import me.elaineqheart.auctionHouse.data.StringUtils;
import me.elaineqheart.auctionHouse.data.persistentStorage.ItemNoteStorage;
import me.elaineqheart.auctionHouse.data.persistentStorage.ItemStackConverter;
import me.elaineqheart.auctionHouse.data.persistentStorage.local.SettingManager;
import me.elaineqheart.auctionHouse.data.persistentStorage.local.data.ConfigManager;
import me.elaineqheart.auctionHouse.data.ram.ItemManager;
import me.elaineqheart.auctionHouse.pluginDependencies.LocaleAPIExtension;
import org.bukkit.Bukkit;
import org.bukkit.block.ShulkerBox;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.stream.Collectors;

public class ItemNote {

    private final String playerName;
    private String buyerName;
    private UUID buyerUUID;
    private final UUID playerUUID;
    private double price;
    private final Date dateCreated;
    private String itemData;
    private boolean isSold;
    private int partiallySoldAmountLeft;
    private final UUID noteID;
    private String adminMessage;
    private long auctionTime;
    private String itemName;
    private final boolean isBIDAuction;
    private List<Bid> bidHistory = new ArrayList<>();
    private Set<UUID> claimedPlayers = new HashSet<>();

    public ItemNote(Player player, ItemStack item, double price, boolean isBIDAuction) {
        this.noteID = UUID.randomUUID();
        this.playerName = player.getDisplayName();
        this.buyerName = null;
        this.buyerUUID = null;
        this.playerUUID = player.getUniqueId();
        this.dateCreated = new Date();
        this.itemData = ItemStackConverter.encode(item);
        this.price = price;
        this.isSold = false;
        this.auctionTime = ConfigManager.permissions.getAuctionDuration(player, isBIDAuction);
        this.itemName = StringUtils.getItemName(item);
        this.isBIDAuction = isBIDAuction;
        ItemNoteStorage.addItem(noteID, ItemStackConverter.decode(itemData));
    }

    /**
     * Reconstruction constructor used when loading notes from MySQL. The
     * {@code auctionTime} is taken verbatim from the row (not re-derived),
     * matching how {@link #auctionTime} is intended to behave when notes are
     * loaded back from disk.
     */
    public ItemNote(UUID noteId, String playerName, UUID playerId,
                    String buyerName, UUID buyerId,
                    String itemName, double price, Date dateCreated,
                    ItemStack item, boolean isBidAuction,
                    boolean isSold, int partiallySoldLeft,
                    String adminMessage, long auctionTime) {
        this.noteID = noteId;
        this.playerName = playerName;
        this.playerUUID = playerId;
        this.buyerName = buyerName;
        this.buyerUUID = buyerId;
        this.itemName = itemName;
        this.price = price;
        this.dateCreated = dateCreated;
        this.itemData = item == null ? null : ItemStackConverter.encode(item);
        this.isBIDAuction = isBidAuction;
        this.isSold = isSold;
        this.partiallySoldAmountLeft = partiallySoldLeft;
        this.adminMessage = adminMessage;
        this.auctionTime = auctionTime;
        if (item != null) ItemNoteStorage.addItem(noteID, item.clone());
    }

    public ItemStack getItem(){
        if (ItemNoteStorage.getItem(noteID) != null) return ItemNoteStorage.getItem(noteID).clone();
        ItemStack myItem = null;
        if (itemData != null) {
            try { myItem = ItemStackConverter.decode(itemData); }
            catch (Throwable ignored) { myItem = null; }
        }
        if (myItem == null) {
            // Last-resort fallback so callers never see a null item and so
            // downstream code (MySQL NOT NULL columns, Redis upserts, GUI
            // lookups) does not blow up.
            myItem = ItemManager.createDirt();
            itemData = ItemStackConverter.encode(myItem);
        }
        ItemNoteStorage.addItem(noteID, myItem);
        return myItem.clone();
    }
    public long getTimeLeft(){
        // +30 seconds [auctionSetupTime] wait time until the item is up on auction
        if(auctionTime == 0) auctionTime = ConfigManager.permissions.getAuctionDuration(Bukkit.getPlayer(playerUUID), isBIDAuction); //backwards compatibility
        return auctionTime + SettingManager.auctionSetupTime - (new Date().getTime() - dateCreated.getTime())/1000; // divided by 1000 to get seconds
    }
    public boolean isExpired(){
        return getTimeLeft()<0;
    }

    public boolean isOnWaitingList() {
        if(auctionTime == 0) auctionTime = ConfigManager.permissions.getAuctionDuration(Bukkit.getPlayer(playerUUID), isBIDAuction); //backwards compatibility
        return getTimeLeft() > auctionTime;
    }

    public double getCurrentPrice() {
        if(getPartiallySoldAmountLeft() == 0) return price;
        return price / getItem().getAmount() * getPartiallySoldAmountLeft();
    }
    public double getSoldPrice() {
        return partiallySoldAmountLeft == 0 ? price : price - getCurrentPrice();
    }
    public int getCurrentAmount() {
        return partiallySoldAmountLeft == 0 ? getItem().getAmount() : partiallySoldAmountLeft;
    }

    public List<String> getSearchIndex(Player p) {
        ItemStack item = getItem();
        ItemMeta meta = item.getItemMeta();
        List<String> index = new ArrayList<>(Collections.singleton(item.toString().toLowerCase()));
        if(LocaleAPIExtension.enabled) {
            List<ItemStack> translateItems = new ArrayList<>(List.of(item));
            if(meta != null) {
                if (meta instanceof BundleMeta bundleMeta) translateItems.addAll(bundleMeta.getItems());
                if (ItemManager.isShulkerBox(item)) Collections.addAll(translateItems, ((ShulkerBox) ((BlockStateMeta) meta).getBlockState()).getInventory().getContents());
            }

            for (ItemStack translateItem : translateItems) {
                if (translateItem == null) continue;
                index.add(Translate.getMaterial(p, translateItem.getType()).toLowerCase());
                ItemMeta translateMeta = item.getItemMeta();
                if (translateMeta == null) continue;
                for (Enchantment enchantment : translateMeta.getEnchants().keySet()) {
                    String translatedEnchantment = Translate.getEnchantment(p, enchantment);
                    if (translatedEnchantment != null) index.add(translatedEnchantment.toLowerCase());
                }
                if (translateMeta instanceof PotionMeta potionMeta) {
                    PotionType type = potionMeta.getBasePotionType();
                    if(type == null) continue;
                    String translatedPotion = Translate.getPotion(p, type, getPotionSort(translateItem));
                    if (translatedPotion != null) index.add(translatedPotion.toLowerCase());
                }
            }
        }
        index.add(itemName.toLowerCase());
        return index;
    }

    private static Translate.@NotNull PotionSort getPotionSort(ItemStack translateItem) {
        Translate.PotionSort sort;
        switch (translateItem.getType()) {
            case POTION -> sort = Translate.PotionSort.POTION;
            case LINGERING_POTION -> sort = Translate.PotionSort.LINGERING_POTION;
            case SPLASH_POTION -> sort = Translate.PotionSort.SPLASH_POTION;
            default -> throw new IllegalStateException("Unexpected potion " +
                    "value: " + translateItem.getType());
        }
        return sort;
    }

    //Getters and Setters
    public String getPlayerName() {return playerName;}
    public String getBuyerName() {return isBIDAuction ? getLastBidderName() : buyerName;}
    public UUID getBuyerUUID() {
        if (isBIDAuction) return getLastBidder();
        if (buyerUUID != null) return buyerUUID;
        if (buyerName == null || buyerName.isEmpty()) return null;
        try {
            return Bukkit.getOfflinePlayer(buyerName).getUniqueId();
        } catch (Throwable t) {
            return null;
        }
    } //offline player backwards compatibility
    public UUID getPlayerUUID() {return playerUUID;}
    public Date getDateCreated() {return dateCreated;}
    public double getPrice() {return price;}
    public boolean isSold() {return isSold;}
    public boolean isTheoreticallyOnAuction() {return !isSold || partiallySoldAmountLeft != 0;} //NOT INCLUDING EXPIRED
    public int getPartiallySoldAmountLeft() {return partiallySoldAmountLeft;}
    public String getAdminMessage() {return adminMessage;}
    public UUID getNoteID() {return noteID;}
    public String getItemName() {
        if (itemName == null || itemName.isEmpty()) {
            try {
                itemName = StringUtils.getItemName(getItem());
            } catch (Throwable ignored) {
                itemName = "Unknown";
            }
        }
        if (itemName == null || itemName.isEmpty()) {
            itemName = "Unknown";
        }
        return StringUtils.stripLegacySection(itemName);
    }
    /** Raw Base64-encoded item payload (as written by {@link ItemStackConverter}). */
    public String getItemData() { return itemData; }
    public List<Bid> getBidHistoryList() {
        if(bidHistory == null) bidHistory = new ArrayList<>();
        return bidHistory;
    }
    public boolean hasBidHistory() {return bidHistory != null && !bidHistory.isEmpty();}
    public boolean isBIDAuction() {return isBIDAuction;}
    public String getLastBidderName() {return getBidHistoryList().isEmpty() ? null : getBidHistoryList().getLast().getPlayerName();}
    public UUID getLastBidder() {return getBidHistoryList().isEmpty() ? null : getBidHistoryList().getLast().getPlayerID();}
    public Set<UUID> getBidders() {
        return getBidHistoryList().stream()
                .map(Bid::getPlayerID)
                .collect(Collectors.toSet());
    }
    public double getBid(Player p) {
        return getBidHistoryList().stream()
                .filter(bid -> bid.getPlayerID().equals(p.getUniqueId()))
                .map(Bid::getPrice)
                .reduce((first, second) -> second)
                .orElse(0.0);
    }
    public Set<UUID> getClaimedPlayers() {
        if(claimedPlayers == null) claimedPlayers = new HashSet<>();
        return claimedPlayers;
    }
    public boolean canClaimBid(UUID playerID) {return !getClaimedPlayers().contains(playerID);}

    public void setBuyerName(String buyerName, UUID id) {
        this.buyerName = buyerName;
        this.buyerUUID = id;
    }

    /**
     * Snapshot of the raw {@code auctionTime} field. Used by the persistence
     * layer so the loaded row's value is preserved 1:1.
     */
    public long getAuctionTimeSnapshot() { return auctionTime; }
    public void addBid(Player player, double bid) {
        // Deduplicate: if the same player is somehow already the latest
        // bidder at the same price, skip the append. Keeps the bid list
        // sane when the addBid path is re-invoked (e.g. via a replay).
        if (bidHistory == null) bidHistory = new ArrayList<>();
        if (!bidHistory.isEmpty()) {
            Bid last = bidHistory.get(bidHistory.size() - 1);
            if (last != null && last.getPlayerID() != null
                    && last.getPlayerID().equals(player.getUniqueId())
                    && last.getPrice() == bid) {
                return;
            }
        }
        this.bidHistory.add(new Bid(player, new Date(), bid));
        this.price = bid;
        if (getTimeLeft() < SettingManager.lastBIDExtraTime) {
            auctionTime = SettingManager.lastBIDExtraTime - SettingManager.auctionSetupTime + (new Date().getTime() - dateCreated.getTime())/1000;
        }
        AuctionHouseStorage.addBid(player.getUniqueId(), noteID);
    }
    public void removeBid(Player player) {getClaimedPlayers().add(player.getUniqueId());}
    public void setSold(boolean isSold) {this.isSold = isSold;}
    public void setAdminMessage(String adminMessage) {this.adminMessage = adminMessage;}
    public void setItem(ItemStack item) {
        this.itemData = ItemStackConverter.encode(item);
        ItemNoteStorage.addItem(noteID, ItemStackConverter.decode(itemData));
    }
    public void setAuctionTime(long time) {this.auctionTime = time;}
    public void setPartiallySoldAmountLeft(int amount) {this.partiallySoldAmountLeft = amount;}
    public void setPrice(double amount) {this.price = amount;}

    /** Used by the multi-server sync layer to replace the bid list atomically. */
    public void resetBidHistory() {
        if (bidHistory == null) bidHistory = new ArrayList<>();
        else bidHistory.clear();
        if (claimedPlayers == null) claimedPlayers = new HashSet<>();
        else claimedPlayers.clear();
    }

    /** Append a {@link Bid} to the history without touching player-refund side effects. */
    public void appendBid(Bid bid) {
        if (bidHistory == null) bidHistory = new ArrayList<>();
        bidHistory.add(bid);
    }

    /** Used by the multi-server sync layer when replaying bids from a remote source. */
    public void addBidFromDto(me.elaineqheart.auctionHouse.data.persistentStorage.database.RedisNoteStorage.BidDto dto) {
        if (dto == null) return;
        UUID id = null;
        if (dto.playerId != null) {
            try { id = UUID.fromString(dto.playerId); } catch (Exception ignored) {}
        }
        if (id == null) return;
        bidHistory.add(new Bid(id, dto.playerName, new Date(dto.time), dto.price));
    }
}
