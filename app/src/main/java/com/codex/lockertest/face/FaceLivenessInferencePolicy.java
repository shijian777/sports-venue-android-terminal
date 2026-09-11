package com.codex.lockertest.face;

/** Pure fail-closed adapter around one optional native RGB-liveness score. */
public final class FaceLivenessInferencePolicy {
    public interface Scorer {
        float score();
    }

    public interface FailureHandler {
        boolean onUnavailable();
    }

    public static final class Decision {
        private final boolean livenessRequired;
        private final boolean livenessPassed;
        private final boolean capabilityChanged;

        private Decision(boolean livenessRequired, boolean livenessPassed,
                boolean capabilityChanged) {
            this.livenessRequired = livenessRequired;
            this.livenessPassed = livenessPassed;
            this.capabilityChanged = capabilityChanged;
        }

        public boolean livenessRequired() { return livenessRequired; }
        public boolean livenessPassed() { return livenessPassed; }
        public boolean capabilityChanged() { return capabilityChanged; }
    }

    private FaceLivenessInferencePolicy() { }

    public static Decision evaluate(FaceLivenessControl control,
            float threshold, Scorer scorer) {
        return evaluate(control, threshold, scorer, () -> {
            control.onProbeFailed();
            return true;
        });
    }

    public static Decision evaluate(FaceLivenessControl control,
            float threshold, Scorer scorer, FailureHandler failureHandler) {
        if (control == null || scorer == null || failureHandler == null) {
            throw new IllegalArgumentException("liveness dependencies cannot be null");
        }
        if (!finiteUnit(threshold)) {
            throw new IllegalArgumentException("threshold must be between zero and one");
        }
        if (control.snapshot().mode() != FaceLivenessControl.Mode.RGB_LIVENESS) {
            return new Decision(false, false, false);
        }
        try {
            float score = scorer.score();
            if (!finiteUnit(score)) return fallback(failureHandler);
            return new Decision(true, score > threshold, false);
        } catch (RuntimeException | LinkageError failure) {
            return fallback(failureHandler);
        }
    }

    private static Decision fallback(FailureHandler failureHandler) {
        boolean changed = false;
        try { changed = failureHandler.onUnavailable(); }
        catch (RuntimeException | LinkageError ignored) { }
        return new Decision(false, false, changed);
    }

    private static boolean finiteUnit(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value)
                && value >= 0.0f && value <= 1.0f;
    }
}
