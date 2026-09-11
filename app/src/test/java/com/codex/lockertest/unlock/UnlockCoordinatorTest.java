package com.codex.lockertest.unlock;

import com.codex.lockertest.model.LockerTarget;
import com.codex.lockertest.model.LockerZone;
import com.codex.lockertest.protocol.FeedbackPolarity;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public final class UnlockCoordinatorTest {
    private static final byte[] C12_COMMAND = bytes(0x8A, 0x03, 0x0C, 0x11, 0x94);
    private static final byte[] C12_LOCKED_SHORT_SUCCESS =
            bytes(0x8A, 0x03, 0x0C, 0x00, 0x85);
    private static final byte[] C12_LOCKED_SHORT_FAILURE =
            bytes(0x8A, 0x03, 0x0C, 0x11, 0x94);

    @Test
    public void nullAuthorizationProducesZeroSerialWork() {
        Fixture fixture = new Fixture();

        assertEquals(0L, fixture.coordinator.startAuthorized(null));

        assertEquals(UnlockCoordinator.State.FAILURE, fixture.listener.lastState());
        assertEquals(0, fixture.serial.ensureCalls);
        assertEquals(0, fixture.serial.sent.size());
        assertEquals(0, fixture.scheduler.jobs.size());
    }

    @Test
    public void exactAuthorizedRequestStartsTheCoordinatorExactlyOnce() {
        Fixture fixture = new Fixture();

        long attemptId = fixture.coordinator.startAuthorized(c12Request(
                1L, FeedbackPolarity.SHORT_WHEN_LOCKED));

        assertTrue(attemptId > 0L);
        assertEquals(1, fixture.serial.ensureCalls);
        assertEquals(UnlockCoordinator.State.CONNECTING, fixture.listener.lastState());
        assertEquals(0, fixture.serial.sent.size());
    }

    @Test
    public void c12WaitsForTheFullQuietBoundaryThenQueuesTheLiteralCommandOnce() {
        Fixture fixture = new Fixture();
        long attemptId = start(fixture, c12(FeedbackPolarity.SHORT_WHEN_LOCKED));

        fixture.coordinator.onSerialConnected(attemptId);
        assertEquals(UnlockCoordinator.State.QUIETING, fixture.listener.lastState());
        fixture.scheduler.advanceBy(299L);
        assertEquals(0, fixture.serial.sent.size());
        fixture.scheduler.advanceBy(1L);

        assertEquals(1, fixture.serial.sent.size());
        assertArrayEquals(C12_COMMAND, fixture.serial.sent.get(0));
        assertEquals(Long.valueOf(attemptId), fixture.serial.sentAttemptIds.get(0));
        fixture.scheduler.advanceBy(999L);
        assertEquals(1, fixture.serial.sent.size());
    }

    @Test
    public void everyNonemptyReadRestartsTheEntireQuietGuard() {
        Fixture fixture = new Fixture();
        long attemptId = start(fixture, c12(FeedbackPolarity.SHORT_WHEN_LOCKED));
        fixture.coordinator.onSerialConnected(attemptId);

        fixture.scheduler.advanceBy(299L);
        fixture.coordinator.onSerialBytes(attemptId, bytes(0x8A));
        fixture.scheduler.advanceBy(299L);
        fixture.coordinator.onSerialBytes(attemptId, bytes(0x03));
        fixture.scheduler.advanceBy(299L);
        assertEquals(0, fixture.serial.sent.size());
        fixture.scheduler.advanceBy(1L);

        assertEquals(1, fixture.serial.sent.size());
        assertArrayEquals(C12_COMMAND, fixture.serial.sent.get(0));
    }

    @Test
    public void duplicateConnectedCallbackNeitherRestartsQuietNorDuplicatesSend() {
        Fixture fixture = new Fixture();
        long attemptId = start(fixture, c12(FeedbackPolarity.SHORT_WHEN_LOCKED));
        fixture.coordinator.onSerialConnected(attemptId);
        fixture.scheduler.advanceBy(100L);

        fixture.coordinator.onSerialConnected(attemptId);
        fixture.scheduler.advanceBy(200L);
        assertEquals(1, fixture.serial.sent.size());
        fixture.coordinator.onSerialConnected(attemptId);
        fixture.coordinator.onSerialConnected(attemptId);

        assertEquals(1, fixture.serial.sent.size());
        assertEquals(1, fixture.listener.count(UnlockCoordinator.State.QUIETING));
    }

    @Test
    public void cancelDuringQuietPreventsSendAndCancelledGuardCannotTouchReplacement() {
        Fixture fixture = new Fixture();
        long oldAttempt = start(fixture, c12(FeedbackPolarity.SHORT_WHEN_LOCKED));
        fixture.coordinator.onSerialConnected(oldAttempt);
        FakeScheduler.Job cancelledGuard = fixture.scheduler.jobs.get(0);
        fixture.coordinator.cancel();

        cancelledGuard.runEvenIfCancelled();
        assertEquals(0, fixture.serial.sent.size());

        LockerTarget replacementTarget = new LockerTarget(
                LockerZone.B, 2, 1, FeedbackPolarity.SHORT_WHEN_LOCKED);
        long replacement = start(fixture, replacementTarget);
        fixture.coordinator.onSerialConnected(replacement);
        cancelledGuard.runEvenIfCancelled();
        fixture.scheduler.advanceBy(300L);

        assertEquals(1, fixture.serial.sent.size());
        assertArrayEquals(bytes(0x8A, 0x02, 0x01, 0x11, 0x98),
                fixture.serial.sent.get(0));
    }

    @Test
    public void c12LockedShortRejectsUnrelatedFramesAndUsesCustomerOnlySuccessCopy() {
        Fixture fixture = sentFixture(c12(FeedbackPolarity.SHORT_WHEN_LOCKED));

        fixture.coordinator.onSerialBytes(fixture.attemptId, concat(
                bytes(0x82, 0x03, 0x0C, 0x11, 0x9C),
                bytes(0x8A, 0x02, 0x0C, 0x00, 0x84),
                bytes(0x8A, 0x03, 0x0B, 0x00, 0x82),
                bytes(0x8A, 0x03, 0x0C, 0x00, 0x84)));
        assertEquals(UnlockCoordinator.State.WAITING_ACK, fixture.listener.lastState());

        fixture.coordinator.onSerialBytes(fixture.attemptId, C12_LOCKED_SHORT_SUCCESS);

        assertEquals(UnlockCoordinator.State.SUCCESS, fixture.listener.lastState());
        assertEquals("C12号柜门已打开，请存放物品后关闭柜门。",
                fixture.listener.lastDetail());
        assertCustomerCopyContainsOnlyC12(fixture.listener.lastDetail());
    }

    @Test
    public void c12LockedShortCommandEchoIsTheTerminalFailure() {
        Fixture fixture = sentFixture(c12(FeedbackPolarity.SHORT_WHEN_LOCKED));

        fixture.coordinator.onSerialBytes(fixture.attemptId, C12_LOCKED_SHORT_FAILURE);

        assertEquals(UnlockCoordinator.State.FAILURE, fixture.listener.lastState());
        assertEquals("C12号柜门开启失败，请再次尝试。", fixture.listener.lastDetail());
        assertCustomerCopyContainsOnlyC12(fixture.listener.lastDetail());
    }

    @Test
    public void c12OpenShortSwapsTheTwoTerminalOutcomes() {
        Fixture success = sentFixture(c12(FeedbackPolarity.SHORT_WHEN_OPEN));
        success.coordinator.onSerialBytes(success.attemptId,
                bytes(0x8A, 0x03, 0x0C, 0x11, 0x94));
        assertEquals(UnlockCoordinator.State.SUCCESS, success.listener.lastState());
        assertCustomerCopyContainsOnlyC12(success.listener.lastDetail());

        Fixture failure = sentFixture(c12(FeedbackPolarity.SHORT_WHEN_OPEN));
        failure.coordinator.onSerialBytes(failure.attemptId,
                bytes(0x8A, 0x03, 0x0C, 0x00, 0x85));
        assertEquals(UnlockCoordinator.State.FAILURE, failure.listener.lastState());
        assertCustomerCopyContainsOnlyC12(failure.listener.lastDetail());
    }

    @Test
    public void synchronousSentCallbackStartsAckLifecycleOnlyAfterSendReturns() {
        Fixture fixture = new Fixture();
        fixture.serial.sendHook = (attemptId, ignored) -> {
            assertTrue(fixture.serial.inSend);
            fixture.coordinator.onSerialSent(attemptId, C12_COMMAND);
        };
        long attemptId = start(fixture, c12(FeedbackPolarity.SHORT_WHEN_LOCKED));
        fixture.coordinator.onSerialConnected(attemptId);

        fixture.scheduler.advanceBy(300L);

        assertFalse(fixture.serial.inSend);
        assertEquals(UnlockCoordinator.State.WAITING_ACK, fixture.listener.lastState());
        assertEquals(1, fixture.serial.sent.size());
        fixture.coordinator.onSerialBytes(attemptId, C12_LOCKED_SHORT_SUCCESS);
        assertEquals(UnlockCoordinator.State.SUCCESS, fixture.listener.lastState());
    }

    @Test
    public void responseBeforeMatchingSentIsBufferedAndCommittedAfterSent() {
        Fixture fixture = queuedFixture(c12(FeedbackPolarity.SHORT_WHEN_LOCKED));

        fixture.coordinator.onSerialBytes(fixture.attemptId, C12_LOCKED_SHORT_SUCCESS);
        assertNotEquals(UnlockCoordinator.State.SUCCESS, fixture.listener.lastState());
        fixture.coordinator.onSerialSent(fixture.attemptId,
                bytes(0x8A, 0x03, 0x0B, 0x11, 0x93));
        assertNotEquals(UnlockCoordinator.State.SUCCESS, fixture.listener.lastState());

        fixture.coordinator.onSerialSent(fixture.attemptId, C12_COMMAND);
        assertEquals(UnlockCoordinator.State.SUCCESS, fixture.listener.lastState());
        assertEquals(1, fixture.listener.count(UnlockCoordinator.State.SUCCESS));
    }

    @Test
    public void responseAndSentBothSynchronousCommitOnlyAfterAcceptedSendReturns() {
        Fixture fixture = new Fixture();
        fixture.serial.sendHook = (attemptId, ignored) -> {
            fixture.coordinator.onSerialBytes(attemptId, C12_LOCKED_SHORT_FAILURE);
            assertNotEquals(UnlockCoordinator.State.FAILURE, fixture.listener.lastState());
            fixture.coordinator.onSerialSent(attemptId, C12_COMMAND);
            assertNotEquals(UnlockCoordinator.State.FAILURE, fixture.listener.lastState());
        };
        long attemptId = start(fixture, c12(FeedbackPolarity.SHORT_WHEN_LOCKED));
        fixture.coordinator.onSerialConnected(attemptId);

        fixture.scheduler.advanceBy(300L);

        assertEquals(UnlockCoordinator.State.FAILURE, fixture.listener.lastState());
        assertEquals("C12号柜门开启失败，请再次尝试。", fixture.listener.lastDetail());
    }

    @Test
    public void queueToSentWatchdogUsesTheOneSecondBoundaryWithoutRetry() {
        Fixture fixture = queuedFixture(c12(FeedbackPolarity.SHORT_WHEN_LOCKED));

        fixture.scheduler.advanceBy(999L);
        assertNotEquals(UnlockCoordinator.State.FAILURE, fixture.listener.lastState());
        fixture.scheduler.advanceBy(1L);

        assertEquals(UnlockCoordinator.State.FAILURE, fixture.listener.lastState());
        assertEquals("开柜指令发送失败，请再次尝试。", fixture.listener.lastDetail());
        assertEquals(1, fixture.serial.ensureCalls);
        assertEquals(1, fixture.serial.sent.size());
        fixture.scheduler.advanceBy(5_000L);
        assertEquals(1, fixture.listener.count(UnlockCoordinator.State.FAILURE));
    }

    @Test
    public void ackTimeoutStartsAtMatchingSentAndUsesTheThreeSecondBoundary() {
        Fixture fixture = queuedFixture(c12(FeedbackPolarity.SHORT_WHEN_LOCKED));
        fixture.scheduler.advanceBy(900L);
        fixture.coordinator.onSerialSent(fixture.attemptId, C12_COMMAND);

        fixture.scheduler.advanceBy(2_999L);
        assertEquals(UnlockCoordinator.State.WAITING_ACK, fixture.listener.lastState());
        fixture.scheduler.advanceBy(1L);

        assertEquals(UnlockCoordinator.State.FAILURE, fixture.listener.lastState());
        assertEquals("设备无响应，请再次尝试。", fixture.listener.lastDetail());
        assertEquals(1, fixture.serial.sent.size());
    }

    @Test
    public void typedSendFailureRequiresExactAttemptAndPayloadAndFiresOnce() {
        Fixture fixture = queuedFixture(c12(FeedbackPolarity.SHORT_WHEN_LOCKED));

        fixture.coordinator.onSerialSendFailed(
                fixture.attemptId + 1L, C12_COMMAND, "stale /dev/ttyS0");
        fixture.coordinator.onSerialSendFailed(
                fixture.attemptId,
                bytes(0x8A, 0x03, 0x0B, 0x11, 0x93),
                "wrong payload");
        assertNotEquals(UnlockCoordinator.State.FAILURE, fixture.listener.lastState());

        fixture.coordinator.onSerialSendFailed(
                fixture.attemptId, C12_COMMAND, "JNI write EIO HEX=8A030C1194");

        assertEquals(UnlockCoordinator.State.FAILURE, fixture.listener.lastState());
        assertEquals("开柜指令发送失败，请再次尝试。", fixture.listener.lastDetail());
        assertFalse(fixture.listener.lastDetail().contains("JNI"));
        assertFalse(fixture.listener.lastDetail().contains("8A"));
        fixture.coordinator.onSerialSendFailed(
                fixture.attemptId, C12_COMMAND, "duplicate");
        fixture.scheduler.advanceBy(5_000L);
        assertEquals(1, fixture.listener.count(UnlockCoordinator.State.FAILURE));
        assertEquals(1, fixture.serial.sent.size());
    }

    @Test
    public void sendFailureBeforeExpectedCommandExistsIsIgnored() {
        Fixture fixture = new Fixture();
        long attemptId = start(fixture, c12(FeedbackPolarity.SHORT_WHEN_LOCKED));

        fixture.coordinator.onSerialSendFailed(attemptId, C12_COMMAND, "too early");
        assertEquals(UnlockCoordinator.State.CONNECTING, fixture.listener.lastState());
        fixture.coordinator.onSerialConnected(attemptId);
        fixture.coordinator.onSerialSendFailed(attemptId, C12_COMMAND, "still quiet");
        assertEquals(UnlockCoordinator.State.QUIETING, fixture.listener.lastState());
        fixture.scheduler.advanceBy(300L);
        assertEquals(1, fixture.serial.sent.size());
    }

    @Test
    public void cancelledQuietWatchdogAckAndCallbacksCannotAffectReplacementAttempt() {
        Fixture fixture = new Fixture();

        long quietAttempt = start(fixture, c12(FeedbackPolarity.SHORT_WHEN_LOCKED));
        fixture.coordinator.onSerialConnected(quietAttempt);
        FakeScheduler.Job quietJob = fixture.scheduler.jobs.get(0);
        fixture.coordinator.cancel();

        long watchdogAttempt = start(fixture, c12(FeedbackPolarity.SHORT_WHEN_LOCKED));
        fixture.coordinator.onSerialConnected(watchdogAttempt);
        fixture.scheduler.advanceBy(300L);
        FakeScheduler.Job watchdogJob = fixture.scheduler.jobs.get(2);
        fixture.coordinator.cancel();

        long ackAttempt = start(fixture, c12(FeedbackPolarity.SHORT_WHEN_LOCKED));
        fixture.coordinator.onSerialConnected(ackAttempt);
        fixture.scheduler.advanceBy(300L);
        fixture.coordinator.onSerialSent(ackAttempt, C12_COMMAND);
        FakeScheduler.Job ackJob = fixture.scheduler.jobs.get(5);
        fixture.coordinator.cancel();

        LockerTarget replacementTarget = new LockerTarget(
                LockerZone.A, 1, 1, FeedbackPolarity.SHORT_WHEN_LOCKED);
        long replacement = start(fixture, replacementTarget);
        int eventsBeforeStaleWork = fixture.listener.states.size();
        int sendsBeforeStaleWork = fixture.serial.sent.size();

        quietJob.runEvenIfCancelled();
        watchdogJob.runEvenIfCancelled();
        ackJob.runEvenIfCancelled();
        fixture.coordinator.onSerialConnected(quietAttempt);
        fixture.coordinator.onSerialSent(watchdogAttempt, C12_COMMAND);
        fixture.coordinator.onSerialBytes(ackAttempt, C12_LOCKED_SHORT_SUCCESS);
        fixture.coordinator.onSerialSendFailed(
                ackAttempt, C12_COMMAND, "late write failure");
        fixture.coordinator.onSerialFailure(ackAttempt, "late serial failure");

        assertTrue(replacement > ackAttempt);
        assertEquals(eventsBeforeStaleWork, fixture.listener.states.size());
        assertEquals(sendsBeforeStaleWork, fixture.serial.sent.size());
        assertEquals(UnlockCoordinator.State.CONNECTING, fixture.listener.lastState());
    }

    @Test
    public void rejectedOrThrownSendUsesSafeSendCategoryWithoutRetry() {
        Fixture rejected = new Fixture();
        rejected.serial.acceptSend = false;
        long rejectedAttempt = start(rejected, c12(FeedbackPolarity.SHORT_WHEN_LOCKED));
        rejected.coordinator.onSerialConnected(rejectedAttempt);
        rejected.scheduler.advanceBy(300L);
        assertEquals(UnlockCoordinator.State.FAILURE, rejected.listener.lastState());
        assertEquals("开柜指令发送失败，请再次尝试。", rejected.listener.lastDetail());
        assertEquals(1, rejected.serial.sent.size());

        Fixture thrown = new Fixture();
        thrown.serial.sendFailure = new IllegalStateException("executor rejected");
        long thrownAttempt = start(thrown, c12(FeedbackPolarity.SHORT_WHEN_LOCKED));
        thrown.coordinator.onSerialConnected(thrownAttempt);
        thrown.scheduler.advanceBy(300L);
        assertEquals(UnlockCoordinator.State.FAILURE, thrown.listener.lastState());
        assertEquals("开柜指令发送失败，请再次尝试。", thrown.listener.lastDetail());
        assertEquals(1, thrown.serial.sent.size());
    }

    @Test
    public void nullQuietCancellableFailsWithConnectionCategoryAndNeverSends() {
        Fixture fixture = new Fixture();
        fixture.scheduler.returnNullOnceForDelay(300L);
        long attemptId = start(fixture, c12(FeedbackPolarity.SHORT_WHEN_LOCKED));

        fixture.coordinator.onSerialConnected(attemptId);

        assertEquals(UnlockCoordinator.State.FAILURE, fixture.listener.lastState());
        assertEquals("设备连接失败，请联系管理员。", fixture.listener.lastDetail());
        assertEquals(0, fixture.serial.sent.size());
        int terminalEvents = fixture.listener.states.size();
        fixture.coordinator.onSerialConnected(attemptId);
        fixture.coordinator.onSerialBytes(attemptId, C12_LOCKED_SHORT_SUCCESS);
        fixture.scheduler.advanceBy(5_000L);
        assertEquals(terminalEvents, fixture.listener.states.size());
        assertEquals(0, fixture.serial.sent.size());
    }

    @Test
    public void nullSentWatchdogFailsWithSendCategoryWithoutRetryOrLateEffects() {
        Fixture fixture = new Fixture();
        fixture.scheduler.returnNullOnceForDelay(1_000L);
        long attemptId = start(fixture, c12(FeedbackPolarity.SHORT_WHEN_LOCKED));
        fixture.coordinator.onSerialConnected(attemptId);

        fixture.scheduler.advanceBy(300L);

        assertEquals(UnlockCoordinator.State.FAILURE, fixture.listener.lastState());
        assertEquals("开柜指令发送失败，请再次尝试。", fixture.listener.lastDetail());
        assertEquals(1, fixture.serial.sent.size());
        int terminalEvents = fixture.listener.states.size();
        fixture.coordinator.onSerialSent(attemptId, C12_COMMAND);
        fixture.coordinator.onSerialBytes(attemptId, C12_LOCKED_SHORT_SUCCESS);
        fixture.scheduler.advanceBy(5_000L);
        assertEquals(terminalEvents, fixture.listener.states.size());
        assertEquals(1, fixture.serial.sent.size());
    }

    @Test
    public void nullAckCancellableFailsWithSendCategoryWithoutRetryOrLateEffects() {
        Fixture fixture = queuedFixture(c12(FeedbackPolarity.SHORT_WHEN_LOCKED));
        fixture.scheduler.returnNullOnceForDelay(3_000L);

        fixture.coordinator.onSerialSent(fixture.attemptId, C12_COMMAND);

        assertEquals(UnlockCoordinator.State.FAILURE, fixture.listener.lastState());
        assertEquals("开柜指令发送失败，请再次尝试。", fixture.listener.lastDetail());
        assertEquals(1, fixture.serial.sent.size());
        int terminalEvents = fixture.listener.states.size();
        fixture.coordinator.onSerialBytes(
                fixture.attemptId, C12_LOCKED_SHORT_SUCCESS);
        fixture.scheduler.advanceBy(5_000L);
        assertEquals(terminalEvents, fixture.listener.states.size());
        assertEquals(1, fixture.serial.sent.size());
    }

    @Test
    public void synchronousMatchingSentSkipsTheUnneededSentWatchdog() {
        Fixture fixture = new Fixture();
        fixture.scheduler.returnNullOnceForDelay(1_000L);
        fixture.serial.sendHook = (attemptId, ignored) ->
                fixture.coordinator.onSerialSent(attemptId, C12_COMMAND);
        long attemptId = start(fixture, c12(FeedbackPolarity.SHORT_WHEN_LOCKED));
        fixture.coordinator.onSerialConnected(attemptId);

        fixture.scheduler.advanceBy(300L);

        assertEquals(UnlockCoordinator.State.WAITING_ACK, fixture.listener.lastState());
        assertEquals(Arrays.asList(300L, 3_000L), fixture.scheduler.requestedDelays);
        assertEquals(1, fixture.serial.sent.size());
    }

    @Test
    public void genericSerialAndOpenFailuresUseOnlyTheSafeConnectionCategory() {
        Fixture opening = new Fixture();
        long openingAttempt = start(opening, c12(FeedbackPolarity.SHORT_WHEN_LOCKED));
        opening.coordinator.onSerialOpenFailed(
                openingAttempt, "/dev/ttyS0 permission denied");
        assertEquals("设备连接失败，请联系管理员。", opening.listener.lastDetail());
        assertFalse(opening.listener.lastDetail().contains("ttyS0"));

        Fixture waiting = sentFixture(c12(FeedbackPolarity.SHORT_WHEN_LOCKED));
        waiting.coordinator.onSerialFailure(
                waiting.attemptId, "read EIO HEX=8A030C0085");
        assertEquals("设备连接失败，请联系管理员。", waiting.listener.lastDetail());
        assertFalse(waiting.listener.lastDetail().contains("EIO"));
        assertEquals(1, waiting.listener.count(UnlockCoordinator.State.FAILURE));
    }

    @Test
    public void authorizedStartSendsTheExactDefensiveSnapshot() {
        Fixture fixture = new Fixture();
        byte[] callerCommand = bytes(0x8A, 0x02, 0x02, 0x11, 0x9B);
        AuthorizedUnlockRequest request = new AuthorizedUnlockRequest(
                9001L,
                b2(),
                callerCommand,
                bytes(0x8A, 0x02, 0x02, 0x00, 0x8A),
                bytes(0x8A, 0x02, 0x02, 0x11, 0x9B));

        long attemptId = fixture.coordinator.startAuthorized(request);
        callerCommand[0] = 0;
        fixture.coordinator.onSerialConnected(attemptId);
        fixture.scheduler.advanceBy(300L);

        assertTrue(attemptId > 0L);
        assertEquals(1, fixture.serial.ensureCalls);
        assertEquals(1, fixture.serial.sent.size());
        assertArrayEquals(bytes(0x8A, 0x02, 0x02, 0x11, 0x9B),
                fixture.serial.sent.get(0));
    }

    @Test
    public void authorizedStartRejectsNullAndAnActiveReplayWithoutSerialWork() {
        Fixture fixture = new Fixture();
        AuthorizedUnlockRequest request = b2Request(9002L);

        assertEquals(0L, fixture.coordinator.startAuthorized(null));
        long first = fixture.coordinator.startAuthorized(request);
        assertTrue(first > 0L);
        assertEquals(0L, fixture.coordinator.startAuthorized(request));

        assertEquals(1, fixture.serial.ensureCalls);
        assertEquals(0, fixture.serial.sent.size());
    }

    @Test
    public void malformedAuthorityCannotReachTheCoordinatorBecauseConstructionRejectsIt() {
        Fixture fixture = new Fixture();

        try {
            new AuthorizedUnlockRequest(
                    9003L,
                    b2(),
                    bytes(0x8A, 0x02, 0x03, 0x11, 0x9A),
                    bytes(0x8A, 0x02, 0x02, 0x00, 0x8A),
                    bytes(0x8A, 0x02, 0x02, 0x11, 0x9B));
            throw new AssertionError("malformed authority must be impossible");
        } catch (IllegalArgumentException expected) {
            // Constructor is the authority boundary; there is nothing to pass to the coordinator.
        }

        assertEquals(0, fixture.serial.ensureCalls);
        assertEquals(0, fixture.serial.sent.size());
        assertEquals(0, fixture.scheduler.jobs.size());
    }

    @Test
    public void authorizedResponseIgnoresOtherFramesAndMatchesAcrossNoiseAndFragments() {
        Fixture fixture = new Fixture();
        fixture.attemptId = fixture.coordinator.startAuthorized(b2Request(9004L));
        fixture.coordinator.onSerialConnected(fixture.attemptId);
        fixture.scheduler.advanceBy(300L);
        byte[] exactCommand = bytes(0x8A, 0x02, 0x02, 0x11, 0x9B);
        fixture.coordinator.onSerialSent(fixture.attemptId, exactCommand);

        fixture.coordinator.onSerialBytes(fixture.attemptId, concat(
                bytes(0x55, 0x8A, 0x01, 0x02, 0x00, 0x89),
                bytes(0x8A, 0x02, 0x03, 0x00, 0x8B),
                bytes(0x8A, 0x02)));
        assertEquals(UnlockCoordinator.State.WAITING_ACK, fixture.listener.lastState());
        fixture.coordinator.onSerialBytes(
                fixture.attemptId, bytes(0x02, 0x00, 0x8A));

        assertEquals(UnlockCoordinator.State.SUCCESS, fixture.listener.lastState());
        assertEquals(1, fixture.listener.count(UnlockCoordinator.State.SUCCESS));
    }

    @Test
    public void authorizedFailureBufferedBeforeMatchingSentCommitsOnlyAfterSent() {
        Fixture fixture = new Fixture();
        fixture.attemptId = fixture.coordinator.startAuthorized(b2Request(9005L));
        fixture.coordinator.onSerialConnected(fixture.attemptId);
        fixture.scheduler.advanceBy(300L);
        byte[] exactCommand = bytes(0x8A, 0x02, 0x02, 0x11, 0x9B);

        fixture.coordinator.onSerialBytes(fixture.attemptId, exactCommand);
        assertNotEquals(UnlockCoordinator.State.FAILURE, fixture.listener.lastState());
        fixture.coordinator.onSerialSent(
                fixture.attemptId, bytes(0x8A, 0x02, 0x03, 0x11, 0x9A));
        assertNotEquals(UnlockCoordinator.State.FAILURE, fixture.listener.lastState());
        fixture.coordinator.onSerialSent(fixture.attemptId, exactCommand);

        assertEquals(UnlockCoordinator.State.FAILURE, fixture.listener.lastState());
        assertEquals("B2号柜门开启失败，请再次尝试。", fixture.listener.lastDetail());
    }

    private static long start(Fixture fixture, LockerTarget target) {
        int ensureCallsBeforeStart = fixture.serial.ensureCalls;
        long attemptId = fixture.coordinator.startAuthorized(
                authorizedRequest(1_000L + fixture.serial.ensureCalls, target));
        assertTrue(attemptId > 0L);
        assertEquals(UnlockCoordinator.State.CONNECTING, fixture.listener.lastState());
        assertEquals(ensureCallsBeforeStart + 1, fixture.serial.ensureCalls);
        return attemptId;
    }

    private static Fixture queuedFixture(LockerTarget target) {
        Fixture fixture = new Fixture();
        fixture.attemptId = start(fixture, target);
        fixture.coordinator.onSerialConnected(fixture.attemptId);
        fixture.scheduler.advanceBy(300L);
        assertEquals(1, fixture.serial.sent.size());
        return fixture;
    }

    private static Fixture sentFixture(LockerTarget target) {
        Fixture fixture = queuedFixture(target);
        fixture.coordinator.onSerialSent(fixture.attemptId, C12_COMMAND);
        assertEquals(UnlockCoordinator.State.WAITING_ACK, fixture.listener.lastState());
        return fixture;
    }

    private static LockerTarget c12(FeedbackPolarity polarity) {
        return new LockerTarget(LockerZone.C, 3, 12, polarity);
    }

    private static LockerTarget b2() {
        return new LockerTarget(
                LockerZone.B, 2, 2, FeedbackPolarity.SHORT_WHEN_LOCKED);
    }

    private static AuthorizedUnlockRequest b2Request(long operationId) {
        return new AuthorizedUnlockRequest(
                operationId,
                b2(),
                bytes(0x8A, 0x02, 0x02, 0x11, 0x9B),
                bytes(0x8A, 0x02, 0x02, 0x00, 0x8A),
                bytes(0x8A, 0x02, 0x02, 0x11, 0x9B));
    }

    private static AuthorizedUnlockRequest c12Request(
            long operationId, FeedbackPolarity polarity) {
        byte[] success = polarity == FeedbackPolarity.SHORT_WHEN_LOCKED
                ? C12_LOCKED_SHORT_SUCCESS : C12_LOCKED_SHORT_FAILURE;
        byte[] failure = polarity == FeedbackPolarity.SHORT_WHEN_LOCKED
                ? C12_LOCKED_SHORT_FAILURE : C12_LOCKED_SHORT_SUCCESS;
        return new AuthorizedUnlockRequest(
                operationId, c12(polarity), C12_COMMAND, success, failure);
    }

    private static AuthorizedUnlockRequest authorizedRequest(
            long operationId, LockerTarget target) {
        if (target.boardAddress() == 3 && target.localLock() == 12) {
            return c12Request(operationId, target.feedbackPolarity());
        }
        if (target.boardAddress() == 2 && target.localLock() == 1) {
            return new AuthorizedUnlockRequest(operationId, target,
                    bytes(0x8A, 0x02, 0x01, 0x11, 0x98),
                    bytes(0x8A, 0x02, 0x01, 0x00, 0x89),
                    bytes(0x8A, 0x02, 0x01, 0x11, 0x98));
        }
        if (target.boardAddress() == 1 && target.localLock() == 1) {
            return new AuthorizedUnlockRequest(operationId, target,
                    bytes(0x8A, 0x01, 0x01, 0x11, 0x9B),
                    bytes(0x8A, 0x01, 0x01, 0x00, 0x8A),
                    bytes(0x8A, 0x01, 0x01, 0x11, 0x9B));
        }
        throw new AssertionError("missing exact request fixture for " + target.customerLabel());
    }

    private static void assertCustomerCopyContainsOnlyC12(String detail) {
        assertTrue(detail.contains("C12"));
        assertFalse(detail.contains("003"));
        assertFalse(detail.contains("012"));
        assertFalse(detail.contains("板"));
        assertFalse(detail.contains("地址"));
        assertFalse(detail.contains("8A"));
    }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int index = 0; index < values.length; index++) {
            result[index] = (byte) values[index];
        }
        return result;
    }

    private static byte[] concat(byte[]... arrays) {
        int length = 0;
        for (byte[] array : arrays) {
            length += array.length;
        }
        byte[] result = new byte[length];
        int offset = 0;
        for (byte[] array : arrays) {
            System.arraycopy(array, 0, result, offset, array.length);
            offset += array.length;
        }
        return result;
    }

    private static final class Fixture {
        final FakeSerialActions serial = new FakeSerialActions();
        final FakeScheduler scheduler = new FakeScheduler();
        final RecordingListener listener = new RecordingListener();
        final UnlockCoordinator coordinator;
        long attemptId;

        Fixture() {
            coordinator = new UnlockCoordinator(serial, scheduler, listener);
        }

    }

    private interface SendHook {
        void onSend(long attemptId, byte[] bytes);
    }

    private static final class FakeSerialActions implements UnlockCoordinator.SerialActions {
        int ensureCalls;
        boolean acceptSend = true;
        boolean inSend;
        RuntimeException ensureFailure;
        RuntimeException sendFailure;
        SendHook sendHook;
        final List<byte[]> sent = new ArrayList<>();
        final List<Long> ensuredAttemptIds = new ArrayList<>();
        final List<Long> sentAttemptIds = new ArrayList<>();

        @Override
        public void ensureDefaultConnected(long attemptId) {
            ensureCalls++;
            ensuredAttemptIds.add(attemptId);
            if (ensureFailure != null) {
                throw ensureFailure;
            }
        }

        @Override
        public boolean send(long attemptId, byte[] bytes) {
            inSend = true;
            try {
                sentAttemptIds.add(attemptId);
                sent.add(Arrays.copyOf(bytes, bytes.length));
                if (sendHook != null) {
                    sendHook.onSend(attemptId, bytes);
                }
                if (sendFailure != null) {
                    throw sendFailure;
                }
                return acceptSend;
            } finally {
                inSend = false;
            }
        }
    }

    private static final class FakeScheduler implements UnlockCoordinator.Scheduler {
        long now;
        final List<Job> jobs = new ArrayList<>();
        final List<Long> requestedDelays = new ArrayList<>();
        final List<Long> nullOnceDelays = new ArrayList<>();

        @Override
        public UnlockCoordinator.Cancellable schedule(Runnable task, long delayMillis) {
            requestedDelays.add(delayMillis);
            if (nullOnceDelays.remove(Long.valueOf(delayMillis))) {
                return null;
            }
            Job job = new Job(now + delayMillis, task);
            jobs.add(job);
            return job;
        }

        void returnNullOnceForDelay(long delayMillis) {
            nullOnceDelays.add(delayMillis);
        }

        void advanceBy(long millis) {
            long target = now + millis;
            while (true) {
                Job next = null;
                for (Job job : jobs) {
                    if (!job.cancelled && !job.ran && job.dueAt <= target
                            && (next == null || job.dueAt < next.dueAt)) {
                        next = job;
                    }
                }
                if (next == null) {
                    break;
                }
                now = next.dueAt;
                next.ran = true;
                next.task.run();
            }
            now = target;
        }

        static final class Job implements UnlockCoordinator.Cancellable {
            final long dueAt;
            final Runnable task;
            boolean cancelled;
            boolean ran;

            Job(long dueAt, Runnable task) {
                this.dueAt = dueAt;
                this.task = task;
            }

            @Override
            public void cancel() {
                cancelled = true;
            }

            void runEvenIfCancelled() {
                task.run();
            }
        }
    }

    private static final class RecordingListener implements UnlockCoordinator.Listener {
        final List<UnlockCoordinator.State> states = new ArrayList<>();
        final List<String> details = new ArrayList<>();

        @Override
        public void onStateChanged(long attemptId, UnlockCoordinator.State state, String detail) {
            states.add(state);
            details.add(detail);
        }

        UnlockCoordinator.State lastState() {
            return states.get(states.size() - 1);
        }

        String lastDetail() {
            return details.get(details.size() - 1);
        }

        int count(UnlockCoordinator.State wanted) {
            int count = 0;
            for (UnlockCoordinator.State state : states) {
                if (state == wanted) {
                    count++;
                }
            }
            return count;
        }
    }
}
