package com.codex.lockertest.returnflow;

/** Typed service outcome that never represents failure with a null payload. */
public final class ReturnServiceResult<T> {
    public enum Code {
        SUCCESS,
        NO_LOCKERS,
        REJECTED,
        RETRYABLE_FAILURE,
        PERMANENT_FAILURE
    }

    private final Code code;
    private final T value;
    private final String message;

    private ReturnServiceResult(Code code, T value, String message) {
        this.code = code;
        this.value = value;
        this.message = message;
    }

    public static <T> ReturnServiceResult<T> success(T value) {
        if (value == null) {
            throw new IllegalArgumentException("Successful result requires a value");
        }
        return new ReturnServiceResult<>(Code.SUCCESS, value, null);
    }

    public static <T> ReturnServiceResult<T> failure(Code code) {
        if (code == null || code == Code.SUCCESS) {
            throw new IllegalArgumentException("Failure code is required");
        }
        return new ReturnServiceResult<>(code, null, null);
    }

    public static <T> ReturnServiceResult<T> failure(Code code, String message) {
        if (code == null || code == Code.SUCCESS) {
            throw new IllegalArgumentException("Failure code is required");
        }
        if (isBlank(message)) {
            throw new IllegalArgumentException("Failure message cannot be empty");
        }
        return new ReturnServiceResult<>(code, null, message);
    }

    public Code code() {
        return code;
    }

    public boolean isSuccess() {
        return code == Code.SUCCESS;
    }

    public boolean hasValue() {
        return value != null;
    }

    public String message() {
        return message;
    }

    private static boolean isBlank(String value) {
        if (value == null || value.length() == 0) return true;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            if (!Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)) {
                return false;
            }
            offset += Character.charCount(codePoint);
        }
        return true;
    }

    public T value() {
        if (value == null) {
            throw new IllegalStateException("Failed result has no value");
        }
        return value;
    }
}
