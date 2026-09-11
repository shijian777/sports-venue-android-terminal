package com.codex.lockertest.face;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.junit.Test;

public final class FaceFrameQualityGateTest {
    @Test
    public void faceCountMustBeExactlyOne() {
        assertOutcome(FaceFrameQualityGate.Outcome.NO_FACE,
                gate().evaluate(valid().faceCount(0).build()));
        assertOutcome(FaceFrameQualityGate.Outcome.MULTIPLE_FACES,
                gate().evaluate(valid().faceCount(2).build()));
        assertOutcome(FaceFrameQualityGate.Outcome.STABILIZING,
                gate().evaluate(valid().faceCount(1).build()));
    }

    @Test
    public void faceWidthAndHeightIncludeSixtyPixelBoundary() {
        assertAccepted(valid().faceWidth(60.0).faceHeight(60.0));
        assertOutcome(FaceFrameQualityGate.Outcome.TOO_SMALL,
                gate().evaluate(valid().faceWidth(Math.nextDown(60.0f)).build()));
        assertOutcome(FaceFrameQualityGate.Outcome.TOO_SMALL,
                gate().evaluate(valid().faceHeight(Math.nextDown(60.0f)).build()));
    }

    @Test
    public void centerBoundsAreInclusiveAndRejectImmediatelyOutside() {
        assertAccepted(valid().centerX(128.0));
        assertAccepted(valid().centerX(512.0));
        assertAccepted(valid().centerY(72.0));
        assertAccepted(valid().centerY(408.0));

        assertOutcome(FaceFrameQualityGate.Outcome.OFF_CENTER,
                gate().evaluate(valid().centerX(Math.nextDown(128.0f)).build()));
        assertOutcome(FaceFrameQualityGate.Outcome.OFF_CENTER,
                gate().evaluate(valid().centerX(Math.nextUp(512.0f)).build()));
        assertOutcome(FaceFrameQualityGate.Outcome.OFF_CENTER,
                gate().evaluate(valid().centerY(Math.nextDown(72.0f)).build()));
        assertOutcome(FaceFrameQualityGate.Outcome.OFF_CENTER,
                gate().evaluate(valid().centerY(Math.nextUp(408.0f)).build()));
    }

    @Test
    public void confidenceIncludesHalfBoundary() {
        assertAccepted(valid().confidence(0.5));
        assertOutcome(FaceFrameQualityGate.Outcome.LOW_CONFIDENCE,
                gate().evaluate(valid().confidence(Math.nextDown(0.5f)).build()));
    }

    @Test
    public void poseIncludesPositiveAndNegativeThirtyDegreeBoundaries() {
        assertAccepted(valid().yaw(30.0));
        assertAccepted(valid().yaw(-30.0));
        assertAccepted(valid().pitch(30.0));
        assertAccepted(valid().pitch(-30.0));
        assertAccepted(valid().roll(30.0));
        assertAccepted(valid().roll(-30.0));

        assertBadPose(valid().yaw(Math.nextUp(30.0f)));
        assertBadPose(valid().yaw(Math.nextDown(-30.0f)));
        assertBadPose(valid().pitch(Math.nextUp(30.0f)));
        assertBadPose(valid().pitch(Math.nextDown(-30.0f)));
        assertBadPose(valid().roll(Math.nextUp(30.0f)));
        assertBadPose(valid().roll(Math.nextDown(-30.0f)));
    }

    @Test
    public void blurIncludesEightTenthsBoundary() {
        assertAccepted(valid().blur(0.8f));
        assertOutcome(FaceFrameQualityGate.Outcome.TOO_BLURRY,
                gate().evaluate(valid().blur(Math.nextUp(0.8f)).build()));
    }

    @Test
    public void illuminationIncludesEightTenthsBoundary() {
        assertAccepted(valid().illum(0.8f));
        assertOutcome(FaceFrameQualityGate.Outcome.TOO_DARK,
                gate().evaluate(valid().illum(Math.nextDown(0.8f)).build()));
    }

