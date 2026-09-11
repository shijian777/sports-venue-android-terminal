package com.codex.lockertest.face;

public final class FaceObservation {
    private final int frameWidth;
    private final int frameHeight;
    private final int faceCount;
    private final float detectionConfidence;
    private final float faceCenterX;
    private final float faceCenterY;
    private final float faceWidth;
    private final float faceHeight;
    private final float yaw;
    private final float pitch;
    private final float roll;
    private final float blur;
    private final float illumination;
    private final float leftEyeOcclusion;
    private final float rightEyeOcclusion;
    private final float noseOcclusion;
    private final float mouthOcclusion;
    private final float leftCheekOcclusion;
    private final float rightCheekOcclusion;
    private final float chinOcclusion;
    private final float completeness;
    private final float bestImageScore;
    private final float livenessRequired;
    private final float livenessPassed;

    public FaceObservation(int frameWidth, int frameHeight, int faceCount,
            float detectionConfidence, float faceCenterX, float faceCenterY,
            float faceWidth, float faceHeight, float yaw, float pitch, float roll,
            float blur, float illumination, float leftEyeOcclusion,
            float rightEyeOcclusion, float noseOcclusion, float mouthOcclusion,
            float leftCheekOcclusion, float rightCheekOcclusion, float chinOcclusion,
            float completeness, float bestImageScore) {
        this(frameWidth, frameHeight, faceCount, detectionConfidence, faceCenterX,
                faceCenterY, faceWidth, faceHeight, yaw, pitch, roll, blur,
                illumination, leftEyeOcclusion, rightEyeOcclusion, noseOcclusion,
                mouthOcclusion, leftCheekOcclusion, rightCheekOcclusion,
                chinOcclusion, completeness, bestImageScore, false, false);
    }

    public FaceObservation(int frameWidth, int frameHeight, int faceCount,
            float detectionConfidence, float faceCenterX, float faceCenterY,
            float faceWidth, float faceHeight, float yaw, float pitch, float roll,
            float blur, float illumination, float leftEyeOcclusion,
            float rightEyeOcclusion, float noseOcclusion, float mouthOcclusion,
            float leftCheekOcclusion, float rightCheekOcclusion, float chinOcclusion,
            float completeness, float bestImageScore, boolean livenessRequired,
            boolean livenessPassed) {
        if (frameWidth <= 0 || frameHeight <= 0) {
            throw new IllegalArgumentException("frame dimensions must be positive");
        }
        if (faceCount < 0) {
            throw new IllegalArgumentException("faceCount cannot be negative");
        }
        requireUnitInterval(detectionConfidence, "detectionConfidence");
        requireNonNegative(faceCenterX, "faceCenterX");
        requireNonNegative(faceCenterY, "faceCenterY");
        requireNonNegative(faceWidth, "faceWidth");
        requireNonNegative(faceHeight, "faceHeight");
        requireFinite(yaw, "yaw");
        requireFinite(pitch, "pitch");
        requireFinite(roll, "roll");
        requireUnitInterval(blur, "blur");
        requireUnitInterval(illumination, "illumination");
        requireUnitInterval(leftEyeOcclusion, "leftEyeOcclusion");
        requireUnitInterval(rightEyeOcclusion, "rightEyeOcclusion");
        requireUnitInterval(noseOcclusion, "noseOcclusion");
        requireUnitInterval(mouthOcclusion, "mouthOcclusion");
        requireUnitInterval(leftCheekOcclusion, "leftCheekOcclusion");
        requireUnitInterval(rightCheekOcclusion, "rightCheekOcclusion");
        requireUnitInterval(chinOcclusion, "chinOcclusion");
        requireUnitInterval(completeness, "completeness");
        requireUnitInterval(bestImageScore, "bestImageScore");

        this.frameWidth = frameWidth;
        this.frameHeight = frameHeight;
        this.faceCount = faceCount;
        this.detectionConfidence = detectionConfidence;
        this.faceCenterX = faceCenterX;
        this.faceCenterY = faceCenterY;
        this.faceWidth = faceWidth;
        this.faceHeight = faceHeight;
        this.yaw = yaw;
        this.pitch = pitch;
        this.roll = roll;
        this.blur = blur;
        this.illumination = illumination;
        this.leftEyeOcclusion = leftEyeOcclusion;
        this.rightEyeOcclusion = rightEyeOcclusion;
        this.noseOcclusion = noseOcclusion;
        this.mouthOcclusion = mouthOcclusion;
        this.leftCheekOcclusion = leftCheekOcclusion;
        this.rightCheekOcclusion = rightCheekOcclusion;
        this.chinOcclusion = chinOcclusion;
        this.completeness = completeness;
        this.bestImageScore = bestImageScore;
        this.livenessRequired = livenessRequired ? 1.0f : 0.0f;
        this.livenessPassed = livenessPassed ? 1.0f : 0.0f;
    }

    public int frameWidth() { return frameWidth; }
    public int frameHeight() { return frameHeight; }
    public int faceCount() { return faceCount; }
    public float detectionConfidence() { return detectionConfidence; }
    public float faceCenterX() { return faceCenterX; }
    public float faceCenterY() { return faceCenterY; }
    public float faceWidth() { return faceWidth; }
    public float faceHeight() { return faceHeight; }
    public float yaw() { return yaw; }
    public float pitch() { return pitch; }
    public float roll() { return roll; }
    public float blur() { return blur; }
    public float illumination() { return illumination; }
    public float leftEyeOcclusion() { return leftEyeOcclusion; }
    public float rightEyeOcclusion() { return rightEyeOcclusion; }
    public float noseOcclusion() { return noseOcclusion; }
    public float mouthOcclusion() { return mouthOcclusion; }
    public float leftCheekOcclusion() { return leftCheekOcclusion; }
    public float rightCheekOcclusion() { return rightCheekOcclusion; }
    public float chinOcclusion() { return chinOcclusion; }
    public float completeness() { return completeness; }
    public float bestImageScore() { return bestImageScore; }
    public boolean livenessRequired() { return livenessRequired == 1.0f; }
    public boolean livenessPassed() { return livenessPassed == 1.0f; }

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
