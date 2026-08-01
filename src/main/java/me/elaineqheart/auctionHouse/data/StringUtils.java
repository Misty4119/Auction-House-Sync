package me.elaineqheart.auctionHouse.data;

import me.elaineqheart.auctionHouse.data.persistentStorage.local.SettingManager;
import me.elaineqheart.auctionHouse.data.persistentStorage.local.configs.M;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.text.DecimalFormat;
import java.util.Locale;
import java.util.Objects;

public class StringUtils {

    private static final net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer LEGACY =
            net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection();
    private static final net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer PLAIN =
            net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText();

    /** Re-exported so other classes can build guaranteed-non-null names. */
    public static final String RESET = "\u00a7r";

    public static String escapeMiniMessage(String value) {
        if (value == null) return "";
        return net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().escapeTags(value);
    }

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
        if (item == null) return "Unknown";

        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            net.kyori.adventure.text.Component displayName = meta.displayName();
            if (displayName != null) {
                String plainName = PLAIN.serialize(displayName);
                if (!plainName.isBlank()) return plainName;
            }
        }

        try {
            if (meta != null && meta.hasItemName()) {
                String itemName = meta.getItemName();
                if (!itemName.isBlank()) return itemName;
            }
        } catch (NoSuchMethodError ignored) {
            // Compatibility with server implementations predating custom item names.
        }
        String materialName = item.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return materialName.isBlank() ? "Unknown" : materialName;
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
            double parsed = Double.parseDouble(input);
            if (!Double.isFinite(parsed)) return -1;
            double price = Math.max(parsed, 0);
            if(price % 1 != 0) throw new RuntimeException();
            return price;
        } catch (Exception e) {
            try{
                double price = Double.parseDouble(input.substring(0, input.length()-1));
                if (!Double.isFinite(price)) return -1;
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
                if (!Double.isFinite(price) || price % 1 != 0) throw new RuntimeException();
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
