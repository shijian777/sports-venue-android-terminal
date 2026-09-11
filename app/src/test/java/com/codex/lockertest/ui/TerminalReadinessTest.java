package com.codex.lockertest.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class TerminalReadinessTest {
    @Test
    public void exposesTheClosedProductionAndLocalDemoStates() {
        assertEquals(15, TerminalReadiness.State.values().length);
        assertEquals(TerminalReadiness.State.READY_LOCAL_DEMO,
                TerminalReadiness.localDemoReady().state());
        assertEquals(TerminalReadiness.State.READY_READ_ONLY,
                TerminalReadiness.readyReadOnly().state());
        assertEquals(TerminalReadiness.State.SERVER_CONNECTING,
                TerminalReadiness.serverConnecting().state());
        assertEquals(TerminalReadiness.State.SERVER_NOT_CONFIGURED,
                TerminalReadiness.serverNotConfigured().state());
        assertEquals(TerminalReadiness.State.SERVER_CONTRACT_UNAPPROVED,
                TerminalReadiness.serverContractUnapproved().state());
        assertEquals(TerminalReadiness.State.DEVICE_SERIAL_UNAVAILABLE,
                TerminalReadiness.deviceSerialUnavailable().state());
        assertEquals(TerminalReadiness.State.DEVICE_NOT_REGISTERED,
                TerminalReadiness.deviceNotRegistered().state());
        assertEquals(TerminalReadiness.State.SERVER_REQUEST_REJECTED,
                TerminalReadiness.serverRequestRejected().state());
        assertEquals(TerminalReadiness.State.DEVICE_CLOCK_INVALID,
                TerminalReadiness.deviceClockInvalid().state());
        assertEquals(TerminalReadiness.State.SERVER_TIMEOUT,
                TerminalReadiness.serverTimeout().state());
        assertEquals(TerminalReadiness.State.NETWORK_UNAVAILABLE,
                TerminalReadiness.networkUnavailable().state());
        assertEquals(TerminalReadiness.State.SERVER_UNAVAILABLE,
                TerminalReadiness.serverUnavailable().state());
        assertEquals(TerminalReadiness.State.SERVER_SECURITY_ERROR,
                TerminalReadiness.serverSecurityError().state());
        assertEquals(TerminalReadiness.State.SERVER_RESPONSE_INVALID,
                TerminalReadiness.serverResponseInvalid().state());
        assertEquals(TerminalReadiness.State.BOOTSTRAP_STOPPED,
                TerminalReadiness.bootstrapStopped().state());
    }

    @Test
    public void onlyReadyLocalDemoEnablesCustomerActions() {
        assertTrue(TerminalReadiness.localDemoReady().customerActionsEnabled());
        for (TerminalReadiness readiness : unavailableStates()) {
            assertFalse(readiness.customerActionsEnabled());
        }
    }

    @Test
    public void unavailableStatesExposeCustomerSafeMessages() {
        assertEquals("", TerminalReadiness.localDemoReady().customerMessage());
        assertEquals("服务器基础配置已读取，客户功能仍未开放",
                TerminalReadiness.readyReadOnly().customerMessage());
        assertEquals("正在连接服务器，请稍候",
                TerminalReadiness.serverConnecting().customerMessage());
        assertEquals("服务器接入尚未完成，客户功能已停用",
                TerminalReadiness.serverNotConfigured().customerMessage());
        assertEquals("服务器接口合同尚未批准，客户功能已停用",
                TerminalReadiness.serverContractUnapproved().customerMessage());
        assertEquals("无法读取终端序列号，客户功能已停用",
                TerminalReadiness.deviceSerialUnavailable().customerMessage());
        assertEquals("设备未登记，客户功能已停用",
                TerminalReadiness.deviceNotRegistered().customerMessage());
        assertEquals("服务器响应超时，请重试",
                TerminalReadiness.serverTimeout().customerMessage());
        assertEquals("服务器连接失败，请检查网络后重试",
                TerminalReadiness.networkUnavailable().customerMessage());
        assertEquals("服务器暂不可用，请稍后重试",
                TerminalReadiness.serverUnavailable().customerMessage());
        assertEquals("服务器安全校验失败，客户功能已停用",
                TerminalReadiness.serverSecurityError().customerMessage());
        assertEquals("服务器数据格式异常，请联系管理员",
                TerminalReadiness.serverResponseInvalid().customerMessage());
        assertEquals("服务器连接已停止，客户功能已停用",
                TerminalReadiness.bootstrapStopped().customerMessage());
    }

    @Test
    public void readinessIsAlsoAnImmutableSourceOfItself() {
        TerminalReadiness readiness = TerminalReadiness.serverNotConfigured();
        assertTrue(readiness == readiness.current());
    }

    private static TerminalReadiness[] unavailableStates() {
        return new TerminalReadiness[] {
                TerminalReadiness.readyReadOnly(),
                TerminalReadiness.serverConnecting(),
                TerminalReadiness.serverNotConfigured(),
                TerminalReadiness.serverContractUnapproved(),
                TerminalReadiness.deviceSerialUnavailable(),
                TerminalReadiness.deviceNotRegistered(),
                TerminalReadiness.serverRequestRejected(),
                TerminalReadiness.deviceClockInvalid(),
                TerminalReadiness.serverTimeout(),
                TerminalReadiness.networkUnavailable(),
                TerminalReadiness.serverUnavailable(),
                TerminalReadiness.serverSecurityError(),
                TerminalReadiness.serverResponseInvalid(),
                TerminalReadiness.bootstrapStopped()
        };
    }
}
