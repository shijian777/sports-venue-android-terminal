package com.codex.lockertest.returnflow;

/** Safe acknowledgement for a completed return authorization. */
public final class ReturnCompletionReceipt {
    private final long operationId;
    private final boolean alreadyCompleted;

    public ReturnCompletionReceipt(long operationId, boolean alreadyCompleted) {
        if (operationId <= 0L) {
            throw new IllegalArgumentException("Operation ID must be positive");
        }
        this.operationId = operationId;
        this.alreadyCompleted = alreadyCompleted;
    }

    public long operationId() {
        return operationId;
    }

    public boolean alreadyCompleted() {
        return alreadyCompleted;
    }
}
