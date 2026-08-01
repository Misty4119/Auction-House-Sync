package me.elaineqheart.auctionHouse.GUI.impl;

import me.elaineqheart.auctionHouse.AuctionHouse;
import me.elaineqheart.auctionHouse.GUI.InventoryButton;
import me.elaineqheart.auctionHouse.GUI.InventoryGUI;
import me.elaineqheart.auctionHouse.GUI.other.Sounds;
import me.elaineqheart.auctionHouse.data.persistentStorage.ItemNoteStorage;
import me.elaineqheart.auctionHouse.data.persistentStorage.local.SettingManager;
import me.elaineqheart.auctionHouse.data.persistentStorage.local.configs.M;
import me.elaineqheart.auctionHouse.data.ram.AhConfiguration;
import me.elaineqheart.auctionHouse.data.ram.AuctionHouseStorage;
import me.elaineqheart.auctionHouse.data.ram.ItemManager;
import me.elaineqheart.auctionHouse.data.ram.ItemNote;
import me.elaineqheart.auctionHouse.pluginDependencies.VaultHook;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

public class CollectSoldItemGUI extends InventoryGUI {

    private final ItemNote note;
    private final ItemStack item;
    private final AhConfiguration c;
    private final double price;
    private final AhConfiguration.View goBackTo;

    public CollectSoldItemGUI(ItemNote note, AhConfiguration configuration, AhConfiguration.View goBackTo) {
        super();
        this.note = note;
        price = note.getSoldPrice();
        item =  ItemManager.createCollectingItemFromNote(note);
        c = configuration;
        c.setView(AhConfiguration.View.COLLECT_SOLD_ITEM);
        this.goBackTo = goBackTo;
    }

    @Override
    protected Inventory createInventory() {
        return Bukkit.createInventory(null,6*9, M.getFormattedComponent("inventory-titles.collect-sold"));
    }

    @Override
    public void decorate(Player player) {
        fillOutPlaces(new String[]{
                "# # # # # # # # #",
                "# # # # . # # # #",
                "# # # # # # # # #",
                "# # # # . # # # #",
                "# # # # # # # # #",
                "# # # # . # # # #"
        },fillerItem());
        this.addButton(13, buyingItem());
        this.addButton(31, collectItem());
        this.addButton(49, back());
        super.decorate(player);
    }

    private void fillOutPlaces(String[] places, InventoryButton fillerItem){
        for(int i = 0; i < places.length; i++){
            for(int j = 0; j < places[i].length(); j+=2){
                if(places[i].charAt(j)=='#') {
                    this.addButton(i*9+j/2, fillerItem);
                }
            }
        }
    }

    private InventoryButton fillerItem(){
        return new InventoryButton()
                .creator(player -> ItemManager.fillerItem)
                .consumer(event -> {});
    }
    private InventoryButton buyingItem() {
        return new InventoryButton()
                .creator(player -> item)
                .consumer(Sounds::click);
    }
    private InventoryButton back() {
        return new InventoryButton()
                .creator(player -> ItemManager.backToMyAuctions)
                .consumer(event -> {
                    Player p = (Player) event.getWhoClicked();
                    Sounds.click(event);
                    AuctionHouse.getGuiManager().openGUI(p, c, goBackTo);
                });
    }
    private InventoryButton collectItem() {
        return new InventoryButton()
                .creator(player -> ItemManager.collectSoldItem(getProfit(price)))
                .consumer(event -> {
                    Player p = (Player) event.getWhoClicked();

                    boolean success = collect(p, note.getNoteID(), item.getAmount(), price);
                    AuctionHouse.getGuiManager().openGUI(p, c, goBackTo);

                    if (!success) return;

                    Sounds.experience(event);
                    M.send(p, "chat.collect-sold-auction", getProfit(price),
                            "%amount%", String.valueOf(item.getAmount()),
                            "%item%", note.getItemName());
                });
    }

    public static boolean collect(OfflinePlayer p, UUID noteID, int itemAmount, double price) {
        if (p == null || noteID == null || itemAmount <= 0 || !Double.isFinite(price) || price < 0) return false;
        ItemNote note = AuctionHouseStorage.getNote(noteID);
        if (note == null) return false;
        if (!p.getUniqueId().equals(note.getPlayerUUID())) return false;
        if (Math.abs(note.getSoldPrice() - price) > 0.000001D) return false;
        Economy eco = VaultHook.getEconomy();
        double profit = getProfit(price);
        var deposit = eco.depositPlayer(p, profit);
        if (!deposit.transactionSuccess()) return false;
        boolean success = ItemNoteStorage.collectSoldAuctionItem(note, itemAmount, price);
        if (!success) {
            var rollback = eco.withdrawPlayer(p, profit);
            if (!rollback.transactionSuccess()) {
                AuctionHouse.getInstance().getLogger().severe(
                        "Failed to roll back conflicted seller payment for note " + noteID);
            }
            if (p instanceof Player onlinePlayer) M.send(onlinePlayer, "chat.non-existent");
            return false;
        }
        return true;
    }

    private static double getProfit(double price) {
        return Math.floor((price * 100 * (1 - SettingManager.taxRate))) / 100;
    }

}
