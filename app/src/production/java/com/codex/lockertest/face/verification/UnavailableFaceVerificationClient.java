package com.codex.lockertest.face.verification;

/** Fails closed until the production server face contract is configured. */
public final class UnavailableFaceVerificationClient implements FaceVerificationClient {
    private static final String UNAVAILABLE_MESSAGE = "服务器人脸接口未配置";
    private static final Cancellable COMPLETED = () -> { };

    @Override
    public Cancellable verify(FaceVerificationRequest request, Callback callback) {
        if (request == null || callback == null) {
            throw new IllegalArgumentException("request and callback cannot be null");
        }
        String requestId = request.requestId();
        request.close();
        FaceVerificationResult result = FaceVerificationResult.terminalFailure(
                FaceVerificationStatus.SERVER_ERROR, requestId, UNAVAILABLE_MESSAGE);
        try {
            callback.onCompleted(result);
        } catch (RuntimeException ignored) {
            // Consumer failures cannot interrupt terminal request cleanup.
        }
        return COMPLETED;
    }
}
