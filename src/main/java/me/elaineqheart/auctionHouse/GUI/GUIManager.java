package me.elaineqheart.auctionHouse.GUI;

import me.elaineqheart.auctionHouse.AuctionHouse;
import me.elaineqheart.auctionHouse.GUI.impl.AuctionHouseGUI;
import me.elaineqheart.auctionHouse.GUI.impl.MyAuctionsGUI;
import me.elaineqheart.auctionHouse.GUI.impl.MyBidsGUI;
import me.elaineqheart.auctionHouse.data.ram.AhConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.Inventory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

//https://www.spigotmc.org/threads/a-modern-approach-to-inventory-guis.594005/

//This is a manager class so it will be treated as a singleton. This means we only create one single
//instance of this class and no more.

public class GUIManager {

    private final Map<Inventory, InventoryHandler> activeInventories = new ConcurrentHashMap<>();

    public void openGUI(InventoryGUI gui, Player player) {
        if (gui == null || player == null || !player.isOnline()) return;
        runForPlayer(player, () -> {
            if (!player.isOnline()) return;
            this.registerHandledInventory(gui.getInventory(), gui);
            try {
                player.openInventory(gui.getInventory());
            } catch (RuntimeException ex) {
                this.unregisterInventory(gui.getInventory());
                throw ex;
            }
        });
    }

    public void closeGUI(Player player) {
        if (player == null) return;
        runForPlayer(player, player::closeInventory);
    }

    public void runForPlayer(Player player, Runnable task) {
        if (player == null || task == null) return;
        AuctionHouse plugin = AuctionHouse.getInstance();
        if (plugin == null || !plugin.isEnabled()) return;
        try {
            plugin.getScheduler().entitySpecificScheduler(player).run(task, () -> {});
        } catch (IllegalStateException ignored) {
            // The player may have left or the plugin may be shutting down.
        }
    }

    public void runForPlayerDelayed(Player player, Runnable task, long delayTicks) {
        if (player == null || task == null) return;
        AuctionHouse plugin = AuctionHouse.getInstance();
        if (plugin == null || !plugin.isEnabled()) return;
        try {
            plugin.getScheduler().entitySpecificScheduler(player)
                    .runDelayed(task, () -> {}, Math.max(1L, delayTicks));
        } catch (IllegalStateException ignored) {
            // The player may have left or the plugin may be shutting down.
        }
    }
    public void openGUI(Player p, AhConfiguration c, AhConfiguration.View goBackTo) {
        if (goBackTo == AhConfiguration.View.AUCTION_HOUSE) openGUI(new AuctionHouseGUI(c), p);
        else if (goBackTo == AhConfiguration.View.MY_AUCTIONS) openGUI(new MyAuctionsGUI(c), p);
        else if (goBackTo == AhConfiguration.View.MY_BIDS) openGUI(new MyBidsGUI(c,0), p);
    }

    public void registerHandledInventory(Inventory inventory, InventoryHandler handler) {
        this.activeInventories.put(inventory,handler);
    }

    public void unregisterInventory(Inventory inventory) {
        this.activeInventories.remove(inventory);
    }

    public void handleClick(InventoryClickEvent event) {
        InventoryHandler handler = this.activeInventories.get(event.getInventory());
        if (handler == null) return;
        handler.onClick(event);
    }

    public void handleOpen(InventoryOpenEvent event) {
        InventoryHandler handler = this.activeInventories.get(event.getInventory());

        if (handler != null){
            handler.onOpen(event);
        }
    }

    public void handleClose(InventoryCloseEvent event) {
        Inventory inventory = event.getInventory();
        InventoryHandler handler = this.activeInventories.get(inventory);
        if (handler != null){
            handler.onClose(event);
            this.unregisterInventory(inventory);
        }
    }

    public void forceCloseAll() {
        // onDisable is invoked after Bukkit has marked this plugin disabled.
        // Registering an entity task here is therefore illegal on Folia/Canvas.
        // The server owns closing player inventories during shutdown; only release
        // the plugin's references so no GUI state remains reachable.
        this.activeInventories.clear();
    }

}
