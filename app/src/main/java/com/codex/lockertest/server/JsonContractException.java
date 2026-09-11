package com.codex.lockertest.server;

/** A fixed-message failure for JSON syntax or numeric contract violations. */
public final class JsonContractException extends RuntimeException {
    private final String code;

    private JsonContractException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }

    static JsonContractException invalidJson() {
        return new JsonContractException("INVALID_JSON", "Invalid JSON");
    }

    static JsonContractException canonicalIntegerRequired() {
        return new JsonContractException(
                "CANONICAL_INTEGER_REQUIRED", "Canonical integer required");
    }

    static JsonContractException integerOutOfRange() {
        return new JsonContractException("INTEGER_OUT_OF_RANGE", "Integer out of range");
    }

    static JsonContractException invalidRange() {
        return new JsonContractException("INVALID_RANGE", "Invalid integer range");
    }
}
