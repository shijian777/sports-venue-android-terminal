package com.codex.lockertest.palm;

final class PalmUsbDeviceFilter {
    private static final int VENDOR_ID = 42921;
    private static final int MIN_PRODUCT_ID = 1559;
    private static final int MAX_PRODUCT_ID = 1568;

    private PalmUsbDeviceFilter() {}

    static boolean isSupported(int vendorId, int productId) {
        return vendorId == VENDOR_ID
                && productId >= MIN_PRODUCT_ID
                && productId <= MAX_PRODUCT_ID;
    }
}
