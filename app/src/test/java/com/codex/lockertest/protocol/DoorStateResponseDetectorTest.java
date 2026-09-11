package com.codex.lockertest.protocol;

import com.codex.lockertest.model.LockerTarget;
import com.codex.lockertest.model.LockerZone;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class DoorStateResponseDetectorTest {
    @Test
    public void pollResponsesMapEachPolarityToThePhysicalDoorState() {
        DoorStateResponseDetector locked = detector(target(LockerZone.A, 1,
                FeedbackPolarity.SHORT_WHEN_LOCKED));
        List<DoorStateResponseDetector.Event> lockedEvents = locked.append(
                bytes(0x80, 0x01, 0x01, 0x00, 0x80, 0x80, 0x01, 0x01, 0x11, 0x91), 10)
                .events();
        assertEquals(DoorStateResponseDetector.PhysicalState.OPEN,
                lockedEvents.get(0).physicalState());
        assertEquals(DoorStateResponseDetector.PhysicalState.CLOSED,
                lockedEvents.get(1).physicalState());
        assertEquals(DoorStateResponseDetector.Source.POLL_RESPONSE, lockedEvents.get(0).source());

        DoorStateResponseDetector opened = detector(target(LockerZone.B, 2,
                FeedbackPolarity.SHORT_WHEN_OPEN));
        List<DoorStateResponseDetector.Event> openedEvents = opened.append(
                bytes(0x80, 0x02, 0x02, 0x00, 0x80, 0x80, 0x02, 0x02, 0x11, 0x91), 10)
                .events();
        assertEquals(DoorStateResponseDetector.PhysicalState.CLOSED,
                openedEvents.get(0).physicalState());
        assertEquals(DoorStateResponseDetector.PhysicalState.OPEN,
                openedEvents.get(1).physicalState());
    }

    @Test
    public void fragmentedNoiseAndInvalidFramesDoNotHideLaterPollAndPushEvents() {
        DoorStateResponseDetector detector = detector(target(LockerZone.A, 1,
                FeedbackPolarity.SHORT_WHEN_LOCKED));

        assertTrue(detector.append(bytes(0x55, 0x80, 0x01), 3).events().isEmpty());
        DoorStateResponseDetector.AppendResult result = detector.append(bytes(
                0x01, 0x00, 0x81,
                0x80, 0x01, 0x01, 0x00, 0x80,
                0x82, 0x01, 0x01, 0x11, 0x93), 13);

        assertEquals(2, result.events().size());
        assertEquals(DoorStateResponseDetector.Source.POLL_RESPONSE, result.events().get(0).source());
        assertEquals(DoorStateResponseDetector.PhysicalState.OPEN,
                result.events().get(0).physicalState());
        assertEquals(DoorStateResponseDetector.Source.ACTIVE_PUSH, result.events().get(1).source());
        assertEquals(DoorStateResponseDetector.PhysicalState.CLOSED,
                result.events().get(1).physicalState());
        assertEquals(1, result.signals().size());
        assertEquals(DoorStateResponseDetector.Signal.REQUEST_IMMEDIATE_POLL,
                result.signals().get(0));
    }

    @Test
    public void wrongAddressLockStateChecksumAndHeaderAreIgnoredWithoutResettingTheStream() {
        DoorStateResponseDetector detector = detector(target(LockerZone.B, 2,
                FeedbackPolarity.SHORT_WHEN_LOCKED));

        DoorStateResponseDetector.AppendResult result = detector.append(bytes(
                0x80, 0x01, 0x02, 0x00, 0x83,
                0x80, 0x02, 0x01, 0x00, 0x83,
                0x80, 0x02, 0x02, 0x33, 0xB3,
                0x80, 0x02, 0x02, 0x00, 0x81,
                0x8A, 0x02, 0x02, 0x00, 0x8A,
                0x80, 0x02, 0x02, 0x11, 0x91), 30);

        assertEquals(1, result.events().size());
        assertEquals(DoorStateResponseDetector.PhysicalState.CLOSED,
                result.events().get(0).physicalState());
    }

    @Test(expected = IllegalArgumentException.class)
    public void appendRejectsALengthOutsideTheProvidedArray() {
        detector(target(LockerZone.A, 1, FeedbackPolarity.SHORT_WHEN_LOCKED))
                .append(bytes(0x80), 2);
    }

    private static DoorStateResponseDetector detector(LockerTarget target) {
        return new DoorStateResponseDetector(target);
    }

    private static LockerTarget target(LockerZone zone, int localLock, FeedbackPolarity polarity) {
        return new LockerTarget(zone, zone.boardAddress(), localLock, polarity);
    }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int index = 0; index < values.length; index++) {
            result[index] = (byte) values[index];
        }
        return result;
    }
}
