package com.codex.lockertest.face;

public final class FaceFrameQualityGate {
    public enum Outcome {
        NO_FACE,
        MULTIPLE_FACES,
        TOO_SMALL,
        OFF_CENTER,
        LOW_CONFIDENCE,
        BAD_POSE,
        TOO_BLURRY,
        TOO_DARK,
        OCCLUDED,
        INCOMPLETE,
        NOT_BEST_IMAGE,
        NOT_LIVE,
        STABILIZING,
        CAPTURE
    }

    public static final class Decision {
        private final Outcome outcome;
        private final String customerHint;

        private Decision(Outcome outcome, String customerHint) {
            this.outcome = outcome;
            this.customerHint = customerHint;
        }

        public Outcome outcome() {
            return outcome;
        }

        public String customerHint() {
            return customerHint;
        }

        public boolean shouldCapture() {
            return outcome == Outcome.CAPTURE;
        }
    }

    private final FaceQualityConfig config;
    private int consecutiveQualifiedFrames;
    private boolean captureLatched;

    public FaceFrameQualityGate() {
        this(FaceQualityConfig.productionDefaults());
    }

    public FaceFrameQualityGate(FaceQualityConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config cannot be null");
        }
        this.config = config;
    }

    public synchronized Decision evaluate(FaceObservation observation) {
        if (observation == null) {
            if (!captureLatched) {
                consecutiveQualifiedFrames = 0;
            }
            throw new IllegalArgumentException("observation cannot be null");
        }
        if (captureLatched) {
            return decision(Outcome.STABILIZING, "正在处理，请稍候");
        }
        if (observation.faceCount() == 0) {
            return reject(Outcome.NO_FACE, "请正对摄像头");
        }
        if (observation.faceCount() != 1) {
            return reject(Outcome.MULTIPLE_FACES, "请仅保留一人面对摄像头");
        }
        if (observation.faceWidth() < config.minimumFaceWidthPixels()
                || observation.faceHeight() < config.minimumFaceHeightPixels()) {
            return reject(Outcome.TOO_SMALL, "请靠近摄像头");
        }
        float minimumX = config.minimumCenterXRatio() * observation.frameWidth();
        float maximumX = config.maximumCenterXRatio() * observation.frameWidth();
        float minimumY = config.minimumCenterYRatio() * observation.frameHeight();
        float maximumY = config.maximumCenterYRatio() * observation.frameHeight();
        if (observation.faceCenterX() < minimumX || observation.faceCenterX() > maximumX
                || observation.faceCenterY() < minimumY
                || observation.faceCenterY() > maximumY) {
            return reject(Outcome.OFF_CENTER, "请将面部移至画面中央");
        }
        if (observation.detectionConfidence() < config.minimumDetectionConfidence()) {
            return reject(Outcome.LOW_CONFIDENCE, "请保持面部清晰可见");
        }
        if (Math.abs(observation.yaw()) > config.maximumAbsolutePoseDegrees()
                || Math.abs(observation.pitch()) > config.maximumAbsolutePoseDegrees()
                || Math.abs(observation.roll()) > config.maximumAbsolutePoseDegrees()) {
            return reject(Outcome.BAD_POSE, "请正对摄像头");
        }
        if (observation.blur() > config.maximumBlur()) {
            return reject(Outcome.TOO_BLURRY, "请保持静止");
        }
        if (observation.illumination() < config.minimumIllumination()) {
            return reject(Outcome.TOO_DARK, "请移至光线充足处");
        }
        if (observation.leftEyeOcclusion() > config.maximumOcclusion()
                || observation.rightEyeOcclusion() > config.maximumOcclusion()
                || observation.noseOcclusion() > config.maximumOcclusion()
                || observation.mouthOcclusion() > config.maximumOcclusion()
                || observation.leftCheekOcclusion() > config.maximumOcclusion()
                || observation.rightCheekOcclusion() > config.maximumOcclusion()
                || observation.chinOcclusion() > config.maximumOcclusion()) {
            return reject(Outcome.OCCLUDED, "请勿遮挡面部");
        }
        if (Float.compare(observation.completeness(), config.requiredCompleteness()) != 0) {
            return reject(Outcome.INCOMPLETE, "请完整露出面部");
        }
        if (observation.bestImageScore() <= config.minimumBestImageScoreExclusive()) {
            return reject(Outcome.NOT_BEST_IMAGE, "请保持姿势");
        }
        if (observation.livenessRequired() && !observation.livenessPassed()) {
            return reject(Outcome.NOT_LIVE, "正在进行活体检测，请保持正对摄像头");
        }

        consecutiveQualifiedFrames++;
        if (consecutiveQualifiedFrames >= config.requiredConsecutiveFrames()) {
            captureLatched = true;
            return decision(Outcome.CAPTURE, "采集成功");
        }
        return decision(Outcome.STABILIZING, "正在检测，请保持不动");
    }

    public synchronized void reset() {
        consecutiveQualifiedFrames = 0;
        captureLatched = false;
    }

    private Decision reject(Outcome outcome, String customerHint) {
        consecutiveQualifiedFrames = 0;
        return decision(outcome, customerHint);
    }

    private static Decision decision(Outcome outcome, String customerHint) {
        return new Decision(outcome, customerHint);
    }
}
