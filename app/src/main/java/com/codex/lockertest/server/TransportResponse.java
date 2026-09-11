package com.codex.lockertest.server;

/** Successful HTTP response stripped down to status and decoded JSON text. */
public final class TransportResponse {
    private final int statusCode;
    private final String body;

    public TransportResponse(int statusCode, String body) {
        if (statusCode < 200 || statusCode > 299 || body == null) {
            throw new IllegalArgumentException("Invalid transport response");
        }
        this.statusCode = statusCode;
        this.body = body;
    }

    public int statusCode() {
        return statusCode;
    }

    public String body() {
        return body;
    }
}
