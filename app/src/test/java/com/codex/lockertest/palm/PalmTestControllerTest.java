package com.codex.lockertest.palm;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public final class PalmTestControllerTest {
    @Test
    public void enrollConsumesCallerFeatureAndMatchRequiresScoreAboveSeventy() {
        Fixture fixture = new Fixture();
        fixture.connectReady();

        fixture.controller.enroll();
        fixture.worker.runAll();
        byte[] enrolled = new byte[] {11, 22, 33, 44};
        fixture.driver.enrollEvents.onFeature(enrolled);
        fixture.worker.runAll();

        assertEquals(PalmTestController.State.ENROLLED, fixture.controller.state());
        assertArrayEquals(new byte[4], enrolled);
        assertEquals(1, fixture.driver.stopCalls);
        assertEquals(0, fixture.driver.closeCalls);

        fixture.driver.verifyResult = 70;
        fixture.controller.verify();
        fixture.worker.runAll();
        byte[] firstPresentation = new byte[] {4, 3, 2, 1};
        fixture.driver.captureEvents.onFeature(firstPresentation);
        fixture.worker.runAll();
        assertEquals(PalmTestController.State.NOT_MATCHED, fixture.controller.state());
        assertArrayEquals(new byte[4], firstPresentation);

        fixture.driver.verifyResult = 71;
        fixture.controller.verify();
        fixture.worker.runAll();
        byte[] secondPresentation = new byte[] {8, 7, 6, 5};
        fixture.driver.captureEvents.onFeature(secondPresentation);
        fixture.worker.runAll();
        assertEquals(PalmTestController.State.MATCHED, fixture.controller.state());
        assertArrayEquals(new byte[4], secondPresentation);
        assertEquals(2, fixture.driver.captureCalls);
        assertEquals(3, fixture.driver.stopCalls);
        assertEquals(0, fixture.driver.closeCalls);
        assertEquals(Arrays.asList(11, 22, 33, 44), fixture.driver.lastEnrolled);
        assertEquals(Arrays.asList(8, 7, 6, 5), fixture.driver.lastPresented);
    }

    @Test
    public void sdkZeroIsFailureAndRemainsDistinctFromInternalErrors() {
        Fixture fixture = new Fixture();
        fixture.connectReady();
        fixture.enroll(new byte[] {1});
        fixture.driver.verifyResult = 0;

        fixture.controller.verify();
        fixture.worker.runAll();
        fixture.driver.captureEvents.onFeature(new byte[] {2});
        fixture.worker.runAll();

        assertEquals(PalmTestController.State.FAILED, fixture.controller.state());
        assertEquals(0, fixture.listener.lastCode());
        assertTrue(PalmTestController.ERROR_TIMEOUT < 0);
        assertNotEquals(0, PalmTestController.ERROR_TIMEOUT);
    }

    @Test
    public void verifyBeforeEnrollmentAndRepeatedClicksNeverOverlap() {
        Fixture fixture = new Fixture();
        fixture.controller.verify();
        fixture.controller.connect();
        fixture.controller.connect();
        fixture.worker.runAll();
        assertEquals(1, fixture.driver.connectCalls);
        assertEquals(0, fixture.driver.captureCalls);

        fixture.driver.connectEvents.onConnected();
        fixture.worker.runAll();
        fixture.controller.verify();
        fixture.controller.enroll();
        fixture.controller.enroll();
        fixture.worker.runAll();
        assertEquals(1, fixture.driver.enrollCalls);
        assertEquals(0, fixture.driver.captureCalls);

        fixture.driver.enrollEvents.onFeature(new byte[] {3});
        fixture.worker.runAll();
        fixture.controller.verify();
        fixture.controller.verify();
        fixture.worker.runAll();
        assertEquals(1, fixture.driver.captureCalls);
    }

    @Test
    public void duplicateAndLateFeaturesAreAlwaysWiped() {
        Fixture fixture = new Fixture();
        fixture.connectReady();
        fixture.controller.enroll();
        fixture.worker.runAll();
        byte[] accepted = new byte[] {9, 8};
        byte[] duplicate = new byte[] {7, 6};

        fixture.driver.enrollEvents.onFeature(accepted);
        fixture.driver.enrollEvents.onFeature(duplicate);
        fixture.worker.runAll();

        assertArrayEquals(new byte[2], accepted);
        assertArrayEquals(new byte[2], duplicate);
        assertEquals(PalmTestController.State.ENROLLED, fixture.controller.state());
        assertEquals(1, fixture.driver.stopCalls);

        fixture.controller.close();
        byte[] afterClose = new byte[] {5, 4};
        fixture.driver.enrollEvents.onFeature(afterClose);
        assertArrayEquals(new byte[2], afterClose);
        fixture.worker.runAll();
        assertEquals(PalmTestController.State.CLOSED, fixture.controller.state());
    }

    @Test
    public void duplicateFeatureStormClaimsOnlyOneWorkerTask() {
        Fixture fixture = new Fixture();
        fixture.connectReady();
        fixture.controller.enroll();
        fixture.worker.runAll();

        byte[] accepted = new byte[] {9, 8};
        fixture.driver.enrollEvents.onFeature(accepted);
        assertEquals(1, fixture.worker.size());
        for (int index = 0; index < 100; index++) {
            byte[] duplicate = new byte[] {(byte) index};
            fixture.driver.enrollEvents.onFeature(duplicate);
            assertArrayEquals(new byte[1], duplicate);
        }

        assertArrayEquals(new byte[2], accepted);
        assertEquals(1, fixture.worker.size());
        fixture.worker.runAll();
        assertEquals(PalmTestController.State.ENROLLED, fixture.controller.state());
    }

    @Test
    public void featureBoundsRejectEmptyAndOversizeBuffers() {
        Fixture empty = new Fixture();
        empty.connectReady();
        empty.controller.enroll();
        empty.worker.runAll();
        empty.driver.enrollEvents.onFeature(new byte[0]);
        empty.worker.runAll();
        assertEquals(PalmTestController.State.FAILED, empty.controller.state());
        assertEquals(PalmTestController.ERROR_INVALID_FEATURE, empty.listener.lastCode());

        Fixture oversize = new Fixture();
        oversize.connectReady();
        oversize.controller.enroll();
        oversize.worker.runAll();
        byte[] feature = new byte[65_537];
        Arrays.fill(feature, (byte) 1);
        oversize.driver.enrollEvents.onFeature(feature);
        oversize.worker.runAll();
        assertEquals(PalmTestController.State.FAILED, oversize.controller.state());
        assertEquals(PalmTestController.ERROR_INVALID_FEATURE, oversize.listener.lastCode());
        assertArrayEquals(new byte[65_537], feature);
    }

    @Test
    public void connectionTimeoutCancelsAndRejectsLateSuccess() {
        Fixture fixture = new Fixture();
        fixture.controller.connect();
        fixture.worker.runAll();
        assertEquals(Arrays.asList(10_000L), fixture.scheduler.delays());

        Scheduled timeout = fixture.scheduler.tasks.get(0);
        timeout.fireEvenIfCancelled();
        fixture.worker.runAll();
        assertEquals(PalmTestController.State.FAILED, fixture.controller.state());
        assertEquals(PalmTestController.ERROR_TIMEOUT, fixture.listener.lastCode());
        assertEquals(1, timeout.cancelCount);

        fixture.driver.connectEvents.onConnected();
        fixture.worker.runAll();
        assertEquals(PalmTestController.State.FAILED, fixture.controller.state());
    }

    @Test
    public void connectAdmissionAndDeadlineDoNotWaitForBusyWorker() {
        Fixture fixture = new Fixture();

        fixture.controller.connect();

        assertEquals(PalmTestController.State.CONNECTING, fixture.controller.state());
        assertEquals(Arrays.asList(10_000L), fixture.scheduler.delays());
        assertEquals(1, fixture.worker.size());

        fixture.scheduler.tasks.get(0).fireEvenIfCancelled();

        assertEquals(PalmTestController.State.FAILED, fixture.controller.state());
        assertEquals(PalmTestController.ERROR_TIMEOUT, fixture.listener.lastCode());
        fixture.worker.runAll();
        assertEquals(0, fixture.driver.connectCalls);
        assertEquals(1, fixture.driver.stopCalls);
    }

    @Test
    public void enrollAndVerifyUseExactTimeoutsAndLateEventsCannotRecover() {
        Fixture enroll = new Fixture();
        enroll.connectReady();
        enroll.controller.enroll();
        enroll.worker.runAll();
        assertEquals(Long.valueOf(30_000L), enroll.scheduler.delays().get(1));
        enroll.scheduler.tasks.get(1).fireEvenIfCancelled();
        enroll.worker.runAll();
        byte[] lateEnrollment = new byte[] {1, 2};
        enroll.driver.enrollEvents.onFeature(lateEnrollment);
        enroll.worker.runAll();
        assertEquals(PalmTestController.State.FAILED, enroll.controller.state());
        assertArrayEquals(new byte[2], lateEnrollment);

        Fixture verify = new Fixture();
        verify.connectReady();
        verify.enroll(new byte[] {5, 6});
        verify.controller.verify();
        verify.worker.runAll();
        assertEquals(Long.valueOf(10_000L), verify.scheduler.delays().get(2));
        verify.scheduler.tasks.get(2).fireEvenIfCancelled();
        verify.worker.runAll();
        byte[] lateCapture = new byte[] {6, 5};
        verify.driver.captureEvents.onFeature(lateCapture);
        verify.worker.runAll();
        assertEquals(PalmTestController.State.FAILED, verify.controller.state());
        assertArrayEquals(new byte[2], lateCapture);
        assertEquals(0, verify.driver.verifyCalls);
    }

    @Test
    public void timeoutImmediatelyRevokesWhileWorkerIsUndrainedAndQueuesCleanup() {
        Fixture fixture = new Fixture();
        fixture.connectReady();
        fixture.controller.enroll();
        fixture.worker.runAll();

        fixture.scheduler.tasks.get(1).fireEvenIfCancelled();

        assertEquals(PalmTestController.State.FAILED, fixture.controller.state());
        assertEquals(PalmTestController.ERROR_TIMEOUT, fixture.listener.lastCode());
        assertEquals(0, fixture.driver.stopCalls);
        assertEquals(1, fixture.worker.size());

        byte[] late = new byte[] {4, 5, 6};
        fixture.driver.enrollEvents.onFeature(late);
        assertArrayEquals(new byte[3], late);
        assertEquals(1, fixture.worker.size());

        fixture.worker.runAll();
        assertEquals(1, fixture.driver.stopCalls);
        assertEquals(PalmTestController.State.FAILED, fixture.controller.state());
    }

    @Test
    public void stopFailureAfterEnrollmentPublishesFailureInsteadOfSuccess() {
        Fixture fixture = new Fixture();
        fixture.connectReady();
        fixture.driver.throwStop = true;
        fixture.controller.enroll();
        fixture.worker.runAll();

        byte[] feature = new byte[] {3, 4, 5};
        fixture.driver.enrollEvents.onFeature(feature);
        fixture.worker.runAll();

        assertArrayEquals(new byte[3], feature);
        assertEquals(PalmTestController.State.FAILED, fixture.controller.state());
        assertEquals(PalmTestController.ERROR_DRIVER, fixture.listener.lastCode());
        assertFalse(fixture.listener.states.contains(PalmTestController.State.ENROLLED));
        assertEquals(1, fixture.driver.stopCalls);

        fixture.driver.throwStop = false;
        fixture.controller.verify();
        fixture.worker.runAll();
        assertEquals(0, fixture.driver.captureCalls);
    }

    @Test
    public void stopFailureAfterVerificationCaptureCannotPublishMatch() {
        Fixture fixture = new Fixture();
        fixture.connectReady();
        fixture.enroll(new byte[] {7, 8});
        fixture.driver.verifyResult = 99;
        fixture.driver.throwStop = true;
        fixture.controller.verify();
        fixture.worker.runAll();

        byte[] presented = new byte[] {8, 7};
        fixture.driver.captureEvents.onFeature(presented);
        fixture.worker.runAll();

        assertArrayEquals(new byte[2], presented);
        assertEquals(PalmTestController.State.FAILED, fixture.controller.state());
        assertEquals(PalmTestController.ERROR_DRIVER, fixture.listener.lastCode());
        assertFalse(fixture.listener.states.contains(PalmTestController.State.MATCHED));
        assertFalse(fixture.listener.states.contains(PalmTestController.State.NOT_MATCHED));
        assertEquals(1, fixture.driver.verifyCalls);
        assertEquals(2, fixture.driver.stopCalls);
    }

    @Test
    public void initialConnectionDisconnectInvalidatesLaterCapture() {
        Fixture fixture = new Fixture();
        fixture.connectReady();
        fixture.enroll(new byte[] {1, 2, 3});
        fixture.controller.verify();
        fixture.worker.runAll();

        fixture.driver.connectEvents.onDisconnected();
        fixture.worker.runAll();
        byte[] late = new byte[] {3, 2, 1};
        fixture.driver.captureEvents.onFeature(late);
        fixture.worker.runAll();

        assertEquals(PalmTestController.State.FAILED, fixture.controller.state());
        assertEquals(PalmTestController.ERROR_DISCONNECTED, fixture.listener.lastCode());
        assertArrayEquals(new byte[3], late);
        assertEquals(0, fixture.driver.verifyCalls);
    }

    @Test
    public void disconnectImmediatelyRevokesWhileWorkerIsUndrained() {
        Fixture fixture = new Fixture();
        fixture.connectReady();
        fixture.enroll(new byte[] {1, 2, 3});
        fixture.controller.verify();
        fixture.worker.runAll();
        fixture.worker.execute(new Runnable() {
            @Override public void run() { }
        });

        fixture.driver.connectEvents.onDisconnected();

        assertEquals(PalmTestController.State.FAILED, fixture.controller.state());
        assertEquals(PalmTestController.ERROR_DISCONNECTED, fixture.listener.lastCode());
        assertEquals(2, fixture.worker.size());
        byte[] late = new byte[] {3, 2, 1};
        fixture.driver.captureEvents.onFeature(late);
        assertArrayEquals(new byte[3], late);
        assertEquals(2, fixture.worker.size());
        fixture.worker.runAll();
        assertEquals(2, fixture.driver.stopCalls);
    }

    @Test
    public void failedListenerReconnectCannotBeStoppedByStaleTimeoutCleanup() {
        Fixture fixture = new Fixture();
        fixture.listener.onFailure = new Runnable() {
            @Override public void run() { fixture.controller.connect(); }
        };
        fixture.controller.connect();

        fixture.scheduler.tasks.get(0).fireEvenIfCancelled();
        fixture.worker.runAll();

        assertEquals(Arrays.asList("close", "connect"), fixture.driver.calls);
        assertEquals(PalmTestController.State.CONNECTING, fixture.controller.state());
        assertEquals(1, fixture.driver.connectCalls);
    }

    @Test
    public void driverFailureIsPublishedBeforeNativeCleanupRuns() {
        Fixture fixture = new Fixture();
        fixture.connectReady();
        fixture.enroll(new byte[] {1});
        fixture.controller.verify();
        fixture.worker.runAll();
        fixture.driver.duringStop = new Runnable() {
            @Override public void run() {
                assertEquals(PalmTestController.State.FAILED,
                        fixture.listener.lastState());
            }
        };

        fixture.driver.captureEvents.onFailure(-77);

        assertEquals(PalmTestController.State.FAILED, fixture.controller.state());
        assertEquals(-77, fixture.listener.lastCode());
        fixture.worker.runAll();
    }

    @Test
    public void closeImmediatelyRevokesQueuedNativeWorkAndPendingResults() {
        Fixture queued = new Fixture();
        queued.controller.connect();
        queued.controller.close();
        assertEquals(PalmTestController.State.CLOSED, queued.controller.state());
        queued.worker.runAll();
        assertEquals(0, queued.driver.connectCalls);
        assertEquals(1, queued.driver.stopCalls);
        assertEquals(1, queued.driver.closeCalls);

        Fixture pending = new Fixture();
        pending.connectReady();
        pending.enroll(new byte[] {4, 4});
        pending.controller.verify();
        pending.worker.runAll();
        byte[] feature = new byte[] {8, 8};
        pending.driver.captureEvents.onFeature(feature);
        assertArrayEquals(new byte[2], feature);
        pending.controller.close();
        pending.worker.runAll();
        assertEquals(PalmTestController.State.CLOSED, pending.controller.state());
        assertEquals(0, pending.driver.verifyCalls);
        assertEquals(1, pending.driver.closeCalls);
    }

    @Test
    public void reconnectAfterFailureClosesPreviousDriverBeforeOpening() {
        Fixture fixture = new Fixture();
        fixture.controller.connect();
        fixture.worker.runAll();
        fixture.driver.connectEvents.onFailure(-7);
        fixture.worker.runAll();
        assertEquals(PalmTestController.State.FAILED, fixture.controller.state());
        assertEquals(-7, fixture.listener.lastCode());
        fixture.driver.calls.clear();

        fixture.controller.connect();
        fixture.worker.runAll();

        assertEquals(Arrays.asList("close", "connect"), fixture.driver.calls);
        assertEquals(PalmTestController.State.CONNECTING, fixture.controller.state());
    }

    @Test
    public void reconnectDoesNotReopenWhenPreviousDriverCannotClose() {
        Fixture fixture = new Fixture();
        fixture.controller.connect();
        fixture.worker.runAll();
        fixture.driver.connectEvents.onFailure(-7);
        fixture.worker.runAll();
        fixture.driver.throwClose = true;

        fixture.controller.connect();
        fixture.worker.runAll();

        assertEquals(1, fixture.driver.connectCalls);
        assertEquals(1, fixture.driver.closeCalls);
        assertEquals(PalmTestController.State.FAILED, fixture.controller.state());
        assertEquals(PalmTestController.ERROR_DRIVER, fixture.listener.lastCode());
    }

    @Test
    public void synchronousTimeoutSchedulingCancelsReturnedHandleAndSkipsDriver() {
        DirectExecutor direct = new DirectExecutor();
        Fixture fixture = new Fixture(direct);
        fixture.scheduler.runTaskInline = true;

        fixture.controller.connect();

        assertEquals(PalmTestController.State.FAILED, fixture.controller.state());
        assertEquals(0, fixture.driver.connectCalls);
        assertEquals(1, fixture.scheduler.tasks.get(0).cancelCount);
    }

    @Test
    public void closeRacingWithTimerInstallationCancelsReturnedHandle() {
        Fixture fixture = new Fixture();
        fixture.scheduler.duringSchedule = new Runnable() {
            @Override public void run() { fixture.controller.close(); }
        };

        fixture.controller.connect();
        fixture.worker.runAll();

        assertEquals(PalmTestController.State.CLOSED, fixture.controller.state());
        assertEquals(0, fixture.driver.connectCalls);
        assertEquals(1, fixture.scheduler.tasks.get(0).cancelCount);
        assertEquals(1, fixture.driver.closeCalls);
    }

    @Test
    public void driverSchedulerCancellationAndListenerNeverRunUnderControllerMonitor() {
        Fixture fixture = new Fixture();
        fixture.connectReady();
        fixture.enroll(new byte[] {1});
        fixture.driver.verifyResult = 71;
        fixture.controller.verify();
        fixture.worker.runAll();
        fixture.driver.captureEvents.onFeature(new byte[] {2});
        fixture.worker.runAll();
        fixture.controller.close();
        fixture.worker.runAll();

        assertFalse(fixture.driver.monitorHeld);
        assertFalse(fixture.scheduler.monitorHeld);
        assertFalse(fixture.listener.monitorHeld);
        for (Scheduled scheduled : fixture.scheduler.tasks) {
            assertFalse(scheduled.monitorHeld);
        }
    }

    private static final class Fixture {
        final Executor executor;
        final ManualExecutor worker;
        final FakeScheduler scheduler = new FakeScheduler();
        final FakeDriver driver = new FakeDriver();
        final RecordingListener listener = new RecordingListener();
        final PalmTestController controller;

        Fixture() { this(new ManualExecutor()); }

        Fixture(Executor executor) {
            this.executor = executor;
            this.worker = executor instanceof ManualExecutor
                    ? (ManualExecutor) executor : null;
            controller = new PalmTestController(driver, executor, scheduler, listener);
            driver.controller = controller;
            scheduler.controller = controller;
            listener.controller = controller;
        }

        void connectReady() {
            controller.connect();
            runAll();
            driver.connectEvents.onConnected();
            runAll();
            assertEquals(PalmTestController.State.READY, controller.state());
        }

        void enroll(byte[] feature) {
            controller.enroll();
            runAll();
            driver.enrollEvents.onFeature(feature);
            runAll();
            assertEquals(PalmTestController.State.ENROLLED, controller.state());
        }

        void runAll() {
            if (worker != null) worker.runAll();
        }
    }

    private static final class ManualExecutor implements Executor {
        final Deque<Runnable> tasks = new ArrayDeque<Runnable>();

        @Override public void execute(Runnable command) { tasks.addLast(command); }

        void runAll() {
            while (!tasks.isEmpty()) tasks.removeFirst().run();
        }

        int size() { return tasks.size(); }
    }

    private static final class DirectExecutor implements Executor {
        @Override public void execute(Runnable command) { command.run(); }
    }

    private static final class FakeScheduler implements PalmTestController.Scheduler {
        final List<Scheduled> tasks = new ArrayList<Scheduled>();
        PalmTestController controller;
        boolean monitorHeld;
        boolean runTaskInline;
        Runnable duringSchedule;

        @Override public Runnable schedule(Runnable task, long delayMillis) {
            monitorHeld |= Thread.holdsLock(controller);
            Scheduled scheduled = new Scheduled(controller, task, delayMillis);
            tasks.add(scheduled);
            if (duringSchedule != null) {
                Runnable callback = duringSchedule;
                duringSchedule = null;
                callback.run();
            }
            if (runTaskInline) task.run();
            return scheduled;
        }

        List<Long> delays() {
            List<Long> result = new ArrayList<Long>();
            for (Scheduled task : tasks) result.add(task.delayMillis);
            return result;
        }
    }

    private static final class Scheduled implements Runnable {
        final PalmTestController controller;
        final Runnable task;
        final long delayMillis;
        int cancelCount;
        boolean monitorHeld;

        Scheduled(PalmTestController controller, Runnable task, long delayMillis) {
            this.controller = controller;
            this.task = task;
            this.delayMillis = delayMillis;
        }

        @Override public void run() {
            monitorHeld |= Thread.holdsLock(controller);
            cancelCount++;
        }

        void fireEvenIfCancelled() { task.run(); }
    }

    private static final class FakeDriver implements PalmTestController.Driver {
        PalmTestController controller;
        PalmTestController.Driver.Events connectEvents;
        PalmTestController.Driver.Events enrollEvents;
        PalmTestController.Driver.Events captureEvents;
        final List<String> calls = new ArrayList<String>();
        List<Integer> lastEnrolled;
        List<Integer> lastPresented;
        int connectCalls;
        int enrollCalls;
        int captureCalls;
        int verifyCalls;
        int stopCalls;
        int closeCalls;
        int verifyResult;
        boolean monitorHeld;
        boolean throwClose;
        boolean throwStop;
        Runnable duringStop;

        @Override public void connect(Events events) {
            checkMonitor();
            calls.add("connect");
            connectCalls++;
            connectEvents = events;
        }

        @Override public void enroll(Events events) {
            checkMonitor();
            calls.add("enroll");
            enrollCalls++;
            enrollEvents = events;
        }

        @Override public void capture(Events events) {
            checkMonitor();
            calls.add("capture");
            captureCalls++;
            captureEvents = events;
        }

        @Override public int verify(byte[] enrolled, byte[] presented) {
            checkMonitor();
            calls.add("verify");
            verifyCalls++;
            lastEnrolled = unsigned(enrolled);
            lastPresented = unsigned(presented);
            Arrays.fill(enrolled, (byte) 99);
            Arrays.fill(presented, (byte) 99);
            return verifyResult;
        }

        @Override public void stop() {
            checkMonitor();
            calls.add("stop");
            stopCalls++;
            if (duringStop != null) duringStop.run();
            if (throwStop) throw new IllegalStateException("stop failed");
        }

        @Override public void close() {
            checkMonitor();
            calls.add("close");
            closeCalls++;
            if (throwClose) throw new IllegalStateException("close failed");
        }

        private void checkMonitor() { monitorHeld |= Thread.holdsLock(controller); }

        private static List<Integer> unsigned(byte[] values) {
            List<Integer> result = new ArrayList<Integer>();
            for (byte value : values) result.add(value & 0xff);
            return result;
        }
    }

    private static final class RecordingListener implements PalmTestController.Listener {
        PalmTestController controller;
        final List<PalmTestController.State> states = new ArrayList<PalmTestController.State>();
        final List<Integer> codes = new ArrayList<Integer>();
        boolean monitorHeld;

        @Override public void onState(PalmTestController.State state, int code) {
            monitorHeld |= Thread.holdsLock(controller);
            states.add(state);
            codes.add(code);
            if (state == PalmTestController.State.FAILED && onFailure != null) {
                Runnable action = onFailure;
                onFailure = null;
                action.run();
            }
        }

        int lastCode() { return codes.get(codes.size() - 1); }

        PalmTestController.State lastState() { return states.get(states.size() - 1); }

        Runnable onFailure;
    }
}
