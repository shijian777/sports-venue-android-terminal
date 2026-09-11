package com.codex.lockertest.face;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import org.junit.Test;

public final class FaceLivenessProbeGateTest {
    @Test
    public void callbackCodesMapWithoutChangingTheBaseLicenseState() {
        assertCallback(0, FaceLivenessControl.Capability.SUPPORTED);
        assertCallback(10, FaceLivenessControl.Capability.UNSUPPORTED);
        assertCallback(9, FaceLivenessControl.Capability.FAILED);
    }

    @Test
    public void newerProbeRejectsAnOlderLateCallback() {
        FaceLivenessControl control = control();
        FaceLivenessProbeGate gate = new FaceLivenessProbeGate(control);
        assertTrue(gate.begin(1L, 11L));
        assertTrue(gate.begin(2L, 12L));

        assertFalse(gate.complete(1L, 11L, 0));
        assertEquals(FaceLivenessControl.Capability.PROBING,
                control.snapshot().capability());
        assertTrue(gate.complete(2L, 12L, 0));
        assertEquals(FaceLivenessControl.Capability.SUPPORTED,
                control.snapshot().capability());
    }

    @Test
    public void releaseAlwaysWinsAgainstAConcurrentSuccessCallback() throws Exception {
        for (int iteration = 0; iteration < 200; iteration++) {
            FaceLivenessControl control = control();
            FaceLivenessProbeGate gate = new FaceLivenessProbeGate(control);
            assertTrue(gate.begin(1L, 1L));
            CountDownLatch start = new CountDownLatch(1);
            Thread callback = new Thread(() -> {
                await(start);
                gate.complete(1L, 1L, 0);
            });
            Thread release = new Thread(() -> {
                await(start);
                gate.release();
            });
            callback.start();
            release.start();
            start.countDown();
            callback.join();
            release.join();

            assertEquals(FaceLivenessControl.Capability.WAITING_FOR_LICENSE,
                    control.snapshot().capability());
            assertFalse(gate.begin(2L, 2L));
        }
    }

    @Test
    public void baseRuntimeFailureGreysAPreviouslySupportedCapability() {
        FaceLivenessControl control = control();
        FaceLivenessProbeGate gate = new FaceLivenessProbeGate(control);
        assertTrue(gate.begin(3L, 7L));
        assertTrue(gate.complete(3L, 7L, 0));

        assertTrue(gate.runtimeUnavailable(3L, 7L));

        assertEquals(FaceLivenessControl.Capability.FAILED,
                control.snapshot().capability());
    }

    private static void assertCallback(int code,
            FaceLivenessControl.Capability capability) {
        FaceLivenessControl control = control();
        FaceLivenessProbeGate gate = new FaceLivenessProbeGate(control);
        assertTrue(gate.begin(1L, 2L));
        assertTrue(gate.complete(1L, 2L, code));
        assertEquals(capability, control.snapshot().capability());
    }

    private static FaceLivenessControl control() {
        return new FaceLivenessControl(new FaceLivenessControl.BooleanStore() {
            private boolean value;
            @Override public boolean read() { return value; }
            @Override public boolean write(boolean value) {
                this.value = value;
                return true;
            }
        });
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
