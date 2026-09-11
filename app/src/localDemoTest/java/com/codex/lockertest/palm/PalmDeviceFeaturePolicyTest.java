package com.codex.lockertest.palm;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class PalmDeviceFeaturePolicyTest {
    @Test
    public void acceptsOnlyNonzeroDeviceFeatureOfExactLength() {
        byte[] valid = featureWithMarker(559);

        assertTrue(PalmDeviceFeaturePolicy.isValid(valid));
        assertFalse(PalmDeviceFeaturePolicy.isValid(null));
        assertFalse(PalmDeviceFeaturePolicy.isValid(new byte[0]));
        assertFalse(PalmDeviceFeaturePolicy.isValid(new byte[559]));
        assertFalse(PalmDeviceFeaturePolicy.isValid(new byte[561]));
        assertFalse(PalmDeviceFeaturePolicy.isValid(new byte[560]));
    }

    @Test
    public void validatedCopyOwnsIndependentBytes() {
        byte[] source = featureWithMarker(17);

        byte[] copy = PalmDeviceFeaturePolicy.copyValidated(source);

        assertNotSame(source, copy);
        assertArrayEquals(source, copy);
        source[17] = 0;
        assertEquals(1, copy[17]);
        assertNull(PalmDeviceFeaturePolicy.copyValidated(new byte[560]));
    }

    @Test
    public void successfulCodeRequiresValidatedFeature() {
        assertEquals(PalmDeviceFeaturePolicy.Decision.SUCCESS,
                PalmDeviceFeaturePolicy.classify(0, featureWithMarker(1)));
        assertEquals(PalmDeviceFeaturePolicy.Decision.FAILURE,
                PalmDeviceFeaturePolicy.classify(0, new byte[560]));
        assertEquals(PalmDeviceFeaturePolicy.Decision.FAILURE,
                PalmDeviceFeaturePolicy.classify(0, null));
    }

    @Test
    public void inProgressAndKnownCaptureQualityCodesWaitForDeadline() {
        assertEquals(PalmDeviceFeaturePolicy.Decision.WAIT,
                PalmDeviceFeaturePolicy.classify(102, null));
        int[] waitCodes = {301, 302, 303, 351, 352, 353, 354, 355, 358, 359};
        for (int code : waitCodes) {
            assertEquals("code " + code, PalmDeviceFeaturePolicy.Decision.WAIT,
                    PalmDeviceFeaturePolicy.classify(code, null));
        }
    }

    @Test
    public void parameterStateAndUnknownErrorsFailImmediately() {
        assertEquals(PalmDeviceFeaturePolicy.Decision.FAILURE,
                PalmDeviceFeaturePolicy.classify(101, null));
        assertEquals(PalmDeviceFeaturePolicy.Decision.FAILURE,
                PalmDeviceFeaturePolicy.classify(103, null));
        assertEquals(PalmDeviceFeaturePolicy.Decision.FAILURE,
                PalmDeviceFeaturePolicy.classify(777, null));
    }

    private static byte[] featureWithMarker(int index) {
        byte[] feature = new byte[560];
        feature[index] = 1;
        return feature;
    }
}
