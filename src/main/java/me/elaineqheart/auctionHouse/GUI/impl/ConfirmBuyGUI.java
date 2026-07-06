package me.elaineqheart.auctionHouse.GUI.impl;

import me.elaineqheart.auctionHouse.AuctionHouse;
import me.elaineqheart.auctionHouse.GUI.InventoryButton;
import me.elaineqheart.auctionHouse.data.StringUtils;
import me.elaineqheart.auctionHouse.GUI.InventoryGUI;
import me.elaineqheart.auctionHouse.GUI.other.Sounds;
import me.elaineqheart.auctionHouse.data.persistentStorage.ItemNoteStorage;
import me.elaineqheart.auctionHouse.data.persistentStorage.database.CrossServerMessenger;
import me.elaineqheart.auctionHouse.data.persistentStorage.local.SettingManager;
import me.elaineqheart.auctionHouse.data.persistentStorage.local.configs.M;
import me.elaineqheart.auctionHouse.data.ram.AhConfiguration;
import me.elaineqheart.auctionHouse.data.ram.AuctionHouseStorage;
import me.elaineqheart.auctionHouse.data.ram.ItemManager;
import me.elaineqheart.auctionHouse.data.ram.ItemNote;
import me.elaineqheart.auctionHouse.pluginDependencies.VaultHook;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

public class ConfirmBuyGUI extends InventoryGUI{

    private final ItemNote note;
    private final ItemStack item;
    private final AhConfiguration c;
    private final double price;
    private static final AuctionHouse instance = AuctionHouse.getInstance();

    public ConfirmBuyGUI(ItemNote note, AhConfiguration configuration, ItemStack item) {
        super();
        this.note = note;
        this.item = item;
        c = configuration;
        price = note.getPrice() / note.getItem().getAmount() * item.getAmount();
    }

    @Override
    protected Inventory createInventory() {
        return Bukkit.createInventory(null,3*9, M.getFormattedComponent("inventory-titles.auction-view"));
    }

    @Override
    public void decorate(Player player) {
        fillOutPlaces(new String[]{
                "# # # # # # # # #",
                "# # . # . # . # #",
                "# # # # # # # # #"
        },fillerItem());
        this.addButton(11, confirm());
        this.addButton(13, buyingItem());
        this.addButton(15, cancel());
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
    private InventoryButton buyingItem(){
        return new InventoryButton()
                .creator(player -> ItemManager.createBuyingItemDisplay(item.clone()))
                .consumer(event -> {});
    }
    private InventoryButton confirm(){
        return new InventoryButton()
                .creator(player -> ItemManager.createConfirm(price))
                .consumer(event -> {
                    Player p = (Player) event.getWhoClicked();
                    if(p.getInventory().firstEmpty() == -1) {
                        M.send(p, "chat.inventory-full");
                        Sounds.villagerDeny(event);
                        return;
                    }

                    ItemNote test = AuctionHouseStorage.getNote(note.getNoteID());
                    if (test == null) {
                        M.send(p, "chat.non-existent2");
                        Sounds.villagerDeny(event);
                        return;
                    }
                    if (!test.isTheoreticallyOnAuction() || test.getCurrentAmount() < item.getAmount()) {
                        M.send(p, "chat.already-sold2");
                        Sounds.villagerDeny(event);
                        return;
                    }
                    if (test.isExpired()) {
                        M.send(p, "chat.expired");
                        Sounds.villagerDeny(event);
                        return;
                    }
                    Economy eco = VaultHook.getEconomy();
                    instance.getScheduler().globalRegionalScheduler().run(() -> AuctionHouse.getGuiManager().openGUI(new AuctionHouseGUI(c), p));
                    if (eco.getBalance(p) < price) {
                        M.send(p, "chat.not-enough-money");
                        Sounds.villagerDeny(event);
                        return;
                    }
                    boolean claimed = ItemNoteStorage.setSoldIfOnAuction(note, p, item.getAmount(), price);
                    if (!claimed) {
                        M.send(p, "chat.already-sold");
                        Sounds.villagerDeny(event);
                        return;
                    }

                    eco.withdrawPlayer(p, price);
                    Sounds.experience(event);
                    p.getInventory().addItem(item);

                    M.send(p, "chat.purchase-auction",
                            "%seller%", M.formatSeller(note.getPlayerName(), note.getPlayerUUID()),
                            "%item%", note.getItemName());
                    UUID sellerUuid = note.getPlayerUUID();
                    String itemName = note.getItemName();
                    String amount = String.valueOf(item.getAmount());
                    String buyer = M.formatBuyer(p.getDisplayName(), p.getUniqueId());
                    String priceStr = formatPriceForBroadcast(price);
                    if (SettingManager.soldMessageEnabled) {
                        if (SettingManager.autoCollect) {
                            CrossServerMessenger.sendToPlayer(sellerUuid,
                                    "chat.sold-message.auto-collect",
                                    "%buyer%", buyer,
                                    "%item%", itemName,
                                    "%amount%", amount,
                                    "%price%", priceStr);
                        } else {
                            Player seller = Bukkit.getPlayer(sellerUuid);
                            String viewCommand = "/ah view " + note.getNoteID();
                            if (seller != null && seller.isOnline()) {
                                M.sendClickable(seller,
                                        "chat.sold-message.prefix",
                                        "chat.sold-message.interaction",
                                        viewCommand,
                                        "%buyer%", buyer,
                                        "%item%", itemName,
                                        "%amount%", amount,
                                        "%price%", priceStr);
                                CrossServerMessenger.sendToPlayerRemoteOnly(sellerUuid,
                                        "chat.sold-message.prefix",
                                        "%buyer%", buyer,
                                        "%item%", itemName,
                                        "%amount%", amount,
                                        "%price%", priceStr);
                            } else {
                                CrossServerMessenger.sendToPlayer(sellerUuid,
                                        "chat.sold-message.prefix",
                                        "%buyer%", buyer,
                                        "%item%", itemName,
                                        "%amount%", amount,
                                        "%price%", priceStr);
                            }
                        }
                    }
                    if (SettingManager.autoCollect) {
                        Player localSeller = Bukkit.getPlayer(sellerUuid);
                        if (localSeller != null && localSeller.isOnline()) {
                            final Player sellerRef = localSeller;
                            instance.getScheduler().globalRegionalScheduler().run(() ->
                                    CollectSoldItemGUI.collect(sellerRef, note.getNoteID(),
                                            item.getAmount(), note.getSoldPrice()));
                        }
                    }
                });
    }
    private InventoryButton cancel(){
        return new InventoryButton()
                .creator(player -> ItemManager.cancel)
                .consumer(event -> {
                    Player p = (Player) event.getWhoClicked();
                    Sounds.click(event);
                    AuctionHouse.getGuiManager().openGUI(new AuctionHouseGUI(c), p);
                });
    }

    private static String formatPriceForBroadcast(double price) {
        try {
            return StringUtils.formatPrice(price, false);
        } catch (Throwable ignored) {
            return String.valueOf(price);
        }
    }

}
