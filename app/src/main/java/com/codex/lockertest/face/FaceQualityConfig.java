package com.codex.lockertest.face;

public final class FaceQualityConfig {
    private final String version;
    private final float minimumDetectionConfidence;
    private final float minimumFaceWidthPixels;
    private final float minimumFaceHeightPixels;
    private final float minimumCenterXRatio;
    private final float maximumCenterXRatio;
    private final float minimumCenterYRatio;
    private final float maximumCenterYRatio;
    private final float maximumAbsolutePoseDegrees;
    private final float maximumBlur;
    private final float minimumIllumination;
    private final float maximumOcclusion;
    private final float requiredCompleteness;
    private final float minimumBestImageScoreExclusive;
    private final int requiredConsecutiveFrames;

    public FaceQualityConfig(String version, float minimumDetectionConfidence,
            float minimumFaceWidthPixels, float minimumFaceHeightPixels,
            float minimumCenterXRatio, float maximumCenterXRatio,
            float minimumCenterYRatio, float maximumCenterYRatio,
            float maximumAbsolutePoseDegrees, float maximumBlur,
            float minimumIllumination, float maximumOcclusion,
            float requiredCompleteness, float minimumBestImageScoreExclusive,
            int requiredConsecutiveFrames) {
        if (version == null || version.trim().isEmpty()) {
            throw new IllegalArgumentException("version cannot be blank");
        }
        requireUnitInterval(minimumDetectionConfidence, "minimumDetectionConfidence");
        requirePositive(minimumFaceWidthPixels, "minimumFaceWidthPixels");
        requirePositive(minimumFaceHeightPixels, "minimumFaceHeightPixels");
        requireOrderedRatios(minimumCenterXRatio, maximumCenterXRatio, "center X");
        requireOrderedRatios(minimumCenterYRatio, maximumCenterYRatio, "center Y");
        requireNonNegative(maximumAbsolutePoseDegrees, "maximumAbsolutePoseDegrees");
        requireUnitInterval(maximumBlur, "maximumBlur");
        requireUnitInterval(minimumIllumination, "minimumIllumination");
        requireUnitInterval(maximumOcclusion, "maximumOcclusion");
        requireUnitInterval(requiredCompleteness, "requiredCompleteness");
        requireUnitInterval(minimumBestImageScoreExclusive,
                "minimumBestImageScoreExclusive");
        if (minimumBestImageScoreExclusive >= 1.0f) {
            throw new IllegalArgumentException(
                    "minimumBestImageScoreExclusive must allow a passing score");
        }
        if (requiredConsecutiveFrames <= 0) {
            throw new IllegalArgumentException("requiredConsecutiveFrames must be positive");
        }

        this.version = version;
        this.minimumDetectionConfidence = minimumDetectionConfidence;
        this.minimumFaceWidthPixels = minimumFaceWidthPixels;
        this.minimumFaceHeightPixels = minimumFaceHeightPixels;
        this.minimumCenterXRatio = minimumCenterXRatio;
        this.maximumCenterXRatio = maximumCenterXRatio;
        this.minimumCenterYRatio = minimumCenterYRatio;
        this.maximumCenterYRatio = maximumCenterYRatio;
        this.maximumAbsolutePoseDegrees = maximumAbsolutePoseDegrees;
        this.maximumBlur = maximumBlur;
        this.minimumIllumination = minimumIllumination;
        this.maximumOcclusion = maximumOcclusion;
        this.requiredCompleteness = requiredCompleteness;
        this.minimumBestImageScoreExclusive = minimumBestImageScoreExclusive;
        this.requiredConsecutiveFrames = requiredConsecutiveFrames;
    }

    public static FaceQualityConfig productionDefaults() {
        return new FaceQualityConfig("baidu-face-8.5-v13-quality-1",
                0.5f, 60.0f, 60.0f, 0.20f, 0.80f, 0.15f, 0.85f,
                30.0f, 0.8f, 0.8f, 0.8f, 1.0f, 0.5f, 3);
    }

    public String version() { return version; }
    public float minimumDetectionConfidence() { return minimumDetectionConfidence; }
    public float minimumFaceWidthPixels() { return minimumFaceWidthPixels; }
    public float minimumFaceHeightPixels() { return minimumFaceHeightPixels; }
    public float minimumCenterXRatio() { return minimumCenterXRatio; }
    public float maximumCenterXRatio() { return maximumCenterXRatio; }
    public float minimumCenterYRatio() { return minimumCenterYRatio; }
    public float maximumCenterYRatio() { return maximumCenterYRatio; }
    public float maximumAbsolutePoseDegrees() { return maximumAbsolutePoseDegrees; }
    public float maximumBlur() { return maximumBlur; }
    public float minimumIllumination() { return minimumIllumination; }
    public float maximumOcclusion() { return maximumOcclusion; }
    public float requiredCompleteness() { return requiredCompleteness; }
    public float minimumBestImageScoreExclusive() {
        return minimumBestImageScoreExclusive;
    }
    public int requiredConsecutiveFrames() { return requiredConsecutiveFrames; }

    private static void requireOrderedRatios(float minimum, float maximum, String name) {
        requireUnitInterval(minimum, name + " minimum");
        requireUnitInterval(maximum, name + " maximum");
        if (minimum > maximum) {
            throw new IllegalArgumentException(name + " minimum cannot exceed maximum");
        }
    }

    private static void requirePositive(float value, String name) {
        requireFinite(value, name);
        if (value <= 0.0f) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void requireNonNegative(float value, String name) {
        requireFinite(value, name);
        if (value < 0.0f) {
            throw new IllegalArgumentException(name + " cannot be negative");
        }
    }

    private static void requireUnitInterval(float value, String name) {
        requireFinite(value, name);
        if (value < 0.0f || value > 1.0f) {
            throw new IllegalArgumentException(name + " must be between zero and one");
        }
    }

    private static void requireFinite(float value, String name) {
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
