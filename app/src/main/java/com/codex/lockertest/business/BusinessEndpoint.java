package com.codex.lockertest.business;

/** Closed allowlist of JSON business endpoints whose wire contract is documented. */
public enum BusinessEndpoint {
    MOBILE_SMS_CODE("/v2/central_control_screen/mobileSmsCode"),
    RIG_LOGIN("/v2/central_control_screen/rigLogin"),
    CONTROL_PANEL_PREVIEW("/v2/central_control_screen/controlPanelPreview"),
    STOREY_CABINET("/v2/central_control_screen/storeyCabinet"),
    INSTALLATION_VERIFY("/v2/central_control_screen/installationVerify"),
    MBR_LOGIN("/v2/central_control_screen/mbrLogin"),
    QUICK_CLEAR_CABINET("/v2/central_control_screen/quickClearCabinet"),
    USER_INFO("/v2/central_control_screen/userInfo"),
    MEMBER_DYNAMIC_CODE("/v2/central_control_screen/memberDynamicCode"),
    BIND_USER_HAND("/v2/central_control_screen/bindUserHand"),
    USER_BOARD("/v2/central_control_screen/userBoard"),
    OPEN_BOARD("/v2/central_control_screen/openBoard"),
    USE_CABINET_LIST("/v2/central_control_screen/useCabinetList");

    private final String path;

    BusinessEndpoint(String path) {
        this.path = path;
    }

    public String path() {
        return path;
    }
}
