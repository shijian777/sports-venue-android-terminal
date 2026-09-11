package com.codex.lockertest.palm;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class PalmUsbDeviceFilterTest {
    @Test
    public void acceptsVendorProductRangeBoundaries() {
        assertTrue(PalmUsbDeviceFilter.isSupported(42921, 1559));
        assertTrue(PalmUsbDeviceFilter.isSupported(42921, 1568));
    }

    @Test
    public void rejectsProductOutsideRangeAndUnrelatedVendor() {
        assertFalse(PalmUsbDeviceFilter.isSupported(42921, 1558));
        assertFalse(PalmUsbDeviceFilter.isSupported(42921, 1569));
        assertFalse(PalmUsbDeviceFilter.isSupported(42920, 1559));
        assertFalse(PalmUsbDeviceFilter.isSupported(42922, 1568));
    }
}
