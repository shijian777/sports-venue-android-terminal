package com.codex.lockertest.business;

import com.codex.lockertest.server.ApiResult;
import com.codex.lockertest.server.CallToken;
import com.codex.lockertest.server.ServerFailure;

/** Replaceable multipart boundary; the supplied package does not define its signing wire format. */
public interface FaceImageUploadAdapter {
    ApiResult<String> upload(byte[] jpeg, CallToken token);
    String unavailableReason();

    static FaceImageUploadAdapter unconfigured() {
        return new FaceImageUploadAdapter() {
            @Override public ApiResult<String> upload(byte[] jpeg, CallToken token) {
                return ApiResult.failure(ServerFailure.of(ServerFailure.Kind.CONFIGURATION));
            }

            @Override public String unavailableReason() {
                return "人脸图片上传签名协议未配置";
            }
        };
    }
}
