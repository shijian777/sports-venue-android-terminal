package com.codex.lockertest.returnflow;

import com.codex.lockertest.runtime.DemoCredentials;
import com.codex.lockertest.model.LockerTarget;
import com.codex.lockertest.model.LockerZone;
import com.codex.lockertest.model.UnlockMethod;
import com.codex.lockertest.protocol.FeedbackPolarity;

import java.util.Collections;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.security.SecureRandom;

/** In-process localDemo return service; it does not verify a person's identity. */
public final class LocalDemoReturnServiceClient implements ReturnServiceClient {
    private static final long AUTHORIZATION_LIFETIME_MILLIS = 30_000L;
    private static final ReturnLocker A1 = new ReturnLocker(
            "local-demo-a1", "A1", "A区", new LockerTarget(
                    LockerZone.A, 1, 1, FeedbackPolarity.SHORT_WHEN_LOCKED));
    private static final ReturnLocker A2 = new ReturnLocker(
            "local-demo-a2", "A2", "A区", new LockerTarget(
                    LockerZone.A, 1, 2, FeedbackPolarity.SHORT_WHEN_LOCKED));
    private final LongSupplier clock;
    private final TokenSource tokenSource;
    private final Set<Long> usedOperationIds = new HashSet<>();
    private final Map<String, Set<String>> returnedLockerIds = new HashMap<>();
    private final IdentityHashMap<ReturnAuthorization, IssuedAuthorization> issued =
            new IdentityHashMap<>();

    public LocalDemoReturnServiceClient() {
        this(new LongSupplier() {
            @Override public long getAsLong() {
                return System.currentTimeMillis();
            }
        }, new SecureTokenSource());
    }

    LocalDemoReturnServiceClient(LongSupplier clock, TokenSource tokenSource) {
        if (clock == null || tokenSource == null) {
            throw new IllegalArgumentException("Clock and token source are required");
        }
        this.clock = clock;
        this.tokenSource = tokenSource;
    }

    @Override
    public synchronized ReturnServiceResult<ReturnLockerList> queryActiveLockers(
            ReturnIdentity identity, long requestId) {
        if (identity == null || requestId <= 0L) {
            return ReturnServiceResult.failure(ReturnServiceResult.Code.REJECTED);
        }
        return ReturnServiceResult.success(ReturnLockerList.of(activeLockers(identity)));
    }

    @Override
    public synchronized ReturnServiceResult<ReturnAuthorization> authorize(
            ReturnIdentity identity, ReturnLocker locker, long operationId) {
        if (identity == null || locker == null || operationId <= 0L || usedOperationIds.contains(operationId)
                || !activeLockers(identity).contains(locker)) {
            return ReturnServiceResult.failure(ReturnServiceResult.Code.REJECTED);
        }
        String token = tokenSource.nextToken();
        if (token == null || token.trim().isEmpty()) {
            return ReturnServiceResult.failure(ReturnServiceResult.Code.RETRYABLE_FAILURE);
        }
        long now = clock.getAsLong();
        if (now < 0L || now > Long.MAX_VALUE - AUTHORIZATION_LIFETIME_MILLIS) {
            return ReturnServiceResult.failure(ReturnServiceResult.Code.RETRYABLE_FAILURE);
        }
        ReturnAuthorization authorization = new ReturnAuthorization(operationId, locker,
                unlockFrame(locker), successFrame(locker), failureFrame(locker), token,
                now + AUTHORIZATION_LIFETIME_MILLIS);
        usedOperationIds.add(operationId);
        issued.put(authorization, new IssuedAuthorization(identityKey(identity), locker.serverLockerId(), authorization));
        return ReturnServiceResult.success(authorization);
    }

    @Override
    public synchronized ReturnServiceResult<ReturnCompletionReceipt> complete(
            ReturnAuthorization authorization) {
        if (authorization == null) {
            return ReturnServiceResult.failure(ReturnServiceResult.Code.REJECTED);
        }
        IssuedAuthorization record = issued.get(authorization);
        if (record == null || !record.matches(authorization)) {
            return ReturnServiceResult.failure(ReturnServiceResult.Code.REJECTED);
        }
        if (record.completed) {
            return ReturnServiceResult.success(new ReturnCompletionReceipt(authorization.operationId(), true));
        }
        if (returned(record.identityKey).contains(record.lockerId)) {
            return ReturnServiceResult.failure(ReturnServiceResult.Code.REJECTED);
        }
        returned(record.identityKey).add(record.lockerId);
        record.completed = true;
        return ReturnServiceResult.success(new ReturnCompletionReceipt(authorization.operationId(), false));
    }

