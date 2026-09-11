package com.codex.lockertest.face;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

public final class FaceLivenessInferencePolicyTest {
    @Test
    public void disabledModeNeverCallsTheOptionalScorer() {
        FaceLivenessControl control = supported(false);
        AtomicBoolean called = new AtomicBoolean();

        FaceLivenessInferencePolicy.Decision decision =
                FaceLivenessInferencePolicy.evaluate(control, 0.80f, () -> {
                    called.set(true);
                    return 1.0f;
                });

        assertFalse(called.get());
        assertFalse(decision.livenessRequired());
        assertFalse(decision.livenessPassed());
        assertFalse(decision.capabilityChanged());
    }

    @Test
    public void enabledModeUsesStrictlyGreaterThanThreshold() {
        FaceLivenessControl control = supported(true);

        FaceLivenessInferencePolicy.Decision equal =
                FaceLivenessInferencePolicy.evaluate(control, 0.80f, () -> 0.80f);
        FaceLivenessInferencePolicy.Decision greater =
                FaceLivenessInferencePolicy.evaluate(control, 0.80f, () -> 0.81f);

        assertTrue(equal.livenessRequired());
        assertFalse(equal.livenessPassed());
        assertTrue(greater.livenessRequired());
        assertTrue(greater.livenessPassed());
    }

    @Test
    public void scorerExceptionDisablesCapabilityAndFallsBackOnTheSameFrame() {
        FaceLivenessControl control = supported(true);

        FaceLivenessInferencePolicy.Decision decision =
                FaceLivenessInferencePolicy.evaluate(control, 0.80f, () -> {
                    throw new IllegalStateException("native scorer unavailable");
                });

        assertFalse(decision.livenessRequired());
        assertFalse(decision.livenessPassed());
        assertTrue(decision.capabilityChanged());
        assertEquals(FaceLivenessControl.Capability.FAILED,
                control.snapshot().capability());
        assertEquals(FaceLivenessControl.Mode.ORDINARY_CAPTURE,
                control.snapshot().mode());
    }

    @Test
    public void invalidNativeScoreAlsoFallsBackInsteadOfFailingCapture() {
        for (float score : new float[] { Float.NaN, Float.POSITIVE_INFINITY, -1.0f, 1.1f }) {
            FaceLivenessControl control = supported(true);
            FaceLivenessInferencePolicy.Decision decision =
                    FaceLivenessInferencePolicy.evaluate(control, 0.80f, () -> score);
            assertFalse(decision.livenessRequired());
            assertTrue(decision.capabilityChanged());
            assertEquals(FaceLivenessControl.Mode.ORDINARY_CAPTURE,
                    control.snapshot().mode());
        }
    }

    private static FaceLivenessControl supported(boolean enabled) {
        FaceLivenessControl control = new FaceLivenessControl(new MemoryStore());
        control.onProbeStarted();
        control.onProbeSupported();
        control.setRequestedEnabled(enabled);
        return control;
    }

    private static final class MemoryStore implements FaceLivenessControl.BooleanStore {
        private boolean value;
        @Override public boolean read() { return value; }
        @Override public boolean write(boolean value) {
            this.value = value;
            return true;
        }
    }
}
