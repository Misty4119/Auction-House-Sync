package me.elaineqheart.auctionHouse.data.persistentStorage.local.configs;

import me.clip.placeholderapi.PlaceholderAPI;
import me.elaineqheart.auctionHouse.data.StringUtils;
import me.elaineqheart.auctionHouse.data.persistentStorage.local.SettingManager;
import me.elaineqheart.auctionHouse.data.persistentStorage.local.data.Config;
import me.elaineqheart.auctionHouse.data.persistentStorage.local.data.ConfigManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.text.DecimalFormat;

/**
 * Message / component helper around {@code messages.yml}.
 *
 * <p>The project ships MiniMessage-formatted messages (gradients, hex colours,
 * hover, click, ...). The previous implementation always converted those
 * messages to legacy {@code §}-formatted strings, which silently stripped the
 * MiniMessage tags. The current implementation always works with Adventure
 * {@link Component}s end-to-end:</p>
 *
 * <ul>
 *   <li>Inventory titles, item display names and lore, NPC names, text-display
 *       entities — all accept a {@link Component}.</li>
 *   <li>Player chat uses {@code Player.sendMessage(Component)} so the server
 *       keeps the full formatting pipeline.</li>
 *   <li>{@link SettingManager#useAdventureAPIMessages} only flips the parser
 *       between MiniMessage and legacy {@code §} so the project still loads on
 *       servers whose owners don't want MiniMessage support.</li>
 * </ul>
 */
public class M extends Config {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
    private static final MiniMessage MM = MiniMessage.miniMessage();
    /**
     * Lenient MiniMessage: ignores unknown tags instead of throwing, so a typo
     * in {@code messages.yml} or a placeholder that landed mid-tag won't blow
     * up the whole chat line.
     */
    private static final MiniMessage MM_LENIENT = MiniMessage.builder()
            .tags(TagResolver.standard())
            .strict(false)
            .build();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    public static FileConfiguration get() {
        return ConfigManager.messages.getCustomFile();
    }

    /** True when the key is missing, empty, or whitespace-only. */
    public static boolean isBlank(String key) {
        String message = get().getString(key);
        return message == null || message.isBlank();
    }

    private static String getRaw(String key, boolean convertNewLine) {
        String message = get().getString(key);
        if (message == null) {
            return ChatColor.RED + "Missing message key: " + key;
        }
        return convertNewLine ? message.replace("&n", "\n") : message;
    }

    private static boolean looksLikeMiniMessage(String input) {
        return input.contains("<") && input.contains(">");
    }

    /**
     * Parses the input as MiniMessage (when it looks like MiniMessage and the
     * owner enabled Adventure messages) and falls back to legacy {@code §}
     * section-sign decoding otherwise. {@code &}-prefixed colour codes are
     * always translated to {@code §} before MiniMessage sees them so admins
     * can still use shortcuts.
     */
    public static Component parse(String input) {
        if (input == null || input.isEmpty()) {
            return Component.empty();
        }
        String normalised = input.replace("&n", "\n");
        normalised = ChatColor.translateAlternateColorCodes('&', normalised);
        boolean adventure = SettingManager.useAdventureAPIMessages;
        if (adventure && looksLikeMiniMessage(normalised)) {
            try {
                return MM_LENIENT.deserialize(normalised);
            } catch (Exception ignored) {
            }
        }
        if (normalised.contains("§")) {
            return LEGACY.deserialize(normalised);
        }
        return Component.text(normalised);
    }

    /** Serialise a Component back to the legacy {@code §}-format. */
    public static String toLegacy(Component component) {
        if (component == null) {
            return "";
        }
        String serialised = LEGACY.serialize(component);
        return serialised == null ? "" : serialised;
    }

    /**
     * Convert a String processed for placeholders ({@code %xxx%} expanded,
     * MiniMessage tags still present) into a {@link Component}.
     */
    public static Component toComponent(String processedText) {
        if (processedText == null || processedText.isEmpty()) {
            return Component.empty();
        }
        return parse(processedText);
    }

