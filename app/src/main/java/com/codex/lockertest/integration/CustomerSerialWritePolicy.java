package com.codex.lockertest.integration;

import com.codex.lockertest.model.LockerTarget;
import com.codex.lockertest.protocol.BoardStatusProtocol;
import com.codex.lockertest.protocol.DoorStateProtocol;
import com.codex.lockertest.protocol.FeedbackPolarity;
import com.codex.lockertest.protocol.LockerProtocol;
import com.codex.lockertest.serial.SerialWriteAttribution;
import com.codex.lockertest.unlock.AuthorizedUnlockRequest;

import java.util.Arrays;

public final class CustomerSerialWritePolicy {
    public enum CustomerSerialPhase {
        FACE_PRE_SELECTION,
        LOCKER_DISCOVERY,
        LOCKER_CONFIRMED,
        RETURN_DOOR_STATUS,
        RETURN_UNLOCK,
        TERMINAL
    }

    public enum Decision {
        ALLOWED,
        INVALID_REQUEST,
        INACTIVE_OPERATION,
        PHASE_MISMATCH,
        ATTRIBUTION_MISMATCH,
        PAYLOAD_NOT_ALLOWED,
        DUPLICATE
    }

    private long highestOperationId;
    private long activeOperationId;
    private CustomerSerialPhase activePhase;
    private int confirmedBoardAddress;
    private int confirmedLocalLock;
    private FeedbackPolarity confirmedFeedbackPolarity;
    private byte[] expectedUnlockPayload;
    private byte[] expectedDoorStatusPayload;
    private final boolean[] discoverySent = new boolean[4];
    private boolean unlockSent;
    private boolean authorizedReturnActive;
    private boolean returnStatusQuerySent;
    private boolean returnUnlockPhaseEntered;

    public synchronized boolean beginOperation(
            long operationId,
            CustomerSerialPhase phase,
            LockerTarget confirmedTarget) {
        if (operationId <= 0L || phase == null || operationId <= highestOperationId) {
            return false;
        }
        if (phase == CustomerSerialPhase.RETURN_DOOR_STATUS
                || phase == CustomerSerialPhase.RETURN_UNLOCK) {
            return false;
        }
        boolean confirmed = phase == CustomerSerialPhase.LOCKER_CONFIRMED;
        if (confirmed != (confirmedTarget != null)) {
            return false;
        }

        byte[] expected = null;
        int boardAddress = 0;
        int localLock = 0;
        FeedbackPolarity polarity = null;
        if (confirmed) {
            boardAddress = confirmedTarget.boardAddress();
            localLock = confirmedTarget.localLock();
            polarity = confirmedTarget.feedbackPolarity();
            expected = LockerProtocol.unlockCommand(confirmedTarget);
            expected = Arrays.copyOf(expected, expected.length);
        }

        highestOperationId = operationId;
        activeOperationId = operationId;
        activePhase = phase;
        confirmedBoardAddress = boardAddress;
        confirmedLocalLock = localLock;
        confirmedFeedbackPolarity = polarity;
        expectedUnlockPayload = expected;
        expectedDoorStatusPayload = null;
        Arrays.fill(discoverySent, false);
        unlockSent = false;
        authorizedReturnActive = false;
        returnStatusQuerySent = false;
        returnUnlockPhaseEntered = false;
        return true;
    }

    public synchronized boolean beginAuthorizedReturn(
            long operationId,
            AuthorizedUnlockRequest request) {
        if (operationId <= 0L
                || request == null
                || operationId <= highestOperationId) {
            return false;
        }

        LockerTarget target = request.target();
        highestOperationId = operationId;
        activeOperationId = operationId;
        activePhase = CustomerSerialPhase.RETURN_DOOR_STATUS;
        confirmedBoardAddress = target.boardAddress();
        confirmedLocalLock = target.localLock();
        confirmedFeedbackPolarity = target.feedbackPolarity();
        expectedUnlockPayload = request.unlockCommand();
        expectedDoorStatusPayload = DoorStateProtocol.query(target);
        Arrays.fill(discoverySent, false);
        unlockSent = false;
        authorizedReturnActive = true;
        returnStatusQuerySent = false;
        returnUnlockPhaseEntered = false;
        return true;
    }

    public synchronized boolean transitionAuthorizedReturn(
            long operationId,
            CustomerSerialPhase nextPhase) {
        if (operationId <= 0L
                || operationId != activeOperationId
                || !authorizedReturnActive
                || nextPhase == null) {
            return false;
        }
        if (activePhase == CustomerSerialPhase.RETURN_DOOR_STATUS
                && nextPhase == CustomerSerialPhase.RETURN_UNLOCK
                && returnStatusQuerySent
                && !returnUnlockPhaseEntered) {
            activePhase = nextPhase;
            returnUnlockPhaseEntered = true;
            return true;
        }
        if (activePhase == CustomerSerialPhase.RETURN_UNLOCK
                && nextPhase == CustomerSerialPhase.RETURN_DOOR_STATUS
                && unlockSent) {
            activePhase = nextPhase;
            return true;
        }
        return false;
    }

