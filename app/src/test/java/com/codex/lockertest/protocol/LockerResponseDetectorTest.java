package com.codex.lockertest.protocol;

import static org.junit.Assert.assertEquals;

import com.codex.lockertest.model.LockerTarget;
import com.codex.lockertest.model.LockerZone;

import org.junit.Test;

public final class LockerResponseDetectorTest {
    @Test
    public void adminA1CompatibilityMatcherFindsSplitSuccessAfterRawNoise() {
        LockerResponseDetector detector = new LockerResponseDetector(new LockerTarget(
                LockerZone.A, 1, 1, FeedbackPolarity.SHORT_WHEN_LOCKED));

        assertEquals(LockerResponseDetector.Result.NONE,
                detector.append(HexCodec.decode("558A0101"), 4));
        assertEquals(LockerResponseDetector.Result.SUCCESS,
                detector.append(HexCodec.decode("008A"), 2));
    }

    @Test
    public void splitSelectedTargetSuccessIsDetected() {
        LockerResponseDetector detector = new LockerResponseDetector(targetA12LockedShort());

        assertEquals(LockerResponseDetector.Result.NONE, detector.append(HexCodec.decode("8A01"), 2));
        assertEquals(LockerResponseDetector.Result.SUCCESS, detector.append(HexCodec.decode("0C0087"), 3));
    }

    @Test
    public void onlyTheSelectedTargetAndPolarityProduceTerminalResults() {
        LockerResponseDetector detector = new LockerResponseDetector(targetB12LockedShort());
        byte[] unrelatedFrames = HexCodec.decode(
                "8A010C0087" + "8A020B0085" + "8A020C008A" + "8A020C1190" + "8201021190");

        assertEquals(LockerResponseDetector.Result.NONE, detector.append(unrelatedFrames, unrelatedFrames.length));
        assertEquals(LockerResponseDetector.Result.SUCCESS, detector.append(HexCodec.decode("8A020C0084"), 5));
    }

    @Test
    public void openShortTargetTreatsActiveFeedbackAsSuccessAndInactiveAsFailure() {
        LockerResponseDetector detector = new LockerResponseDetector(new LockerTarget(
                LockerZone.B, 2, 12, FeedbackPolarity.SHORT_WHEN_OPEN));

        assertEquals(LockerResponseDetector.Result.SUCCESS, detector.append(HexCodec.decode("8A020C1195"), 5));
        detector.reset();
        assertEquals(LockerResponseDetector.Result.FAILURE, detector.append(HexCodec.decode("8A020C0084"), 5));
    }

    @Test
    public void earliestCompleteTerminalFrameWinsWithinOneChunk() {
        LockerResponseDetector detector = new LockerResponseDetector(targetB12LockedShort());

        assertEquals(LockerResponseDetector.Result.SUCCESS,
                detector.append(HexCodec.decode("8A020C00848A020C1195"), 10));
        detector.reset();
        assertEquals(LockerResponseDetector.Result.FAILURE,
                detector.append(HexCodec.decode("8A020C11958A020C0084"), 10));
    }

    @Test
    public void staleFixedChecksumAndBadBccFramesRemainNone() {
        LockerResponseDetector detector = new LockerResponseDetector(targetB12LockedShort());
        byte[] invalidFrames = HexCodec.decode("8A020C008A8A020C11968A020C0085");

        assertEquals(LockerResponseDetector.Result.NONE, detector.append(invalidFrames, invalidFrames.length));
    }

    @Test
    public void noiseAndWrongBoardAreIgnoredBeforeAValidFrame() {
        LockerResponseDetector detector = new LockerResponseDetector(targetC12LockedShort());
        byte[] noiseAndWrongBoard = HexCodec.decode("55AA8A020C0084778A010C1196");

        assertEquals(LockerResponseDetector.Result.NONE,
                detector.append(noiseAndWrongBoard, noiseAndWrongBoard.length));
        assertEquals(LockerResponseDetector.Result.SUCCESS, detector.append(HexCodec.decode("8A030C0085"), 5));
    }

    @Test
    public void resetDropsPartialFrameState() {
        LockerResponseDetector detector = new LockerResponseDetector(targetA12LockedShort());

        assertEquals(LockerResponseDetector.Result.NONE, detector.append(HexCodec.decode("8A01"), 2));
        detector.reset();
        assertEquals(LockerResponseDetector.Result.NONE, detector.append(HexCodec.decode("0C0087"), 3));
    }

    @Test
    public void appendConsumesOnlyTheSuppliedLength() {
        LockerResponseDetector detector = new LockerResponseDetector(targetA12LockedShort());
        byte[] frameWithIgnoredTail = HexCodec.decode("8A010C0087");

        assertEquals(LockerResponseDetector.Result.NONE, detector.append(frameWithIgnoredTail, 2));
    }

    @Test(expected = IllegalArgumentException.class)
    public void appendRejectsLengthPastBuffer() {
        new LockerResponseDetector(targetA12LockedShort()).append(new byte[]{0x01}, 2);
    }

    @Test(expected = IllegalArgumentException.class)
    public void appendRejectsNullBuffer() {
        new LockerResponseDetector(targetA12LockedShort()).append(null, 0);
    }

    private static LockerTarget targetA12LockedShort() {
        return new LockerTarget(LockerZone.A, 1, 12, FeedbackPolarity.SHORT_WHEN_LOCKED);
    }

    private static LockerTarget targetB12LockedShort() {
        return new LockerTarget(LockerZone.B, 2, 12, FeedbackPolarity.SHORT_WHEN_LOCKED);
    }

    private static LockerTarget targetC12LockedShort() {
        return new LockerTarget(LockerZone.C, 3, 12, FeedbackPolarity.SHORT_WHEN_LOCKED);
    }
}
