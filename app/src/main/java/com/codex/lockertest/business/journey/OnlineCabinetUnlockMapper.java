package com.codex.lockertest.business.journey;

import com.codex.lockertest.business.ControlPanelPreview;
import com.codex.lockertest.model.LockerTarget;
import com.codex.lockertest.model.LockerZone;
import com.codex.lockertest.protocol.FeedbackPolarity;
import com.codex.lockertest.protocol.LockerProtocol;
import com.codex.lockertest.unlock.AuthorizedUnlockRequest;

public final class OnlineCabinetUnlockMapper {
    private static final int FRAME_BYTES = 5;

    private OnlineCabinetUnlockMapper() {
    }

    public static AuthorizedUnlockRequest create(
            long operationId, ControlPanelPreview.Cabinet cabinet) {
        if (operationId <= 0L) {
            throw new IllegalArgumentException("Operation ID must be positive");
        }
        if (cabinet == null) {
            throw new IllegalArgumentException("Cabinet is required");
        }

        int boardAddress = decodeByte(cabinet.boardHex(), "Board address");
        LockerZone zone = zoneFor(boardAddress);
        int localLock = decodeByte(cabinet.channelNo(), "Channel number");
        if (!LockerProtocol.isValidLocker(localLock)) {
            throw new IllegalArgumentException("Unsupported channel number");
        }
        LockerTarget target = new LockerTarget(
                zone,
                boardAddress,
                localLock,
                FeedbackPolarity.SHORT_WHEN_LOCKED);
        byte[] command = decodeCommand(cabinet.openCommand());
        return new AuthorizedUnlockRequest(
                operationId,
                target,
                command,
                LockerProtocol.successFrame(target),
                LockerProtocol.failureFrame(target));
    }

    private static LockerZone zoneFor(int boardAddress) {
        switch (boardAddress) {
            case 1:
                return LockerZone.A;
            case 2:
                return LockerZone.B;
            case 3:
                return LockerZone.C;
            default:
                throw new IllegalArgumentException("Unsupported board address");
        }
    }

    private static int decodeByte(String value, String name) {
        String trimmed = trimWhitespace(value);
        if (trimmed.length() != 2) {
            throw new IllegalArgumentException(name + " must be one hexadecimal byte");
        }
        return decodePair(trimmed, 0, name);
    }

    private static byte[] decodeCommand(String value) {
        String command = trimWhitespace(value);
        if (command.length() == FRAME_BYTES * 2) {
            byte[] compact = new byte[FRAME_BYTES];
            for (int index = 0; index < FRAME_BYTES; index++) {
                compact[index] = (byte) decodePair(
                        command, index * 2, "Open command");
            }
            return compact;
        }

        byte[] separated = new byte[FRAME_BYTES];
        int offset = 0;
        for (int index = 0; index < FRAME_BYTES; index++) {
            if (offset + 2 > command.length()
                    || isWhitespace(command.charAt(offset))
                    || isWhitespace(command.charAt(offset + 1))) {
                throw new IllegalArgumentException("Open command encoding is invalid");
            }
            separated[index] = (byte) decodePair(command, offset, "Open command");
            offset += 2;
            if (index == FRAME_BYTES - 1) {
                if (offset != command.length()) {
                    throw new IllegalArgumentException("Open command must be one frame");
                }
                continue;
            }
            if (offset >= command.length() || !isWhitespace(command.charAt(offset))) {
                throw new IllegalArgumentException(
                        "Open command bytes must be whitespace-separated");
            }
            while (offset < command.length() && isWhitespace(command.charAt(offset))) {
                offset++;
            }
        }
        return separated;
    }

    private static int decodePair(String value, int offset, String name) {
        int high = hexDigit(value.charAt(offset));
        int low = hexDigit(value.charAt(offset + 1));
        if (high < 0 || low < 0) {
            throw new IllegalArgumentException(name + " contains non-hexadecimal data");
        }
        return (high << 4) | low;
    }

    private static int hexDigit(char value) {
        if (value >= '0' && value <= '9') {
            return value - '0';
        }
        if (value >= 'A' && value <= 'F') {
            return value - 'A' + 10;
        }
        if (value >= 'a' && value <= 'f') {
            return value - 'a' + 10;
        }
        return -1;
    }

    private static String trimWhitespace(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Hexadecimal value is required");
        }
        int start = 0;
        int end = value.length();
        while (start < end && isWhitespace(value.charAt(start))) {
            start++;
        }
        while (end > start && isWhitespace(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(start, end);
    }

    private static boolean isWhitespace(char value) {
        return Character.isWhitespace(value) || Character.isSpaceChar(value);
    }
}