    @Test
    public void everyOcclusionScoreIncludesEightTenthsBoundary() {
        for (int index = 0; index < 7; index++) {
            assertAccepted(valid().occlusion(index, 0.8f));
            assertOutcome("occlusion index " + index,
                    FaceFrameQualityGate.Outcome.OCCLUDED,
                    gate().evaluate(valid().occlusion(index, Math.nextUp(0.8f)).build()));
        }
    }

    @Test
    public void completenessMustEqualOneExactly() {
        assertAccepted(valid().completeness(1.0));
        assertOutcome(FaceFrameQualityGate.Outcome.INCOMPLETE,
                gate().evaluate(valid().completeness(Math.nextDown(1.0f)).build()));
    }

    @Test
    public void bestImageScoreMustBeStrictlyGreaterThanHalf() {
        assertOutcome(FaceFrameQualityGate.Outcome.NOT_BEST_IMAGE,
                gate().evaluate(valid().bestImageScore(0.5).build()));
        assertAccepted(valid().bestImageScore(Math.nextUp(0.5f)));
    }

    @Test
    public void requiredLivenessMustPassBeforeQualifiedFramesCanAccumulate() {
        FaceFrameQualityGate gate = gate();

        assertOutcome(FaceFrameQualityGate.Outcome.NOT_LIVE,
                gate.evaluate(valid().liveness(true, false).build()));
        assertOutcome(FaceFrameQualityGate.Outcome.NOT_LIVE,
                gate.evaluate(valid().liveness(true, false).build()));
        assertOutcome(FaceFrameQualityGate.Outcome.STABILIZING,
                gate.evaluate(valid().liveness(true, true).build()));
        assertOutcome(FaceFrameQualityGate.Outcome.STABILIZING,
                gate.evaluate(valid().liveness(true, true).build()));
        assertOutcome(FaceFrameQualityGate.Outcome.CAPTURE,
                gate.evaluate(valid().liveness(true, true).build()));
    }

    @Test
    public void ordinaryCaptureStillWorksWhenLivenessIsNotRequired() {
        FaceFrameQualityGate gate = gate();
        FaceObservation ordinary = valid().liveness(false, false).build();

        assertOutcome(FaceFrameQualityGate.Outcome.STABILIZING, gate.evaluate(ordinary));
        assertOutcome(FaceFrameQualityGate.Outcome.STABILIZING, gate.evaluate(ordinary));
        assertOutcome(FaceFrameQualityGate.Outcome.CAPTURE, gate.evaluate(ordinary));
    }

    @Test
    public void rejectionPriorityIsFixedAndActionable() {
        Values allBad = valid()
                .faceCount(0)
                .faceWidth(10.0)
                .centerX(0.0)
                .confidence(0.1)
                .yaw(50.0)
                .blur(0.9)
                .illum(0.1)
                .occlusion(0, 0.9)
                .completeness(0.9)
                .bestImageScore(0.1);
        assertOutcome(FaceFrameQualityGate.Outcome.NO_FACE, gate().evaluate(allBad.build()));
        assertOutcome(FaceFrameQualityGate.Outcome.MULTIPLE_FACES,
                gate().evaluate(allBad.faceCount(2).build()));
        assertOutcome(FaceFrameQualityGate.Outcome.TOO_SMALL,
                gate().evaluate(allBad.faceCount(1).build()));
        assertOutcome(FaceFrameQualityGate.Outcome.OFF_CENTER,
                gate().evaluate(allBad.faceWidth(100.0).build()));
        assertOutcome(FaceFrameQualityGate.Outcome.LOW_CONFIDENCE,
                gate().evaluate(allBad.centerX(320.0).build()));
        assertOutcome(FaceFrameQualityGate.Outcome.BAD_POSE,
                gate().evaluate(allBad.confidence(0.9).build()));
        assertOutcome(FaceFrameQualityGate.Outcome.TOO_BLURRY,
                gate().evaluate(allBad.yaw(0.0).build()));
        assertOutcome(FaceFrameQualityGate.Outcome.TOO_DARK,
                gate().evaluate(allBad.blur(0.1).build()));
        assertOutcome(FaceFrameQualityGate.Outcome.OCCLUDED,
                gate().evaluate(allBad.illum(0.9).build()));
        assertOutcome(FaceFrameQualityGate.Outcome.INCOMPLETE,
                gate().evaluate(allBad.occlusion(0, 0.1).build()));
        assertOutcome(FaceFrameQualityGate.Outcome.NOT_BEST_IMAGE,
                gate().evaluate(allBad.completeness(1.0).build()));
    }

