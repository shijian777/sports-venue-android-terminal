package com.codex.lockertest.business.journey;

import com.codex.lockertest.business.ControlPanelPreview;
import com.codex.lockertest.model.LockerZone;
import com.codex.lockertest.protocol.FeedbackPolarity;
import com.codex.lockertest.unlock.AuthorizedUnlockRequest;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;

public final class OnlineCabinetUnlockMapperTest {
    private static final String[] BOARD_ONE_COMMANDS = {
            "8A0101119B", "8A01021198", "8A01031199", "8A0104119E",
            "8A0105119F", "8A0106119C", "8A0107119D", "8A01081192",
            "8A01091193", "8A010A1190", "8A010B1191", "8A010C1196"
    };
    private static final int[] BOARD_ONE_COMMAND_CHECKSUMS = {
            0x9B, 0x98, 0x99, 0x9E, 0x9F, 0x9C,
            0x9D, 0x92, 0x93, 0x90, 0x91, 0x96
    };
    private static final int[] BOARD_ONE_SUCCESS_CHECKSUMS = {
            0x8A, 0x89, 0x88, 0x8F, 0x8E, 0x8D,
            0x8C, 0x83, 0x82, 0x81, 0x80, 0x87
    };

    @Test
    public void mapsEveryBoardOneCommandAndReplyWithoutChangingBytes() {
        for (int index = 0; index < BOARD_ONE_COMMANDS.length; index++) {
            int channel = index + 1;
            AuthorizedUnlockRequest request = OnlineCabinetUnlockMapper.create(
                    100L + channel,
                    cabinet("server-label-" + channel, 9000L + channel,
                            "01", String.format("%02X", channel),
                            BOARD_ONE_COMMANDS[index]));

            assertEquals(100L + channel, request.operationId());
            assertEquals(LockerZone.A, request.target().zone());
            assertEquals(1, request.target().boardAddress());
            assertEquals(channel, request.target().localLock());
            assertEquals(FeedbackPolarity.SHORT_WHEN_LOCKED,
                    request.target().feedbackPolarity());
            byte[] expectedCommand = bytes(
                    0x8A, 0x01, channel, 0x11, BOARD_ONE_COMMAND_CHECKSUMS[index]);
            assertArrayEquals(expectedCommand, request.unlockCommand());
            assertArrayEquals(bytes(
                    0x8A, 0x01, channel, 0x00, BOARD_ONE_SUCCESS_CHECKSUMS[index]),
                    request.expectedSuccessFrame());
            assertArrayEquals(expectedCommand, request.expectedFailureFrame());
        }
    }

    @Test
    public void mapsTrimmedBoardTwoFromHardwareFieldsNotDisplayFields() {
        AuthorizedUnlockRequest request = OnlineCabinetUnlockMapper.create(
                77L,
                cabinet("VIP south label 777", 982734L,
                        " 02 ", " 0A ", "8a020a1193"));

        assertEquals(LockerZone.B, request.target().zone());
        assertEquals(2, request.target().boardAddress());
        assertEquals(10, request.target().localLock());
        assertArrayEquals(bytes(0x8A, 0x02, 0x0A, 0x11, 0x93),
                request.unlockCommand());
        assertArrayEquals(bytes(0x8A, 0x02, 0x0A, 0x00, 0x82),
                request.expectedSuccessFrame());
    }

    @Test
    public void acceptsOnlyCompactOrWhitespaceSeparatedFiveByteCommands() {
        assertArrayEquals(bytes(0x8A, 0x01, 0x01, 0x11, 0x9B),
                OnlineCabinetUnlockMapper.create(1L,
                        cabinet("x", 1L, "01", "01", "  8A 01 01 11 9B  "))
                        .unlockCommand());
        assertArrayEquals(bytes(0x8A, 0x03, 0x0C, 0x11, 0x94),
                OnlineCabinetUnlockMapper.create(2L,
                        cabinet("x", 1L, "03", "0c", "8A030C1194"))
                        .unlockCommand());

        expectInvalid(cabinet("x", 1L, "01", "01", "8 A 01 01 11 9B"));
        expectInvalid(cabinet("x", 1L, "01", "01", "8A 0101 11 9B"));
        expectInvalid(cabinet("x", 1L, "01", "01", "8A-01-01-11-9B"));
        expectInvalid(cabinet("x", 1L, "01", "01", "8A0101119"));
        expectInvalid(cabinet("x", 1L, "01", "01",
                "8A 01 01 11 9B 8A 01 01 11 9B"));
    }

