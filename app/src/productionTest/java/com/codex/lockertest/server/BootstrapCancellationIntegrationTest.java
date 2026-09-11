package com.codex.lockertest.server;

import com.codex.lockertest.bootstrap.BootstrapScheduler;
import com.codex.lockertest.bootstrap.BootstrapSnapshot;
import com.codex.lockertest.bootstrap.DeviceBootstrapCoordinator;
import com.codex.lockertest.bootstrap.DeviceSerial;
import com.codex.lockertest.bootstrap.DeviceSerialProvider;

import org.junit.Test;

import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class BootstrapCancellationIntegrationTest {
    private static final String TEST_KEY = "TEST_ONLY_SYS_CODE";
    private static final String CHECK_SUCCESS = "{\"code\":200,\"message\":\"ok\","
            + "\"data\":{\"merchant_code\":\"M\",\"device_no\":\"D\"}}";

    @Test
    public void lifecycleCancellationDisconnectsBlockingTransportAndRejectsLateSuccess()
            throws Exception {
        BlockingTransport transport = new BlockingTransport(CHECK_SUCCESS, true);
        ProductionBootstrapService service = new ProductionBootstrapService(
                transport,
                () -> ProtocolTimestamp.fromEpochMillis(
                        1788408000000L, java.util.TimeZone.getTimeZone("Asia/Shanghai")),
                TEST_KEY.toCharArray());
        RealScheduler scheduler = new RealScheduler(false);
        DeviceBootstrapCoordinator coordinator = new DeviceBootstrapCoordinator(
                serialProvider(), service, scheduler, ignored -> { });
        try {
            coordinator.start();
            assertTrue(transport.entered.await(2, TimeUnit.SECONDS));

            coordinator.cancel();
            assertTrue(transport.completed.await(2, TimeUnit.SECONDS));

            assertEquals(BootstrapSnapshot.Phase.CANCELLED,
                    coordinator.snapshot().phase());
            assertEquals(1, transport.calls.get());
            assertEquals(1, transport.disconnectHooks.get());
            assertEquals(1, transport.unregisterCalls.get());
            assertFalse(coordinator.snapshot().phase()
                    == BootstrapSnapshot.Phase.READY_READ_ONLY);
        } finally {
            coordinator.close();
            service.close();
            scheduler.close();
        }
    }

    @Test
    public void watchdogTimeoutWinsAndBlocksAfterOneEndpointLocalRetry()
            throws Exception {
        BlockingTransport transport = new BlockingTransport(CHECK_SUCCESS, false);
        ProductionBootstrapService service = new ProductionBootstrapService(
                transport,
                () -> ProtocolTimestamp.fromEpochMillis(
                        1788408000000L, java.util.TimeZone.getTimeZone("Asia/Shanghai")),
                TEST_KEY.toCharArray());
        RealScheduler scheduler = new RealScheduler(true);
        DeviceBootstrapCoordinator coordinator = new DeviceBootstrapCoordinator(
                serialProvider(), service, scheduler, ignored -> { });
        try {
            coordinator.start();
            assertTrue(waitForPhase(coordinator, BootstrapSnapshot.Phase.BLOCKED, 3000L));

            assertEquals(BootstrapSnapshot.FailureReason.TIMEOUT,
                    coordinator.snapshot().failureReason());
            assertEquals(BootstrapSnapshot.Endpoint.CHECK_DEVICE,
                    coordinator.snapshot().endpoint());
            assertEquals(2, transport.calls.get());
            assertEquals(2, transport.disconnectHooks.get());
            assertEquals(2, transport.unregisterCalls.get());
        } finally {
            coordinator.close();
            service.close();
            scheduler.close();
        }
    }

    private static boolean waitForPhase(DeviceBootstrapCoordinator coordinator,
            BootstrapSnapshot.Phase phase, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (coordinator.snapshot().phase() == phase) return true;
            Thread.sleep(5L);
        }
        return coordinator.snapshot().phase() == phase;
    }

    private static DeviceSerialProvider serialProvider() throws Exception {
        Method method = DeviceSerial.class.getDeclaredMethod(
                "available", String.class, String.class);
        method.setAccessible(true);
        DeviceSerial serial = (DeviceSerial) method.invoke(
                null, "RK3288-TEST-001", "***********-001");
        return () -> serial;
    }

    private static final class BlockingTransport implements ServerTransport {
        final String response;
        final boolean returnLateSuccess;
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch completed = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger disconnectHooks = new AtomicInteger();
        final AtomicInteger unregisterCalls = new AtomicInteger();

        BlockingTransport(String response, boolean returnLateSuccess) {
            this.response = response;
            this.returnLateSuccess = returnLateSuccess;
        }

        @Override
        public ApiResult<TransportResponse> execute(TransportRequest request) {
            calls.incrementAndGet();
            CountDownLatch released = new CountDownLatch(1);
            CallToken.Registration registration = request.token().onCancel(() -> {
                disconnectHooks.incrementAndGet();
                released.countDown();
            });
            entered.countDown();
            try {
                released.await(2, TimeUnit.SECONDS);
                if (!returnLateSuccess && request.token().isCancelled()) {
                    ServerFailure.Kind kind = request.token().reason()
                            == CallToken.Reason.TIMEOUT
                            ? ServerFailure.Kind.TIMEOUT
                            : ServerFailure.Kind.CANCELLED;
                    return ApiResult.failure(ServerFailure.of(kind));
                }
                return ApiResult.success(new TransportResponse(200, response));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return ApiResult.failure(ServerFailure.of(ServerFailure.Kind.CANCELLED));
            } finally {
                registration.unregister();
                unregisterCalls.incrementAndGet();
                completed.countDown();
            }
        }
    }

    private static final class RealScheduler implements BootstrapScheduler, AutoCloseable {
        private final ScheduledThreadPoolExecutor executor =
                new ScheduledThreadPoolExecutor(2);
        private final boolean accelerateDelays;

        RealScheduler(boolean accelerateDelays) {
            this.accelerateDelays = accelerateDelays;
            executor.setRemoveOnCancelPolicy(true);
        }

        @Override
        public Future<?> submit(Runnable task) {
            return executor.submit(task);
        }

        @Override
        public Future<?> schedule(Runnable task, long delayMillis) {
            long actual = accelerateDelays
                    ? (delayMillis == DeviceBootstrapCoordinator.WATCHDOG_MILLIS
                            ? 75L : 1L)
                    : delayMillis;
            return executor.schedule(task, actual, TimeUnit.MILLISECONDS);
        }

        @Override
        public void close() {
            executor.shutdownNow();
        }
    }
}