    @Test
    public void threeConsecutiveQualifiedFramesCaptureExactlyOnceUntilReset() {
        FaceFrameQualityGate gate = gate();
        FaceObservation observation = valid().build();

        assertOutcome(FaceFrameQualityGate.Outcome.STABILIZING, gate.evaluate(observation));
        assertOutcome(FaceFrameQualityGate.Outcome.STABILIZING, gate.evaluate(observation));
        assertOutcome(FaceFrameQualityGate.Outcome.CAPTURE, gate.evaluate(observation));
        FaceFrameQualityGate.Decision latched = gate.evaluate(observation);
        assertOutcome(FaceFrameQualityGate.Outcome.STABILIZING, latched);
        assertFalse(latched.shouldCapture());
        assertOutcome(FaceFrameQualityGate.Outcome.STABILIZING,
                gate.evaluate(valid().faceCount(0).build()));

        gate.reset();
        assertOutcome(FaceFrameQualityGate.Outcome.STABILIZING, gate.evaluate(observation));
        assertOutcome(FaceFrameQualityGate.Outcome.STABILIZING, gate.evaluate(observation));
        assertOutcome(FaceFrameQualityGate.Outcome.CAPTURE, gate.evaluate(observation));
    }

    @Test
    public void everyRejectionResetsConsecutiveQualifiedCount() {
        FaceFrameQualityGate.Outcome[] rejectionOutcomes = {
                FaceFrameQualityGate.Outcome.NO_FACE,
                FaceFrameQualityGate.Outcome.MULTIPLE_FACES,
                FaceFrameQualityGate.Outcome.TOO_SMALL,
                FaceFrameQualityGate.Outcome.OFF_CENTER,
                FaceFrameQualityGate.Outcome.LOW_CONFIDENCE,
                FaceFrameQualityGate.Outcome.BAD_POSE,
                FaceFrameQualityGate.Outcome.TOO_BLURRY,
                FaceFrameQualityGate.Outcome.TOO_DARK,
                FaceFrameQualityGate.Outcome.OCCLUDED,
                FaceFrameQualityGate.Outcome.INCOMPLETE,
                FaceFrameQualityGate.Outcome.NOT_BEST_IMAGE,
                FaceFrameQualityGate.Outcome.NOT_LIVE
        };
        FaceObservation[] rejected = {
                valid().faceCount(0).build(),
                valid().faceCount(2).build(),
                valid().faceWidth(59.0).build(),
                valid().centerX(100.0).build(),
                valid().confidence(0.4).build(),
                valid().yaw(31.0).build(),
                valid().blur(0.9).build(),
                valid().illum(0.7).build(),
                valid().occlusion(3, 0.9).build(),
                valid().completeness(0.9).build(),
                valid().bestImageScore(0.5).build(),
                valid().liveness(true, false).build()
        };

        for (int index = 0; index < rejected.length; index++) {
            FaceFrameQualityGate gate = gate();
            assertOutcome(FaceFrameQualityGate.Outcome.STABILIZING,
                    gate.evaluate(valid().build()));
            assertOutcome(FaceFrameQualityGate.Outcome.STABILIZING,
                    gate.evaluate(valid().build()));
            assertOutcome(rejectionOutcomes[index], gate.evaluate(rejected[index]));
            assertOutcome(FaceFrameQualityGate.Outcome.STABILIZING,
                    gate.evaluate(valid().build()));
            assertOutcome(FaceFrameQualityGate.Outcome.STABILIZING,
                    gate.evaluate(valid().build()));
            assertOutcome(FaceFrameQualityGate.Outcome.CAPTURE,
                    gate.evaluate(valid().build()));
        }
    }