    /** Plain-text representation of a component (no formatting). */
    public static String toPlain(Component component) {
        if (component == null) return "";
        return PLAIN.serialize(component);
    }

    private static boolean isEmptyComponent(Component component) {
        return component == null || toPlain(component).isBlank();
    }

    private static String formatRaw(String key, boolean convertNewLine, double[] prices, String... replacements) {
        if (isBlank(key)) {
            return "";
        }
        String message = getRaw(key, convertNewLine);
        message = replacePlaceholders(key, message, replacements);
        if (prices != null && prices.length > 0) {
            message = replace(message, prices);
        }
        return message;
    }

    private static Component formatComponent(String key, boolean convertNewLine, double[] prices, String... replacements) {
        return toComponent(formatRaw(key, convertNewLine, prices, replacements));
    }

    // -----------------------------------------------------------------
    // Legacy / "give me a string" API (kept for backwards compatibility
    // and for places that really need a String, like signs).
    // -----------------------------------------------------------------

    public static String getFormatted(String key, String... replacements) {
        return toLegacy(formatComponent(key, true, null, replacements));
    }

    public static String getFormatted(String key, double price, String... replacements) {
        return toLegacy(formatComponent(key, true, new double[]{price}, replacements));
    }

    /** Returns null when the key is blank or the formatted result is empty. */
    public static String getFormattedOrNull(String key, String... replacements) {
        if (isBlank(key)) {
            return null;
        }
        String result = getFormatted(key, replacements);
        return result.isBlank() ? null : result;
    }

    public static String getFormattedOrNull(String key, double price, String... replacements) {
        if (isBlank(key)) {
            return null;
        }
        String result = getFormatted(key, price, replacements);
        return result.isBlank() ? null : result;
    }

    /**
     * Get the formatted lore as a list of legacy strings, split on
     * {@code &n}. Kept around for code paths that still need a String list.
     */
    public static List<String> getLoreList(String key, String... replacements) {
        if (isBlank(key)) {
            return List.of();
        }
        String message = replacePlaceholders(key, getRaw(key, false), replacements);
        List<String> out = new ArrayList<>(Arrays.asList(message.split("&n", -1)));
        out.replaceAll(line -> line.isBlank() ? "" : toLegacy(toComponent(line)));
        return out;
    }

    public static List<String> getLoreList(String key, double price, String... replacements) {
        if (isBlank(key)) {
            return List.of();
        }
        String message = replacePlaceholders(key, getRaw(key, false), replacements);
        message = replace(message, price);
        List<String> out = new ArrayList<>(Arrays.asList(message.split("&n", -1)));
        out.replaceAll(line -> line.isBlank() ? "" : toLegacy(toComponent(line)));
        return out;
    }

    // -----------------------------------------------------------------
    // New Component-aware API. Prefer these for new code.
    // -----------------------------------------------------------------

    /** Resolve a message key to a Component, with placeholders and prices. */
    public static Component getFormattedComponent(String key, String... replacements) {
        return formatComponent(key, true, null, replacements);
    }

    public static Component getFormattedComponent(String key, double price, String... replacements) {
        return formatComponent(key, true, new double[]{price}, replacements);
    }

    /** Resolve a message key as a Component but keep it as a single line (no &n splitting). */
    public static Component getInlineComponent(String key, String... replacements) {
        return formatComponent(key, false, null, replacements);
    }

    public static Component getInlineComponent(String key, double price, String... replacements) {
        return formatComponent(key, false, new double[]{price}, replacements);
    }

    /** Get the formatted lore as a list of Components, split on {@code &n}. */
    public static List<Component> getLoreComponents(String key, String... replacements) {
        if (isBlank(key)) {
            return List.of();
        }
        String message = replacePlaceholders(key, getRaw(key, false), replacements);
        String[] lines = message.split("&n", -1);
        List<Component> out = new ArrayList<>(lines.length);
        for (String line : lines) {
            out.add(toComponent(line));
        }
        return out;
    }

