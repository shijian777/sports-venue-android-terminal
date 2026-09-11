package com.codex.lockertest.server;

/** Closed allowlist of bootstrap endpoints. */
public enum ApiEndpoint {
    CHECK_DEVICE("/v2/central_control_screen/checkDevice"),
    BASE_SETTING("/v2/central_control_screen/baseSetting"),
    BASIC_DATA("/v2/central_control_screen/basicData");

    private final String path;

    ApiEndpoint(String path) {
        this.path = path;
    }

    public String path() {
        return path;
    }
}