    @Test
    public void decisionsExposeOnlyTypedOutcomeAndSafeCustomerHint() {
        FaceFrameQualityGate.Decision decision = gate().evaluate(valid().faceCount(2).build());

        assertEquals(FaceFrameQualityGate.Outcome.MULTIPLE_FACES, decision.outcome());
        assertEquals("请仅保留一人面对摄像头", decision.customerHint());
        assertFalse(decision.shouldCapture());
        assertFalse(decision.customerHint().matches(".*[0-9].*"));
        for (Method method : FaceFrameQualityGate.Decision.class.getMethods()) {
            if (method.getDeclaringClass() == FaceFrameQualityGate.Decision.class) {
                assertTrue("decision must expose only outcome and safe hint: " + method.getName(),
                        method.getName().equals("outcome")
                                || method.getName().equals("customerHint")
                                || method.getName().equals("shouldCapture"));
            }
        }

        FaceFrameQualityGate captureGate = gate();
        captureGate.evaluate(valid().build());
        captureGate.evaluate(valid().build());
        assertTrue(captureGate.evaluate(valid().build()).shouldCapture());
    }

    @Test
    public void everyOutcomeUsesAFixedSafeHintAndOnlyCaptureRequestsCapture() {
        for (FaceFrameQualityGate.Outcome outcome : FaceFrameQualityGate.Outcome.values()) {
            FaceFrameQualityGate.Decision first = decisionForOutcome(outcome);
            FaceFrameQualityGate.Decision second = decisionForOutcome(outcome);

            assertEquals(outcome, first.outcome());
            assertEquals("hint must be fixed for " + outcome,
                    first.customerHint(), second.customerHint());
            assertTrue("hint must be present for " + outcome,
                    first.customerHint() != null && !first.customerHint().trim().isEmpty());
            assertFalse("hint must not expose numeric SDK values for " + outcome,
                    first.customerHint().matches(".*[0-9].*"));
            assertEquals(outcome == FaceFrameQualityGate.Outcome.CAPTURE,
                    first.shouldCapture());
        }
    }

    @Test
    public void defaultConfigurationOwnsAllExactThresholds() {
        FaceQualityConfig config = FaceQualityConfig.productionDefaults();

        assertEquals("baidu-face-8.5-v13-quality-1", config.version());
        assertEquals(0.5f, config.minimumDetectionConfidence(), 0.0f);
        assertEquals(60.0f, config.minimumFaceWidthPixels(), 0.0f);
        assertEquals(60.0f, config.minimumFaceHeightPixels(), 0.0f);
        assertEquals(0.20f, config.minimumCenterXRatio(), 0.0f);
        assertEquals(0.80f, config.maximumCenterXRatio(), 0.0f);
        assertEquals(0.15f, config.minimumCenterYRatio(), 0.0f);
        assertEquals(0.85f, config.maximumCenterYRatio(), 0.0f);
        assertEquals(30.0f, config.maximumAbsolutePoseDegrees(), 0.0f);
        assertEquals(0.8f, config.maximumBlur(), 0.0f);
        assertEquals(0.8f, config.minimumIllumination(), 0.0f);
        assertEquals(0.8f, config.maximumOcclusion(), 0.0f);
        assertEquals(1.0f, config.requiredCompleteness(), 0.0f);
        assertEquals(0.5f, config.minimumBestImageScoreExclusive(), 0.0f);
        assertEquals(3, config.requiredConsecutiveFrames());
    }

    @Test
    public void nullsAndInvalidConfigurationAreRejected() {
        assertIllegalArgument(() -> new FaceFrameQualityGate(null));
        assertIllegalArgument(() -> gate().evaluate(null));
        assertIllegalArgument(() -> config(null, 0.5, 60.0, 60.0, 0.2, 0.8,
                0.15, 0.85, 30.0, 0.8, 0.8, 0.8, 1.0, 0.5, 3));
        assertIllegalArgument(() -> config(" ", 0.5, 60.0, 60.0, 0.2, 0.8,
                0.15, 0.85, 30.0, 0.8, 0.8, 0.8, 1.0, 0.5, 3));
        assertIllegalArgument(() -> config("test", 0.5, 60.0, 60.0, 0.8, 0.2,
                0.15, 0.85, 30.0, 0.8, 0.8, 0.8, 1.0, 0.5, 3));
        assertIllegalArgument(() -> config("test", 0.5, 60.0, 60.0, 0.2, 0.8,
                0.85, 0.15, 30.0, 0.8, 0.8, 0.8, 1.0, 0.5, 3));
        assertIllegalArgument(() -> config("test", 0.5, 0.0, 60.0, 0.2, 0.8,
                0.15, 0.85, 30.0, 0.8, 0.8, 0.8, 1.0, 0.5, 3));
        assertIllegalArgument(() -> config("test", 0.5, 60.0, 60.0, 0.2, 0.8,
                0.15, 0.85, 30.0, 0.8, 0.8, 0.8, 1.0, 0.5, 0));
        assertIllegalArgument(() -> config("test", 0.5, 60.0, 60.0, 0.2, 0.8,
                0.15, 0.85, 30.0, 0.8, 0.8, 0.8, 1.0, 1.0, 3));
    }

