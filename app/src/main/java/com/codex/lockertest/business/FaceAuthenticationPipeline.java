package com.codex.lockertest.business;

import com.codex.lockertest.server.ApiResult;
import com.codex.lockertest.server.CallToken;
import com.codex.lockertest.server.ServerFailure;

import java.util.Arrays;

/** Capture-to-upload-to-userInfo pipeline; it never treats a missing upload as success. */
public final class FaceAuthenticationPipeline {
    private static final int MAX_JPEG_BYTES = 8 * 1024 * 1024;

    private final BusinessService service;
    private final FaceImageUploadAdapter uploadAdapter;

    public FaceAuthenticationPipeline(
            BusinessService service, FaceImageUploadAdapter uploadAdapter) {
        if (service == null || uploadAdapter == null) {
            throw new IllegalArgumentException("Face pipeline dependencies are required");
        }
        this.service = service;
        this.uploadAdapter = uploadAdapter;
    }

    public ApiResult<AuthenticatedUser> authenticate(byte[] jpeg, CallToken token) {
        if (!validJpeg(jpeg) || token == null) return configurationFailure();
        if (token.isCancelled()) return cancelled(token);
        byte[] owned = Arrays.copyOf(jpeg, jpeg.length);
        try {
            ApiResult<String> upload = uploadAdapter.upload(owned, token);
            if (token.isCancelled()) return cancelled(token);
            if (upload == null) return configurationFailure();
            if (!upload.isSuccess()) return ApiResult.failure(upload.failure());
            UserInfoRequest request = UserInfoRequest.faceImage(upload.value());
            ApiResult<AuthenticatedUser> result = service.authenticate(request, token);
            if (token.isCancelled()) return cancelled(token);
            return result == null ? configurationFailure() : result;
        } catch (RuntimeException unavailableBoundary) {
            // Pluggable dependencies must not escape with payload-bearing exceptions.
            return token.isCancelled() ? cancelled(token) : configurationFailure();
        } finally {
            Arrays.fill(owned, (byte) 0);
        }
    }

    private static boolean validJpeg(byte[] value) {
        return value != null && value.length >= 4 && value.length <= MAX_JPEG_BYTES
                && (value[0] & 0xff) == 0xff && (value[1] & 0xff) == 0xd8
                && (value[value.length - 2] & 0xff) == 0xff
                && (value[value.length - 1] & 0xff) == 0xd9;
    }

    private static <T> ApiResult<T> configurationFailure() {
        return ApiResult.failure(ServerFailure.of(ServerFailure.Kind.CONFIGURATION));
    }

    private static <T> ApiResult<T> cancelled(CallToken token) {
        return ApiResult.failure(ServerFailure.of(token.reason() == CallToken.Reason.TIMEOUT
                ? ServerFailure.Kind.TIMEOUT : ServerFailure.Kind.CANCELLED));
    }
}
