package com.codex.lockertest.face.verification;

import java.util.Arrays;
import java.util.regex.Pattern;

public final class FaceVerificationRequest implements AutoCloseable {
    private enum JpegOwnership {
        AVAILABLE, TRANSFER_PENDING, TRANSFER_COMMITTED, CLOSED
    }

    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

    private final String requestId;
    private byte[] jpeg;
    private JpegOwnership jpegOwnership = JpegOwnership.AVAILABLE;
    private final long clientEpochMillis;
    private final String deviceBinding;
    private final String processBinding;

    public FaceVerificationRequest(String requestId, byte[] jpeg, long clientEpochMillis,
            String deviceBinding, String processBinding) {
        String acceptedRequestId = requireIdentifier(requestId, "requestId");
        if (jpeg == null || jpeg.length == 0) {
            throw new IllegalArgumentException("jpeg cannot be empty");
        }
        if (clientEpochMillis < 0L) {
            throw new IllegalArgumentException("clientEpochMillis cannot be negative");
        }
        String acceptedDeviceBinding = requireIdentifier(deviceBinding, "deviceBinding");
        String acceptedProcessBinding = requireIdentifier(processBinding, "processBinding");
        this.requestId = acceptedRequestId;
        this.jpeg = Arrays.copyOf(jpeg, jpeg.length);
        this.clientEpochMillis = clientEpochMillis;
        this.deviceBinding = acceptedDeviceBinding;
        this.processBinding = acceptedProcessBinding;
    }

    public String requestId() {
        return requestId;
    }

    synchronized byte[] takeOwnedJpeg() {
        if (jpegOwnership != JpegOwnership.AVAILABLE || jpeg == null) {
            throw new IllegalStateException("JPEG ownership is already terminal");
        }
        jpegOwnership = JpegOwnership.TRANSFER_PENDING;
        return jpeg;
    }

    /** Commits a verifier handoff only after it returned an accepted cancellation handle. */
    public synchronized void commitOwnedJpegTransfer() {
        if (jpegOwnership == JpegOwnership.TRANSFER_PENDING) {
            jpeg = null;
            jpegOwnership = JpegOwnership.TRANSFER_COMMITTED;
        }
    }

    @Override
    public void close() {
        byte[] wipe;
        synchronized (this) {
            if (jpegOwnership == JpegOwnership.CLOSED
                    || jpegOwnership == JpegOwnership.TRANSFER_COMMITTED) return;
            jpegOwnership = JpegOwnership.CLOSED;
            wipe = jpeg;
            jpeg = null;
        }
        if (wipe != null) Arrays.fill(wipe, (byte) 0);
    }

    public long clientEpochMillis() {
        return clientEpochMillis;
    }

    public String deviceBinding() {
        return deviceBinding;
    }

    public String processBinding() {
        return processBinding;
    }

    private static String requireIdentifier(String value, String name) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " is malformed");
        }
        return value;
    }
}
