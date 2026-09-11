package com.codex.lockertest.returnflow;

/** Transport-agnostic synchronous contract for server-authorized returns. */
public interface ReturnServiceClient {
    ReturnServiceResult<ReturnLockerList> queryActiveLockers(
            ReturnIdentity identity, long requestId);

    ReturnServiceResult<ReturnAuthorization> authorize(
            ReturnIdentity identity, ReturnLocker locker, long operationId);

    ReturnServiceResult<ReturnCompletionReceipt> complete(ReturnAuthorization authorization);
}
