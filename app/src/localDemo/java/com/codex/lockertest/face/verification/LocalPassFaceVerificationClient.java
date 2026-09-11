package com.codex.lockertest.face.verification;

import java.security.SecureRandom;
import java.util.Arrays;

public final class LocalPassFaceVerificationClient implements FaceVerificationClient {
    static final long PASS_DELAY_MILLIS = 800L;
    static final long CREDENTIAL_LIFETIME_MILLIS = 60_000L;

    public interface Clock {
        long epochMillis();
        long elapsedMillis();
    }

    public interface Scheduler {
        ScheduledTask schedule(Runnable task, long delayMillis);
    }

    public interface ScheduledTask {
        void cancel();
    }

    public interface LogSink {
        void log(String requestId, String stage, long elapsedMillis, int byteCount);
    }

    public interface CredentialGenerator {
        String nextCredential();
    }

    private final FaceVerificationEnvironment environment;
    private final FaceVerificationEnvironment.IssuerCapability issuerCapability;
    private final Clock clock;
    private final Scheduler scheduler;
    private final FaceJpegContract.Decoder decoder;
    private final LogSink log;
    private final CredentialGenerator credentials;

    LocalPassFaceVerificationClient(FaceVerificationEnvironment environment,
            FaceVerificationEnvironment.IssuerCapability issuerCapability,
            Clock clock, Scheduler scheduler, FaceJpegContract.Decoder decoder,
            LogSink log, CredentialGenerator credentials) {
        if (environment == null || issuerCapability == null || clock == null || scheduler == null
                || decoder == null || log == null || credentials == null) {
            throw new IllegalArgumentException("client dependencies cannot be null");
        }
        this.environment = environment;
        this.issuerCapability = issuerCapability;
        this.clock = clock;
        this.scheduler = scheduler;
        this.decoder = decoder;
        this.log = log;
        this.credentials = credentials;
    }

    @Override
    public Cancellable verify(FaceVerificationRequest request, Callback callback) {
        if (request == null || callback == null) {
            throw new IllegalArgumentException("request and callback cannot be null");
        }
        byte[] ownedJpeg = request.takeOwnedJpeg();
        Pending pending = new Pending(request, callback, ownedJpeg,
                clock.elapsedMillis(), environment.currentPolicyEpoch());
        pending.safeLog("RECEIVED");

        if (!environment.isEnabled()) {
            pending.complete(FaceVerificationResult.terminalFailure(
                    FaceVerificationStatus.NOT_PASSED, request.requestId()), "REJECTED", true);
            return pending;
        }
        if (!FaceJpegContract.isValid(ownedJpeg, decoder)) {
            pending.complete(FaceVerificationResult.terminalFailure(
                    FaceVerificationStatus.NOT_PASSED, request.requestId()), "REJECTED", true);
            return pending;
        }
        if (!environment.addDisableListener(pending)) {
            pending.onDisabled();
            return pending;
        }
        pending.schedule();
        return pending;
    }

    static String secureDemoCredential() {
        byte[] random = new byte[16];
        new SecureRandom().nextBytes(random);
        StringBuilder value = new StringBuilder("local-demo:");
        for (byte item : random) {
            value.append(Character.forDigit((item >>> 4) & 0x0f, 16));
            value.append(Character.forDigit(item & 0x0f, 16));
        }
        return value.toString();
    }

