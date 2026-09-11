package com.codex.lockertest.face;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class FaceLivenessControlTest {
    @Test
    public void switchStaysDisabledUntilAProbeProvesSupport() {
        MemoryStore store = new MemoryStore(false);
        FaceLivenessControl control = new FaceLivenessControl(store);

        assertSnapshot(control, FaceLivenessControl.Capability.WAITING_FOR_LICENSE,
                false, false, FaceLivenessControl.Mode.ORDINARY_CAPTURE);

        control.onProbeStarted();
        assertSnapshot(control, FaceLivenessControl.Capability.PROBING,
                false, false, FaceLivenessControl.Mode.ORDINARY_CAPTURE);

        control.onProbeSupported();
        assertSnapshot(control, FaceLivenessControl.Capability.SUPPORTED,
                true, false, FaceLivenessControl.Mode.ORDINARY_CAPTURE);
        assertFalse("support detection must not silently enable liveness", store.value);
    }

    @Test
    public void supportedSwitchEnablesAndPersistsRgbLivenessMode() {
        MemoryStore store = new MemoryStore(false);
        FaceLivenessControl control = supported(store);

        assertTrue(control.setRequestedEnabled(true));

        assertTrue(store.value);
        assertSnapshot(control, FaceLivenessControl.Capability.SUPPORTED,
                true, true, FaceLivenessControl.Mode.RGB_LIVENESS);
    }

    @Test
    public void closingSupportedSwitchReturnsToOrdinaryCapture() {
        MemoryStore store = new MemoryStore(true);
        FaceLivenessControl control = supported(store);
        assertSnapshot(control, FaceLivenessControl.Capability.SUPPORTED,
                true, true, FaceLivenessControl.Mode.RGB_LIVENESS);

        assertFalse(control.setRequestedEnabled(false));

        assertFalse(store.value);
        assertSnapshot(control, FaceLivenessControl.Capability.SUPPORTED,
                true, false, FaceLivenessControl.Mode.ORDINARY_CAPTURE);
    }

    @Test
    public void unsupportedCapabilityClearsPreferenceAndKeepsOrdinaryCaptureWorking() {
        MemoryStore store = new MemoryStore(true);
        FaceLivenessControl control = new FaceLivenessControl(store);
        control.onProbeStarted();

        control.onProbeUnsupported();

        assertFalse(store.value);
        assertFalse(control.setRequestedEnabled(true));
        assertSnapshot(control, FaceLivenessControl.Capability.UNSUPPORTED,
                false, false, FaceLivenessControl.Mode.ORDINARY_CAPTURE);
    }

    @Test
    public void initializationFailureIsDistinctButAlsoFallsBackToOrdinaryCapture() {
        MemoryStore store = new MemoryStore(true);
        FaceLivenessControl control = new FaceLivenessControl(store);
        control.onProbeStarted();

        control.onProbeFailed();

        assertFalse(store.value);
        assertFalse(control.setRequestedEnabled(true));
        assertSnapshot(control, FaceLivenessControl.Capability.FAILED,
                false, false, FaceLivenessControl.Mode.ORDINARY_CAPTURE);
    }

    @Test
    public void persistedAdministratorChoiceActivatesOnlyAfterSupportIsReconfirmed() {
        MemoryStore store = new MemoryStore(true);
        FaceLivenessControl control = new FaceLivenessControl(store);

        assertSnapshot(control, FaceLivenessControl.Capability.WAITING_FOR_LICENSE,
                false, false, FaceLivenessControl.Mode.ORDINARY_CAPTURE);
        control.onProbeStarted();
        assertSnapshot(control, FaceLivenessControl.Capability.PROBING,
                false, false, FaceLivenessControl.Mode.ORDINARY_CAPTURE);

        control.onProbeSupported();

        assertSnapshot(control, FaceLivenessControl.Capability.SUPPORTED,
                true, true, FaceLivenessControl.Mode.RGB_LIVENESS);
    }

    @Test
    public void losingBaseLicenseDisablesLivenessAndClearsPersistedChoice() {
        MemoryStore store = new MemoryStore(true);
        FaceLivenessControl control = supported(store);

        control.onLicenseUnavailable();

        assertFalse(store.value);
        assertSnapshot(control, FaceLivenessControl.Capability.WAITING_FOR_LICENSE,
                false, false, FaceLivenessControl.Mode.ORDINARY_CAPTURE);
    }

    @Test
    public void failedPersistenceNeverReportsOrEnablesLiveness() {
        MemoryStore store = new MemoryStore(false);
        FaceLivenessControl control = supported(store);
        store.failWrites = true;

        assertFalse(control.setRequestedEnabled(true));

        assertSnapshot(control, FaceLivenessControl.Capability.SUPPORTED,
                true, false, FaceLivenessControl.Mode.ORDINARY_CAPTURE);
    }

    @Test
    public void unconfirmedPersistenceNeverReportsOrEnablesLiveness() {
        MemoryStore store = new MemoryStore(false);
        FaceLivenessControl control = supported(store);
        store.rejectWrites = true;

        assertFalse(control.setRequestedEnabled(true));

        assertFalse(store.value);
        assertSnapshot(control, FaceLivenessControl.Capability.SUPPORTED,
                true, false, FaceLivenessControl.Mode.ORDINARY_CAPTURE);
    }

    private static FaceLivenessControl supported(MemoryStore store) {
        FaceLivenessControl control = new FaceLivenessControl(store);
        control.onProbeStarted();
        control.onProbeSupported();
        return control;
    }

    private static void assertSnapshot(FaceLivenessControl control,
            FaceLivenessControl.Capability capability, boolean switchEnabled,
            boolean requestedEnabled, FaceLivenessControl.Mode mode) {
        FaceLivenessControl.Snapshot snapshot = control.snapshot();
        assertEquals(capability, snapshot.capability());
        assertEquals(switchEnabled, snapshot.switchEnabled());
        assertEquals(requestedEnabled, snapshot.requestedEnabled());
        assertEquals(mode, snapshot.mode());
    }

    private static final class MemoryStore implements FaceLivenessControl.BooleanStore {
        private boolean value;
        private boolean failWrites;
        private boolean rejectWrites;

        MemoryStore(boolean value) {
            this.value = value;
        }

        @Override public boolean read() {
            return value;
        }

        @Override public boolean write(boolean value) {
            if (failWrites) throw new IllegalStateException("storage unavailable");
            if (rejectWrites) return false;
            this.value = value;
            return true;
        }
    }
}