    @Test
    public void configurationRejectsEveryNonFiniteThresholdIndependently() {
        ConfigMutation[] mutations = {
                (values, number) -> values.confidence(number),
                (values, number) -> values.minimumFaceWidth(number),
                (values, number) -> values.minimumFaceHeight(number),
                (values, number) -> values.minimumCenterX(number),
                (values, number) -> values.maximumCenterX(number),
                (values, number) -> values.minimumCenterY(number),
                (values, number) -> values.maximumCenterY(number),
                (values, number) -> values.maximumPose(number),
                (values, number) -> values.maximumBlur(number),
                (values, number) -> values.minimumIllumination(number),
                (values, number) -> values.maximumOcclusion(number),
                (values, number) -> values.requiredCompleteness(number),
                (values, number) -> values.minimumBestImageScore(number)
        };
        float[] malformed = {
                Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY
        };

        for (int field = 0; field < mutations.length; field++) {
            for (float number : malformed) {
                final ConfigValues values = mutations[field].apply(validConfig(), number);
                assertIllegalArgument(values::build);
            }
        }
    }

    @Test
    public void nullObservationResetsTheConsecutiveCountBeforeRejecting() {
        FaceFrameQualityGate gate = gate();
        assertOutcome(FaceFrameQualityGate.Outcome.STABILIZING,
                gate.evaluate(valid().build()));
        assertOutcome(FaceFrameQualityGate.Outcome.STABILIZING,
                gate.evaluate(valid().build()));

        assertIllegalArgument(() -> gate.evaluate(null));

        assertOutcome(FaceFrameQualityGate.Outcome.STABILIZING,
                gate.evaluate(valid().build()));
        assertOutcome(FaceFrameQualityGate.Outcome.STABILIZING,
                gate.evaluate(valid().build()));
        assertOutcome(FaceFrameQualityGate.Outcome.CAPTURE,
                gate.evaluate(valid().build()));
        assertIllegalArgument(() -> gate.evaluate(null));
        FaceFrameQualityGate.Decision stillLatched = gate.evaluate(valid().build());
        assertOutcome(FaceFrameQualityGate.Outcome.STABILIZING, stillLatched);
        assertFalse(stillLatched.shouldCapture());
    }

    @Test
    public void observationRejectsInvalidDimensionsCountsAndNumericValues() {
        assertIllegalArgument(() -> valid().frameWidth(0).build());
        assertIllegalArgument(() -> valid().frameHeight(-1).build());
        assertIllegalArgument(() -> valid().faceCount(-1).build());
        assertIllegalArgument(() -> valid().faceWidth(-0.1).build());
        assertIllegalArgument(() -> valid().faceHeight(-0.1).build());
        assertIllegalArgument(() -> valid().centerX(-0.1).build());
        assertIllegalArgument(() -> valid().centerY(-0.1).build());
        assertIllegalArgument(() -> valid().confidence(Double.NaN).build());
        assertIllegalArgument(() -> valid().yaw(Double.POSITIVE_INFINITY).build());
        assertIllegalArgument(() -> valid().pitch(Double.NEGATIVE_INFINITY).build());
        assertIllegalArgument(() -> valid().roll(Double.NaN).build());
        assertIllegalArgument(() -> valid().blur(Double.NaN).build());
        assertIllegalArgument(() -> valid().illum(Double.POSITIVE_INFINITY).build());
        assertIllegalArgument(() -> valid().occlusion(6, Double.NaN).build());
        assertIllegalArgument(() -> valid().completeness(Double.NEGATIVE_INFINITY).build());
        assertIllegalArgument(() -> valid().bestImageScore(Double.NaN).build());
    }

