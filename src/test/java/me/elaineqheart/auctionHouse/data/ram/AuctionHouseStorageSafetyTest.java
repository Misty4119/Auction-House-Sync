package me.elaineqheart.auctionHouse.data.ram;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class AuctionHouseStorageSafetyTest {

    @AfterEach
    void clearStorage() {
        AuctionHouseStorage.clear();
    }

    @Test
    void checkRemoveToleratesMissingRamNoteAndBidIndex() {
        UUID missingNote = UUID.randomUUID();

        assertDoesNotThrow(() -> AuctionHouseStorage.checkRemove(missingNote));
    }
}
