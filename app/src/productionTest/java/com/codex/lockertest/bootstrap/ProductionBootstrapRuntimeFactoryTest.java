package com.codex.lockertest.bootstrap;

import com.codex.lockertest.server.ApiResult;
import com.codex.lockertest.server.CallToken;
import com.codex.lockertest.ui.TerminalReadiness;

import org.junit.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class ProductionBootstrapRuntimeFactoryTest {
    @Test
    public void actualProductionEntryIsNoArgAndStartsInConnectingReadOnlyState() {
        BootstrapRuntime runtime = ProductionBootstrapRuntimeFactory.create();
        assertEquals(TerminalReadiness.State.SERVER_CONNECTING,
                runtime.readinessSource().current().state());
        assertFalse(runtime.readinessSource().current().customerActionsEnabled());
        assertEquals(BootstrapSnapshot.Phase.IDLE, runtime.snapshot().phase());
        runtime.close();

        Method productionEntry = null;
        for (Method method : ProductionBootstrapRuntimeFactory.class.getDeclaredMethods()) {
            if (method.getName().equals("create")) productionEntry = method;
        }
        assertNotNull(productionEntry);
        assertTrue(Modifier.isPublic(productionEntry.getModifiers()));
        assertTrue(Modifier.isStatic(productionEntry.getModifiers()));
        assertEquals(0, productionEntry.getParameterTypes().length);
    }

    @Test
    public void liveEntryCanCloseBeforeStartWithoutOpeningTheNetwork() {
        BootstrapRuntime runtime = ProductionBootstrapRuntimeFactory.create();

        runtime.close();

        assertEquals(TerminalReadiness.State.BOOTSTRAP_STOPPED,
                runtime.readinessSource().current().state());
        assertEquals(BootstrapSnapshot.Phase.CLOSED, runtime.snapshot().phase());
        assertFalse(runtime.readinessSource().current().customerActionsEnabled());
    }

    @Test
    public void closingLiveEntryStopsItsWorkerAndZeroesItsOwnedCredential()
            throws Exception {
        BootstrapRuntime runtime = ProductionBootstrapRuntimeFactory.create();
        Field resourcesField = ProductionBootstrapRuntime.class
                .getDeclaredField("ownedResources");
        resourcesField.setAccessible(true);
        AutoCloseable[] resources = (AutoCloseable[]) resourcesField.get(runtime);
        assertEquals(2, resources.length);

        char[] ownedCredential = null;
        ExecutorService workerExecutor = null;
        ScheduledThreadPoolExecutor timerExecutor = null;
        for (AutoCloseable resource : resources) {
            if (resource.getClass().getName().endsWith("ProductionBootstrapService")) {
                Field keyField = resource.getClass().getDeclaredField("sysCode");
                keyField.setAccessible(true);
                ownedCredential = (char[]) keyField.get(resource);
            } else if (resource instanceof ProductionBootstrapScheduler) {
                Field workerField = ProductionBootstrapScheduler.class
                        .getDeclaredField("workerExecutor");
                workerField.setAccessible(true);
                workerExecutor = (ExecutorService) workerField.get(resource);
                Field timerField = ProductionBootstrapScheduler.class
                        .getDeclaredField("timerExecutor");
                timerField.setAccessible(true);
                timerExecutor = (ScheduledThreadPoolExecutor) timerField.get(resource);
            }
        }
        assertNotNull(ownedCredential);
        assertNotNull(workerExecutor);
        assertNotNull(timerExecutor);
        assertFalse(allZero(ownedCredential));
        assertFalse(workerExecutor.isShutdown());
        assertFalse(timerExecutor.isShutdown());

        runtime.close();

        assertTrue(allZero(ownedCredential));
        assertTrue(workerExecutor.isShutdown());
        assertTrue(timerExecutor.isShutdown());
        Arrays.fill(ownedCredential, '\0');
    }

    @Test
    public void pureJavaHarnessRunsACompleteFakeChainButRemainsReadOnly() {
        FakeScheduler scheduler = new FakeScheduler();
        BootstrapRuntime runtime = ProductionBootstrapRuntimeFactory.createForTest(
                new PassingService(), serialProvider(), scheduler);
        List<BootstrapSnapshot> callbacks = new ArrayList<>();

        runtime.start(callbacks::add);
        scheduler.runWorker();

        assertEquals(BootstrapSnapshot.Phase.READY_READ_ONLY,
                runtime.snapshot().phase());
        assertEquals(TerminalReadiness.State.READY_READ_ONLY,
                runtime.readinessSource().current().state());
        assertFalse(runtime.readinessSource().current().customerActionsEnabled());
        assertEquals("VENUE", runtime.snapshot().baseSetting().venueName());
        assertEquals(32, runtime.snapshot().basicData().totalLockers());
        assertTrue(callbacks.size() >= 5);
    }

    @Test
    public void closeIsIdempotentAndPublishesClosedOnlyOnce() {
        FakeScheduler scheduler = new FakeScheduler();
        BootstrapRuntime runtime = ProductionBootstrapRuntimeFactory.createForTest(
                new PassingService(), serialProvider(), scheduler);
        List<BootstrapSnapshot> callbacks = new ArrayList<>();
        runtime.start(callbacks::add);

        runtime.close();
        runtime.close();

        int closed = 0;
        for (BootstrapSnapshot snapshot : callbacks) {
            if (snapshot.phase() == BootstrapSnapshot.Phase.CLOSED) closed++;
        }
        assertEquals(1, closed);
        assertEquals(1, scheduler.worker.cancelCalls);
    }

    @Test
    public void harnessRejectsMissingDependenciesAndMapsProviderFailureClosed() {
        FakeScheduler scheduler = new FakeScheduler();
        expectIllegalArgument(() -> ProductionBootstrapRuntimeFactory.createForTest(
                null, serialProvider(), scheduler));
        expectIllegalArgument(() -> ProductionBootstrapRuntimeFactory.createForTest(
                new PassingService(), null, scheduler));
        expectIllegalArgument(() -> ProductionBootstrapRuntimeFactory.createForTest(
                new PassingService(), serialProvider(), null));

        BootstrapRuntime runtime = ProductionBootstrapRuntimeFactory.createForTest(
                new PassingService(), () -> { throw new IllegalStateException("hardware"); },
                scheduler);
        runtime.start(ignored -> { });
        scheduler.runWorker();
        assertEquals(TerminalReadiness.State.DEVICE_SERIAL_UNAVAILABLE,
                runtime.readinessSource().current().state());
        assertFalse(runtime.readinessSource().current().customerActionsEnabled());
        runtime.close();
    }

    private static DeviceSerialProvider serialProvider() {
        return () -> DeviceSerial.available(
                "RK3288-TEST-001", "***********-001");
    }

    private static void expectIllegalArgument(Runnable action) {
        try {
            action.run();
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static boolean allZero(char[] values) {
        for (char value : values) {
            if (value != '\0') return false;
        }
        return true;
    }

    private static final class PassingService implements BootstrapService {
        @Override
        public ApiResult<DeviceRegistration> checkDevice(
                String deviceSerial, CallToken token) {
            return ApiResult.success(new DeviceRegistration("M", "D"));
        }

        @Override
        public ApiResult<BaseSettingSnapshot> baseSetting(
                DeviceRegistration registration, CallToken token) {
            return ApiResult.success(new BaseSettingSnapshot(
                    "VENUE", "1", Collections.singletonList("1"),
                    "", "", "", "COMPANY", "", 0,
                    Collections.emptyList(), "", "",
                    Collections.singletonList(new BaseSettingSnapshot.Area(1, "A")), "1"));
        }

        @Override
        public ApiResult<BasicDataSnapshot> basicData(
                DeviceRegistration registration, CallToken token) {
            return ApiResult.success(new BasicDataSnapshot(32, 12, "1"));
        }
    }

    private static final class FakeScheduler implements BootstrapScheduler {
        Job worker;

        @Override
        public Future<?> submit(Runnable task) {
            worker = new Job(task);
            return worker;
        }

        @Override
        public Future<?> schedule(Runnable task, long delayMillis) {
            return new Job(task);
        }

        void runWorker() {
            worker.run();
        }

        private static final class Job implements Future<Object> {
            final Runnable task;
            boolean cancelled;
            boolean done;
            int cancelCalls;

            Job(Runnable task) { this.task = task; }

            void run() {
                if (cancelled || done) return;
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
}
