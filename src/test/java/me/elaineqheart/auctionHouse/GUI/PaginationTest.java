package me.elaineqheart.auctionHouse.GUI;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PaginationTest {

    @Test
    void clampsAStalePageAfterResultsShrink() {
        assertEquals(0, Pagination.clampPage(15, 0, 7));
        assertEquals(1, Pagination.clampPage(15, 8, 7));
    }

    @Test
    void clampsNegativePagesAndHandlesMissingSlots() {
        assertEquals(0, Pagination.clampPage(-1, 20, 7));
        assertEquals(0, Pagination.clampPage(3, 20, 0));
    }
}
