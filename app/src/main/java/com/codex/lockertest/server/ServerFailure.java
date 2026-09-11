package com.codex.lockertest.server;

/** Server failure with an optional bounded, plain-text business rejection reason. */
public final class ServerFailure {
    public enum Kind {
        CONFIGURATION,
        CLOCK_INVALID,
        CANCELLED,
        TIMEOUT,
        NETWORK,
        TLS,
        REDIRECT,
        HTTP,
        RESPONSE_TOO_LARGE,
        INVALID_UTF8,
        INVALID_JSON,
        REMOTE_REJECTED,
        NO_ENTRY_RECORD,
        CONTRACT
    }

    private final Kind kind;
    private final String publicMessage;

    private ServerFailure(Kind kind) {
        this(kind, "");
    }

    private ServerFailure(Kind kind, String publicMessage) {
        this.kind = kind;
        this.publicMessage = publicMessage;
    }

    public static ServerFailure of(Kind kind) {
        if (kind == null) {
            throw new IllegalArgumentException("Failure kind is required");
        }
        return new ServerFailure(kind);
    }

    public Kind kind() {
        return kind;
    }

    /** Only endpoint-validated business rejections may use this factory. */
    public static ServerFailure businessRejection(String message) {
        if (message == null || message.length() > 512 || message.trim().isEmpty()) {
            return of(Kind.REMOTE_REJECTED);
        }
        for (int i = 0; i < message.length(); i++) {
            char ch = message.charAt(i);
            if (Character.isISOControl(ch) || Character.getType(ch) == Character.FORMAT
                    || ch == '<' || ch == '>') {
                return of(Kind.REMOTE_REJECTED);
            }
        }
        return new ServerFailure(Kind.REMOTE_REJECTED, message);
    }

    public String publicMessage() { return publicMessage; }
}
