package com.codex.lockertest.runtime;

import com.codex.lockertest.unlock.AuthorizedUnlockRequest;

/** Immutable result that grants serial authority only through a defensive request snapshot. */
public final class CustomerUnlockAuthorization {
    private final boolean authorized;
    private final AuthorizedUnlockRequest request;
    private final String message;

    private CustomerUnlockAuthorization(boolean authorized, AuthorizedUnlockRequest request,
            String message) {
        this.authorized = authorized;
        this.request = request;
        this.message = message;
    }

    public static CustomerUnlockAuthorization authorized(AuthorizedUnlockRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Authorized unlock request is required");
        }
        return new CustomerUnlockAuthorization(true, copyOf(request), null);
    }

    public static CustomerUnlockAuthorization unavailable(String message) {
        requireMessage(message);
        return new CustomerUnlockAuthorization(false, null, message);
    }

    public boolean authorized() {
        return authorized;
    }

    public AuthorizedUnlockRequest request() {
        return request == null ? null : copyOf(request);
    }

    public String message() {
        return message;
    }

    private static AuthorizedUnlockRequest copyOf(AuthorizedUnlockRequest request) {
        return new AuthorizedUnlockRequest(request.operationId(), request.target(),
                request.unlockCommand(), request.expectedSuccessFrame(), request.expectedFailureFrame());
    }

    private static void requireMessage(String message) {
        if (message == null || message.trim().isEmpty()) {
            throw new IllegalArgumentException("Unavailable message is required");
        }
    }
}
