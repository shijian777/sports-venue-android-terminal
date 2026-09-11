package com.codex.lockertest.business;

import com.codex.lockertest.server.ApiResult;
import com.codex.lockertest.server.CallToken;
import com.codex.lockertest.server.ServerFailure;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertArrayEquals;

public final class MissingContractAdaptersTest {
    @Test
    public void unconfiguredFaceUploadFailsWithoutCallingAuthentication() {
        RecordingBusinessService service = new RecordingBusinessService();
        FaceAuthenticationPipeline pipeline = new FaceAuthenticationPipeline(
                service, FaceImageUploadAdapter.unconfigured());

        ApiResult<AuthenticatedUser> result = pipeline.authenticate(
                new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xd9},
                new CallToken());

        assertFalse(result.isSuccess());
        assertEquals(ServerFailure.Kind.CONFIGURATION, result.failure().kind());
        assertEquals(0, service.authenticateCalls);
    }

    @Test
    public void successfulUploadIdentifierFlowsIntoTypeFiveAuthentication() {
        RecordingBusinessService service = new RecordingBusinessService();
        FaceImageUploadAdapter adapter = new FaceImageUploadAdapter() {
            @Override
            public ApiResult<String> upload(byte[] jpeg, CallToken token) {
                return ApiResult.success("opaque-image-id");
            }

            @Override public String unavailableReason() { return null; }
        };
        FaceAuthenticationPipeline pipeline = new FaceAuthenticationPipeline(service, adapter);

        ApiResult<AuthenticatedUser> result = pipeline.authenticate(
                new byte[] {(byte) 0xff, (byte) 0xd8, 1, (byte) 0xff, (byte) 0xd9},
                new CallToken());

        assertEquals(1, service.authenticateCalls);
        assertEquals(5, service.lastRequest.type());
        assertEquals("opaque-image-id", service.lastRequest.keyword());
        assertEquals(service.result, result);
    }

    @Test
    public void uploadRuntimeAndNullAreConfigurationFailuresAndWipeOwnedJpeg() {
        RecordingBusinessService service = new RecordingBusinessService();
        RecordingUploadAdapter adapter = new RecordingUploadAdapter();
        FaceAuthenticationPipeline pipeline = new FaceAuthenticationPipeline(service, adapter);

        adapter.failure = new IllegalStateException("upload fixture");
        ApiResult<AuthenticatedUser> thrown = pipeline.authenticate(validJpeg(), new CallToken());
        assertFailure(thrown, ServerFailure.Kind.CONFIGURATION);
        assertAllZero(adapter.lastJpeg);
        assertEquals(0, service.authenticateCalls);

        adapter.failure = null;
        adapter.result = null;
        ApiResult<AuthenticatedUser> missing = pipeline.authenticate(validJpeg(), new CallToken());
        assertFailure(missing, ServerFailure.Kind.CONFIGURATION);
        assertAllZero(adapter.lastJpeg);
        assertEquals(0, service.authenticateCalls);
    }

    @Test
    public void authenticationRuntimeAndNullAreConfigurationFailuresAndWipeOwnedJpeg() {
        RecordingBusinessService service = new RecordingBusinessService();
        RecordingUploadAdapter adapter = new RecordingUploadAdapter();
        FaceAuthenticationPipeline pipeline = new FaceAuthenticationPipeline(service, adapter);

        service.failure = new IllegalStateException("authentication fixture");
        ApiResult<AuthenticatedUser> thrown = pipeline.authenticate(validJpeg(), new CallToken());
        assertFailure(thrown, ServerFailure.Kind.CONFIGURATION);
        assertAllZero(adapter.lastJpeg);

        service.failure = null;
        service.result = null;
        ApiResult<AuthenticatedUser> missing = pipeline.authenticate(validJpeg(), new CallToken());
        assertFailure(missing, ServerFailure.Kind.CONFIGURATION);
        assertAllZero(adapter.lastJpeg);
        assertEquals(2, service.authenticateCalls);
    }

    @Test
    public void cancellationAfterAuthenticationWinsOverSuccessExceptionOrNull() {
        for (int mode = 0; mode < 3; mode++) {
            RecordingBusinessService service = new RecordingBusinessService();
            RecordingUploadAdapter adapter = new RecordingUploadAdapter();
            FaceAuthenticationPipeline pipeline = new FaceAuthenticationPipeline(service, adapter);
            CallToken token = new CallToken();
            service.cancelToken = true;
            if (mode == 1) service.failure = new IllegalStateException("late fixture");
            if (mode == 2) service.result = null;

            ApiResult<AuthenticatedUser> result = pipeline.authenticate(validJpeg(), token);

            assertFailure(result, ServerFailure.Kind.TIMEOUT);
            assertAllZero(adapter.lastJpeg);
        }
    }

    @Test
    public void cancellationDuringUploadWinsWithoutAuthenticatingOrWipingCallerBytes() {
        for (int mode = 0; mode < 4; mode++) {
            RecordingBusinessService service = new RecordingBusinessService();
            RecordingUploadAdapter adapter = new RecordingUploadAdapter();
            adapter.cancelToken = true;
            if (mode == 1) adapter.failure = new IllegalStateException("cancelled upload fixture");
            if (mode == 2) adapter.result = null;
            if (mode == 3) adapter.result = ApiResult.failure(
                    ServerFailure.of(ServerFailure.Kind.CONFIGURATION));
            byte[] caller = validJpeg();
            ApiResult<AuthenticatedUser> result = new FaceAuthenticationPipeline(service, adapter)
                    .authenticate(caller, new CallToken());

            assertFailure(result, ServerFailure.Kind.TIMEOUT);
            assertEquals(0, service.authenticateCalls);
            assertAllZero(adapter.lastJpeg);
            assertArrayEquals(validJpeg(), caller);
        }
    }

    @Test
    public void missingPalmAndMqttContractsExposeReasonsWithoutExternalIo() {
        PalmKeywordProvider palm = PalmKeywordProvider.unconfigured();
        RealtimeOpenAdapter mqtt = RealtimeOpenAdapter.unconfigured();
        InstallationVerifyAdapter installation = InstallationVerifyAdapter.unconfigured();
        assertEquals("掌静脉身份编码协议未配置", palm.unavailableReason());
        assertEquals("MQTT连接与消息协议未配置", mqtt.unavailableReason());
        assertEquals("安装校验实机流程尚未接入", installation.unavailableReason());
    }

    private static final class RecordingBusinessService implements BusinessService {
        int authenticateCalls;
        UserInfoRequest lastRequest;
        ApiResult<AuthenticatedUser> result = ApiResult.success(new AuthenticatedUser(
                SessionToken.of("token"), UserType.USER, 0, 1));
        RuntimeException failure;
        boolean cancelToken;

        @Override
        public ApiResult<AuthenticatedUser> authenticate(
                UserInfoRequest request, CallToken token) {
            authenticateCalls++;
            lastRequest = request;
            if (cancelToken) token.cancel(CallToken.Reason.TIMEOUT);
            if (failure != null) throw failure;
            return result;
        }

        @Override public ApiResult<AuthenticatedUser> verifyMemberDynamicCode(
                AuthenticatedUser user, String code, CallToken token) { throw unused(); }
        @Override public ApiResult<ControlPanelPreview> controlPanelPreview(
                AuthenticatedUser user, int type, long areaId, int page,
                CallToken token) { throw unused(); }
        @Override public ApiResult<UsedCabinetList> useCabinetList(
                AuthenticatedUser user, CallToken token) { throw unused(); }
        @Override public ApiResult<AssignedCabinet> userBoard(
                AuthenticatedUser user, long areaId, long fcId,
                CallToken token) { throw unused(); }
        @Override public ApiResult<EmptyBusinessResult> openBoard(
                AuthenticatedUser user, BoardAction action, long fcId,
                CallToken token) { throw unused(); }

        private static AssertionError unused() { return new AssertionError("Unexpected call"); }
    }

    private static final class RecordingUploadAdapter implements FaceImageUploadAdapter {
        ApiResult<String> result = ApiResult.success("opaque-image-id");
        RuntimeException failure;
        byte[] lastJpeg;
        boolean cancelToken;

        @Override
        public ApiResult<String> upload(byte[] jpeg, CallToken token) {
            lastJpeg = jpeg;
            if (cancelToken) token.cancel(CallToken.Reason.TIMEOUT);
            if (failure != null) throw failure;
            return result;
        }

        @Override public String unavailableReason() { return null; }
    }

    private static byte[] validJpeg() {
        return new byte[] {(byte) 0xff, (byte) 0xd8, 1, (byte) 0xff, (byte) 0xd9};
    }

    private static void assertFailure(ApiResult<?> result, ServerFailure.Kind expected) {
        assertFalse(result.isSuccess());
        assertEquals(expected, result.failure().kind());
    }

    private static void assertAllZero(byte[] bytes) {
        assertTrue(bytes != null);
        for (byte value : bytes) assertEquals(0, value);
    }
}
