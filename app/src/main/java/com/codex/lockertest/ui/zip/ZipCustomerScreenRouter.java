package com.codex.lockertest.ui.zip;

/** Pure-Java route table for the customer-facing ZIP screens 1 through 13. */
public final class ZipCustomerScreenRouter {
    public enum State {
        HOME_WAITING,
        HOME_READING_CREDENTIAL,
        HOME_UNREGISTERED_COUNTDOWN,
        HOME_CREDENTIAL_ERROR,
        FACE_PREPARING,
        FACE_DETECTING,
        FACE_UPLOADING,
        FACE_CAMERA_PERMISSION_TEMPORARY,
        FACE_CAMERA_PERMISSION_PERMANENT,
        FACE_RETRYABLE_FAILURE,
        FACE_COMPONENT_UNAVAILABLE,
        PALM_GUIDE,
        PALM_DEVICE_UNAVAILABLE
    }

    private ZipCustomerScreenRouter() {
    }

    public static ZipScreenAsset assetFor(State state) {
        if (state == null) {
            throw new IllegalArgumentException("customer screen state is required");
        }
        switch (state) {
            case HOME_WAITING:
                return ZipScreenAsset.HOME_WAITING;
            case HOME_READING_CREDENTIAL:
                return ZipScreenAsset.HOME_READING_CREDENTIAL;
            case HOME_UNREGISTERED_COUNTDOWN:
                return ZipScreenAsset.HOME_UNREGISTERED_COUNTDOWN;
            case HOME_CREDENTIAL_ERROR:
                return ZipScreenAsset.HOME_CREDENTIAL_ERROR;
            case FACE_PREPARING:
                return ZipScreenAsset.FACE_PREPARING;
            case FACE_DETECTING:
                return ZipScreenAsset.FACE_DETECTING;
            case FACE_UPLOADING:
                return ZipScreenAsset.FACE_UPLOADING;
            case FACE_CAMERA_PERMISSION_TEMPORARY:
                return ZipScreenAsset.FACE_CAMERA_PERMISSION_TEMPORARY;
            case FACE_CAMERA_PERMISSION_PERMANENT:
                return ZipScreenAsset.FACE_CAMERA_PERMISSION_PERMANENT;
            case FACE_RETRYABLE_FAILURE:
                return ZipScreenAsset.FACE_RETRYABLE_FAILURE;
            case FACE_COMPONENT_UNAVAILABLE:
                return ZipScreenAsset.FACE_COMPONENT_UNAVAILABLE;
            case PALM_GUIDE:
                return ZipScreenAsset.PALM_GUIDE;
            case PALM_DEVICE_UNAVAILABLE:
                return ZipScreenAsset.PALM_DEVICE_UNAVAILABLE;
            default:
                throw new IllegalArgumentException(
                        "unknown customer screen state: " + state);
        }
    }
}
