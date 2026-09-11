package com.codex.lockertest.ui;

import com.codex.lockertest.model.FeatureAvailability;
import com.codex.lockertest.model.UnlockMethod;

import java.util.List;

/** Fail-closed projection of final-package home action visibility. */
public final class FinalHomeActions {
    private static final String PALM_RECOGNITION_TYPE = "3";
    private static final String FACE_RECOGNITION_TYPE = "5";

    private final boolean faceVisible;
    private final boolean palmEnrollmentVisible;

    private FinalHomeActions(boolean faceVisible, boolean palmEnrollmentVisible) {
        this.faceVisible = faceVisible;
        this.palmEnrollmentVisible = palmEnrollmentVisible;
    }

    public static FinalHomeActions create(
            boolean serverCapabilitiesKnown,
            List<String> serverRecognitionTypes,
            FeatureAvailability localFallback) {
        if (serverCapabilitiesKnown) {
            boolean face = false;
            boolean palm = false;
            if (serverRecognitionTypes != null) {
                for (String type : serverRecognitionTypes) {
                    if (!isKnownRecognitionType(type)) {
                        return new FinalHomeActions(false, false);
                    }
                    if (FACE_RECOGNITION_TYPE.equals(type)) face = true;
                    if (PALM_RECOGNITION_TYPE.equals(type)) palm = true;
                }
            }
            return new FinalHomeActions(face, palm);
        }
        if (localFallback == null) return new FinalHomeActions(false, false);
        try {
            return new FinalHomeActions(
                    localFallback.isEnabled(UnlockMethod.FACE),
                    localFallback.isEnabled(UnlockMethod.PALM));
        } catch (RuntimeException | LinkageError ignored) {
            return new FinalHomeActions(false, false);
        }
    }

    public boolean faceVisible() {
        return faceVisible;
    }

    public boolean palmEnrollmentVisible() {
        return palmEnrollmentVisible;
    }

    private static boolean isKnownRecognitionType(String type) {
        return "1".equals(type) || "2".equals(type) || "3".equals(type)
                || "4".equals(type) || "5".equals(type);
    }
}
