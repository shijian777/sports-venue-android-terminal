package com.codex.lockertest.returnflow;

/** Safe production placeholder: no network integration means no return authority. */
public final class FailClosedReturnServiceClient implements ReturnServiceClient {
    private static final String UNAVAILABLE_MESSAGE = "服务器还柜协议未配置";

    @Override
    public ReturnServiceResult<ReturnLockerList> queryActiveLockers(
            ReturnIdentity identity, long requestId) {
        return unavailable();
    }

    @Override
    public ReturnServiceResult<ReturnAuthorization> authorize(
            ReturnIdentity identity, ReturnLocker locker, long operationId) {
        return unavailable();
    }

    @Override
    public ReturnServiceResult<ReturnCompletionReceipt> complete(ReturnAuthorization authorization) {
        return unavailable();
    }

    private static <T> ReturnServiceResult<T> unavailable() {
        return ReturnServiceResult.failure(
                ReturnServiceResult.Code.PERMANENT_FAILURE, UNAVAILABLE_MESSAGE);
    }
}
