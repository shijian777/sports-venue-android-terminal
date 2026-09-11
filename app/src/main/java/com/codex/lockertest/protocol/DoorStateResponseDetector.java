package com.codex.lockertest.protocol;

import com.codex.lockertest.model.LockerTarget;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Incrementally parses single-door status responses without retaining caller-owned bytes. */
public final class DoorStateResponseDetector {
    public enum Source {
        POLL_RESPONSE,
        ACTIVE_PUSH
    }

    public enum PhysicalState {
        OPEN,
        CLOSED,
        UNKNOWN
    }

    public enum Signal {
        REQUEST_IMMEDIATE_POLL
    }

    public static final class Event {
        private final Source source;
        private final PhysicalState physicalState;

        private Event(Source source, PhysicalState physicalState) {
            this.source = source;
            this.physicalState = physicalState;
        }

        public Source source() {
            return source;
        }

        public PhysicalState physicalState() {
            return physicalState;
        }
    }

    public static final class AppendResult {
        private final List<Event> events;
        private final List<Signal> signals;

        private AppendResult(List<Event> events, List<Signal> signals) {
            this.events = Collections.unmodifiableList(new ArrayList<>(events));
            this.signals = Collections.unmodifiableList(new ArrayList<>(signals));
        }

        public List<Event> events() {
            return events;
        }

        public List<Signal> signals() {
            return signals;
        }
    }

    private static final int FRAME_LENGTH = 5;
    private static final byte POLL_HEADER = (byte) 0x80;
    private static final byte PUSH_HEADER = (byte) 0x82;
    private static final byte RAW_OPEN = 0x00;
    private static final byte RAW_CLOSED = 0x11;

    private final LockerTarget target;
    private final byte[] pending = new byte[FRAME_LENGTH];
    private int pendingLength;

    public DoorStateResponseDetector(LockerTarget target) {
        if (target == null) {
            throw new IllegalArgumentException("Locker target is required");
        }
        this.target = target;
    }

    public synchronized AppendResult append(byte[] bytes, int length) {
        if (bytes == null || length < 0 || length > bytes.length) {
            throw new IllegalArgumentException("Invalid received data length");
        }
        List<Event> events = new ArrayList<>();
        List<Signal> signals = new ArrayList<>();
        for (int index = 0; index < length; index++) {
            appendByte(bytes[index], events, signals);
        }
        return new AppendResult(events, signals);
    }

    public synchronized void reset() {
        pendingLength = 0;
    }

    private void appendByte(byte value, List<Event> events, List<Signal> signals) {
        if (pendingLength == FRAME_LENGTH) {
            discardFirst();
        }
        pending[pendingLength++] = value;
        while (pendingLength > 0 && !isHeader(pending[0])) {
            discardFirst();
        }
        if (pendingLength != FRAME_LENGTH) {
            return;
        }
        if (!isValidFrame()) {
            discardFirst();
            return;
        }
        Source source = pending[0] == POLL_HEADER ? Source.POLL_RESPONSE : Source.ACTIVE_PUSH;
        PhysicalState physicalState = physicalState(pending[3]);
        events.add(new Event(source, physicalState));
        if (source == Source.ACTIVE_PUSH) {
            signals.add(Signal.REQUEST_IMMEDIATE_POLL);
        }
        pendingLength = 0;
    }

    private boolean isValidFrame() {
        return isHeader(pending[0])
                && pending[1] == (byte) target.boardAddress()
                && pending[2] == (byte) target.localLock()
                && (pending[3] == RAW_OPEN || pending[3] == RAW_CLOSED)
                && pending[4] == DoorStateProtocol.xor(
                        pending[0], pending[1], pending[2], pending[3]);
    }

    private PhysicalState physicalState(byte rawState) {
        boolean closed = target.feedbackPolarity() == FeedbackPolarity.SHORT_WHEN_LOCKED
                ? rawState == RAW_CLOSED : rawState == RAW_OPEN;
        return closed ? PhysicalState.CLOSED : PhysicalState.OPEN;
    }

    private static boolean isHeader(byte value) {
        return value == POLL_HEADER || value == PUSH_HEADER;
    }

    private void discardFirst() {
        if (pendingLength > 1) {
            System.arraycopy(pending, 1, pending, 0, pendingLength - 1);
        }
        pendingLength--;
    }
}
