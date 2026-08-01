package me.elaineqheart.auctionHouse.data.persistentStorage.database;

import me.elaineqheart.auctionHouse.data.ram.ItemNote;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RedisSyncManagerTest {

    @Test
    void remoteUpsertUpdatesBuyerIdentityOnExistingNote() throws Exception {
        UUID noteId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        UUID buyerId = UUID.randomUUID();
        ItemNote existing = new ItemNote(
                noteId, "seller", sellerId, null, null,
                "item", 50.0D, new Date(1L), null,
                false, false, 0, null, 3600L);

        Map<String, String> soldSnapshot = new HashMap<>();
        soldSnapshot.put("buyerName", "buyer");
        soldSnapshot.put("buyerUUID", buyerId.toString());
        soldSnapshot.put("isSold", "true");

        Method apply = RedisSyncManager.class.getDeclaredMethod(
                "applyHashToLocal", ItemNote.class, Map.class);
        apply.setAccessible(true);
        apply.invoke(null, existing, soldSnapshot);

        assertEquals("buyer", existing.getBuyerName());
        assertEquals(buyerId, existing.getBuyerUUID(),
                "Remote sale must not leave RAM buyer UUID stale against MySQL");
    }
}
