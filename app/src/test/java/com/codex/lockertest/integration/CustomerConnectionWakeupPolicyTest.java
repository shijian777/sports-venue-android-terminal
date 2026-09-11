package com.codex.lockertest.integration;

import com.codex.lockertest.serial.SerialConfig;
import com.codex.lockertest.serial.SerialSessionState;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class CustomerConnectionWakeupPolicyTest {
    @Test
    public void staleCallbackIsIgnoredEvenWhenItsOwnerSnapshotIsMissing() {
        assertEquals(CustomerConnectionWakeupPolicy.Action.IGNORE,
                CustomerConnectionWakeupPolicy.decide(
                        false,
                        true,
                        null,
                        null,
                        false,
                        "串口读取失败"));
    }

    @Test
    public void currentOperationWithMissingSnapshotFailsInsteadOfLoadingForever() {
        assertEquals(CustomerConnectionWakeupPolicy.Action.FAIL,
                CustomerConnectionWakeupPolicy.decide(
                        true,
                        false,
                        null,
                        null,
                        true,
                        ""));
    }

    @Test
    public void historicalFalseStatusDoesNotFailAnExactConnectedSession() {
        assertEquals(CustomerConnectionWakeupPolicy.Action.IGNORE,
                CustomerConnectionWakeupPolicy.decide(
                        true,
                        true,
                        SerialSessionState.Phase.OPEN,
                        SerialConfig.defaults(),
                        false,
                        "正在打开 /dev/ttyS0"));
        assertEquals(CustomerConnectionWakeupPolicy.Action.IGNORE,
                CustomerConnectionWakeupPolicy.decide(
                        true,
                        true,
                        SerialSessionState.Phase.OPEN,
                        SerialConfig.defaults(),
                        false,
                        "串口已关闭"));
    }

    @Test
    public void connectedSessionTrustsFreshExactSnapshotInsteadOfCallbackHistory() {
        assertEquals(CustomerConnectionWakeupPolicy.Action.IGNORE,
                CustomerConnectionWakeupPolicy.decide(
                        true,
                        true,
                        SerialSessionState.Phase.OPEN,
                        SerialConfig.defaults(),
                        false,
                        "串口读取失败"));
    }

    @Test
    public void freshNonDefaultOrClosedStateFailsConnectedSession() {
        assertEquals(CustomerConnectionWakeupPolicy.Action.FAIL,
                CustomerConnectionWakeupPolicy.decide(
                        true,
                        true,
                        SerialSessionState.Phase.OPEN,
                        new SerialConfig("/dev/ttyS2", 19200, 2, 7, 2, 1),
                        true,
                        "已打开"));
        assertEquals(CustomerConnectionWakeupPolicy.Action.FAIL,
                CustomerConnectionWakeupPolicy.decide(
                        true,
                        true,
                        SerialSessionState.Phase.CLOSED,
                        null,
                        true,
                        ""));
    }

    @Test
    public void ordinaryPreConnectionClosingSnapshotReconcilesButRealFailureIsEvent() {
        assertEquals(CustomerConnectionWakeupPolicy.Action.RECONCILE,
                CustomerConnectionWakeupPolicy.decide(
                        true,
                        false,
                        SerialSessionState.Phase.CLOSING,
                        SerialConfig.defaults(),
                        false,
                        "串口已关闭"));
        assertEquals(CustomerConnectionWakeupPolicy.Action.CONNECTION_EVENT,
                CustomerConnectionWakeupPolicy.decide(
                        true,
                        false,
                        SerialSessionState.Phase.CLOSING,
                        SerialConfig.defaults(),
                        false,
                        "串口关闭失败"));
    }

    @Test
    public void normalPreConnectionSnapshotAlwaysUsesExactReconciliation() {
        assertEquals(CustomerConnectionWakeupPolicy.Action.RECONCILE,
                CustomerConnectionWakeupPolicy.decide(
                        true,
                        false,
                        SerialSessionState.Phase.OPEN,
                        SerialConfig.defaults(),
                        true,
                        "已打开"));
    }
}
