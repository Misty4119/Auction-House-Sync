package me.elaineqheart.auctionHouse.data;

import me.elaineqheart.auctionHouse.data.persistentStorage.local.SettingManager;
import me.elaineqheart.auctionHouse.data.persistentStorage.local.configs.M;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;

import java.text.DecimalFormat;
import java.util.Objects;

public class StringUtils {

    private static final net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer LEGACY =
            net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection();
    private static final net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer PLAIN =
            net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText();

    /** Re-exported so other classes can build guaranteed-non-null names. */
    public static final String RESET = ChatColor.RESET.toString();

    public static String getTime(Long seconds, boolean convertDays) { //output example: 4h 23m 59s
        StringBuilder s = new StringBuilder();
        if (seconds == null || seconds < 0) seconds = 0L;
        int sec = (int) ((seconds)%60);
        int min = (int) ((seconds/60)%60);
        int hours = convertDays ? (int) (seconds/60/60%24) : (int) (seconds/60/60);
        int days = (int) (seconds / 60 / 60 / 24);
        if (convertDays && days != 0) {
            s.append(days).append(SettingManager.formatTimeCharacters.charAt(0)).append(' ');
        }
        s.append(pad(hours)).append(SettingManager.formatTimeCharacters.charAt(1)).append(' ');
        s.append(pad(min)).append(SettingManager.formatTimeCharacters.charAt(2)).append(' ');
        s.append(pad(sec)).append(SettingManager.formatTimeCharacters.charAt(3));
        return s.toString();
    }

    private static String pad(int value) {
        return value < 10 ? "0" + value : String.valueOf(value);
    }
    //dhms

    public static String getTimeTrimmed(long seconds) { //output example: 4h
        if(seconds < 60) {
            return seconds + SettingManager.formatTimeCharacters.substring(3,4);
        } else if(seconds < 60*60) {
            return (int)(seconds/60) + SettingManager.formatTimeCharacters.substring(2,3);
        } else {
            return (int)(seconds/60/60) + SettingManager.formatTimeCharacters.substring(1,2);
        }
    }

    public static String formatNumber(double number) {
        return M.formatNumberPlaceholder(number);
    }
    public static String formatNumberPlain(double number) {
        // fallback for async threads
        DecimalFormat fmt = Objects.requireNonNullElseGet(SettingManager.formatter, () ->
                new DecimalFormat(M.getFormatted("placeholders.format-numbers")));
        return fmt.format(number);
    }
    public static String formatNumber(String number) {
        return M.getFormatted("placeholders.number", "%input%", number);
    }

    public static String formatPrice(double price, boolean trimmed) {
        return M.formatPricePlaceholder(price, trimmed);
    }

    public static String getItemName(ItemStack item) {
        if (item == null) {
            return "Unknown";
        }
        String name = null;
        try {
            World world = Bukkit.getWorlds().getFirst();
            if (world != null) {
                Item itemEntity = (Item) world.spawnEntity(new Location(world, 0, 0, 0), EntityType.ITEM);
                itemEntity.setItemStack(item);
                name = itemEntity.getName();
                itemEntity.remove();
            }
        } catch (Throwable t) {
            // Some servers (or odd lifecycles) throw when we try to spawn an
            // entity — fall back to material name.
        }
        if (name == null || name.isEmpty()) {
            try {
                String mat = item.getType() == null ? "Unknown" : item.getType().name();
                name = mat.toLowerCase().replace('_', ' ');
            } catch (Throwable t) {
                name = "Unknown";
            }
        }
        if (item.getItemMeta() != null && item.getItemMeta().hasDisplayName()) {
            net.kyori.adventure.text.Component displayName = item.getItemMeta().displayName();
            if (displayName != null) {
                name = PLAIN.serialize(displayName);
            }
        }
        return name;
    }

    /**
     * Strip the legacy {@code §}-section glyphs from a string. Used for
     * cached/serialised item names that still carry old legacy codes and are
     * about to be interpolated into a MiniMessage template.
     *
     * <p>All of Minecraft's legacy colour/format codes are explicitly listed so
     * the regex is portable and never throws {@link java.util.regex.PatternSyntaxException}
     * at runtime (e.g. if Java's regex engine ever tightens its character-range
     * validation).</p>
     */
    public static String stripLegacySection(String input) {
        if (input == null || input.isEmpty()) return input;
        StringBuilder out = new StringBuilder(input.length());
        int i = 0;
        while (i < input.length()) {
            char c = input.charAt(i);
            if (c == '\u00A7' && i + 1 < input.length()
                    && LEGACY_CODE_CHARS.indexOf(Character.toLowerCase(input.charAt(i + 1))) >= 0) {
                i += 2;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static final String LEGACY_CODE_CHARS = "0123456789abcdefklmnorx";

    public static double parsePositiveNumber(String input) {
        try{
            double price = Math.max(Double.parseDouble(input), 0);
            if(price % 1 != 0) throw new RuntimeException();
            return price;
        } catch (Exception e) {
            try{
                double price = Double.parseDouble(input.substring(0, input.length()-1));
                String suffix = input.substring(input.length()-1).toLowerCase();
                switch (suffix) {
                    case "k":
                        price *= 1000;
                        break;
                    case "m":
                        price *= 1000000;
                        break;
                    default:
                        return -1;
                }
                if(price % 1 != 0) throw new RuntimeException();
                return Math.max(price, 0);
            } catch (Exception f) {
                return -1;
            }
        }
    }

    public static String getPriceTrimmed(double price) {
        if (price < 1000) {
            return String.valueOf(price);
        } else if (price < 1000000) {
            return String.format("%.1fK", price / 1000.0);
        } else if (price < 1000000000) {
            return String.format("%.1fM", price / 1000000.0);
        } else {
            return String.format("%.1fB", price / 1000000000.0);
        }
    }

}
