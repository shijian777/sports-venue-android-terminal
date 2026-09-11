package com.codex.lockertest.ui;

import com.codex.lockertest.bootstrap.BootstrapRuntime;
import com.codex.lockertest.bootstrap.BootstrapSnapshot;

import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class BootstrapUiSessionGateTest {
    @Test
    public void customerAdminAndRetryAreThreeIndependentLanes() throws Exception {
        FakeRuntime runtime = new FakeRuntime(
                TerminalReadiness.networkUnavailable(),
                state(BootstrapSnapshot.FailureReason.NETWORK));
        BootstrapUiSessionGate gate = new BootstrapUiSessionGate(runtime);

        assertFalse(gate.customerActionsEnabled());
        assertTrue(gate.adminActionsEnabled());
        assertTrue(gate.canRetryBootstrap());
        assertEquals(42L, gate.restartBootstrap());
        assertEquals(1, runtime.restartCalls);

        runtime.readiness.update(TerminalReadiness.localDemoReady());
        assertTrue(gate.customerActionsEnabled());
    }

    @Test
    public void permanentConfigurationAndRegistrationFailuresCannotRetry()
            throws Exception {
        for (BootstrapSnapshot.FailureReason reason : new BootstrapSnapshot.FailureReason[] {
                BootstrapSnapshot.FailureReason.CONFIGURATION,
                BootstrapSnapshot.FailureReason.KEY_MISSING,
                BootstrapSnapshot.FailureReason.REMOTE_REJECTED,
                BootstrapSnapshot.FailureReason.CONTRACT
        }) {
            FakeRuntime runtime = new FakeRuntime(
                    TerminalReadiness.serverNotConfigured(), state(reason));
            BootstrapUiSessionGate gate = new BootstrapUiSessionGate(runtime);
            assertFalse(reason.name(), gate.canRetryBootstrap());
            assertEquals(-1L, gate.restartBootstrap());
            assertEquals(0, runtime.restartCalls);
            assertTrue(gate.adminActionsEnabled());
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void missingRuntimeIsRejected() {
        new BootstrapUiSessionGate(null);
    }

    private static BootstrapSnapshot state(BootstrapSnapshot.FailureReason reason)
            throws Exception {
        Method factory = BootstrapSnapshot.class.getDeclaredMethod(
                "state", long.class, BootstrapSnapshot.Phase.class,
                BootstrapSnapshot.Endpoint.class,
                BootstrapSnapshot.FailureReason.class, String.class);
        factory.setAccessible(true);
        return (BootstrapSnapshot) factory.invoke(null, 1L,
                BootstrapSnapshot.Phase.BLOCKED,
                BootstrapSnapshot.Endpoint.NONE, reason, "********");
    }

    private static final class FakeRuntime implements BootstrapRuntime {
        final MutableTerminalReadinessSource readiness;
        BootstrapSnapshot snapshot;
        int restartCalls;

        FakeRuntime(TerminalReadiness readiness, BootstrapSnapshot snapshot) {
            this.readiness = new MutableTerminalReadinessSource(readiness);
            this.snapshot = snapshot;
        }

        @Override public long start(Listener listener) { return 1L; }
        @Override public long restartBootstrap() { restartCalls++; return 42L; }
        @Override public void cancel() { }
        @Override public BootstrapSnapshot snapshot() { return snapshot; }
        @Override public TerminalReadinessSource readinessSource() { return readiness; }
        @Override public void close() { }
    }
}
