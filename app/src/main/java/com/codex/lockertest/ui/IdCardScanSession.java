package com.codex.lockertest.ui;

/** One device-bound HID frame, including overflow poisoning and stale-idle isolation. */
public final class IdCardScanSession {
    public enum AppendResult {
        ACCEPTED,
        FRAME_INVALID,
        IGNORED_OTHER_DEVICE
    }

    public static final class Completion {
        private final String value;
        private final int deviceId;
        private final int length;

        Completion(String value, int deviceId, int length) {
            this.value = value;
            this.deviceId = deviceId;
            this.length = length;
        }

        public boolean isValidFrame() {
            return value != null;
        }

        public String value() {
            return value;
        }

        public int deviceId() {
            return deviceId;
        }

        public int length() {
            return length;
        }
    }

    private final IdCardInputCollector collector;
    private boolean frameActive;
    private boolean frameInvalid;
    private int frameDeviceId;
    private long generation;

    public IdCardScanSession(int maxLength) {
        collector = new IdCardInputCollector(maxLength);
    }

    public AppendResult appendCharacter(int deviceId, char character) {
        if (!frameActive) {
            frameActive = true;
            frameDeviceId = deviceId;
        } else if (deviceId != frameDeviceId) {
            return AppendResult.IGNORED_OTHER_DEVICE;
        }

        advanceGeneration();
        if (frameInvalid) {
            return AppendResult.FRAME_INVALID;
        }
        if (!collector.appendCharacter(character)) {
            frameInvalid = true;
            return AppendResult.FRAME_INVALID;
        }
        return AppendResult.ACCEPTED;
    }

    /** Compatibility bridge for the original numeric reader. */
    public AppendResult appendDigit(int deviceId, char digit) {
        if (digit < '0' || digit > '9') {
            return AppendResult.FRAME_INVALID;
        }
        return appendCharacter(deviceId, digit);
    }

    public long generation() {
        return generation;
    }

    public Completion finish(int deviceId) {
        if (!frameActive || frameDeviceId != deviceId) {
            return null;
        }
        return completeFrame();
    }

    public Completion finishIfCurrent(long expectedGeneration) {
        if (!frameActive || expectedGeneration != generation) {
            return null;
        }
        return completeFrame();
    }

    public void reset() {
        collector.reset();
        frameActive = false;
        frameInvalid = false;
        frameDeviceId = 0;
        advanceGeneration();
    }

    private Completion completeFrame() {
        int completedDeviceId = frameDeviceId;
        int completedLength = collector.length();
        String value = frameInvalid ? null : collector.finish();
        collector.reset();
        frameActive = false;
        frameInvalid = false;
        frameDeviceId = 0;
        advanceGeneration();
        return new Completion(value, completedDeviceId, completedLength);
    }

    private void advanceGeneration() {
        generation++;
        if (generation <= 0L) {
            generation = 1L;
        }
    }
}
