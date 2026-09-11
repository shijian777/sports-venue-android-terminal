package com.codex.lockertest.runtime;

public final class DemoCredentials {
    public static final String PHONE = "13800138000";
    public static final String PASSWORD = "123456";
    public static final String ID_CARD = "0014872138";
    public static final String QR_CODE =
            "111993413628001787216027-00144049324404404044044~712~1~3~"
                    + "30303030303137373331";
    public static final String ADMIN_PIN = "888888";

    private DemoCredentials() {
    }

    public static boolean isValidPhone(String value) {
        return PHONE.equals(value);
    }

    public static boolean isValidPassword(String value) {
        return PASSWORD.equals(value);
    }

    public static boolean isValidIdCard(String value) {
        return ID_CARD.equals(value);
    }

    public static boolean isValidQrCode(String value) {
        return QR_CODE.equals(value);
    }

    public static boolean isValidScannedCredential(String value) {
        return isValidIdCard(value) || isValidQrCode(value);
    }

    public static boolean isValidAdminPin(String value) {
        return ADMIN_PIN.equals(value);
    }
}
