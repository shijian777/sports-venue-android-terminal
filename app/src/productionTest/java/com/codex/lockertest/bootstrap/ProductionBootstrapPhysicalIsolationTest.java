package com.codex.lockertest.bootstrap;

import com.codex.lockertest.server.ApiResult;
import com.codex.lockertest.server.CallToken;
import com.codex.lockertest.ui.BootstrapUiSessionGate;
import com.codex.lockertest.ui.CustomerActionBoundary;
import com.codex.lockertest.ui.TerminalReadiness;

import org.junit.Test;

import java.util.Collections;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Proves that even three successful bootstrap calls cannot reach physical/customer effects. */
public final class ProductionBootstrapPhysicalIsolationTest {
    @Test
    public void fakeThreeEndpointSuccessRemainsReadOnlyAndEffectFree() {
        FakeScheduler scheduler = new FakeScheduler();
        BootstrapRuntime runtime = ProductionBootstrapRuntimeFactory.createForTest(
                new PassingService(),
                () -> DeviceSerial.available("RK3288-TEST-001", "***********-001"),
                scheduler);
        runtime.start(ignored -> { });
        scheduler.runWorker();

        assertEquals(BootstrapSnapshot.Phase.READY_READ_ONLY,
                runtime.snapshot().phase());
        assertEquals(TerminalReadiness.State.READY_READ_ONLY,
                runtime.readinessSource().current().state());

        CustomerActionBoundary boundary = new CustomerActionBoundary(
                runtime.readinessSource());
        EffectCounter effects = new EffectCounter();
        assertFalse(boundary.run(CustomerActionBoundary.Effect.SERIAL_CONNECT,
                effects::recordSerial));
        assertFalse(boundary.run(CustomerActionBoundary.Effect.SERIAL_SEND,
                effects::recordSerial));
        assertFalse(boundary.run(CustomerActionBoundary.Effect.FACE,
                effects::recordFace));
        assertFalse(boundary.run(CustomerActionBoundary.Effect.PALM,
                effects::recordPalm));
        assertFalse(boundary.run(CustomerActionBoundary.Effect.RETURN_SERVICE,
                effects::recordReturn));
        assertEquals(0, effects.total());

        BootstrapUiSessionGate gate = new BootstrapUiSessionGate(runtime);
        assertFalse(gate.customerActionsEnabled());
        assertFalse(gate.canRetryBootstrap());
        assertEquals(-1L, gate.restartBootstrap());
        assertTrue(gate.adminActionsEnabled());
        assertEquals(0, effects.total());
        runtime.close();
    }

    private static final class EffectCounter {
        private int serial;
        private int face;
        private int palm;
        private int returned;

        void recordSerial() { serial++; }
        void recordFace() { face++; }
        void recordPalm() { palm++; }
        void recordReturn() { returned++; }
        int total() { return serial + face + palm + returned; }
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
        private Job worker;

        @Override
        public Future<?> submit(Runnable task) {
            worker = new Job(task);
            return worker;
        }

        @Override
        public Future<?> schedule(Runnable task, long delayMillis) {
            return new Job(task);
        }

        void runWorker() { worker.run(); }

        private static final class Job implements Future<Object> {
            private final Runnable task;
            private boolean done;
            private boolean cancelled;

            Job(Runnable task) { this.task = task; }
            void run() {
                if (done || cancelled) return;
                done = true;
                task.run();
            }
            @Override public boolean cancel(boolean interrupt) {
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
