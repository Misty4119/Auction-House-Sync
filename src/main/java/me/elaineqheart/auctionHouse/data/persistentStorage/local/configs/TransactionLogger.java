package me.elaineqheart.auctionHouse.data.persistentStorage.local.configs;

import me.elaineqheart.auctionHouse.AuctionHouse;
import me.elaineqheart.auctionHouse.data.persistentStorage.database.MySQLMetaStore;
import me.elaineqheart.auctionHouse.data.persistentStorage.database.RedisSyncManager;
import me.elaineqheart.auctionHouse.data.persistentStorage.local.SettingManager;
import me.elaineqheart.auctionHouse.data.persistentStorage.local.data.Config;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.UUID;

/**
 * Audit logger for auctions.
 *
 * <p>When MySQL is enabled every entry is pushed into the {@code
 * ah_transaction_log} table and a Redis pub/sub event is broadcast so other
 * servers can refresh any on-screen audit feed. The per-day log file under
 * {@code logs/} is still produced as a local mirror so operators retain a
 * familiar file-based audit trail.</p>
 */
public class TransactionLogger extends Config {

    public void logTransaction(String buyer, String seller, String item, double price, int amount, boolean isBID) {
        writeLogLine("BUY", buyer + " <- " + seller, item, price, amount, isBID, null);
    }

    public void logSetUpAuction(String player, String item, double price, int amount, boolean isBID) {
        writeLogLine("CREATE", player, item, price, amount, isBID, null);
    }

    public void logCancelAuction(String player, String item, double price, int amount, boolean isBID) {
        writeLogLine("CANCEL", player, item, price, amount, isBID, null);
    }

    public void logExpiredAuction(String player, String item, double price, int amount, boolean isBID) {
        writeLogLine("EXPIRE", player, item, price, amount, isBID, null);
    }

    public void logAdminExpiredAuction(String player, String item, double price, int amount, boolean isBID) {
        writeLogLine("ADMIN_EXPIRE", player, item, price, amount, isBID, null);
    }

    public void logAdminDeletedAuction(String player, String item, double price, int amount, boolean isBID) {
        writeLogLine("ADMIN_DELETE", player, item, price, amount, isBID, null);
    }

    public void logPurge(String player, String item, double price, int amount, boolean isBID) {
        writeLogLine("PURGE", player, item, price, amount, isBID, null);
    }

    private void writeLogLine(String kind, String player, String item, double price,
                              int amount, boolean isBID, UUID noteId) {
        String timeStamp = getTimeStamp();
        String entry = String.format("[%s] %s | Player: %s | Item: %s | Amount: %d | Price: %.2f | BID: %b",
                timeStamp, kind, player, item, amount, price, isBID);

        // 1) Append to the local file (regardless of backend). Operators
        //    still expect a .log file under <datafolder>/logs/.
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(getFile(), true))) {
            writer.write(entry);
            writer.newLine();
        } catch (IOException e) {
            AuctionHouse.getInstance().getLogger().warning(
                    "Could not write transaction log file: " + e.getMessage());
        }

        // 2) Persist to MySQL and broadcast.
        if (SettingManager.useMetaPersistence()) {
            MySQLMetaStore.appendLog(kind, player, item, amount, price, isBID, noteId);
            if (SettingManager.useMetaRedisCache()) {
                MySQLMetaStore.LogRow row = new MySQLMetaStore.LogRow(
                        System.currentTimeMillis(), kind, player, item, amount, price, isBID, noteId);
                RedisSyncManager.publishLogAppend(row);
            }
        }
    }

    private String getTimeStamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
    }

    public String getNewName() {
        Date date = new Date();
        var localDate = date.toInstant()
                .atZone(ZoneId.systemDefault())
                .toLocalDate();
        String formattedDate = localDate.format(DateTimeFormatter.ISO_LOCAL_DATE);

        int number = 1;
        File folder = new File(AuctionHouse.getInstance().getDataFolder() + "/logs");
        File[] listOfFiles = folder.listFiles();
        if (listOfFiles != null) {
            for (File file : listOfFiles) {
                if (!file.isFile()) continue;
                String fileDate = file.getName().substring(0,10);
                if(!fileDate.equals(formattedDate)) continue;
                int fileNumber = Integer.parseInt(file.getName().replaceFirst(".log", "").substring(11));
                if(fileNumber >= number) number = fileNumber+1;
            }
        }
        return formattedDate + "-" + number + ".log";
    }

    @Override
    public void reload(){}
}