    interface TokenSource {
        String nextToken();
    }

    private List<ReturnLocker> activeLockers(ReturnIdentity identity) {
        List<ReturnLocker> fixtures = fixturesFor(identity);
        if (fixtures.isEmpty()) {
            return fixtures;
        }
        Set<String> returned = returned(identityKey(identity));
        ArrayList<ReturnLocker> active = new ArrayList<>();
        for (ReturnLocker locker : fixtures) {
            if (!returned.contains(locker.serverLockerId())) {
                active.add(locker);
            }
        }
        return active;
    }

    private static List<ReturnLocker> fixturesFor(ReturnIdentity identity) {
        if (identity == null) {
            return Collections.emptyList();
        }
        if (identity.method() == UnlockMethod.ID_CARD && DemoCredentials.ID_CARD.equals(identity.credential())) {
            return Collections.singletonList(A1);
        }
        if (identity.method() == UnlockMethod.QR && DemoCredentials.QR_CODE.equals(identity.credential())) {
            return Arrays.asList(A1, A2);
        }
        if ((identity.method() == UnlockMethod.PHONE && DemoCredentials.PHONE.equals(identity.credential()))
                || (identity.method() == UnlockMethod.PASSWORD && DemoCredentials.PASSWORD.equals(identity.credential()))
                || ((identity.method() == UnlockMethod.FACE || identity.method() == UnlockMethod.PALM)
                && !identity.credential().trim().isEmpty())) {
            return Collections.singletonList(A1);
        }
        return Collections.emptyList();
    }

    private Set<String> returned(String identityKey) {
        Set<String> values = returnedLockerIds.get(identityKey);
        if (values == null) {
            values = new HashSet<>();
            returnedLockerIds.put(identityKey, values);
        }
        return values;
    }

    private static String identityKey(ReturnIdentity identity) {
        return identity.method().name() + '\u0000' + identity.credential();
    }

    private static byte[] unlockFrame(ReturnLocker locker) {
        return locker.equals(A1)
                ? new byte[] {(byte) 0x8A, 0x01, 0x01, 0x11, (byte) 0x9B}
                : new byte[] {(byte) 0x8A, 0x01, 0x02, 0x11, (byte) 0x98};
    }

    private static byte[] successFrame(ReturnLocker locker) {
        return locker.equals(A1)
                ? new byte[] {(byte) 0x8A, 0x01, 0x01, 0x00, (byte) 0x8A}
                : new byte[] {(byte) 0x8A, 0x01, 0x02, 0x00, (byte) 0x89};
    }

    private static byte[] failureFrame(ReturnLocker locker) {
        return unlockFrame(locker);
    }

    private static final class IssuedAuthorization {
        private final String identityKey;
        private final String lockerId;
        private final ReturnAuthorization authorization;
        private boolean completed;

        IssuedAuthorization(String identityKey, String lockerId, ReturnAuthorization authorization) {
            this.identityKey = identityKey;
            this.lockerId = lockerId;
            this.authorization = authorization;
        }

        boolean matches(ReturnAuthorization candidate) {
            return authorization.operationId() == candidate.operationId()
                    && authorization.locker().equals(candidate.locker())
                    && authorization.completionToken().equals(candidate.completionToken())
                    && authorization.expiresAt() == candidate.expiresAt()
                    && Arrays.equals(authorization.unlockCommand(), candidate.unlockCommand())
                    && Arrays.equals(authorization.expectedSuccessFrame(), candidate.expectedSuccessFrame())
                    && Arrays.equals(authorization.expectedFailureFrame(), candidate.expectedFailureFrame());
        }
    }

    private static final class SecureTokenSource implements TokenSource {
        private final SecureRandom random = new SecureRandom();

        @Override
        public String nextToken() {
            byte[] bytes = new byte[18];
            random.nextBytes(bytes);
            StringBuilder result = new StringBuilder(36);
            for (byte value : bytes) {
                result.append(Character.forDigit((value >>> 4) & 0x0f, 16));
                result.append(Character.forDigit(value & 0x0f, 16));
            }
            return result.toString();
        }
    }
}
