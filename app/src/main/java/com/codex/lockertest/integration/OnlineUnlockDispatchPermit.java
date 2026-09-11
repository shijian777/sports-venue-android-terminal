package com.codex.lockertest.integration;

import com.codex.lockertest.unlock.AuthorizedUnlockRequest;

import java.util.Arrays;
import java.util.function.BooleanSupplier;

public final class OnlineUnlockDispatchPermit {
    public interface WriteAction {
        void write() throws Throwable;
    }

    private final byte[] expectedCommand;
    private boolean cancelled;
    private boolean consumed;

    public OnlineUnlockDispatchPermit(AuthorizedUnlockRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Authorized unlock request is required");
        }
        expectedCommand = request.unlockCommand();
    }

    public synchronized boolean active() {
        return !cancelled;
    }

    public synchronized void cancel() {
        cancelled = true;
    }

    public synchronized boolean write(
            byte[] bytes,
            BooleanSupplier ready,
            WriteAction action) throws Throwable {
        if (ready == null || action == null) {
            throw new IllegalArgumentException("Authorized write dependencies are required");
        }
        if (cancelled
                || consumed
                || bytes == null
                || !Arrays.equals(expectedCommand, bytes)
                || !ready.getAsBoolean()) {
            return false;
        }
        consumed = true;
        action.write();
        return true;
    }
}
