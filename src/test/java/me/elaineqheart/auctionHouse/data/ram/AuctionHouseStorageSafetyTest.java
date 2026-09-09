package me.elaineqheart.auctionHouse.data.ram;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void bidClaimantsIncludeSellerWithoutTurningBinAuctionsIntoBids() {
        UUID bidder = UUID.randomUUID();
        UUID seller = UUID.randomUUID();

        assertEquals(Set.of(bidder, seller),
                AuctionHouseStorage.collectBidClaimants(true, List.of(bidder), seller));
        assertEquals(Set.of(),
                AuctionHouseStorage.collectBidClaimants(false, List.of(bidder), seller));
    }
}
