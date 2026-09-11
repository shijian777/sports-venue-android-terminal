package com.codex.lockertest.server;

/** Immutable result that is exactly one of a non-null value or a failure. */
public final class ApiResult<T> {
    private final T value;
    private final ServerFailure failure;

    private ApiResult(T value, ServerFailure failure) {
        this.value = value;
        this.failure = failure;
    }

    public static <T> ApiResult<T> success(T value) {
        if (value == null) {
            throw new IllegalArgumentException("Successful API result requires a value");
        }
        return new ApiResult<>(value, null);
    }

    public static <T> ApiResult<T> failure(ServerFailure failure) {
        if (failure == null) {
            throw new IllegalArgumentException("API failure is required");
        }
        return new ApiResult<>(null, failure);
    }

    public boolean isSuccess() {
        return value != null;
    }

    public T value() {
        if (value == null) {
            throw new IllegalStateException("Failed API result has no value");
        }
        return value;
    }

    public ServerFailure failure() {
        if (failure == null) {
            throw new IllegalStateException("Successful API result has no failure");
        }
        return failure;
    }
}
