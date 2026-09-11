package com.codex.lockertest.face.verification;

public interface FaceVerificationClient {
    interface Callback {
        void onCompleted(FaceVerificationResult result);
    }

    interface Cancellable {
        void cancel();
    }

    Cancellable verify(FaceVerificationRequest request, Callback callback);
}
