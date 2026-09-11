package com.codex.lockertest.serial;

import java.util.Arrays;

/**
 * Pure queue-to-write transport state machine used by {@link SerialGateway}.
 */
public final class SerialSendDispatcher {
    public interface Queue {
        void execute(Runnable task);
    }

    public interface Resource {
        boolean isCurrent();

        void writeAndFlush(byte[] payload) throws Throwable;
    }

    public interface Events {
        void onSent(byte[] payload);

        void onSendFailed(byte[] payload, String detail);
    }

    private final Queue queue;
    private final Events events;

    public SerialSendDispatcher(Queue queue, Events events) {
        if (queue == null) {
            throw new IllegalArgumentException("queue cannot be null");
        }
        if (events == null) {
            throw new IllegalArgumentException("events cannot be null");
        }
        this.queue = queue;
        this.events = events;
    }

    public boolean dispatch(byte[] bytes, Resource resource) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("send payload cannot be empty");
        }
        if (resource == null) {
            throw new IllegalArgumentException("serial resource cannot be null");
        }
        final byte[] payload = Arrays.copyOf(bytes, bytes.length);
        try {
            queue.execute(() -> dispatchAccepted(payload, resource));
            return true;
        } catch (RuntimeException rejection) {
            notifyFailure(payload, "serial task rejected: " + readableMessage(rejection));
            return false;
        }
    }

    private void dispatchAccepted(byte[] payload, Resource resource) {
        final boolean current;
        try {
            current = resource.isCurrent();
        } catch (Throwable failure) {
            notifyFailure(payload, "serial resource check failed: " + readableMessage(failure));
            return;
        }
        if (!current) {
            notifyFailure(payload, "serial resource became stale before write");
            return;
        }
        try {
            resource.writeAndFlush(Arrays.copyOf(payload, payload.length));
        } catch (Throwable failure) {
            notifyFailure(payload, "serial write failed: " + readableMessage(failure));
            return;
        }
        events.onSent(Arrays.copyOf(payload, payload.length));
    }

    private void notifyFailure(byte[] payload, String detail) {
        events.onSendFailed(Arrays.copyOf(payload, payload.length), detail);
    }

    private static String readableMessage(Throwable failure) {
        String message = failure.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return failure.getClass().getSimpleName();
        }
        return message;
    }
}
