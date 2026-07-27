package me.elaineqheart.auctionHouse.GUI;

public final class Pagination {

    private Pagination() {}

    public static int lastPage(int itemCount, int pageSize) {
        if (itemCount <= 0 || pageSize <= 0) return 0;
        return (itemCount - 1) / pageSize;
    }

    public static int clampPage(int page, int itemCount, int pageSize) {
        return Math.max(0, Math.min(page, lastPage(itemCount, pageSize)));
    }
}
