package com.codex.lockertest.face;

final class CameraSelectionPolicy {
    interface FacingReader {
        boolean isFrontFacing(int cameraId);
    }

    private CameraSelectionPolicy() { }

    static int selectCameraId(int cameraCount, FacingReader facingReader) {
        for (int cameraId = 0; cameraId < cameraCount; cameraId++) {
            if (facingReader.isFrontFacing(cameraId)) return cameraId;
        }
        // Some single-camera terminals label their user-facing USB camera as rear-facing.
        // With multiple cameras, never guess which non-front camera faces the customer.
        return cameraCount == 1 ? 0 : -1;
    }
}