    @Test
    public void observationRejectsEveryNonFiniteSdkScalar() {
        ScalarMutation[] mutations = {
                (values, number) -> values.confidence(number),
                (values, number) -> values.centerX(number),
                (values, number) -> values.centerY(number),
                (values, number) -> values.faceWidth(number),
                (values, number) -> values.faceHeight(number),
                (values, number) -> values.yaw(number),
                (values, number) -> values.pitch(number),
                (values, number) -> values.roll(number),
                (values, number) -> values.blur(number),
                (values, number) -> values.illum(number),
                (values, number) -> values.occlusion(0, number),
                (values, number) -> values.occlusion(1, number),
                (values, number) -> values.occlusion(2, number),
                (values, number) -> values.occlusion(3, number),
                (values, number) -> values.occlusion(4, number),
                (values, number) -> values.occlusion(5, number),
                (values, number) -> values.occlusion(6, number),
                (values, number) -> values.completeness(number),
                (values, number) -> values.bestImageScore(number)
        };
        float[] malformed = {
                Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY
        };

        for (int field = 0; field < mutations.length; field++) {
            for (float number : malformed) {
                final Values values = mutations[field].apply(valid(), number);
                assertIllegalArgument(values::build);
            }
        }
    }

    @Test
    public void normalizedScoresRejectValuesOutsideZeroToOne() {
        ScalarMutation[] normalizedScores = {
                (values, number) -> values.confidence(number),
                (values, number) -> values.blur(number),
                (values, number) -> values.illum(number),
                (values, number) -> values.occlusion(0, number),
                (values, number) -> values.occlusion(1, number),
                (values, number) -> values.occlusion(2, number),
                (values, number) -> values.occlusion(3, number),
                (values, number) -> values.occlusion(4, number),
                (values, number) -> values.occlusion(5, number),
                (values, number) -> values.occlusion(6, number),
                (values, number) -> values.completeness(number),
                (values, number) -> values.bestImageScore(number)
        };

        for (ScalarMutation mutation : normalizedScores) {
            assertIllegalArgument(() -> mutation.apply(valid(), -Float.MIN_VALUE).build());
            assertIllegalArgument(() -> mutation.apply(valid(), Math.nextUp(1.0f)).build());
        }
    }

    @Test
    public void completenessAboveOneIsMalformedRatherThanQualified() {
        assertIllegalArgument(() -> valid().completeness(Math.nextUp(1.0f)).build());
    }

    @Test
    public void observationsAndConfigurationsAreFinalScalarSnapshots() {
        assertTrue(Modifier.isFinal(FaceObservation.class.getModifiers()));
        assertTrue(Modifier.isFinal(FaceQualityConfig.class.getModifiers()));
        assertTrue(Modifier.isFinal(FaceFrameQualityGate.class.getModifiers()));
        assertOnlyPrivateFinalScalarFields(FaceObservation.class);
        assertOnlyPrivateFinalScalarFields(FaceQualityConfig.class);
        assertObservationAndConfigUseSdkNativeFloatScalars();
    }

    private static FaceFrameQualityGate gate() {
        return new FaceFrameQualityGate(FaceQualityConfig.productionDefaults());
    }

    private static void assertAccepted(Values values) {
        assertOutcome(FaceFrameQualityGate.Outcome.STABILIZING,
                gate().evaluate(values.build()));
    }

    private static void assertBadPose(Values values) {
        assertOutcome(FaceFrameQualityGate.Outcome.BAD_POSE,
                gate().evaluate(values.build()));
    }

    private static void assertOutcome(FaceFrameQualityGate.Outcome expected,
            FaceFrameQualityGate.Decision actual) {
        assertOutcome("", expected, actual);
    }

    private static void assertOutcome(String message, FaceFrameQualityGate.Outcome expected,
            FaceFrameQualityGate.Decision actual) {
        assertEquals(message, expected, actual.outcome());
        assertTrue("customer hint must be present", actual.customerHint() != null
                && !actual.customerHint().trim().isEmpty());
    }

