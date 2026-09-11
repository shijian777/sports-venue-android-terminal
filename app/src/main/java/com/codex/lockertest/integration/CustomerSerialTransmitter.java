package com.codex.lockertest.integration;

import com.codex.lockertest.integration.CustomerSerialWritePolicy.CustomerSerialPhase;
import com.codex.lockertest.integration.CustomerSerialWritePolicy.Decision;
import com.codex.lockertest.model.LockerTarget;
import com.codex.lockertest.protocol.HexCodec;
import com.codex.lockertest.serial.SerialWriteAttribution;
import com.codex.lockertest.unlock.AuthorizedUnlockRequest;

import java.util.Arrays;

public final class CustomerSerialTransmitter {
    public interface GatewayWriter {
        boolean write(
                CustomerSerialPhase phase,
                long operationId,
                byte[] payload);
    }

    public interface AuditSink {
        void append(String line);
    }

    public enum Status {
        ACCEPTED,
        POLICY_REJECTED,
        AUDIT_FAILED,
        WRITER_REJECTED,
        WRITER_FAILED
    }

    public static final class SendResult {
        private final Status status;
        private final Decision policyDecision;

        private SendResult(Status status, Decision policyDecision) {
            this.status = status;
            this.policyDecision = policyDecision;
        }

        public Status status() {
            return status;
        }

        public Decision policyDecision() {
            return policyDecision;
        }

        public boolean accepted() {
            return status == Status.ACCEPTED;
        }

        @Override
        public String toString() {
            return "SendResult{status=" + status
                    + ", policyDecision=" + policyDecision + '}';
        }
    }

    private final CustomerSerialWritePolicy policy;
    private final GatewayWriter writer;
    private final AuditSink audit;
    private long lifecycleEpoch;
    private long activeOperationId;
    private CustomerSerialPhase activePhase;
    private boolean sendInProgress;
    private boolean returnStatusWriteAccepted;
    private boolean returnUnlockWriteAccepted;

    public CustomerSerialTransmitter(
            GatewayWriter writer,
            AuditSink audit) {
        if (writer == null || audit == null) {
            throw new IllegalArgumentException("Serial transmitter dependencies are required");
        }
        this.policy = new CustomerSerialWritePolicy();
        this.writer = writer;
        this.audit = audit;
    }

    public synchronized boolean beginOperation(
            long operationId,
            CustomerSerialPhase phase,
            LockerTarget confirmedTarget) {
        boolean began = policy.beginOperation(operationId, phase, confirmedTarget);
        if (began) {
            lifecycleEpoch = nextEpoch(lifecycleEpoch);
            activeOperationId = operationId;
            activePhase = phase;
            returnStatusWriteAccepted = false;
            returnUnlockWriteAccepted = false;
        }
        return began;
    }

    public synchronized boolean beginAuthorizedReturn(
            long operationId,
            AuthorizedUnlockRequest request) {
        boolean began = policy.beginAuthorizedReturn(operationId, request);
        if (began) {
            lifecycleEpoch = nextEpoch(lifecycleEpoch);
            activeOperationId = operationId;
            activePhase = CustomerSerialPhase.RETURN_DOOR_STATUS;
            returnStatusWriteAccepted = false;
            returnUnlockWriteAccepted = false;
        }
        return began;
    }

    public synchronized boolean transitionAuthorizedReturn(
            long operationId,
            CustomerSerialPhase nextPhase) {
        if (operationId <= 0L || operationId != activeOperationId) {
            return false;
        }
        if (activePhase == CustomerSerialPhase.RETURN_DOOR_STATUS
                && nextPhase == CustomerSerialPhase.RETURN_UNLOCK
                && !returnStatusWriteAccepted) {
            return false;
        }
        if (activePhase == CustomerSerialPhase.RETURN_UNLOCK
                && nextPhase == CustomerSerialPhase.RETURN_DOOR_STATUS
                && !returnUnlockWriteAccepted) {
            return false;
        }
        boolean transitioned = policy.transitionAuthorizedReturn(
                operationId, nextPhase);
        if (transitioned) {
            lifecycleEpoch = nextEpoch(lifecycleEpoch);
            activePhase = nextPhase;
        }
        return transitioned;
    }

    public synchronized SendResult send(
            CustomerSerialPhase phase,
            long operationId,
            SerialWriteAttribution attribution,
            byte[] payload) {
        if (sendInProgress) {
            return new SendResult(Status.POLICY_REJECTED, Decision.INVALID_REQUEST);
        }
        sendInProgress = true;
        try {
            byte[] safePayload = payload == null
                    ? null : Arrays.copyOf(payload, payload.length);
            Decision decision = policy.authorize(
                    phase, operationId, attribution, safePayload);
            if (decision != Decision.ALLOWED) {
                return new SendResult(Status.POLICY_REJECTED, decision);
            }
            if (activeOperationId != operationId || activePhase != phase) {
                return new SendResult(
                        Status.POLICY_REJECTED, Decision.INACTIVE_OPERATION);
            }
            long authorizedEpoch = lifecycleEpoch;

            String auditLine = "Authorized origin=" + attribution.origin()
                    + " phase=" + phase
                    + " operationId=" + operationId
                    + " HEX=" + HexCodec.format(safePayload);
            try {
                audit.append(auditLine);
            } catch (RuntimeException | LinkageError ignored) {
                return new SendResult(Status.AUDIT_FAILED, null);
            }
            if (lifecycleEpoch != authorizedEpoch
                    || activeOperationId != operationId
                    || activePhase != phase) {
                return new SendResult(
                        Status.POLICY_REJECTED, Decision.INACTIVE_OPERATION);
            }
            try {
                boolean accepted = writer.write(
                        phase,
                        operationId,
                        Arrays.copyOf(safePayload, safePayload.length));
                if (accepted
                        && lifecycleEpoch == authorizedEpoch
                        && activeOperationId == operationId
                        && activePhase == phase) {
                    if (phase == CustomerSerialPhase.RETURN_DOOR_STATUS) {
                        returnStatusWriteAccepted = true;
                    } else if (phase == CustomerSerialPhase.RETURN_UNLOCK) {
                        returnUnlockWriteAccepted = true;
                    }
                }
                return new SendResult(
                        accepted ? Status.ACCEPTED : Status.WRITER_REJECTED,
                        null);
            } catch (RuntimeException | LinkageError ignored) {
                return new SendResult(Status.WRITER_FAILED, null);
            }
        } finally {
            sendInProgress = false;
        }
    }

    public synchronized void endOperation(long operationId) {
        policy.endOperation(operationId);
        if (operationId > 0L && operationId == activeOperationId) {
            lifecycleEpoch = nextEpoch(lifecycleEpoch);
            activeOperationId = 0L;
            activePhase = null;
            returnStatusWriteAccepted = false;
            returnUnlockWriteAccepted = false;
        }
    }

    private static long nextEpoch(long current) {
        return current == Long.MAX_VALUE ? 1L : current + 1L;
    }
}
