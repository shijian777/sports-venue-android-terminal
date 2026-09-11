package com.codex.lockertest.server;

/** Fixed non-secret production transport configuration. */
public final class ProductionServerConfig {
    public static final String BASE_URL = "https://devyoga.gmtfit.com";
    public static final int CONNECT_TIMEOUT_MILLIS = 5_000;
    public static final int READ_TIMEOUT_MILLIS = 10_000;
    public static final int REQUEST_TIMEOUT_MILLIS = 15_000;
    public static final int MAX_RESPONSE_BODY_BYTES = 1_048_576;

    private ProductionServerConfig() { }
}
