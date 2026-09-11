package com.codex.lockertest.business;

import com.codex.lockertest.server.ApiResult;
import com.codex.lockertest.server.CallToken;
import com.codex.lockertest.server.ServerFailure;

import java.util.List;

/** Synchronous typed server contract. Callers must execute it off the UI thread. */
public interface BusinessService {
    ApiResult<AuthenticatedUser> authenticate(UserInfoRequest request, CallToken token);

    ApiResult<AuthenticatedUser> verifyMemberDynamicCode(
            AuthenticatedUser user, String code, CallToken token);

    ApiResult<ControlPanelPreview> controlPanelPreview(
            AuthenticatedUser user, int type, long areaId, int page, CallToken token);

    ApiResult<UsedCabinetList> useCabinetList(AuthenticatedUser user, CallToken token);

    ApiResult<AssignedCabinet> userBoard(
            AuthenticatedUser user, long areaId, long fcId, CallToken token);

    ApiResult<EmptyBusinessResult> openBoard(
            AuthenticatedUser user, BoardAction action, long fcId, CallToken token);

    default ApiResult<EmptyBusinessResult> mobileSmsCode(String mobile, CallToken token) {
        return unavailable();
    }

    default ApiResult<InstallerSession> installerLogin(
            String mobile, String useCode, CallToken token) {
        return unavailable();
    }

    default ApiResult<ControlPanelPreview> controlPanelPreview(
            SessionToken token, int type, long areaId, int page, CallToken callToken) {
        return unavailable();
    }

    default ApiResult<EmptyBusinessResult> storeyCabinet(
            InstallerSession session, long fcId, int storey, int number, CallToken token) {
        return unavailable();
    }

    default ApiResult<EmptyBusinessResult> installationVerify(
            SessionToken session, long areaId, long fcId, int type,
            List<SignalRecord> commands, CallToken token) {
        return unavailable();
    }

    default ApiResult<AdminLogin> adminLogin(String username, String password,
            String dynamicCode, CallToken token) {
        return unavailable();
    }

    default ApiResult<EmptyBusinessResult> quickClearCabinet(SessionToken token,
            long areaId, List<SignalRecord> commands, CallToken callToken) {
        return unavailable();
    }

    default ApiResult<EmptyBusinessResult> bindUserHand(
            AuthenticatedUser user, PalmKeywordProvider provider, CallToken token) {
        return unavailable();
    }

    static <T> ApiResult<T> unavailable() {
        return ApiResult.failure(ServerFailure.of(ServerFailure.Kind.CONFIGURATION));
    }
}
