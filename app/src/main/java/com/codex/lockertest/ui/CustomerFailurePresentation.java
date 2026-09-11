package com.codex.lockertest.ui;

/** Customer-safe copy for a failed unlock attempt. */
public final class CustomerFailurePresentation {
    private static final String INVALID_PHONE = "手机号不正确";
    private static final String INVALID_PASSWORD = "密码不正确";
    private static final String OPEN_FAILURE = "设备连接失败，请联系管理员。";
    private static final String SEND_FAILURE = "开柜指令发送失败，请再次尝试。";
    private static final String TIMEOUT_FAILURE = "设备无响应，请再次尝试。";

    private final String title;
    private final String detail;

    private CustomerFailurePresentation(String title, String detail) {
        this.title = title;
        this.detail = detail;
    }

    public static CustomerFailurePresentation fromCoordinatorDetail(String coordinatorDetail) {
        if (INVALID_PHONE.equals(coordinatorDetail)) {
            return new CustomerFailurePresentation(INVALID_PHONE, "请重新输入手机号");
        }
        if (INVALID_PASSWORD.equals(coordinatorDetail)) {
            return new CustomerFailurePresentation(INVALID_PASSWORD, "请重新输入密码");
        }
        if (OPEN_FAILURE.equals(coordinatorDetail)) {
            return new CustomerFailurePresentation("设备连接失败", "请联系管理员");
        }
        if (SEND_FAILURE.equals(coordinatorDetail)) {
            return new CustomerFailurePresentation("开柜指令发送失败", "请再次尝试");
        }
        if (TIMEOUT_FAILURE.equals(coordinatorDetail)) {
            return new CustomerFailurePresentation("设备无响应", "请再次尝试");
        }
        if (isSelectedLockerFailure(coordinatorDetail)) {
            return new CustomerFailurePresentation(
                    coordinatorDetail.substring(0, coordinatorDetail.indexOf('，')),
                    "请再次尝试");
        }
        return new CustomerFailurePresentation(
                "开柜失败", "请再次尝试或联系管理员");
    }

    public String title() {
        return title;
    }

    public String detail() {
        return detail;
    }

    private static boolean isSelectedLockerFailure(String detail) {
        return detail != null
                && detail.matches("[0-9]{3}号柜门开启失败，请再次尝试。");
    }
}
