package com.codex.lockertest.face.verification;

import java.util.regex.Pattern;

public final class FaceVerificationResult {
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final Pattern TICKET_ID = Pattern.compile("[0-9a-f]{32}");

    private final FaceVerificationStatus status;
    private final FaceVerificationSource source;
    private final String requestId;
    private final String credential;
    private final String ticketId;
    private final long expiresAtEpochMillis;
    private final String deviceBinding;
    private final String processBinding;
    private final long verificationPolicyEpoch;
    private final String message;

    private FaceVerificationResult(FaceVerificationStatus status,
            FaceVerificationSource source, String requestId, String credential,
            String ticketId, long expiresAtEpochMillis, String deviceBinding,
            String processBinding, long verificationPolicyEpoch, String message) {
        this.status = status;
        this.source = source;
        this.requestId = requestId;
        this.credential = credential;
        this.ticketId = ticketId;
        this.expiresAtEpochMillis = expiresAtEpochMillis;
        this.deviceBinding = deviceBinding;
        this.processBinding = processBinding;
        this.verificationPolicyEpoch = verificationPolicyEpoch;
        this.message = message;
    }

    static FaceVerificationResult passed(FaceVerificationSource source, String requestId,
            String credential, String ticketId, long expiresAtEpochMillis,
            String deviceBinding, String processBinding, long verificationPolicyEpoch) {
        if (source == null) {
            throw new IllegalArgumentException("source cannot be null");
        }
        requireIdentifier(requestId, "requestId");
        requireSecret(credential, "credential");
        if (ticketId == null || !TICKET_ID.matcher(ticketId).matches()) {
            throw new IllegalArgumentException("ticketId must be a 128-bit lowercase hex ID");
        }
        if (expiresAtEpochMillis <= 0L) {
            throw new IllegalArgumentException("expiresAtEpochMillis must be positive");
        }
        requireIdentifier(deviceBinding, "deviceBinding");
        requireIdentifier(processBinding, "processBinding");
        if (verificationPolicyEpoch < 0L) {
            throw new IllegalArgumentException("verificationPolicyEpoch cannot be negative");
        }
        return new FaceVerificationResult(FaceVerificationStatus.PASSED, source,
                requestId, credential, ticketId, expiresAtEpochMillis,
                deviceBinding, processBinding, verificationPolicyEpoch, null);
    }

    public static FaceVerificationResult terminalFailure(
            FaceVerificationStatus status, String requestId) {
        if (status == null || status == FaceVerificationStatus.PASSED) {
            throw new IllegalArgumentException("terminal failure requires a non-passed status");
        }
        requireIdentifier(requestId, "requestId");
        return new FaceVerificationResult(status, null, requestId, null, null,
                0L, null, null, 0L, null);
    }

    public static FaceVerificationResult terminalFailure(
            FaceVerificationStatus status, String requestId, String message) {
        if (isBlank(message)) {
            throw new IllegalArgumentException("terminal failure message cannot be empty");
        }
        if (status == null || status == FaceVerificationStatus.PASSED) {
            throw new IllegalArgumentException("terminal failure requires a non-passed status");
        }
        requireIdentifier(requestId, "requestId");
        return new FaceVerificationResult(status, null, requestId, null, null,
                0L, null, null, 0L, message);
    }

    public FaceVerificationStatus status() {
        return status;
    }

    public FaceVerificationSource source() {
        return source;
    }

    public String requestId() {
        return requestId;
    }

    public String credential() {
        return credential;
    }

    public String ticketId() {
        return ticketId;
    }

    public long expiresAtEpochMillis() {
        return expiresAtEpochMillis;
    }

    public String deviceBinding() {
        return deviceBinding;
    }

    public String processBinding() {
        return processBinding;
    }

    public long verificationPolicyEpoch() {
        return verificationPolicyEpoch;
    }

    public boolean isPassed() {
        return status == FaceVerificationStatus.PASSED;
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

    private static void requireIdentifier(String value, String name) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " is malformed");
        }
    }

    private static void requireSecret(String value, String name) {
        if (value == null || value.length() == 0 || value.length() > 4096) {
            throw new IllegalArgumentException(name + " is missing or too long");
        }
    }
}