    private final class Pending
            implements Cancellable, FaceVerificationEnvironment.DisableListener {
        private final FaceVerificationRequest request;
        private final Callback callback;
        private final byte[] ownedJpeg;
        private final int byteCount;
        private final long startedAt;
        private final long issuePolicyEpoch;
        private ScheduledTask scheduledTask;
        private boolean done;

        Pending(FaceVerificationRequest request, Callback callback, byte[] ownedJpeg,
                long startedAt, long issuePolicyEpoch) {
            this.request = request;
            this.callback = callback;
            this.ownedJpeg = ownedJpeg;
            this.byteCount = ownedJpeg.length;
            this.startedAt = startedAt;
            this.issuePolicyEpoch = issuePolicyEpoch;
        }

        void schedule() {
            ScheduledTask created;
            try {
                created = scheduler.schedule(this::issue, PASS_DELAY_MILLIS);
                if (created == null) {
                    throw new IllegalStateException("scheduler returned no task");
                }
            } catch (RuntimeException failure) {
                complete(FaceVerificationResult.terminalFailure(
                        FaceVerificationStatus.SERVER_ERROR, request.requestId()),
                        "SERVER_ERROR", true);
                return;
            }
            boolean cancelCreated;
            synchronized (this) {
                cancelCreated = done;
                if (!done) {
                    scheduledTask = created;
                }
            }
            if (cancelCreated) {
                safeCancel(created);
                return;
            }
            safeLog("SCHEDULED");
        }

        void issue() {
            long elapsedNow = clock.elapsedMillis();
            if (elapsedNow < startedAt
                    || elapsedNow - startedAt < PASS_DELAY_MILLIS) {
                complete(FaceVerificationResult.terminalFailure(
                        FaceVerificationStatus.SERVER_ERROR, request.requestId()),
                        "SERVER_ERROR", true);
                return;
            }
            try {
                String credential = credentials.nextCredential();
                FaceVerificationResult result;
                ScheduledTask cancelTask;
                synchronized (this) {
                    if (done) {
                        return;
                    }
                    long issuedAt = clock.epochMillis();
                    if (issuedAt > Long.MAX_VALUE - CREDENTIAL_LIFETIME_MILLIS) {
                        throw new IllegalStateException("credential expiry overflow");
                    }
                    result = environment.issuePassed(
                            issuerCapability, request.requestId(), credential,
                            issuedAt + CREDENTIAL_LIFETIME_MILLIS,
                            request.deviceBinding(), request.processBinding(), issuePolicyEpoch,
                            issuedAt);
                    done = true;
                    cancelTask = scheduledTask;
                    scheduledTask = null;
                    Arrays.fill(ownedJpeg, (byte) 0);
                }
                finishClaimed(cancelTask, result, "PASSED", true);
            } catch (RuntimeException failure) {
                complete(FaceVerificationResult.terminalFailure(
                        FaceVerificationStatus.SERVER_ERROR, request.requestId()),
                        "SERVER_ERROR", true);
            }
        }

        @Override
        public void cancel() {
            complete(null, "CANCELLED", false);
        }

        @Override
        public void onDisabled() {
            complete(FaceVerificationResult.terminalFailure(
                    FaceVerificationStatus.CANCELLED, request.requestId()),
                    "CANCELLED", true);
        }

        void complete(FaceVerificationResult result, String stage, boolean notifyCallback) {
            ScheduledTask cancelTask;
            synchronized (this) {
                if (done) {
                    return;
                }
                done = true;
                cancelTask = scheduledTask;
                scheduledTask = null;
                Arrays.fill(ownedJpeg, (byte) 0);
            }
            finishClaimed(cancelTask, result, stage, notifyCallback);
        }

        private void finishClaimed(ScheduledTask cancelTask,
                FaceVerificationResult result, String stage, boolean notifyCallback) {
            if (cancelTask != null) {
                safeCancel(cancelTask);
            }
            environment.removeDisableListener(this);
            safeLog(stage);
            if (notifyCallback) {
                safeCallback(result);
            }
        }

        void safeCancel(ScheduledTask task) {
            try {
                task.cancel();
            } catch (RuntimeException ignored) {
                // A scheduler adapter cannot interrupt terminal cleanup.
            }
        }

        void safeCallback(FaceVerificationResult result) {
            try {
                callback.onCompleted(result);
            } catch (RuntimeException ignored) {
                // A consumer cannot interrupt cleanup or disable fanout.
            }
        }

        void safeLog(String stage) {
            try {
                writeLog(stage);
            } catch (RuntimeException ignored) {
                // Logging is best-effort and never owns verification control flow.
            }
        }

        void writeLog(String stage) {
            long elapsed = clock.elapsedMillis() - startedAt;
            log.log(request.requestId(), "face-verifier=LOCAL_DEMO;stage=" + stage,
                    Math.max(0L, elapsed), byteCount);
        }
    }
}