    @Test
    public void rejectsBroadcastUnsupportedAndMismatchedHardware() {
        expectInvalid(cabinet("x", 1L, "00", "01", "8A0001119A"));
        expectInvalid(cabinet("x", 1L, "04", "01", "8A0401119E"));
        expectInvalid(cabinet("x", 1L, "01", "00", "8A0100119A"));
        expectInvalid(cabinet("x", 1L, "01", "0D", "8A010D1197"));
        expectInvalid(cabinet("x", 1L, "1", "01", "8A0101119B"));
        expectInvalid(cabinet("x", 1L, "01", "1", "8A0101119B"));
        expectInvalid(cabinet("x", 1L, "01", "01", "8A02011198"));
        expectInvalid(cabinet("x", 1L, "01", "01", "8A01021198"));
        expectInvalid(cabinet("x", 1L, "01", "01", "8A0101119A"));
        expectInvalid(cabinet("x", 1L, "01", "01", "8B0101119A"));
        expectInvalid(cabinet("x", 1L, "01", "01", "8A0101008A"));
        expectInvalid(cabinet("x", 1L, "GG", "01", "8A0101119B"));
        expectInvalid(cabinet("x", 1L, "０１", "01", "8A0101119B"));
    }

    @Test
    public void rejectsInvalidOperationAndMissingCabinet() {
        ControlPanelPreview.Cabinet cabinet = cabinet(
                "x", 1L, "01", "01", "8A0101119B");
        expectInvalid(0L, cabinet);
        expectInvalid(-1L, cabinet);
        expectInvalid(1L, null);
    }

    @Test
    public void missingHardwareFieldsAreRejectedAtDtoBoundary() {
        expectCabinetInvalid(null, "01", "8A0101119B");
        expectCabinetInvalid(" ", "01", "8A0101119B");
        expectCabinetInvalid("01", null, "8A0101119B");
        expectCabinetInvalid("01", " ", "8A0101119B");
        expectCabinetInvalid("01", "01", null);
        expectCabinetInvalid("01", "01", " ");
    }

    @Test
    public void returnedFramesAreDefensiveCopies() {
        AuthorizedUnlockRequest request = OnlineCabinetUnlockMapper.create(
                9L, cabinet("anything", Long.MAX_VALUE,
                        "01", "01", "8A0101119B"));

        byte[] firstCommand = request.unlockCommand();
        byte[] firstSuccess = request.expectedSuccessFrame();
        byte[] firstFailure = request.expectedFailureFrame();
        firstCommand[0] = 0;
        firstSuccess[0] = 0;
        firstFailure[0] = 0;

        assertNotSame(firstCommand, request.unlockCommand());
        assertNotSame(firstSuccess, request.expectedSuccessFrame());
        assertNotSame(firstFailure, request.expectedFailureFrame());
        assertArrayEquals(bytes(0x8A, 0x01, 0x01, 0x11, 0x9B),
                request.unlockCommand());
        assertArrayEquals(bytes(0x8A, 0x01, 0x01, 0x00, 0x8A),
                request.expectedSuccessFrame());
        assertArrayEquals(bytes(0x8A, 0x01, 0x01, 0x11, 0x9B),
                request.expectedFailureFrame());
    }

    private static ControlPanelPreview.Cabinet cabinet(
            String label, long areaId, String board, String channel, String command) {
        return new ControlPanelPreview.Cabinet(
                41L, 51L, label, 0, areaId, board, channel, command, 0);
    }

    private static void expectInvalid(ControlPanelPreview.Cabinet cabinet) {
        expectInvalid(1L, cabinet);
    }

    private static void expectInvalid(
            long operationId, ControlPanelPreview.Cabinet cabinet) {
        try {
            OnlineCabinetUnlockMapper.create(operationId, cabinet);
            throw new AssertionError("Expected invalid online cabinet mapping");
        } catch (IllegalArgumentException expected) {
            // Expected fail-closed outcome.
        }
    }

    private static void expectCabinetInvalid(
            String board, String channel, String command) {
        try {
            cabinet("x", 1L, board, channel, command);
            throw new AssertionError("Expected invalid cabinet DTO");
        } catch (IllegalArgumentException expected) {
            // The immutable server DTO rejects missing fields before mapper entry.
        }
    }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int index = 0; index < values.length; index++) {
            result[index] = (byte) values[index];
        }
        return result;
    }
}
