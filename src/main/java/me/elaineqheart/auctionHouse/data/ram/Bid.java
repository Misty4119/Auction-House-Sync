package me.elaineqheart.auctionHouse.data.ram;

import me.elaineqheart.auctionHouse.data.StringUtils;
import me.elaineqheart.auctionHouse.data.persistentStorage.local.SettingManager;
import org.bukkit.entity.Player;

import java.util.Date;
import java.util.UUID;

public class Bid {

    private final UUID player;
    private final String playerName;
    private final Date date;
    private final double bid;

    public Bid(Player player, Date date, double bid) {
        this.player = player.getUniqueId();
        this.playerName = player.getDisplayName();
        this.date = date;
        this.bid = bid;
    }

    /** Constructor used by the persistence / sync layer. */
    public Bid(UUID player, String playerName, Date date, double bid) {
        this.player = player;
        this.playerName = playerName;
        this.date = date;
        this.bid = bid;
    }

    public UUID getPlayerID() {return player;}
    public String getPlayerName() {return playerName;}
    public double getPrice() {return bid;}
    public Date getDate() { return date; }
    /** Epoch milliseconds of when this bid was placed. Used by the sync layer. */
    public long getTimeMs() { return date == null ? 0L : date.getTime(); }
    public String getTimeAgo() {
        long seconds = (new Date().getTime() - date.getTime()) / 1000;
        return StringUtils.getTimeTrimmed(seconds);
    }
    public static double nextMinBid(double bid) {return Math.ceil(SettingManager.bidIncreaseRatio * bid + bid);}
}
