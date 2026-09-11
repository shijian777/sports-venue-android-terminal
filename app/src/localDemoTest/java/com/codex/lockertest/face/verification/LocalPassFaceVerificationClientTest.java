package com.codex.lockertest.face.verification;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public final class LocalPassFaceVerificationClientTest {
    private static final String TICKET = "00112233445566778899aabbccddeeff";

    @Test
    public void defaultDisabledRejectsImmediatelyWithoutScheduling() {
        Fixture fixture = new Fixture(false);
        List<FaceVerificationResult> results = new ArrayList<FaceVerificationResult>();

        fixture.client.verify(fixture.request(validJpeg()), results::add);

        assertEquals(1, results.size());
        assertEquals(FaceVerificationStatus.NOT_PASSED, results.get(0).status());
        assertEquals(0, fixture.scheduler.jobs.size());
    }

    @Test
    public void enabledValidJpegCompletesOnceAtExactlyEightHundredMilliseconds() {
        Fixture fixture = new Fixture(true);
        List<FaceVerificationResult> results = new ArrayList<FaceVerificationResult>();

        fixture.client.verify(fixture.request(validJpeg()), results::add);
        fixture.scheduler.advanceBy(799L);
        assertTrue(results.isEmpty());
        fixture.scheduler.advanceBy(1L);

        assertEquals(1, results.size());
        assertEquals(FaceVerificationStatus.PASSED, results.get(0).status());
        fixture.scheduler.advanceBy(10_000L);
        assertEquals(1, results.size());
    }

    @Test
    public void schedulerRunningSynchronouslyBeforeDelayFailsClosedWithoutIssuing() {
        Fixture fixture = new Fixture(true);
        CapturingDecoder decoder = new CapturingDecoder();
        int[] credentialCalls = new int[1];
        LocalPassFaceVerificationClient.Scheduler immediateScheduler =
                (task, delayMillis) -> {
                    task.run();
                    return () -> { };
                };
        fixture.client = new LocalPassFaceVerificationClient(
                fixture.environment, fixture.issuerCapability, fixture.clock,
                immediateScheduler, decoder,
                (requestId, stage, elapsedMillis, byteCount) -> fixture.logs.add(
                        new LogRecord(requestId, stage, elapsedMillis, byteCount)),
                () -> {
                    credentialCalls[0]++;
                    return "credential-secret";
                });
        List<FaceVerificationResult> results = new ArrayList<FaceVerificationResult>();

        fixture.client.verify(fixture.request(validJpeg()), results::add);

        assertEquals(1, results.size());
        assertEquals(FaceVerificationStatus.SERVER_ERROR, results.get(0).status());
        assertEquals(0, credentialCalls[0]);
        assertEquals(0, fixture.ticketIds.calls);
        assertArrayEquals(new byte[decoder.seen.length], decoder.seen);
    }

    @Test
    public void synchronousCompletionCancelsReturnedTaskOutsidePendingLockWithoutScheduledLog() {
        Fixture fixture = new Fixture(true);
        CapturingDecoder decoder = new CapturingDecoder();
        ReentrantCancelScheduler scheduler = new ReentrantCancelScheduler();
        fixture.client = new LocalPassFaceVerificationClient(
                fixture.environment, fixture.issuerCapability, fixture.clock,
                scheduler, decoder,
                (requestId, stage, elapsedMillis, byteCount) -> fixture.logs.add(
                        new LogRecord(requestId, stage, elapsedMillis, byteCount)),
                () -> "credential-secret");
        List<FaceVerificationResult> results = new ArrayList<FaceVerificationResult>();

        fixture.client.verify(fixture.request(validJpeg()), results::add);

        assertFalse("cancel must not wait on a worker blocked by the Pending lock",
                scheduler.cancelTimedOut);
        assertEquals(1, results.size());
        assertEquals(FaceVerificationStatus.SERVER_ERROR, results.get(0).status());
        assertArrayEquals(new byte[decoder.seen.length], decoder.seen);
        for (LogRecord record : fixture.logs) {
            assertFalse("a terminal request must not be logged as scheduled",
                    record.stage.endsWith("SCHEDULED"));
        }
    }

    @Test
    public void schedulerQueueRejectionFailsClosedOnceAndClearsOwnedJpeg() {
        Fixture fixture = new Fixture(true);
        CapturingDecoder decoder = new CapturingDecoder();
        int[] credentialCalls = new int[1];
        fixture.client = new LocalPassFaceVerificationClient(
                fixture.environment, fixture.issuerCapability, fixture.clock,
                (task, delayMillis) -> null, decoder,
                (requestId, stage, elapsedMillis, byteCount) -> fixture.logs.add(
                        new LogRecord(requestId, stage, elapsedMillis, byteCount)),
                () -> {
                    credentialCalls[0]++;
                    return "credential-secret";
                });
        List<FaceVerificationResult> results = new ArrayList<FaceVerificationResult>();

        fixture.client.verify(fixture.request(validJpeg()), results::add);

        assertEquals(1, results.size());
        assertEquals(FaceVerificationStatus.SERVER_ERROR, results.get(0).status());
        assertEquals(0, credentialCalls[0]);
        assertEquals(0, fixture.ticketIds.calls);
        assertArrayEquals(new byte[decoder.seen.length], decoder.seen);
    }

    @Test
    public void jpegRequiresMarkersDecoderPositiveDimensionsAndOneMiBMaximum() {
        assertRejected(new byte[] {1, 2, 3}, new FixedDecoder(10, 10));
        assertRejected(new byte[] {(byte) 0xff, (byte) 0xd8, 1, 2},
                new FixedDecoder(10, 10));
        assertRejected(validJpeg(), new FixedDecoder(0, 10));
        assertRejected(validJpeg(), new FixedDecoder(10, 0));
        assertRejected(validJpeg(), new FixedDecoder(-1, -1));
        assertRejected(validJpeg(), new FixedDecoder(null));
        byte[] tooLarge = new byte[FaceJpegContract.MAX_BYTES + 1];
        tooLarge[0] = (byte) 0xff;
        tooLarge[1] = (byte) 0xd8;
        tooLarge[tooLarge.length - 2] = (byte) 0xff;
        tooLarge[tooLarge.length - 1] = (byte) 0xd9;
        assertRejected(tooLarge, new FixedDecoder(10, 10));
    }

    @Test
    public void jpegAtExactlyOneMiBRemainsEligible() {
        Fixture fixture = new Fixture(true);
        byte[] jpeg = new byte[FaceJpegContract.MAX_BYTES];
        jpeg[0] = (byte) 0xff;
        jpeg[1] = (byte) 0xd8;
        jpeg[jpeg.length - 2] = (byte) 0xff;
        jpeg[jpeg.length - 1] = (byte) 0xd9;
        List<FaceVerificationResult> results = new ArrayList<FaceVerificationResult>();

        fixture.client.verify(fixture.request(jpeg), results::add);
        fixture.scheduler.advanceBy(800L);

        assertEquals(1, results.size());
        assertEquals(FaceVerificationStatus.PASSED, results.get(0).status());
    }

    @Test
    public void cancelBeforeDelaySuppressesCallbackAndZeroesOwnedJpegCopy() {
        Fixture fixture = new Fixture(true);
        CapturingDecoder decoder = new CapturingDecoder();
        fixture.client = fixture.newClient(decoder);
        List<FaceVerificationResult> results = new ArrayList<FaceVerificationResult>();

        FaceVerificationClient.Cancellable cancellable =
                fixture.client.verify(fixture.request(validJpeg()), results::add);
        assertNotNull(decoder.seen);
        cancellable.cancel();
        fixture.scheduler.advanceBy(800L);

        assertTrue(results.isEmpty());
        assertArrayEquals(new byte[decoder.seen.length], decoder.seen);
    }

    @Test
    public void cancelWhileCredentialGenerationIsBlockedCannotRegisterAnOrphanTicket()
            throws Exception {
        Fixture fixture = new Fixture(true);
        CapturingDecoder decoder = new CapturingDecoder();
        BlockingCredentialGenerator credentials = new BlockingCredentialGenerator();
        fixture.client = fixture.newClient(decoder, credentials);
        List<FaceVerificationResult> results = new ArrayList<FaceVerificationResult>();

        FaceVerificationClient.Cancellable cancellable =
                fixture.client.verify(fixture.request(validJpeg()), results::add);
        Thread issuer = new Thread(() -> fixture.scheduler.advanceBy(800L));
        issuer.start();
        assertTrue(credentials.entered.await(5L, TimeUnit.SECONDS));

        cancellable.cancel();
        credentials.release.countDown();
        issuer.join(5_000L);

        assertFalse("issuer thread must terminate", issuer.isAlive());
        assertTrue(results.isEmpty());
        assertEquals(0, fixture.ticketIds.calls);
        assertArrayEquals(new byte[decoder.seen.length], decoder.seen);
        FaceVerificationResult forged = FaceVerificationResult.passed(
                FaceVerificationSource.LOCAL_DEMO, "request-1", "credential-secret",
                TICKET, fixture.clock.epochMillis + 60_000L,
                "device-binding", "process-binding",
                fixture.environment.currentPolicyEpoch());
        assertEquals(FaceVerificationTicketValidator.TicketVerdict.TICKET_NOT_REGISTERED,
                fixture.environment.validateTicket(forged, "request-1",
                        "device-binding", "process-binding", fixture.clock.epochMillis));
    }

    @Test
    public void issuedCredentialExpiresAtSixtySecondsAndBindsAllInputs() {
        Fixture fixture = new Fixture(true);
        List<FaceVerificationResult> results = new ArrayList<FaceVerificationResult>();
        fixture.clock.epochMillis = 4_000L;

        fixture.client.verify(fixture.request(validJpeg()), results::add);
        fixture.scheduler.advanceBy(800L);
        FaceVerificationResult result = results.get(0);

        assertEquals(64_800L, result.expiresAtEpochMillis());
        assertEquals("request-1", result.requestId());
        assertEquals("device-binding", result.deviceBinding());
        assertEquals("process-binding", result.processBinding());
        assertEquals(fixture.environment.currentPolicyEpoch(),
                result.verificationPolicyEpoch());
        assertEquals(FaceVerificationTicketValidator.TicketVerdict.VALID,
                fixture.environment.validateTicket(result, "request-1",
                        "device-binding", "process-binding", 64_799L));
        assertEquals(FaceVerificationTicketValidator.TicketVerdict.EXPIRED,
                fixture.environment.validateTicket(result, "request-1",
                        "device-binding", "process-binding", 64_800L));
    }

    @Test
    public void disablingCancelsOutstandingRequestRevokesTicketsAndAdvancesEpoch() {
        Fixture fixture = new Fixture(true);
        List<FaceVerificationResult> first = new ArrayList<FaceVerificationResult>();
        fixture.client.verify(fixture.request(validJpeg()), first::add);
        fixture.scheduler.advanceBy(800L);
        FaceVerificationResult issued = first.get(0);
        long issuedEpoch = issued.verificationPolicyEpoch();

        CapturingDecoder decoder = new CapturingDecoder();
        fixture.client = fixture.newClient(decoder);
        List<FaceVerificationResult> pending = new ArrayList<FaceVerificationResult>();
        fixture.client.verify(fixture.request(validJpeg()), pending::add);
        fixture.environment.setEnabled(false);

        assertEquals(issuedEpoch + 1L, fixture.environment.currentPolicyEpoch());
        assertEquals(1, pending.size());
        assertEquals(FaceVerificationStatus.CANCELLED, pending.get(0).status());
        assertArrayEquals(new byte[decoder.seen.length], decoder.seen);
        assertEquals(FaceVerificationTicketValidator.TicketVerdict.DISABLED,
                fixture.environment.validateTicket(issued, "request-1",
                        "device-binding", "process-binding", fixture.clock.epochMillis));

        fixture.environment.setEnabled(true);
        assertEquals(issuedEpoch + 2L, fixture.environment.currentPolicyEpoch());
        assertEquals(FaceVerificationTicketValidator.TicketVerdict.POLICY_EPOCH_MISMATCH,
                fixture.environment.validateTicket(issued, "request-1",
                        "device-binding", "process-binding", fixture.clock.epochMillis));
        fixture.scheduler.advanceBy(800L);
        assertEquals(1, pending.size());
    }

    @Test
    public void logsExposeOnlyAllowedMetadataFields() {
        Fixture fixture = new Fixture(true);
        byte[] jpeg = validJpeg();

        fixture.client.verify(fixture.request(jpeg), result -> { });
        fixture.scheduler.advanceBy(800L);

        assertFalse(fixture.logs.isEmpty());
        for (LogRecord record : fixture.logs) {
            assertEquals("request-1", record.requestId);
            assertTrue(record.stage.matches(
                    "face-verifier=LOCAL_DEMO;stage=[A-Z_]+"));
            assertTrue(record.elapsedMillis >= 0L);
            assertEquals(jpeg.length, record.byteCount);
            String visible = record.requestId + record.stage
                    + record.elapsedMillis + record.byteCount;
            assertFalse(visible.contains("credential-secret"));
            assertFalse(visible.contains(TICKET));
            assertFalse(visible.contains("device-binding"));
            assertFalse(visible.contains("process-binding"));
            assertFalse(visible.contains(Arrays.toString(jpeg)));
        }
    }

    @Test
    public void firstLogFailureDoesNotPreventSuccessAndOwnedJpegCleanup() {
        Fixture fixture = new Fixture(true);
        CapturingDecoder decoder = new CapturingDecoder();
        ThrowFirstLogSink log = new ThrowFirstLogSink();
        fixture.client = new LocalPassFaceVerificationClient(
                fixture.environment, fixture.issuerCapability, fixture.clock,
                fixture.scheduler, decoder, log, () -> "credential-secret");
        List<FaceVerificationResult> results = new ArrayList<FaceVerificationResult>();

        fixture.client.verify(fixture.request(validJpeg()), results::add);
        fixture.scheduler.advanceBy(800L);

        assertEquals(1, results.size());
        assertEquals(FaceVerificationStatus.PASSED, results.get(0).status());
        assertArrayEquals(new byte[decoder.seen.length], decoder.seen);
        assertTrue(log.calls >= 3);
    }

    @Test
    public void cancelLogAndCallbackRuntimeFailuresRemainIsolatedAfterCleanup() {
        Fixture fixture = new Fixture(true);
        CapturingDecoder decoder = new CapturingDecoder();
        ThrowingCancelScheduler scheduler = new ThrowingCancelScheduler(fixture.clock);
        ThrowOnPassedLogSink log = new ThrowOnPassedLogSink();
        CountingThrowingCallback callback = new CountingThrowingCallback();
        fixture.client = new LocalPassFaceVerificationClient(
                fixture.environment, fixture.issuerCapability, fixture.clock,
                scheduler, decoder, log, () -> "credential-secret");

        fixture.client.verify(fixture.request(validJpeg()), callback);
        scheduler.runAtDelay();

        assertEquals(1, scheduler.cancelCalls);
        assertEquals(1, log.passedCalls);
        assertEquals(1, callback.calls);
        assertArrayEquals(new byte[decoder.seen.length], decoder.seen);
    }

    @Test
    public void disableFanoutContinuesAfterFirstPendingCallbackThrows() {
        Fixture fixture = new Fixture(true);
        CapturingDecoder firstDecoder = new CapturingDecoder();
        CapturingDecoder secondDecoder = new CapturingDecoder();
        CountingThrowingCallback firstCallback = new CountingThrowingCallback();
        List<FaceVerificationResult> secondResults = new ArrayList<FaceVerificationResult>();

        fixture.client = fixture.newClient(firstDecoder);
        fixture.client.verify(fixture.request(validJpeg()), firstCallback);
        fixture.client = fixture.newClient(secondDecoder);
        fixture.client.verify(fixture.request(validJpeg()), secondResults::add);
        fixture.environment.setEnabled(false);

        assertEquals(1, firstCallback.calls);
        assertEquals(1, secondResults.size());
        assertEquals(FaceVerificationStatus.CANCELLED, secondResults.get(0).status());
        assertArrayEquals(new byte[firstDecoder.seen.length], firstDecoder.seen);
        assertArrayEquals(new byte[secondDecoder.seen.length], secondDecoder.seen);
        assertTrue(fixture.scheduler.jobs.get(0).cancelled);
        assertTrue(fixture.scheduler.jobs.get(1).cancelled);
    }

    private static void assertRejected(byte[] jpeg, FaceJpegContract.Decoder decoder) {
        Fixture fixture = new Fixture(true);
        fixture.client = fixture.newClient(decoder);
        List<FaceVerificationResult> results = new ArrayList<FaceVerificationResult>();

        fixture.client.verify(fixture.request(jpeg), results::add);

        assertEquals(1, results.size());
        assertEquals(FaceVerificationStatus.NOT_PASSED, results.get(0).status());
        assertEquals(0, fixture.scheduler.jobs.size());
    }

    private static byte[] validJpeg() {
        return new byte[] {(byte) 0xff, (byte) 0xd8, 1, 2, (byte) 0xff, (byte) 0xd9};
    }

    private static final class Fixture {
        final FakeClock clock = new FakeClock();
        final FakeScheduler scheduler = new FakeScheduler(clock);
        final MemoryStateStore stateStore;
        final CountingTicketIds ticketIds = new CountingTicketIds();
        final FaceVerificationEnvironment.IssuerCapability issuerCapability =
                FaceVerificationEnvironment.newIssuerCapability();
        final FaceVerificationEnvironment environment;
        final List<LogRecord> logs = new ArrayList<LogRecord>();
        LocalPassFaceVerificationClient client;

        Fixture(boolean enabled) {
            stateStore = new MemoryStateStore(enabled);
            environment = new FaceVerificationEnvironment(
                    FaceVerificationSource.LOCAL_DEMO, true, stateStore, ticketIds,
                    issuerCapability);
            client = newClient(new FixedDecoder(640, 480));
        }

        LocalPassFaceVerificationClient newClient(FaceJpegContract.Decoder decoder) {
            return newClient(decoder, () -> "credential-secret");
        }

        LocalPassFaceVerificationClient newClient(FaceJpegContract.Decoder decoder,
                LocalPassFaceVerificationClient.CredentialGenerator credentials) {
            return new LocalPassFaceVerificationClient(environment, issuerCapability,
                    clock, scheduler,
                    decoder, (requestId, stage, elapsedMillis, byteCount) ->
                            logs.add(new LogRecord(requestId, stage, elapsedMillis, byteCount)),
                    credentials);
        }

        FaceVerificationRequest request(byte[] jpeg) {
            return new FaceVerificationRequest("request-1", jpeg, clock.epochMillis,
                    "device-binding", "process-binding");
        }
    }

    private static final class CountingTicketIds
            implements FaceVerificationEnvironment.TicketIdGenerator {
        int calls;

        @Override
        public String nextTicketId() {
            calls++;
            return TICKET;
        }
    }

    private static final class BlockingCredentialGenerator
            implements LocalPassFaceVerificationClient.CredentialGenerator {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public String nextCredential() {
            entered.countDown();
            try {
                if (!release.await(5L, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("credential generator was not released");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("credential generation interrupted", interrupted);
            }
            return "credential-secret";
        }
    }

    private static final class ThrowFirstLogSink
            implements LocalPassFaceVerificationClient.LogSink {
        int calls;

        @Override
        public void log(String requestId, String stage, long elapsedMillis, int byteCount) {
            calls++;
            if (calls == 1) {
                throw new IllegalStateException("first log failed");
            }
        }
    }

    private static final class ThrowOnPassedLogSink
            implements LocalPassFaceVerificationClient.LogSink {
        int passedCalls;

        @Override
        public void log(String requestId, String stage, long elapsedMillis, int byteCount) {
            if (stage.endsWith("PASSED")) {
                passedCalls++;
                throw new IllegalStateException("terminal log failed");
            }
        }
    }

    private static final class CountingThrowingCallback
            implements FaceVerificationClient.Callback {
        int calls;

        @Override
        public void onCompleted(FaceVerificationResult result) {
            calls++;
            throw new IllegalStateException("callback failed");
        }
    }

    private static final class ThrowingCancelScheduler
            implements LocalPassFaceVerificationClient.Scheduler {
        final FakeClock clock;
        Runnable task;
        long delayMillis;
        int cancelCalls;

        ThrowingCancelScheduler(FakeClock clock) {
            this.clock = clock;
        }

        @Override
        public LocalPassFaceVerificationClient.ScheduledTask schedule(
                Runnable task, long delayMillis) {
            this.task = task;
            this.delayMillis = delayMillis;
            return () -> {
                cancelCalls++;
                throw new IllegalStateException("cancel failed");
            };
        }

        void runAtDelay() {
            clock.elapsedMillis += delayMillis;
            clock.epochMillis += delayMillis;
            task.run();
        }
    }

    private static final class ReentrantCancelScheduler
            implements LocalPassFaceVerificationClient.Scheduler {
        volatile boolean cancelTimedOut;

        @Override
        public LocalPassFaceVerificationClient.ScheduledTask schedule(
                Runnable task, long delayMillis) {
            task.run();
            return () -> {
                CountDownLatch workerFinished = new CountDownLatch(1);
                Thread worker = new Thread(() -> {
                    try {
                        task.run();
                    } finally {
                        workerFinished.countDown();
                    }
                });
                worker.start();
                try {
                    cancelTimedOut = !workerFinished.await(2L, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("cancel wait interrupted", interrupted);
                }
            };
        }
    }

    private static final class MemoryStateStore
            implements FaceVerificationEnvironment.EnabledStateStore {
        private boolean enabled;

        MemoryStateStore(boolean enabled) {
            this.enabled = enabled;
        }

        @Override
        public boolean load() {
            return enabled;
        }

        @Override
        public void save(boolean enabled) {
            this.enabled = enabled;
        }
    }

    private static final class FakeClock implements LocalPassFaceVerificationClient.Clock {
        long epochMillis = 1_000L;
        long elapsedMillis;

        @Override
        public long epochMillis() {
            return epochMillis;
        }

        @Override
        public long elapsedMillis() {
            return elapsedMillis;
        }
    }

    private static final class FakeScheduler
            implements LocalPassFaceVerificationClient.Scheduler {
        final FakeClock clock;
        final List<Job> jobs = new ArrayList<Job>();

        FakeScheduler(FakeClock clock) {
            this.clock = clock;
        }

        @Override
        public LocalPassFaceVerificationClient.ScheduledTask schedule(
                Runnable task, long delayMillis) {
            Job job = new Job(task, clock.elapsedMillis + delayMillis);
            jobs.add(job);
            return job;
        }

        void advanceBy(long millis) {
            clock.elapsedMillis += millis;
            clock.epochMillis += millis;
            for (Job job : new ArrayList<Job>(jobs)) {
                if (!job.cancelled && !job.ran && job.dueMillis <= clock.elapsedMillis) {
                    job.ran = true;
                    job.task.run();
                }
            }
        }

        private static final class Job implements LocalPassFaceVerificationClient.ScheduledTask {
            final Runnable task;
            final long dueMillis;
            boolean cancelled;
            boolean ran;

            Job(Runnable task, long dueMillis) {
                this.task = task;
                this.dueMillis = dueMillis;
            }

            @Override
            public void cancel() {
                cancelled = true;
            }
        }
    }

    private static class FixedDecoder implements FaceJpegContract.Decoder {
        private final FaceJpegContract.Dimensions dimensions;

        FixedDecoder(int width, int height) {
            dimensions = new FaceJpegContract.Dimensions(width, height);
        }

        FixedDecoder(FaceJpegContract.Dimensions dimensions) {
            this.dimensions = dimensions;
        }

        @Override
        public FaceJpegContract.Dimensions decode(byte[] jpeg) {
            return dimensions;
        }
    }

    private static final class CapturingDecoder extends FixedDecoder {
        byte[] seen;

        CapturingDecoder() {
            super(640, 480);
        }

        @Override
        public FaceJpegContract.Dimensions decode(byte[] jpeg) {
            seen = jpeg;
            return super.decode(jpeg);
        }
    }

    private static final class LogRecord {
        final String requestId;
        final String stage;
        final long elapsedMillis;
        final int byteCount;

        LogRecord(String requestId, String stage, long elapsedMillis, int byteCount) {
            this.requestId = requestId;
            this.stage = stage;
            this.elapsedMillis = elapsedMillis;
            this.byteCount = byteCount;
        }
    }
}
