package com.codex.lockertest.server;

import com.codex.lockertest.business.BusinessEndpoint;

/** Complete request input without any arbitrary URL, method, or raw-header surface. */
public final class TransportRequest {
    private final ApiEndpoint endpoint;
    private final BusinessEndpoint businessEndpoint;
    private final String endpointPath;
    private final String body;
    private final BootstrapHeaders headers;
    private final CallToken token;

    public TransportRequest(ApiEndpoint endpoint, String body,
            BootstrapHeaders headers, CallToken token) {
        if (endpoint == null) throw new IllegalArgumentException("Invalid transport request");
        validate(body, headers, token);
        boolean checkDevice = endpoint == ApiEndpoint.CHECK_DEVICE;
        if (checkDevice != headers.isEmpty()) {
            throw new IllegalArgumentException("Invalid transport request");
        }
        this.endpoint = endpoint;
        this.businessEndpoint = null;
        this.endpointPath = endpoint.path();
        this.body = body;
        this.headers = headers;
        this.token = token;
    }

    public TransportRequest(BusinessEndpoint endpoint, String body,
            BootstrapHeaders headers, CallToken token) {
        if (endpoint == null) throw new IllegalArgumentException("Invalid transport request");
        validate(body, headers, token);
        if (headers.isEmpty()) throw new IllegalArgumentException("Invalid transport request");
        this.endpoint = null;
        this.businessEndpoint = endpoint;
        this.endpointPath = endpoint.path();
        this.body = body;
        this.headers = headers;
        this.token = token;
    }

    private static void validate(String body, BootstrapHeaders headers, CallToken token) {
        if (body == null || body.length() == 0
                || headers == null || token == null) {
            throw new IllegalArgumentException("Invalid transport request");
        }
    }

    public ApiEndpoint endpoint() {
        return endpoint;
    }

    public BusinessEndpoint businessEndpoint() {
        return businessEndpoint;
    }

    public String endpointPath() {
        return endpointPath;
    }

    public String body() {
        return body;
    }

    public BootstrapHeaders headers() {
        return headers;
    }

    public CallToken token() {
        return token;
    }
}