    private static void assertOnlyPrivateFinalScalarFields(Class<?> type) {
        for (Field field : type.getDeclaredFields()) {
            assertTrue(type.getSimpleName() + "." + field.getName() + " must be private",
                    Modifier.isPrivate(field.getModifiers()));
            assertTrue(type.getSimpleName() + "." + field.getName() + " must be final",
                    Modifier.isFinal(field.getModifiers()));
            assertTrue(type.getSimpleName() + "." + field.getName() + " must be scalar",
                    field.getType().isPrimitive() || field.getType() == String.class);
        }
    }

    private static void assertObservationAndConfigUseSdkNativeFloatScalars() {
        for (Field field : FaceObservation.class.getDeclaredFields()) {
            if (!field.getType().equals(int.class)) {
                assertEquals("observation SDK scalar must remain float: " + field.getName(),
                        float.class, field.getType());
            }
        }
        for (Field field : FaceQualityConfig.class.getDeclaredFields()) {
            if (!field.getType().equals(int.class) && !field.getType().equals(String.class)) {
                assertEquals("config threshold must remain float: " + field.getName(),
                        float.class, field.getType());
            }
        }
    }

    private static void assertIllegalArgument(ThrowingRunnable runnable) {
        try {
            runnable.run();
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Expected validation failure.
        } catch (Exception unexpected) {
            throw new AssertionError(unexpected);
        }
    }

    private static FaceFrameQualityGate.Decision decisionForOutcome(
            FaceFrameQualityGate.Outcome outcome) {
        FaceFrameQualityGate gate = gate();
        switch (outcome) {
            case NO_FACE: return gate.evaluate(valid().faceCount(0).build());
            case MULTIPLE_FACES: return gate.evaluate(valid().faceCount(2).build());
            case TOO_SMALL: return gate.evaluate(valid().faceWidth(59.0).build());
            case OFF_CENTER: return gate.evaluate(valid().centerX(100.0).build());
            case LOW_CONFIDENCE: return gate.evaluate(valid().confidence(0.4).build());
            case BAD_POSE: return gate.evaluate(valid().yaw(31.0).build());
            case TOO_BLURRY: return gate.evaluate(valid().blur(0.9).build());
            case TOO_DARK: return gate.evaluate(valid().illum(0.7).build());
            case OCCLUDED: return gate.evaluate(valid().occlusion(0, 0.9).build());
            case INCOMPLETE: return gate.evaluate(valid().completeness(0.9).build());
            case NOT_BEST_IMAGE:
                return gate.evaluate(valid().bestImageScore(0.5).build());
            case NOT_LIVE:
                return gate.evaluate(valid().liveness(true, false).build());
            case STABILIZING: return gate.evaluate(valid().build());
            case CAPTURE:
                gate.evaluate(valid().build());
                gate.evaluate(valid().build());
                return gate.evaluate(valid().build());
            default: throw new AssertionError("unhandled outcome " + outcome);
        }
    }

    private static FaceQualityConfig config(String version, double confidence,
            double width, double height,
            double minX, double maxX, double minY, double maxY, double pose, double blur,
            double illum, double occlusion, double completeness, double best, int frames) {
        return new FaceQualityConfig(version, (float) confidence, (float) width, (float) height,
                (float) minX, (float) maxX, (float) minY, (float) maxY, (float) pose,
                (float) blur, (float) illum, (float) occlusion, (float) completeness,
                (float) best, frames);
    }

    private static Values valid() {
        return new Values();
    }

