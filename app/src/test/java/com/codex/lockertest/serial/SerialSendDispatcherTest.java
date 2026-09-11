package com.codex.lockertest.serial;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public final class SerialSendDispatcherTest {
    private static final byte[] A1_UNLOCK = hex(0x8A, 0x01, 0x01, 0x11, 0x9B);

    @Test
    public void rejectedSubmissionReturnsFalseAndReportsOneCorrelatedFailure() {
        RecordingEvents events = new RecordingEvents();
        SerialSendDispatcher dispatcher = new SerialSendDispatcher(
                task -> {
                    throw new IllegalStateException("executor stopped");
                },
                events);
        byte[] callerPayload = Arrays.copyOf(A1_UNLOCK, A1_UNLOCK.length);

        assertFalse(dispatcher.dispatch(callerPayload, new RecordingResource()));
        callerPayload[0] = 0x00;

        assertEquals(0, events.sent.size());
        assertEquals(1, events.failures.size());
        assertArrayEquals(A1_UNLOCK, events.failures.get(0).payload);
        assertTrue(events.failures.get(0).detail.contains("executor stopped"));
    }

    @Test
    public void resourceThatBecomesStaleBeforeWriteReportsOneFailureWithoutWriting() {
        CapturingQueue queue = new CapturingQueue();
        RecordingEvents events = new RecordingEvents();
        RecordingResource resource = new RecordingResource();
        SerialSendDispatcher dispatcher = new SerialSendDispatcher(queue, events);
        byte[] callerPayload = Arrays.copyOf(A1_UNLOCK, A1_UNLOCK.length);

        assertTrue(dispatcher.dispatch(callerPayload, resource));
        callerPayload[1] = 0x7F;
        resource.current = false;
        queue.runAcceptedTask();

        assertEquals(0, resource.writes.size());
        assertEquals(0, events.sent.size());
        assertEquals(1, events.failures.size());
        assertArrayEquals(A1_UNLOCK, events.failures.get(0).payload);
    }

    @Test
    public void writerFailureReportsOneCorrelatedFailureAndNoSuccess() {
        RecordingEvents events = new RecordingEvents();
        RecordingResource resource = new RecordingResource();
        resource.writeFailure = new IllegalStateException("flush boom");
        SerialSendDispatcher dispatcher = new SerialSendDispatcher(Runnable::run, events);

        assertTrue(dispatcher.dispatch(A1_UNLOCK, resource));

        assertEquals(1, resource.writes.size());
        assertEquals(0, events.sent.size());
        assertEquals(1, events.failures.size());
        assertArrayEquals(A1_UNLOCK, events.failures.get(0).payload);
        assertTrue(events.failures.get(0).detail.contains("flush boom"));
    }

    @Test
    public void successfulWriteAndFlushPrecedesOneSentCallback() {
        List<String> order = new ArrayList<>();
        RecordingEvents events = new RecordingEvents(order);
        RecordingResource resource = new RecordingResource(order);
        SerialSendDispatcher dispatcher = new SerialSendDispatcher(Runnable::run, events);

        assertTrue(dispatcher.dispatch(A1_UNLOCK, resource));

        assertEquals(Arrays.asList("writeAndFlush", "onSent"), order);
        assertEquals(1, resource.writes.size());
        assertArrayEquals(A1_UNLOCK, resource.writes.get(0));
        assertEquals(1, events.sent.size());
        assertArrayEquals(A1_UNLOCK, events.sent.get(0));
        assertEquals(0, events.failures.size());
    }

    @Test
    public void callerWriterAndListenerMutationCannotChangeOtherPayloadViews() {
        CapturingQueue queue = new CapturingQueue();
        MutatingEvents events = new MutatingEvents();
        MutatingResource resource = new MutatingResource();
        SerialSendDispatcher dispatcher = new SerialSendDispatcher(queue, events);
        byte[] callerPayload = Arrays.copyOf(A1_UNLOCK, A1_UNLOCK.length);

        assertTrue(dispatcher.dispatch(callerPayload, resource));
        callerPayload[0] = 0x00;
        queue.runAcceptedTask();

        assertArrayEquals(A1_UNLOCK, resource.observedBeforeMutation);
        assertArrayEquals(A1_UNLOCK, events.observedBeforeMutation);
        assertArrayEquals(hex(0x00, 0x01, 0x01, 0x11, 0x9B), callerPayload);
    }

    @Test(expected = IllegalArgumentException.class)
    public void emptyPayloadIsRejectedBeforeQueueing() {
        new SerialSendDispatcher(Runnable::run, new RecordingEvents())
                .dispatch(new byte[0], new RecordingResource());
    }

    private static byte[] hex(int... values) {
        byte[] bytes = new byte[values.length];
        for (int index = 0; index < values.length; index++) {
            bytes[index] = (byte) values[index];
        }
        return bytes;
    }

    private static final class CapturingQueue implements SerialSendDispatcher.Queue {
        private Runnable acceptedTask;

        @Override
        public void execute(Runnable task) {
            acceptedTask = task;
        }

        void runAcceptedTask() {
            Runnable task = acceptedTask;
            acceptedTask = null;
            task.run();
        }
    }

    private static class RecordingResource implements SerialSendDispatcher.Resource {
        boolean current = true;
        RuntimeException writeFailure;
        final List<byte[]> writes = new ArrayList<>();
        private final List<String> order;

        RecordingResource() {
            this(new ArrayList<>());
        }

        RecordingResource(List<String> order) {
            this.order = order;
        }

        @Override
        public boolean isCurrent() {
            return current;
        }

        @Override
        public void writeAndFlush(byte[] payload) {
            order.add("writeAndFlush");
            writes.add(Arrays.copyOf(payload, payload.length));
            if (writeFailure != null) {
                throw writeFailure;
            }
        }
    }

    private static final class MutatingResource implements SerialSendDispatcher.Resource {
        byte[] observedBeforeMutation;

        @Override
        public boolean isCurrent() {
            return true;
        }

        @Override
        public void writeAndFlush(byte[] payload) {
            observedBeforeMutation = Arrays.copyOf(payload, payload.length);
            payload[1] = 0x55;
        }
    }

    private static class RecordingEvents implements SerialSendDispatcher.Events {
        final List<byte[]> sent = new ArrayList<>();
        final List<Failure> failures = new ArrayList<>();
        private final List<String> order;

        RecordingEvents() {
            this(new ArrayList<>());
        }

        RecordingEvents(List<String> order) {
            this.order = order;
        }

        @Override
        public void onSent(byte[] payload) {
            order.add("onSent");
            sent.add(Arrays.copyOf(payload, payload.length));
        }

        @Override
        public void onSendFailed(byte[] payload, String detail) {
            failures.add(new Failure(
                    Arrays.copyOf(payload, payload.length), detail));
        }
    }

    private static final class MutatingEvents implements SerialSendDispatcher.Events {
        byte[] observedBeforeMutation;

        @Override
        public void onSent(byte[] payload) {
            observedBeforeMutation = Arrays.copyOf(payload, payload.length);
            payload[2] = 0x66;
        }

        @Override
        public void onSendFailed(byte[] payload, String detail) {
            throw new AssertionError("unexpected failure");
        }
    }

    private static final class Failure {
        final byte[] payload;
        final String detail;

        Failure(byte[] payload, String detail) {
            this.payload = payload;
            this.detail = detail;
        }
    }
}