    public static List<Component> getLoreComponents(String key, double price, String... replacements) {
        if (isBlank(key)) {
            return List.of();
        }
        String message = replacePlaceholders(key, getRaw(key, false), replacements);
        message = replace(message, price);
        String[] lines = message.split("&n", -1);
        List<Component> out = new ArrayList<>(lines.length);
        for (String line : lines) {
            out.add(toComponent(line));
        }
        return out;
    }

    /**
     * Replace placeholders in an already-fetched lore list, then convert the
     * result back to Components. Used when the source lore needs additional
     * price-aware replacements that don't live in the message key itself
     * (e.g. {@code items.submit-another-bid.lore} has three independent
     * prices).
     */
    public static List<Component> applyPriceReplacements(List<Component> lore, double... prices) {
        if (lore == null || lore.isEmpty() || prices == null || prices.length == 0) {
            return lore == null ? Collections.emptyList() : lore;
        }
        String joined = toLegacy(Component.join(joinSeparator(), lore));
        joined = replace(joined, prices);
        String[] lines = joined.split("\n", -1);
        List<Component> out = new ArrayList<>(lines.length);
        for (String line : lines) {
            out.add(toComponent(line));
        }
        return out;
    }

    private static Component joinSeparator() {
        return Component.newline();
    }

    // -----------------------------------------------------------------
    // Chat dispatch
    // -----------------------------------------------------------------

    public static void send(Player player, String key, String... replacements) {
        if (player == null || isBlank(key)) {
            return;
        }
        sendComponent(player, formatComponent(key, true, null, replacements));
    }

    public static void send(Player player, String key, double price, String... replacements) {
        if (player == null || isBlank(key)) {
            return;
        }
        sendComponent(player, formatComponent(key, true, new double[]{price}, replacements));
    }

    /** Send a pre-built Component to a player. */
    public static void sendComponent(Player player, Component component) {
        if (player == null || isEmptyComponent(component)) {
            return;
        }
        player.sendMessage(component);
    }

    /**
     * Send a chat line with a clickable suffix. The prefix and click parts are
     * resolved independently from {@code messages.yml} so admins can keep
     * tweaking them. The two pieces are concatenated as Adventure Components
     * so any MiniMessage styling (e.g. {@code [CLICK]} in lavender gradient)
     * is preserved.
     */
    public static void sendClickable(Player player, String prefixKey, String clickKey,
                                     String command, String... replacements) {
        if (player == null || isBlank(prefixKey)) {
            return;
        }
        Component prefix = formatComponent(prefixKey, true, null, replacements);
        if (isEmptyComponent(prefix)) {
            return;
        }
        if (isBlank(clickKey)) {
            player.sendMessage(prefix);
            return;
        }
        Component click = formatComponent(clickKey, true, null, replacements);
        if (isEmptyComponent(click)) {
            player.sendMessage(prefix);
            return;
        }
        Component clickable = click.clickEvent(ClickEvent.runCommand(command));
        player.sendMessage(prefix.append(Component.space()).append(clickable));
    }

    public static void sendClickable(Player player, String prefixKey, String clickKey,
                                     String command, double price, String... replacements) {
        if (player == null || isBlank(prefixKey)) {
            return;
        }
        Component prefix = formatComponent(prefixKey, true, new double[]{price}, replacements);
        if (isEmptyComponent(prefix)) {
            return;
        }
        if (isBlank(clickKey)) {
            player.sendMessage(prefix);
            return;
        }
        Component click = formatComponent(clickKey, true, new double[]{price}, replacements);
        if (isEmptyComponent(click)) {
            player.sendMessage(prefix);
            return;
        }
        Component clickable = click.clickEvent(ClickEvent.runCommand(command));
        player.sendMessage(prefix.append(Component.space()).append(clickable));
    }

    // -----------------------------------------------------------------
    // Placeholders
    // -----------------------------------------------------------------

    private static String replacePlaceholders(String key, String message, String... replacements) {
        if (replacements.length % 2 != 0) {
            return ChatColor.RED + "Invalid placeholder replacements for key: " + key;
        }
        for (int i = 0; i < replacements.length; i += 2) {
            message = message.replace(replacements[i], replacements[i + 1]);
        }
        return message;
    }

