package com.codex.lockertest.bootstrap;

import com.codex.lockertest.server.ApiResult;
import com.codex.lockertest.server.CallToken;
import com.codex.lockertest.server.ServerFailure;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class DeviceBootstrapCoordinatorTest {
    @Test
    public void publishesAllThreeResultsAtomicallyAfterOneSuccessfulChain() {
        Fixture fixture = fixture();

        fixture.coordinator.start();
        fixture.scheduler.runNext(FakeScheduler.WORKER);

        assertEquals(1, fixture.service.checkCalls);
        assertEquals(1, fixture.service.baseCalls);
        assertEquals(1, fixture.service.basicCalls);
        BootstrapSnapshot ready = fixture.coordinator.snapshot();
        assertEquals(BootstrapSnapshot.Phase.READY_READ_ONLY, ready.phase());
        assertEquals(BootstrapSnapshot.FailureReason.NONE, ready.failureReason());
        assertEquals("MERCHANT", ready.registration().merchantCode());
        assertEquals("VENUE", ready.baseSetting().venueName());
        assertEquals(32, ready.basicData().totalLockers());
        for (BootstrapSnapshot snapshot : fixture.published) {
            if (snapshot.phase() == BootstrapSnapshot.Phase.READY_READ_ONLY) {
                assertNotNull(snapshot.registration());
                assertNotNull(snapshot.baseSetting());
                assertNotNull(snapshot.basicData());
            } else {
                assertNull(snapshot.registration());
                assertNull(snapshot.baseSetting());
                assertNull(snapshot.basicData());
            }
        }
    }

    @Test
    public void retriesOnlyCheckAfterFirstNetworkFailure() {
        Fixture fixture = fixture();
        fixture.service.checkResults.addFirst(failure(ServerFailure.Kind.NETWORK));

        fixture.coordinator.start();
        fixture.scheduler.runNext(FakeScheduler.WORKER);
        assertRetryQueued(fixture, BootstrapSnapshot.Endpoint.CHECK_DEVICE);
        fixture.scheduler.runNext(DeviceBootstrapCoordinator.RETRY_DELAY_MILLIS);
        fixture.scheduler.runNext(FakeScheduler.WORKER);

        assertReadyWithCalls(fixture, 2, 1, 1);
    }

    @Test
    public void retriesOnlyBaseAfterFirstTimeout() {
        Fixture fixture = fixture();
        fixture.service.baseResults.addFirst(failure(ServerFailure.Kind.TIMEOUT));

        fixture.coordinator.start();
        fixture.scheduler.runNext(FakeScheduler.WORKER);
        assertRetryQueued(fixture, BootstrapSnapshot.Endpoint.BASE_SETTING);
        fixture.scheduler.runNext(DeviceBootstrapCoordinator.RETRY_DELAY_MILLIS);
        fixture.scheduler.runNext(FakeScheduler.WORKER);

        assertReadyWithCalls(fixture, 1, 2, 1);
    }

    @Test
    public void retriesOnlyBasicDataAfterFirstNetworkFailure() {
        Fixture fixture = fixture();
        fixture.service.basicResults.addFirst(failure(ServerFailure.Kind.NETWORK));

        fixture.coordinator.start();
        fixture.scheduler.runNext(FakeScheduler.WORKER);
        assertRetryQueued(fixture, BootstrapSnapshot.Endpoint.BASIC_DATA);
        fixture.scheduler.runNext(DeviceBootstrapCoordinator.RETRY_DELAY_MILLIS);
        fixture.scheduler.runNext(FakeScheduler.WORKER);

        assertReadyWithCalls(fixture, 1, 1, 2);
    }

    @Test
    public void retryTimerOnlyQueuesWorkAndNeverRunsTheBlockingCallItself() {
        Fixture fixture = fixture();
        fixture.service.checkResults.addFirst(failure(ServerFailure.Kind.NETWORK));

        fixture.coordinator.start();
        fixture.scheduler.runNext(FakeScheduler.WORKER);
        fixture.scheduler.runNext(DeviceBootstrapCoordinator.RETRY_DELAY_MILLIS);

        assertEquals(1, fixture.service.checkCalls);
        assertEquals(1, fixture.scheduler.activeCount(FakeScheduler.WORKER));
        fixture.scheduler.runNext(FakeScheduler.WORKER);
        assertReadyWithCalls(fixture, 2, 1, 1);
    }

    @Test
    public void rejectedRetryWorkerFailsClosedWithoutCallingTheServiceAgain() {
        Fixture fixture = fixture();
        fixture.service.checkResults.addFirst(failure(ServerFailure.Kind.NETWORK));

        fixture.coordinator.start();
        fixture.scheduler.runNext(FakeScheduler.WORKER);
        fixture.scheduler.rejectNextSubmit = true;
        fixture.scheduler.runNext(DeviceBootstrapCoordinator.RETRY_DELAY_MILLIS);

        assertEquals(1, fixture.service.checkCalls);
        assertEquals(0, fixture.scheduler.activeCount(FakeScheduler.WORKER));
        assertBlocked(fixture, BootstrapSnapshot.FailureReason.CONFIGURATION,
                BootstrapSnapshot.Endpoint.CHECK_DEVICE);
    }

    @Test
    public void secondRetryableFailureBlocksWithoutRestartingEarlierEndpoints() {
        Fixture fixture = fixture();
        fixture.service.baseResults.clear();
        fixture.service.baseResults.add(failure(ServerFailure.Kind.NETWORK));
        fixture.service.baseResults.add(failure(ServerFailure.Kind.TIMEOUT));

        fixture.coordinator.start();
        fixture.scheduler.runNext(FakeScheduler.WORKER);
        fixture.scheduler.runNext(DeviceBootstrapCoordinator.RETRY_DELAY_MILLIS);
        fixture.scheduler.runNext(FakeScheduler.WORKER);

        assertEquals(1, fixture.service.checkCalls);
        assertEquals(2, fixture.service.baseCalls);
        assertEquals(0, fixture.service.basicCalls);
        assertBlocked(fixture, BootstrapSnapshot.FailureReason.TIMEOUT,
                BootstrapSnapshot.Endpoint.BASE_SETTING);
    }

    @Test
    public void tlsRedirectContractAndInvalidJsonNeverRetry() {
        ServerFailure.Kind[] failures = {
                ServerFailure.Kind.CONFIGURATION,
                ServerFailure.Kind.TLS,
                ServerFailure.Kind.REDIRECT,
                ServerFailure.Kind.CONTRACT,
                ServerFailure.Kind.INVALID_JSON
        };
        BootstrapSnapshot.FailureReason[] reasons = {
                BootstrapSnapshot.FailureReason.KEY_MISSING,
                BootstrapSnapshot.FailureReason.TLS,
                BootstrapSnapshot.FailureReason.REDIRECT,
                BootstrapSnapshot.FailureReason.CONTRACT,
                BootstrapSnapshot.FailureReason.INVALID_RESPONSE
        };
        for (int index = 0; index < failures.length; index++) {
            Fixture fixture = fixture();
            fixture.service.checkResults.addFirst(failure(failures[index]));

            fixture.coordinator.start();
            fixture.scheduler.runNext(FakeScheduler.WORKER);

            assertEquals(1, fixture.service.checkCalls);
            assertEquals(0, fixture.scheduler.activeCount(
                    DeviceBootstrapCoordinator.RETRY_DELAY_MILLIS));
            assertBlocked(fixture, reasons[index],
                    BootstrapSnapshot.Endpoint.CHECK_DEVICE);
        }
    }

    @Test
    public void cancellationInsideSerialReadPreventsEveryApiCall() {
        Fixture fixture = fixture();
        fixture.serialProvider.afterReadStarted = fixture.coordinator::cancel;

        fixture.coordinator.start();
        fixture.scheduler.runNext(FakeScheduler.WORKER);

        assertEquals(0, fixture.service.totalCalls());
        assertEquals(BootstrapSnapshot.Phase.CANCELLED,
                fixture.coordinator.snapshot().phase());
    }

    @Test
    public void cancellationAfterSerialReturnsButBeforeCheckPreventsEveryApiCall() {
        Fixture fixture = fixture();
        fixture.serialProvider.beforeReturn = fixture.coordinator::cancel;

        fixture.coordinator.start();
        fixture.scheduler.runNext(FakeScheduler.WORKER);

        assertEquals(0, fixture.service.totalCalls());
        assertEquals(BootstrapSnapshot.Phase.CANCELLED,
                fixture.coordinator.snapshot().phase());
    }

    @Test
    public void cancellationInCheckCallbackPreventsBaseAndBasicData() {
        Fixture fixture = fixture();
        fixture.service.afterCheck = fixture.coordinator::cancel;

        fixture.coordinator.start();
        fixture.scheduler.runNext(FakeScheduler.WORKER);

        assertEquals(1, fixture.service.checkCalls);
        assertEquals(0, fixture.service.baseCalls);
        assertEquals(0, fixture.service.basicCalls);
        assertEquals(BootstrapSnapshot.Phase.CANCELLED,
                fixture.coordinator.snapshot().phase());
    }

    @Test
    public void cancellationInBaseCallbackPreventsBasicData() {
        Fixture fixture = fixture();
        fixture.service.afterBase = fixture.coordinator::cancel;

        fixture.coordinator.start();
        fixture.scheduler.runNext(FakeScheduler.WORKER);

        assertEquals(1, fixture.service.checkCalls);
        assertEquals(1, fixture.service.baseCalls);
        assertEquals(0, fixture.service.basicCalls);
        assertEquals(BootstrapSnapshot.Phase.CANCELLED,
                fixture.coordinator.snapshot().phase());
    }

    @Test
    public void cancellingQueuedRetryCancelsItsFutureOnceAndLateRunDoesNothing() {
        Fixture fixture = fixture();
        fixture.service.checkResults.addFirst(failure(ServerFailure.Kind.NETWORK));

        fixture.coordinator.start();
        fixture.scheduler.runNext(FakeScheduler.WORKER);
        FakeScheduler.Job retry = fixture.scheduler.onlyActive(
                DeviceBootstrapCoordinator.RETRY_DELAY_MILLIS);

        fixture.coordinator.cancel();
        assertTrue(retry.isCancelled());
        assertEquals(1, retry.cancelCalls);
        retry.runEvenIfCancelled();

        assertEquals(1, fixture.service.checkCalls);
        assertEquals(0, fixture.service.baseCalls);
        assertEquals(0, fixture.service.basicCalls);
        assertEquals(1, retry.cancelCalls);
    }

    @Test
    public void supersedingStartInvalidatesLateWorkerAndKeepsOnlyNewGeneration() {
        Fixture fixture = fixture();
        long first = fixture.coordinator.start();
        FakeScheduler.Job oldWorker = fixture.scheduler.onlyActive(FakeScheduler.WORKER);
        long second = fixture.coordinator.start();
        assertTrue(second > first);
        assertEquals(1, oldWorker.cancelCalls);

        oldWorker.runEvenIfCancelled();
        fixture.scheduler.runNext(FakeScheduler.WORKER);

        assertReadyWithCalls(fixture, 1, 1, 1);
        assertEquals(second, fixture.coordinator.snapshot().generation());
    }

    @Test
    public void unavailableSerialAndSchedulerFailureBlockWithoutApiCalls() {
        Fixture unavailable = fixture();
        unavailable.serialProvider.serial = DeviceSerial.unavailable("********");
        unavailable.coordinator.start();
        unavailable.scheduler.runNext(FakeScheduler.WORKER);
        assertBlocked(unavailable, BootstrapSnapshot.FailureReason.SERIAL_UNAVAILABLE,
                BootstrapSnapshot.Endpoint.NONE);
        assertEquals(0, unavailable.service.totalCalls());

        Fixture rejected = fixture();
        rejected.scheduler.rejectNextSubmit = true;
        rejected.coordinator.start();
        assertBlocked(rejected, BootstrapSnapshot.FailureReason.CONFIGURATION,
                BootstrapSnapshot.Endpoint.NONE);
        assertEquals(0, rejected.service.totalCalls());
    }

    @Test
    public void closeInvalidatesAndCancelsAllOwnedWorkExactlyOnce() {
        Fixture fixture = fixture();
        fixture.coordinator.start();
        FakeScheduler.Job worker = fixture.scheduler.onlyActive(FakeScheduler.WORKER);

        fixture.coordinator.close();
        fixture.coordinator.close();

        assertEquals(1, worker.cancelCalls);
        assertEquals(BootstrapSnapshot.Phase.CLOSED,
                fixture.coordinator.snapshot().phase());
        assertFalse(fixture.coordinator.start() > fixture.coordinator.snapshot().generation());
    }

    @Test
    public void everyCompletedRequestCancelsItsWatchdogExactlyOnceAndLateJobsAreHarmless() {
        Fixture fixture = fixture();
        fixture.coordinator.start();
        fixture.scheduler.runNext(FakeScheduler.WORKER);
        BootstrapSnapshot ready = fixture.coordinator.snapshot();

        int watchdogs = 0;
        for (FakeScheduler.Job job : fixture.scheduler.jobs) {
            if (job.delayMillis == DeviceBootstrapCoordinator.WATCHDOG_MILLIS) {
                watchdogs++;
                assertEquals(1, job.cancelCalls);
                job.runEvenIfCancelled();
            }
        }

        assertEquals(3, watchdogs);
        assertTrue(fixture.coordinator.snapshot() == ready);
        assertEquals(3, fixture.service.totalCalls());
    }

    private static Fixture fixture() {
        FakeScheduler scheduler = new FakeScheduler();
        FakeService service = new FakeService();
        FakeSerialProvider provider = new FakeSerialProvider();
        List<BootstrapSnapshot> published = new ArrayList<>();
        DeviceBootstrapCoordinator coordinator = new DeviceBootstrapCoordinator(
                provider, service, scheduler, published::add);
        return new Fixture(coordinator, provider, service, scheduler, published);
    }

    private static void assertRetryQueued(Fixture fixture,
            BootstrapSnapshot.Endpoint endpoint) {
        assertEquals(BootstrapSnapshot.Phase.RETRY_WAIT,
                fixture.coordinator.snapshot().phase());
        assertEquals(endpoint, fixture.coordinator.snapshot().endpoint());
        assertEquals(1, fixture.scheduler.activeCount(
                DeviceBootstrapCoordinator.RETRY_DELAY_MILLIS));
    }

    private static void assertReadyWithCalls(
            Fixture fixture, int check, int base, int basic) {
        assertEquals(check, fixture.service.checkCalls);
        assertEquals(base, fixture.service.baseCalls);
        assertEquals(basic, fixture.service.basicCalls);
        assertEquals(BootstrapSnapshot.Phase.READY_READ_ONLY,
                fixture.coordinator.snapshot().phase());
    }

    private static void assertBlocked(Fixture fixture,
            BootstrapSnapshot.FailureReason reason,
            BootstrapSnapshot.Endpoint endpoint) {
        assertEquals(BootstrapSnapshot.Phase.BLOCKED,
                fixture.coordinator.snapshot().phase());
        assertEquals(reason, fixture.coordinator.snapshot().failureReason());
        assertEquals(endpoint, fixture.coordinator.snapshot().endpoint());
    }

    private static <T> ApiResult<T> failure(ServerFailure.Kind kind) {
        return ApiResult.failure(ServerFailure.of(kind));
    }

    private static final class Fixture {
        final DeviceBootstrapCoordinator coordinator;
        final FakeSerialProvider serialProvider;
        final FakeService service;
        final FakeScheduler scheduler;
        final List<BootstrapSnapshot> published;

        Fixture(DeviceBootstrapCoordinator coordinator,
                FakeSerialProvider serialProvider,
                FakeService service,
                FakeScheduler scheduler,
                List<BootstrapSnapshot> published) {
            this.coordinator = coordinator;
            this.serialProvider = serialProvider;
            this.service = service;
            this.scheduler = scheduler;
            this.published = published;
        }
    }

    private static final class FakeSerialProvider implements DeviceSerialProvider {
        DeviceSerial serial = DeviceSerial.available(
                "RK3288-TEST-001", "***********-001");
        Runnable afterReadStarted;
        Runnable beforeReturn;

        @Override
        public DeviceSerial read() {
            run(afterReadStarted);
            run(beforeReturn);
            return serial;
        }
    }

    private static final class FakeService implements BootstrapService {
        final Deque<ApiResult<DeviceRegistration>> checkResults = new ArrayDeque<>();
        final Deque<ApiResult<BaseSettingSnapshot>> baseResults = new ArrayDeque<>();
        final Deque<ApiResult<BasicDataSnapshot>> basicResults = new ArrayDeque<>();
        int checkCalls;
        int baseCalls;
        int basicCalls;
        Runnable afterCheck;
        Runnable afterBase;

        FakeService() {
            checkResults.add(ApiResult.success(registration()));
            baseResults.add(ApiResult.success(base()));
            basicResults.add(ApiResult.success(basic()));
        }

        @Override
        public ApiResult<DeviceRegistration> checkDevice(
                String deviceSerial, CallToken token) {
            checkCalls++;
            ApiResult<DeviceRegistration> result = removeOrLast(
                    checkResults, ApiResult.success(registration()));
            run(afterCheck);
            return result;
        }

        @Override
        public ApiResult<BaseSettingSnapshot> baseSetting(
                DeviceRegistration registration, CallToken token) {
            baseCalls++;
            ApiResult<BaseSettingSnapshot> result = removeOrLast(
                    baseResults, ApiResult.success(base()));
            run(afterBase);
            return result;
        }

        @Override
        public ApiResult<BasicDataSnapshot> basicData(
                DeviceRegistration registration, CallToken token) {
            basicCalls++;
            return removeOrLast(basicResults, ApiResult.success(basic()));
        }

        int totalCalls() {
            return checkCalls + baseCalls + basicCalls;
        }

        private static <T> ApiResult<T> removeOrLast(
                Deque<ApiResult<T>> values, ApiResult<T> fallback) {
            return values.isEmpty() ? fallback : values.removeFirst();
        }
    }

    private static final class FakeScheduler implements BootstrapScheduler {
        static final long WORKER = -1L;
        final List<Job> jobs = new ArrayList<>();
        boolean rejectNextSubmit;

        @Override
        public Future<?> submit(Runnable task) {
            if (rejectNextSubmit) {
                rejectNextSubmit = false;
                throw new IllegalStateException("rejected");
            }
            return add(task, WORKER);
        }

        @Override
        public Future<?> schedule(Runnable task, long delayMillis) {
            return add(task, delayMillis);
        }

        Job add(Runnable task, long delayMillis) {
            Job job = new Job(task, delayMillis);
            jobs.add(job);
            return job;
        }

        void runNext(long delayMillis) {
            onlyActive(delayMillis).run();
        }

        int activeCount(long delayMillis) {
            int count = 0;
            for (Job job : jobs) {
                if (job.delayMillis == delayMillis && !job.cancelled && !job.done) count++;
            }
            return count;
        }

        Job onlyActive(long delayMillis) {
            Job result = null;
            for (Job job : jobs) {
                if (job.delayMillis == delayMillis && !job.cancelled && !job.done) {
                    if (result != null) throw new AssertionError("Multiple active jobs");
                    result = job;
                }
            }
            if (result == null) throw new AssertionError("Missing active job " + delayMillis);
            return result;
        }

        static final class Job implements Future<Object> {
            final Runnable task;
            final long delayMillis;
            boolean cancelled;
            boolean done;
            int cancelCalls;

            Job(Runnable task, long delayMillis) {
                this.task = task;
                this.delayMillis = delayMillis;
            }

            void run() {
                if (cancelled || done) return;
                done = true;
                task.run();
            }

            void runEvenIfCancelled() {
                if (done) return;
                done = true;
                task.run();
            }

            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                cancelCalls++;
                if (done || cancelled) return false;
                cancelled = true;
                return true;
            }

            @Override public boolean isCancelled() { return cancelled; }
            @Override public boolean isDone() { return done || cancelled; }
            @Override public Object get() { throw new UnsupportedOperationException(); }
            @Override public Object get(long timeout, TimeUnit unit) {
                throw new UnsupportedOperationException();
            }
        }
    }

    private static DeviceRegistration registration() {
        return new DeviceRegistration("MERCHANT", "DEVICE");
    }

    private static BaseSettingSnapshot base() {
        return new BaseSettingSnapshot(
                "VENUE", "1", java.util.Collections.singletonList("1"),
                "", "", "", "COMPANY", "", 0,
                java.util.Collections.emptyList(), "", "",
                java.util.Collections.singletonList(
                        new BaseSettingSnapshot.Area(1, "A")), "1");
    }

    private static BasicDataSnapshot basic() {
        return new BasicDataSnapshot(32, 12, "1");
    }

    private static void run(Runnable action) {
        if (action != null) action.run();
    }
}