    public synchronized Decision authorize(
            CustomerSerialPhase phase,
            long operationId,
            SerialWriteAttribution attribution,
            byte[] payload) {
        if (phase == null
                || operationId <= 0L
                || attribution == null
                || payload == null
                || payload.length == 0) {
            return Decision.INVALID_REQUEST;
        }
        byte[] safePayload = Arrays.copyOf(payload, payload.length);
        if (activeOperationId == 0L || operationId != activeOperationId) {
            return Decision.INACTIVE_OPERATION;
        }
        if (phase != activePhase) {
            return Decision.PHASE_MISMATCH;
        }

        if (phase == CustomerSerialPhase.LOCKER_DISCOVERY) {
            return authorizeDiscovery(attribution, safePayload);
        }
        if (phase == CustomerSerialPhase.LOCKER_CONFIRMED) {
            return authorizeUnlock(attribution, safePayload);
        }
        if (phase == CustomerSerialPhase.RETURN_DOOR_STATUS) {
            return authorizeReturnDoorStatus(attribution, safePayload);
        }
        if (phase == CustomerSerialPhase.RETURN_UNLOCK) {
            return authorizeReturnUnlock(attribution, safePayload);
        }
        return Decision.PAYLOAD_NOT_ALLOWED;
    }

    public synchronized void endOperation(long operationId) {
        if (operationId <= 0L || operationId != activeOperationId) {
            return;
        }
        activeOperationId = 0L;
        activePhase = null;
        confirmedBoardAddress = 0;
        confirmedLocalLock = 0;
        confirmedFeedbackPolarity = null;
        expectedUnlockPayload = null;
        expectedDoorStatusPayload = null;
        Arrays.fill(discoverySent, false);
        unlockSent = false;
        authorizedReturnActive = false;
        returnStatusQuerySent = false;
        returnUnlockPhaseEntered = false;
    }

    private Decision authorizeDiscovery(
            SerialWriteAttribution attribution, byte[] payload) {
        if (attribution.origin() != SerialWriteAttribution.Origin.DISCOVERY
                || attribution.localLock() != 0
                || attribution.feedbackPolarity() != null) {
            return Decision.ATTRIBUTION_MISMATCH;
        }

        int payloadBoard = exactDiscoveryBoard(payload);
        if (payloadBoard == 0) {
            return Decision.PAYLOAD_NOT_ALLOWED;
        }
        if (attribution.boardAddress() != payloadBoard) {
            return Decision.ATTRIBUTION_MISMATCH;
        }
        if (discoverySent[payloadBoard]) {
            return Decision.DUPLICATE;
        }
        discoverySent[payloadBoard] = true;
        return Decision.ALLOWED;
    }

    private Decision authorizeUnlock(
            SerialWriteAttribution attribution, byte[] payload) {
        if (attribution.origin() != SerialWriteAttribution.Origin.UNLOCK
                || attribution.boardAddress() != confirmedBoardAddress
                || attribution.localLock() != confirmedLocalLock
                || attribution.feedbackPolarity() != confirmedFeedbackPolarity) {
            return Decision.ATTRIBUTION_MISMATCH;
        }
        if (expectedUnlockPayload == null
                || !Arrays.equals(expectedUnlockPayload, payload)) {
            return Decision.PAYLOAD_NOT_ALLOWED;
        }
        if (unlockSent) {
            return Decision.DUPLICATE;
        }
        unlockSent = true;
        return Decision.ALLOWED;
    }

    private Decision authorizeReturnDoorStatus(
            SerialWriteAttribution attribution, byte[] payload) {
        if (!authorizedReturnActive
                || attribution.origin() != SerialWriteAttribution.Origin.DOOR_STATUS
                || !matchesConfirmedTarget(attribution)) {
            return Decision.ATTRIBUTION_MISMATCH;
        }
        if (expectedDoorStatusPayload == null
                || !Arrays.equals(expectedDoorStatusPayload, payload)) {
            return Decision.PAYLOAD_NOT_ALLOWED;
        }
        returnStatusQuerySent = true;
        return Decision.ALLOWED;
    }

    private Decision authorizeReturnUnlock(
            SerialWriteAttribution attribution, byte[] payload) {
        if (!authorizedReturnActive
                || attribution.origin() != SerialWriteAttribution.Origin.RETURN_UNLOCK
                || !matchesConfirmedTarget(attribution)) {
            return Decision.ATTRIBUTION_MISMATCH;
        }
        if (expectedUnlockPayload == null
                || !Arrays.equals(expectedUnlockPayload, payload)) {
            return Decision.PAYLOAD_NOT_ALLOWED;
        }
        if (unlockSent) {
            return Decision.DUPLICATE;
        }
        unlockSent = true;
        return Decision.ALLOWED;
    }

    private boolean matchesConfirmedTarget(SerialWriteAttribution attribution) {
        return attribution.boardAddress() == confirmedBoardAddress
                && attribution.localLock() == confirmedLocalLock
                && attribution.feedbackPolarity() == confirmedFeedbackPolarity;
    }

    private static int exactDiscoveryBoard(byte[] payload) {
        for (int boardAddress = 1; boardAddress <= 3; boardAddress++) {
            if (Arrays.equals(BoardStatusProtocol.query(boardAddress), payload)) {
                return boardAddress;
            }
        }
        return 0;
    }
}
