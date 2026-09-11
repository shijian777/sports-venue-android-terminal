package com.codex.lockertest.ui;

import com.codex.lockertest.bootstrap.BootstrapSnapshot;

import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class BootstrapReadinessMapperTest {
    private final BootstrapReadinessMapper mapper = new BootstrapReadinessMapper();

    @Test
    public void timeoutAndConnectionFailureGiveDistinctRetryGuidance() throws Exception {
        assertFailureMessage(BootstrapSnapshot.FailureReason.TIMEOUT,
                "服务器响应超时，请重试");
        assertFailureMessage(BootstrapSnapshot.FailureReason.NETWORK,
                "服务器连接失败，请检查网络后重试");
    }

    @Test
    public void httpFailureDoesNotTellCustomersTheirNetworkIsBroken() throws Exception {
        assertFailureMessage(BootstrapSnapshot.FailureReason.HTTP,
                "服务器暂不可用，请稍后重试");
    }

    @Test
    public void contractAndMalformedDataDirectCustomersToAdministrator() throws Exception {
        assertFailureMessage(BootstrapSnapshot.FailureReason.CONTRACT,
                "服务器数据格式异常，请联系管理员");
        assertFailureMessage(BootstrapSnapshot.FailureReason.INVALID_RESPONSE,
                "服务器数据格式异常，请联系管理员");
    }

    @Test
    public void genericServerRejectionDoesNotInventAnUnregisteredDeviceDiagnosis() throws Exception {
        for (BootstrapSnapshot.Endpoint endpoint : BootstrapSnapshot.Endpoint.values()) {
            TerminalReadiness readiness = mapper.map(state(BootstrapSnapshot.Phase.BLOCKED,
                    BootstrapSnapshot.FailureReason.REMOTE_REJECTED, endpoint));
            assertFalse("No documented rejection code identifies registration: " + endpoint,
                    readiness.customerMessage().contains("未登记"));
            assertTrue(readiness.customerMessage().contains("拒绝"));
            assertFalse(readiness.customerActionsEnabled());
        }
    }

    @Test
    public void invalidDeviceClockAsksForTimeCorrectionNotServerDataRepair() throws Exception {
        TerminalReadiness readiness = mapper.map(state(BootstrapSnapshot.Phase.BLOCKED,
                BootstrapSnapshot.FailureReason.CLOCK_INVALID));
        assertTrue(readiness.customerMessage().contains("终端时间"));
        assertFalse(readiness.customerActionsEnabled());
    }

    @Test
    public void everyProgressPhaseIsConnectingAndNeverCustomerReady() throws Exception {
        BootstrapSnapshot.Phase[] phases = {
                BootstrapSnapshot.Phase.IDLE,
                BootstrapSnapshot.Phase.READING_SERIAL,
                BootstrapSnapshot.Phase.CHECKING_DEVICE,
                BootstrapSnapshot.Phase.LOADING_BASE_SETTING,
                BootstrapSnapshot.Phase.LOADING_BASIC_DATA,
                BootstrapSnapshot.Phase.RETRY_WAIT
        };
        for (BootstrapSnapshot.Phase phase : phases) {
            TerminalReadiness readiness = mapper.map(state(
                    phase, BootstrapSnapshot.FailureReason.NONE));
            assertEquals(TerminalReadiness.State.SERVER_CONNECTING, readiness.state());
            assertFalse(readiness.customerActionsEnabled());
        }
    }

    @Test
    public void blockedReasonsMapToCustomerSafeClosedStates() throws Exception {
        assertMapped(BootstrapSnapshot.FailureReason.CONFIGURATION,
                TerminalReadiness.State.SERVER_NOT_CONFIGURED);
        assertMapped(BootstrapSnapshot.FailureReason.KEY_MISSING,
                TerminalReadiness.State.SERVER_NOT_CONFIGURED);
        assertMapped(BootstrapSnapshot.FailureReason.SERIAL_UNAVAILABLE,
                TerminalReadiness.State.DEVICE_SERIAL_UNAVAILABLE);
        assertMapped(BootstrapSnapshot.FailureReason.REMOTE_REJECTED,
                TerminalReadiness.State.SERVER_REQUEST_REJECTED);
        assertMapped(BootstrapSnapshot.FailureReason.NETWORK,
                TerminalReadiness.State.NETWORK_UNAVAILABLE);
        assertMapped(BootstrapSnapshot.FailureReason.TIMEOUT,
                TerminalReadiness.State.SERVER_TIMEOUT);
        assertMapped(BootstrapSnapshot.FailureReason.HTTP,
                TerminalReadiness.State.SERVER_UNAVAILABLE);
        assertMapped(BootstrapSnapshot.FailureReason.TLS,
                TerminalReadiness.State.SERVER_SECURITY_ERROR);
        assertMapped(BootstrapSnapshot.FailureReason.REDIRECT,
                TerminalReadiness.State.SERVER_SECURITY_ERROR);
        assertMapped(BootstrapSnapshot.FailureReason.CONTRACT,
                TerminalReadiness.State.SERVER_RESPONSE_INVALID);
        assertMapped(BootstrapSnapshot.FailureReason.INVALID_RESPONSE,
                TerminalReadiness.State.SERVER_RESPONSE_INVALID);
        assertMapped(BootstrapSnapshot.FailureReason.CLOCK_INVALID,
                TerminalReadiness.State.DEVICE_CLOCK_INVALID);
    }

    @Test
    public void cancelledAndClosedMapToStopped() throws Exception {
        assertEquals(TerminalReadiness.State.BOOTSTRAP_STOPPED,
                mapper.map(state(BootstrapSnapshot.Phase.CANCELLED,
                        BootstrapSnapshot.FailureReason.CANCELLED)).state());
        assertEquals(TerminalReadiness.State.BOOTSTRAP_STOPPED,
                mapper.map(state(BootstrapSnapshot.Phase.CLOSED,
                        BootstrapSnapshot.FailureReason.NONE)).state());
    }

    @Test(expected = IllegalArgumentException.class)
    public void nullSnapshotIsRejected() {
        mapper.map(null);
    }

    private void assertMapped(BootstrapSnapshot.FailureReason reason,
            TerminalReadiness.State expected) throws Exception {
        TerminalReadiness readiness = mapper.map(state(
                BootstrapSnapshot.Phase.BLOCKED, reason));
        assertEquals(expected, readiness.state());
        assertFalse(readiness.customerActionsEnabled());
    }

    private void assertFailureMessage(BootstrapSnapshot.FailureReason reason,
            String message) throws Exception {
        for (BootstrapSnapshot.Endpoint endpoint : BootstrapSnapshot.Endpoint.values()) {
            TerminalReadiness readiness = mapper.map(state(
                    BootstrapSnapshot.Phase.BLOCKED, reason, endpoint));
            assertEquals(reason + " at " + endpoint, message, readiness.customerMessage());
            assertFalse(readiness.customerActionsEnabled());
        }
    }

    private static BootstrapSnapshot state(BootstrapSnapshot.Phase phase,
            BootstrapSnapshot.FailureReason reason) throws Exception {
        return state(phase, reason, BootstrapSnapshot.Endpoint.NONE);
    }

    private static BootstrapSnapshot state(BootstrapSnapshot.Phase phase,
            BootstrapSnapshot.FailureReason reason, BootstrapSnapshot.Endpoint endpoint) throws Exception {
        Method factory = BootstrapSnapshot.class.getDeclaredMethod(
                "state", long.class, BootstrapSnapshot.Phase.class,
                BootstrapSnapshot.Endpoint.class,
                BootstrapSnapshot.FailureReason.class, String.class);
        factory.setAccessible(true);
        return (BootstrapSnapshot) factory.invoke(
                null, 1L, phase, endpoint, reason, "********");
    }
}
