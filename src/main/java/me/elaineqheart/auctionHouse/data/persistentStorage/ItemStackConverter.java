package me.elaineqheart.auctionHouse.data.persistentStorage;

import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;

import java.io.ByteArrayInputStream;
import java.io.ObjectInputFilter;
import java.util.Base64;

public class ItemStackConverter {

    public static final int MAX_ENCODED_LENGTH = 4 * 1024 * 1024;
    private static final String FORMAT_PREFIX = "v2:";
    private static final int MAX_DECODED_BYTES = 3 * 1024 * 1024;

    //private static final Type MAPTYPE = new TypeToken<Map<String, Object>>(){}.getType();

    public static String encode(ItemStack item) {
        if (item == null) return null;
        try {
            byte[] bytes = item.serializeAsBytes();
            if (bytes.length > MAX_DECODED_BYTES) {
                throw new IllegalArgumentException("Item payload exceeds " + MAX_DECODED_BYTES + " bytes");
            }
            return FORMAT_PREFIX + Base64.getEncoder().encodeToString(bytes);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
//        try {
//            Gson gson = new Gson();
//            return gson.toJson(item.serialize());
//        } catch (Exception e) {
//
//        }

    }

    public static ItemStack decode(String data) {
        if(data == null) return null;
        if (data.length() > MAX_ENCODED_LENGTH) {
            throw new IllegalArgumentException("Item payload exceeds " + MAX_ENCODED_LENGTH + " characters");
        }
        try {
            if (data.startsWith(FORMAT_PREFIX)) {
                byte[] decoded = Base64.getDecoder().decode(data.substring(FORMAT_PREFIX.length()));
                if (decoded.length > MAX_DECODED_BYTES) {
                    throw new IllegalArgumentException("Decoded item exceeds " + MAX_DECODED_BYTES + " bytes");
                }
                return ItemStack.deserializeBytes(decoded);
            }

            // Legacy 1.x/2.0.1 migration path. Keep it bounded; every later
            // write upgrades the value to the structured v2 format above.
            byte[] decoded = Base64.getMimeDecoder().decode(data);
            if (decoded.length > MAX_DECODED_BYTES) {
                throw new IllegalArgumentException("Decoded item exceeds " + MAX_DECODED_BYTES + " bytes");
            }
            try (ByteArrayInputStream inputStream = new ByteArrayInputStream(decoded);
                 BukkitObjectInputStream dataInput = new BukkitObjectInputStream(inputStream)) {
                dataInput.setObjectInputFilter(ObjectInputFilter.Config.createFilter(
                        "maxdepth=32;maxrefs=10000;maxarray=100000;maxbytes=3145728;*"));
                Object value = dataInput.readObject();
                if (!(value instanceof ItemStack item)) {
                    throw new IllegalArgumentException("Decoded payload is not an ItemStack");
                }
                return item;
            }
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
//        try {
//            Gson gson = new Gson();
//            Map<String, Object> map = gson.fromJson(data, MAPTYPE);
//            ItemStack item = ItemStack.deserialize(map);
//        } catch (JsonSyntaxException e) {
//
//        }
    }

}