    public static String replace(String message, double... prices) {
        message = message.replace("%price%", StringUtils.formatPrice(prices[0], false));
        message = message.replace("%price-trim%", StringUtils.formatPrice(prices[0], true));
        message = message.replace("%number%", StringUtils.formatNumber(prices[0]));
        for (int i = 2; i - 1 < prices.length; i++) {
            message = message.replace("%price" + i + "%", StringUtils.formatPrice(prices[i - 1], false));
            message = message.replace("%price-trim" + i + "%", StringUtils.formatPrice(prices[i - 1], true));
            message = message.replace("%number" + i + "%", StringUtils.formatNumber(prices[i - 1]));
        }
        return message;
    }

    /**
     * Inline-formatted number that keeps MiniMessage tags unparsed. Use this
     * when you intend to drop the value into another MiniMessage template via
     * the {@code %number%} / {@code %price%} / etc. placeholders so the
     * receiving {@link #parse(String)} call resolves the styling.
     */
    public static String formatPricePlaceholder(double price, boolean trimmed) {
        String numberTag = resolveNumberTag(price, trimmed);
        String currencyTag = resolveCurrencyTag();
        return resolvePriceTag(numberTag, currencyTag);
    }

    public static String formatNumberPlaceholder(double number) {
        return resolveNumberTag(number, false);
    }

    private static String resolveNumberTag(double number, boolean trimmed) {
        String numberTemplate = get().getString("placeholders.number", "<color:#9CC3FF>%input%</color>");
        String currencyTemplate = get().getString("placeholders.format-numbers", "#,###.##");
        DecimalFormat fmt = SettingManager.formatter != null ? SettingManager.formatter : new DecimalFormat(currencyTemplate);
        String rendered;
        if (trimmed) {
            rendered = getPriceTrimmed(number);
        } else {
            rendered = fmt.format(number);
        }
        return numberTemplate.replace("%input%", rendered);
    }

    private static String resolveCurrencyTag() {
        return get().getString("placeholders.currency-symbol", "<color:#B89BE8> coins</color>");
    }

    private static String resolvePriceTag(String numberTag, String currencyTag) {
        String priceTemplate = get().getString("placeholders.price", "%number%" + "<color:#B89BE8> coins</color>");
        return priceTemplate.replace("%number%", numberTag).replace("%currency-symbol%", currencyTag);
    }

    private static String getPriceTrimmed(double price) {
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

    /** Resolve the {@code placeholders.player} template to a String (legacy section). */
    public static String formatPlayer(String playerName, UUID playerID) {
        return resolveNameTemplate("placeholders.player", playerName, playerID);
    }

    public static String formatSeller(String playerName, UUID playerID) {
        return resolveNameTemplate("placeholders.seller", playerName, playerID);
    }

    public static String formatBuyer(String playerName, UUID playerID) {
        return resolveNameTemplate("placeholders.buyer", playerName, playerID);
    }

    /** Same as {@link #formatPlayer}, but returns an Adventure Component. */
    public static Component formatPlayerComponent(String playerName, UUID playerID) {
        return toComponent(resolveNameTemplate("placeholders.player", playerName, playerID));
    }

    public static Component formatSellerComponent(String playerName, UUID playerID) {
        return toComponent(resolveNameTemplate("placeholders.seller", playerName, playerID));
    }

    public static Component formatBuyerComponent(String playerName, UUID playerID) {
        return toComponent(resolveNameTemplate("placeholders.buyer", playerName, playerID));
    }

    private static String resolveNameTemplate(String templateKey, String playerName, UUID playerID) {
        if (playerName == null) {
            return "";
        }
        String template = get().getString(templateKey, "%player_name%");
        String result = template.replace("%player_name%", playerName);

        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI") && playerID != null) {
            OfflinePlayer target = Bukkit.getOfflinePlayer(playerID);
            result = PlaceholderAPI.setPlaceholders(target, result);
        }

        return result;
    }

}