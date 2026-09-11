package com.codex.lockertest.ui;

/** Immutable customer-availability state selected by the runtime environment. */
public final class TerminalReadiness implements TerminalReadinessSource {
    public enum State {
        READY_LOCAL_DEMO,
        READY_READ_ONLY,
        SERVER_CONNECTING,
        SERVER_NOT_CONFIGURED,
        SERVER_CONTRACT_UNAPPROVED,
        DEVICE_SERIAL_UNAVAILABLE,
        DEVICE_NOT_REGISTERED,
        SERVER_REQUEST_REJECTED,
        DEVICE_CLOCK_INVALID,
        SERVER_TIMEOUT,
        NETWORK_UNAVAILABLE,
        SERVER_UNAVAILABLE,
        SERVER_SECURITY_ERROR,
        SERVER_RESPONSE_INVALID,
        BOOTSTRAP_STOPPED
    }

    private final State state;
    private final String customerMessage;

    private TerminalReadiness(State state, String customerMessage) {
        if (state == null) {
            throw new IllegalArgumentException("Terminal readiness state is required");
        }
        this.state = state;
        this.customerMessage = customerMessage == null ? "" : customerMessage;
    }

    public static TerminalReadiness localDemoReady() {
        return new TerminalReadiness(State.READY_LOCAL_DEMO, "");
    }

    public static TerminalReadiness readyReadOnly() {
        return new TerminalReadiness(
                State.READY_READ_ONLY,
                "服务器基础配置已读取，客户功能仍未开放");
    }

    public static TerminalReadiness serverConnecting() {
        return new TerminalReadiness(
                State.SERVER_CONNECTING,
                "正在连接服务器，请稍候");
    }

    public static TerminalReadiness serverNotConfigured() {
        return new TerminalReadiness(
                State.SERVER_NOT_CONFIGURED,
                "服务器接入尚未完成，客户功能已停用");
    }

    public static TerminalReadiness serverContractUnapproved() {
        return new TerminalReadiness(
                State.SERVER_CONTRACT_UNAPPROVED,
                "服务器接口合同尚未批准，客户功能已停用");
    }

    public static TerminalReadiness deviceSerialUnavailable() {
        return new TerminalReadiness(
                State.DEVICE_SERIAL_UNAVAILABLE,
                "无法读取终端序列号，客户功能已停用");
    }

    public static TerminalReadiness deviceNotRegistered() {
        return new TerminalReadiness(
                State.DEVICE_NOT_REGISTERED,
                "设备未登记，客户功能已停用");
    }

    public static TerminalReadiness serverTimeout() {
        return new TerminalReadiness(
                State.SERVER_TIMEOUT,
                "服务器响应超时，请重试");
    }

    public static TerminalReadiness networkUnavailable() {
        return new TerminalReadiness(
                State.NETWORK_UNAVAILABLE,
                "服务器连接失败，请检查网络后重试");
    }

    public static TerminalReadiness serverUnavailable() {
        return new TerminalReadiness(
                State.SERVER_UNAVAILABLE,
                "服务器暂不可用，请稍后重试");
    }

    public static TerminalReadiness serverRequestRejected() {
        return new TerminalReadiness(
                State.SERVER_REQUEST_REJECTED,
                "服务器拒绝请求，请联系管理员检查接入配置");
    }

    public static TerminalReadiness deviceClockInvalid() {
        return new TerminalReadiness(
                State.DEVICE_CLOCK_INVALID,
                "终端时间异常，请联系管理员校准时间后重试");
    }

    public static TerminalReadiness serverSecurityError() {
        return new TerminalReadiness(
                State.SERVER_SECURITY_ERROR,
                "服务器安全校验失败，客户功能已停用");
    }

    public static TerminalReadiness serverResponseInvalid() {
        return new TerminalReadiness(
                State.SERVER_RESPONSE_INVALID,
                "服务器数据格式异常，请联系管理员");
    }

    public static TerminalReadiness bootstrapStopped() {
        return new TerminalReadiness(
                State.BOOTSTRAP_STOPPED,
                "服务器连接已停止，客户功能已停用");
    }

    public State state() {
        return state;
    }

    public boolean customerActionsEnabled() {
        return state == State.READY_LOCAL_DEMO;
    }

    public String customerMessage() {
        return customerMessage;
    }

    @Override
    public TerminalReadiness current() {
        return this;
    }
}
