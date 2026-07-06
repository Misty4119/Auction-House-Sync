package me.elaineqheart.auctionHouse.listeners;

import me.elaineqheart.auctionHouse.AuctionHouse;
import me.elaineqheart.auctionHouse.GUI.impl.CollectSoldItemGUI;
import me.elaineqheart.auctionHouse.data.StringUtils;
import me.elaineqheart.auctionHouse.data.persistentStorage.local.SettingManager;
import me.elaineqheart.auctionHouse.data.persistentStorage.local.configs.M;
import me.elaineqheart.auctionHouse.data.ram.AuctionHouseStorage;
import me.elaineqheart.auctionHouse.data.ram.ItemNote;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public class PlayerJoinCollectListener implements Listener {

    private static final AuctionHouse instance = AuctionHouse.getInstance();

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if(!SettingManager.autoCollect) return;
        instance.getScheduler().globalRegionalScheduler().runDelayed(() -> {
            Player p = event.getPlayer();
            if (p == null || !p.isOnline()) return;
            // Iterate the player's notes (stored in MySQL + mirrored in RAM);
            // selling an item we no longer have locally falls back to the
            // CollectSoldItemGUI's MySQL hydration path. The point: a player
            // who joins on server B can still collect an item that was sold
            // while they were on server A.
            for(ItemNote note : AuctionHouseStorage.getMySortedDateCreated(p.getUniqueId())) sell(note, p);
        }, 20L);
    }

    public static void sell(ItemNote note, Player p) {
        if (note == null || p == null) return;
        if (!note.isSold() && !(note.isBIDAuction() && note.hasBidHistory() && note.isExpired())) return;
        int amount = note.getItem().getAmount() - note.getPartiallySoldAmountLeft();
        String priceStr;
        try { priceStr = StringUtils.formatPrice(note.getSoldPrice(), false); }
        catch (Throwable ignored) { priceStr = String.valueOf(note.getSoldPrice()); }
        boolean success = CollectSoldItemGUI.collect(p, note.getNoteID(), amount, note.getSoldPrice());
        if (!success || !p.isOnline()) return;
        if (SettingManager.soldMessageEnabled) {
            String message = M.getFormatted("chat.sold-message.auto-collect",
                    "%buyer%", M.formatBuyer(note.getBuyerName(), note.getBuyerUUID()),
                    "%item%", note.getItemName(),
                    "%amount%", String.valueOf(amount),
                    "%price%", priceStr);
            p.sendMessage(message);
        }
    }

}
