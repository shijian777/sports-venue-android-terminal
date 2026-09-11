package com.codex.lockertest.serial;

final class CloseReopenCoordinator {
    interface DetachedResources {
        void closeAndInterrupt();

        boolean awaitTermination();
    }

    enum Result {
        CLOSED,
        READER_STILL_RUNNING,
        STALE
    }

    private final SerialSessionState state;

    CloseReopenCoordinator(SerialSessionState state) {
        if (state == null) {
            throw new IllegalArgumentException("state cannot be null");
        }
        this.state = state;
    }

    long beginClose() {
        return state.beginClose();
    }

    Result finishClose(long closeToken, DetachedResources resources) {
        return finishClose(closeToken, resources, () -> { });
    }

    Result finishClose(
            long closeToken,
            DetachedResources resources,
            Runnable beforeReopen) {
        if (resources == null) {
            throw new IllegalArgumentException("resources cannot be null");
        }
        if (beforeReopen == null) {
            throw new IllegalArgumentException("beforeReopen cannot be null");
        }
        boolean ownsTransition = state.isClosing(closeToken);
        try {
            resources.closeAndInterrupt();
        } catch (Throwable failure) {
            return ownsTransition && state.isClosing(closeToken)
                    ? Result.READER_STILL_RUNNING : Result.STALE;
        }

        boolean terminated;
        try {
            terminated = resources.awaitTermination();
        } catch (Throwable failure) {
            terminated = false;
        }
        if (!ownsTransition) {
            return Result.STALE;
        }
        if (!terminated) {
            return state.isClosing(closeToken)
                    ? Result.READER_STILL_RUNNING : Result.STALE;
        }
        return state.completeClose(closeToken, true, beforeReopen)
                ? Result.CLOSED : Result.STALE;
    }

    Result finishCloseThen(
            long closeToken,
            DetachedResources resources,
            Runnable afterClose) {
        if (afterClose == null) {
            throw new IllegalArgumentException("afterClose cannot be null");
        }
        Result result = finishClose(closeToken, resources);
        if (result == Result.CLOSED) {
            try {
                afterClose.run();
            } catch (Throwable ignored) {
                // Publication cannot change the already completed close outcome.
            }
        }
        return result;
    }
}
