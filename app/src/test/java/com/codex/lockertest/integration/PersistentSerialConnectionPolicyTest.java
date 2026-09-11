package com.codex.lockertest.integration;

import com.codex.lockertest.serial.SerialConfig;
import com.codex.lockertest.serial.SerialSessionState;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public final class PersistentSerialConnectionPolicyTest {
    @Test
    public void firstMainStartOpensClosedPortOnceAndLaterPassiveReturnDoesNothing() {
        PersistentSerialConnectionPolicy policy =
                new PersistentSerialConnectionPolicy();

        assertEquals(PersistentSerialConnectionPolicy.Action.OPEN_DEFAULTS,
                policy.onMainStarted(SerialSessionState.Phase.CLOSED));
        assertEquals(PersistentSerialConnectionPolicy.Action.NONE,
                policy.onMainStarted(SerialSessionState.Phase.CLOSED));
    }

    @Test
    public void firstMainStartReusesOrWaitsWithoutSecondOpen() {
        assertEquals(PersistentSerialConnectionPolicy.Action.REUSE,
                new PersistentSerialConnectionPolicy().onMainStarted(
                        SerialSessionState.Phase.OPEN));
        assertEquals(PersistentSerialConnectionPolicy.Action.WAIT,
                new PersistentSerialConnectionPolicy().onMainStarted(
                        SerialSessionState.Phase.OPENING));
        assertEquals(PersistentSerialConnectionPolicy.Action.WAIT,
                new PersistentSerialConnectionPolicy().onMainStarted(
                        SerialSessionState.Phase.CLOSING));
    }

    @Test
    public void acceptedManualCloseBeforeFirstMainSuppressesPassiveReopen() {
        PersistentSerialConnectionPolicy policy =
                new PersistentSerialConnectionPolicy();

        policy.onManualCloseAccepted();

        assertEquals(PersistentSerialConnectionPolicy.Action.NONE,
                policy.onMainStarted(SerialSessionState.Phase.CLOSED));
        assertEquals(PersistentSerialConnectionPolicy.Action.OPEN_DEFAULTS,
                policy.newCustomerAttempt().reconcile(
                        SerialSessionState.Phase.CLOSED));
    }

    @Test
    public void explicitCustomerAttemptReusesOpenAndOpensManuallyClosedPort() {
        PersistentSerialConnectionPolicy policy =
                new PersistentSerialConnectionPolicy();

        assertEquals(PersistentSerialConnectionPolicy.Action.REUSE,
                policy.newCustomerAttempt().reconcile(SerialSessionState.Phase.OPEN));
        assertEquals(PersistentSerialConnectionPolicy.Action.OPEN_DEFAULTS,
                policy.newCustomerAttempt().reconcile(SerialSessionState.Phase.CLOSED));
    }

    @Test
    public void explicitAttemptWaitsForOpeningOrClosingWithoutDuplicateOpen() {
        PersistentSerialConnectionPolicy policy =
                new PersistentSerialConnectionPolicy();

        assertEquals(PersistentSerialConnectionPolicy.Action.WAIT,
                policy.newCustomerAttempt().reconcile(
                        SerialSessionState.Phase.OPENING));
        assertEquals(PersistentSerialConnectionPolicy.Action.WAIT,
                policy.newCustomerAttempt().reconcile(
                        SerialSessionState.Phase.CLOSING));
    }

    @Test
    public void closingCompletionBeforeInitialSnapshotStillOpensExactlyOnce() {
        PersistentSerialConnectionPolicy.Attempt attempt =
                new PersistentSerialConnectionPolicy().newCustomerAttempt();

        assertEquals(PersistentSerialConnectionPolicy.Action.OPEN_DEFAULTS,
                attempt.onCloseCompleted(SerialSessionState.Phase.CLOSED));
        assertEquals(PersistentSerialConnectionPolicy.Action.WAIT,
                attempt.reconcile(SerialSessionState.Phase.OPENING));
        assertEquals(PersistentSerialConnectionPolicy.Action.FAIL,
                attempt.onCloseCompleted(SerialSessionState.Phase.CLOSED));
    }

    @Test
    public void cancelledOrDisposedAttemptNeverOpens() {
        PersistentSerialConnectionPolicy.Attempt cancelled =
                new PersistentSerialConnectionPolicy().newCustomerAttempt();
        cancelled.cancel();

        assertEquals(PersistentSerialConnectionPolicy.Action.NONE,
                cancelled.reconcile(SerialSessionState.Phase.CLOSED));
        assertEquals(PersistentSerialConnectionPolicy.Action.FAIL,
                new PersistentSerialConnectionPolicy().newCustomerAttempt()
                        .reconcile(SerialSessionState.Phase.DISPOSED));
    }

    @Test
    public void processCallSitesShareOnePolicyIdentity() {
        assertSame(PersistentSerialConnectionPolicy.shared(),
                PersistentSerialConnectionPolicy.shared());
    }

    @Test
    public void explicitAttemptReusesOnlyAnExactlyDefaultOpenPort() {
        PersistentSerialConnectionPolicy.Attempt attempt =
                new PersistentSerialConnectionPolicy().newCustomerAttempt();

        assertEquals(PersistentSerialConnectionPolicy.Action.REUSE,
                attempt.reconcile(
                        SerialSessionState.Phase.OPEN,
                        SerialConfig.defaults()));
    }

    @Test
    public void nonDefaultOpenPortClosesOnceThenOpensAndReusesDefaults() {
        PersistentSerialConnectionPolicy.Attempt attempt =
                new PersistentSerialConnectionPolicy().newCustomerAttempt();
        SerialConfig adminConfig =
                new SerialConfig("/dev/ttyS2", 19200, 2, 7, 2, 1);

        assertEquals(PersistentSerialConnectionPolicy.Action.CLOSE_FOR_DEFAULTS,
                attempt.reconcile(SerialSessionState.Phase.OPEN, adminConfig));
        assertEquals(PersistentSerialConnectionPolicy.Action.WAIT,
                attempt.reconcile(SerialSessionState.Phase.OPEN, adminConfig));
        assertEquals(PersistentSerialConnectionPolicy.Action.WAIT,
                attempt.onCloseRequestResult(true));
        assertEquals(PersistentSerialConnectionPolicy.Action.WAIT,
                attempt.reconcile(SerialSessionState.Phase.CLOSING, adminConfig));
        assertEquals(PersistentSerialConnectionPolicy.Action.OPEN_DEFAULTS,
                attempt.onConnectionEvent(SerialSessionState.Phase.CLOSED, null));
        assertEquals(PersistentSerialConnectionPolicy.Action.WAIT,
                attempt.reconcile(SerialSessionState.Phase.OPENING, null));
        assertEquals(PersistentSerialConnectionPolicy.Action.REUSE,
                attempt.onConnectionEvent(
                        SerialSessionState.Phase.OPEN,
                        SerialConfig.defaults()));
    }

    @Test
    public void nullConfigurationWhileOpenIsNeverReusable() {
        PersistentSerialConnectionPolicy.Attempt attempt =
                new PersistentSerialConnectionPolicy().newCustomerAttempt();

        assertEquals(PersistentSerialConnectionPolicy.Action.CLOSE_FOR_DEFAULTS,
                attempt.reconcile(SerialSessionState.Phase.OPEN, null));
        assertEquals(PersistentSerialConnectionPolicy.Action.WAIT,
                attempt.reconcile(SerialSessionState.Phase.OPEN, null));
    }

    @Test
    public void rejectedCloseBecomesTerminalFailureInsteadOfWaitingForever() {
        PersistentSerialConnectionPolicy.Attempt attempt =
                new PersistentSerialConnectionPolicy().newCustomerAttempt();

        assertEquals(PersistentSerialConnectionPolicy.Action.CLOSE_FOR_DEFAULTS,
                attempt.reconcile(SerialSessionState.Phase.OPEN, null));
        assertEquals(PersistentSerialConnectionPolicy.Action.FAIL,
                attempt.onCloseRequestResult(false));
        assertEquals(PersistentSerialConnectionPolicy.Action.FAIL,
                attempt.reconcile(SerialSessionState.Phase.CLOSING, null));
    }

    @Test
    public void failedCloseEventThatLeavesClosingIsTerminal() {
        PersistentSerialConnectionPolicy.Attempt attempt =
                new PersistentSerialConnectionPolicy().newCustomerAttempt();

        assertEquals(PersistentSerialConnectionPolicy.Action.CLOSE_FOR_DEFAULTS,
                attempt.reconcile(SerialSessionState.Phase.OPEN, null));
        assertEquals(PersistentSerialConnectionPolicy.Action.WAIT,
                attempt.onCloseRequestResult(true));
        assertEquals(PersistentSerialConnectionPolicy.Action.FAIL,
                attempt.onConnectionEvent(SerialSessionState.Phase.CLOSING, null));
        assertEquals(PersistentSerialConnectionPolicy.Action.FAIL,
                attempt.onConnectionEvent(SerialSessionState.Phase.CLOSED, null));
    }

    @Test
    public void wrongConfigurationAfterDefaultOpenWasRequestedFailsWithoutLooping() {
        PersistentSerialConnectionPolicy.Attempt attempt =
                new PersistentSerialConnectionPolicy().newCustomerAttempt();
        SerialConfig wrong =
                new SerialConfig("/dev/ttyS0", 115200, 0, 8, 1, 0);

        assertEquals(PersistentSerialConnectionPolicy.Action.OPEN_DEFAULTS,
                attempt.reconcile(SerialSessionState.Phase.CLOSED, null));
        assertEquals(PersistentSerialConnectionPolicy.Action.WAIT,
                attempt.reconcile(SerialSessionState.Phase.OPENING, null));
        assertEquals(PersistentSerialConnectionPolicy.Action.FAIL,
                attempt.onConnectionEvent(SerialSessionState.Phase.OPEN, wrong));
        assertEquals(PersistentSerialConnectionPolicy.Action.FAIL,
                attempt.reconcile(SerialSessionState.Phase.OPEN, wrong));
    }

    @Test
    public void cancelledExactDefaultAttemptNeverClosesOrOpens() {
        PersistentSerialConnectionPolicy.Attempt attempt =
                new PersistentSerialConnectionPolicy().newCustomerAttempt();
        attempt.cancel();

        assertEquals(PersistentSerialConnectionPolicy.Action.NONE,
                attempt.reconcile(SerialSessionState.Phase.OPEN, null));
        assertEquals(PersistentSerialConnectionPolicy.Action.NONE,
                attempt.onConnectionEvent(SerialSessionState.Phase.CLOSED, null));
        assertEquals(PersistentSerialConnectionPolicy.Action.NONE,
                attempt.onCloseRequestResult(false));
    }

    @Test
    public void invalidExactAttemptPhasesFailTerminally() {
        PersistentSerialConnectionPolicy.Attempt nullPhase =
                new PersistentSerialConnectionPolicy().newCustomerAttempt();
        PersistentSerialConnectionPolicy.Attempt disposed =
                new PersistentSerialConnectionPolicy().newCustomerAttempt();

        assertEquals(PersistentSerialConnectionPolicy.Action.FAIL,
                nullPhase.reconcile(null, SerialConfig.defaults()));
        assertEquals(PersistentSerialConnectionPolicy.Action.FAIL,
                nullPhase.reconcile(
                        SerialSessionState.Phase.OPEN,
                        SerialConfig.defaults()));
        assertEquals(PersistentSerialConnectionPolicy.Action.FAIL,
                disposed.onConnectionEvent(
                        SerialSessionState.Phase.DISPOSED,
                        null));
    }
}