    private static ConfigValues validConfig() {
        return new ConfigValues();
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private interface ScalarMutation {
        Values apply(Values values, float number);
    }

    private interface ConfigMutation {
        ConfigValues apply(ConfigValues values, float number);
    }

    private static final class ConfigValues {
        private float confidence = 0.5f;
        private float minimumFaceWidth = 60.0f;
        private float minimumFaceHeight = 60.0f;
        private float minimumCenterX = 0.20f;
        private float maximumCenterX = 0.80f;
        private float minimumCenterY = 0.15f;
        private float maximumCenterY = 0.85f;
        private float maximumPose = 30.0f;
        private float maximumBlur = 0.8f;
        private float minimumIllumination = 0.8f;
        private float maximumOcclusion = 0.8f;
        private float requiredCompleteness = 1.0f;
        private float minimumBestImageScore = 0.5f;

        ConfigValues confidence(float value) { confidence = value; return this; }
        ConfigValues minimumFaceWidth(float value) {
            minimumFaceWidth = value;
            return this;
        }
        ConfigValues minimumFaceHeight(float value) {
            minimumFaceHeight = value;
            return this;
        }
        ConfigValues minimumCenterX(float value) { minimumCenterX = value; return this; }
        ConfigValues maximumCenterX(float value) { maximumCenterX = value; return this; }
        ConfigValues minimumCenterY(float value) { minimumCenterY = value; return this; }
        ConfigValues maximumCenterY(float value) { maximumCenterY = value; return this; }
        ConfigValues maximumPose(float value) { maximumPose = value; return this; }
        ConfigValues maximumBlur(float value) { maximumBlur = value; return this; }
        ConfigValues minimumIllumination(float value) {
            minimumIllumination = value;
            return this;
        }
        ConfigValues maximumOcclusion(float value) {
            maximumOcclusion = value;
            return this;
        }
        ConfigValues requiredCompleteness(float value) {
            requiredCompleteness = value;
            return this;
        }
        ConfigValues minimumBestImageScore(float value) {
            minimumBestImageScore = value;
            return this;
        }

        FaceQualityConfig build() {
            return new FaceQualityConfig("test-quality-config", confidence,
                    minimumFaceWidth, minimumFaceHeight, minimumCenterX, maximumCenterX,
                    minimumCenterY, maximumCenterY, maximumPose, maximumBlur,
                    minimumIllumination, maximumOcclusion, requiredCompleteness,
                    minimumBestImageScore, 3);
        }
    }

    private static final class Values {
        private int frameWidth = 640;
        private int frameHeight = 480;
        private int faceCount = 1;
        private float confidence = 0.9f;
        private float centerX = 320.0f;
        private float centerY = 240.0f;
        private float faceWidth = 100.0f;
        private float faceHeight = 100.0f;
        private float yaw;
        private float pitch;
        private float roll;
        private float blur = 0.1f;
        private float illum = 0.9f;
        private final float[] occlusion = {0.1f, 0.1f, 0.1f, 0.1f, 0.1f, 0.1f, 0.1f};
        private float completeness = 1.0f;
        private float bestImageScore = 0.9f;
        private boolean livenessRequired;
        private boolean livenessPassed;

        Values frameWidth(int value) { frameWidth = value; return this; }
        Values frameHeight(int value) { frameHeight = value; return this; }
        Values faceCount(int value) { faceCount = value; return this; }
        Values confidence(double value) { confidence = (float) value; return this; }
        Values centerX(double value) { centerX = (float) value; return this; }
        Values centerY(double value) { centerY = (float) value; return this; }
        Values faceWidth(double value) { faceWidth = (float) value; return this; }
        Values faceHeight(double value) { faceHeight = (float) value; return this; }
        Values yaw(double value) { yaw = (float) value; return this; }
        Values pitch(double value) { pitch = (float) value; return this; }
        Values roll(double value) { roll = (float) value; return this; }
        Values blur(double value) { blur = (float) value; return this; }
        Values illum(double value) { illum = (float) value; return this; }
        Values completeness(double value) { completeness = (float) value; return this; }
        Values bestImageScore(double value) { bestImageScore = (float) value; return this; }
        Values liveness(boolean required, boolean passed) {
            livenessRequired = required;
            livenessPassed = passed;
            return this;
        }
        Values occlusion(int index, double value) { occlusion[index] = (float) value; return this; }

        FaceObservation build() {
            return new FaceObservation(frameWidth, frameHeight, faceCount, confidence,
                    centerX, centerY, faceWidth, faceHeight, yaw, pitch, roll, blur, illum,
                    occlusion[0], occlusion[1], occlusion[2], occlusion[3], occlusion[4],
                    occlusion[5], occlusion[6], completeness, bestImageScore,
                    livenessRequired, livenessPassed);
        }
    }
}
