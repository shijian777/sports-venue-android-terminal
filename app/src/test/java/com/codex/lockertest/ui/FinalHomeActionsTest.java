package com.codex.lockertest.ui;

import com.codex.lockertest.model.FeatureAvailability;
import com.codex.lockertest.model.UnlockMethod;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class FinalHomeActionsTest {
    private static final FeatureAvailability ALL_LOCAL_FEATURES = method -> true;

    @Test
    public void knownServerCapabilitiesOverrideLocalFallback() {
        FinalHomeActions actions = FinalHomeActions.create(
                true, Collections.singletonList("3"), ALL_LOCAL_FEATURES);

        assertFalse(actions.faceVisible());
        assertTrue(actions.palmEnrollmentVisible());
    }

    @Test
    public void knownServerCapabilitiesRecognizeOnlyFinalFaceAndPalmCodes() {
        FinalHomeActions actions = FinalHomeActions.create(
                true, Arrays.asList("5", "3"), method -> false);

        assertTrue(actions.faceVisible());
        assertTrue(actions.palmEnrollmentVisible());
    }

    @Test
    public void unknownServerCapabilitiesUseInjectedLocalFallback() {
        FeatureAvailability fallback = method -> method == UnlockMethod.FACE;

        FinalHomeActions actions = FinalHomeActions.create(
                false, Collections.<String>emptyList(), fallback);

        assertTrue(actions.faceVisible());
        assertFalse(actions.palmEnrollmentVisible());
    }

    @Test
    public void malformedKnownServerCapabilitiesCannotEnableActions() {
        for (java.util.List<String> values : Arrays.<java.util.List<String>>asList(
                null,
                Collections.<String>emptyList(),
                Collections.singletonList(null),
                Arrays.asList("", " 3", "5 ", "face", "0"))) {
            FinalHomeActions actions = FinalHomeActions.create(
                    true, values, ALL_LOCAL_FEATURES);
            assertFalse(actions.faceVisible());
            assertFalse(actions.palmEnrollmentVisible());
        }
    }

    @Test
    public void oneMalformedServerValueInvalidatesTheWholeCapabilitySet() {
        for (java.util.List<String> values : Arrays.asList(
                Arrays.asList("3", null),
                Arrays.asList("5", "unknown"))) {
            FinalHomeActions actions = FinalHomeActions.create(
                    true, values, ALL_LOCAL_FEATURES);
            assertFalse(actions.faceVisible());
            assertFalse(actions.palmEnrollmentVisible());
        }
    }

    @Test
    public void missingOrBrokenFallbackCannotEnableActions() {
        FinalHomeActions missing = FinalHomeActions.create(
                false, null, null);
        assertFalse(missing.faceVisible());
        assertFalse(missing.palmEnrollmentVisible());

        FinalHomeActions broken = FinalHomeActions.create(
                false, null, method -> { throw new IllegalStateException("broken"); });
        assertFalse(broken.faceVisible());
        assertFalse(broken.palmEnrollmentVisible());
    }
}
