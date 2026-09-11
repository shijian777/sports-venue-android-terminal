package com.codex.lockertest.runtime;

import com.codex.lockertest.layout.LockerArea;
import com.codex.lockertest.layout.LockerLayoutSnapshot;
import com.codex.lockertest.layout.LockerLayoutSource;
import com.codex.lockertest.layout.LockerPage;
import com.codex.lockertest.layout.LockerSlot;
import com.codex.lockertest.model.LockerTarget;
import com.codex.lockertest.model.LockerZone;
import com.codex.lockertest.protocol.FeedbackPolarity;
import com.codex.lockertest.protocol.LockerProtocol;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class LocalDemoLegacyLayoutSourceTest {
    @Test
    public void alwaysCreatesTheExactThreeAreaFourByEightColumnMajorCompatibilityLayout() {
        LockerLayoutSnapshot snapshot = new LocalDemoLegacyLayoutSource()
                .layoutFor(Collections.<LockerZone>emptyList());

        assertEquals(0, snapshot.version());
        assertEquals(3, snapshot.areas().size());
        for (int areaIndex = 0; areaIndex < 3; areaIndex++) {
            LockerZone zone = LockerZone.values()[areaIndex];
            String prefix = zone.name();
            LockerArea area = snapshot.areas().get(areaIndex);
            assertEquals("legacy-area-" + prefix, area.id());
            assertEquals(prefix + "区", area.displayName());
            assertEquals(1, area.pages().size());

            LockerPage page = area.pages().get(0);
            assertEquals("legacy-page-" + prefix + "-1", page.id());
            assertEquals(1, page.pageNumber());
            assertEquals(4, page.rows());
            assertEquals(8, page.columns());
            assertEquals(12, page.slots().size());
            for (int lock = 1; lock <= 12; lock++) {
                int row = (lock - 1) % 4 + 1;
                int column = (lock - 1) / 4 + 1;
                LockerSlot slot = page.slotAt(row, column);
                assertNotNull(slot);
                assertEquals("legacy-slot-" + prefix + "-" + twoDigits(lock), slot.id());
                assertEquals(prefix + lock, slot.displayLabel());
                assertFalse(slot.enabled());
            }
            for (int row = 1; row <= 4; row++) {
                for (int column = 4; column <= 8; column++) {
                    assertNull(page.slotAt(row, column));
                }
            }
        }
    }

    @Test
    public void availabilityUsesOnlyTheOnlineZonesBitmaskWithoutReorderingOrCompactingAreas() {
        assertAvailability(Collections.<LockerZone>emptyList(), 0, false, false, false);
        assertAvailability(Collections.singletonList(LockerZone.A), 1, true, false, false);
        assertAvailability(Collections.singletonList(LockerZone.B), 2, false, true, false);
        assertAvailability(Collections.singletonList(LockerZone.C), 4, false, false, true);
        assertAvailability(Arrays.asList(LockerZone.C, LockerZone.A), 5, true, false, true);
        assertAvailability(Arrays.asList(LockerZone.C, LockerZone.B, LockerZone.A), 7, true, true, true);
        assertAvailability(Arrays.asList(LockerZone.B, LockerZone.B, LockerZone.A, LockerZone.B),
                3, true, true, false);
    }

    @Test
    public void everySlotHasItsLegacyTargetAndProducesTheExpectedUnlockFrameAndXor() {
        LockerLayoutSnapshot snapshot = new LocalDemoLegacyLayoutSource()
                .layoutFor(Arrays.asList(LockerZone.A, LockerZone.B, LockerZone.C));

        for (int areaIndex = 0; areaIndex < 3; areaIndex++) {
            LockerZone zone = LockerZone.values()[areaIndex];
            LockerPage page = snapshot.areas().get(areaIndex).pages().get(0);
            for (int lock = 1; lock <= 12; lock++) {
                int row = (lock - 1) % 4 + 1;
                int column = (lock - 1) / 4 + 1;
                LockerSlot slot = page.slotAt(row, column);
                LockerTarget target = slot.target();
                assertEquals(zone, target.zone());
                assertEquals(zone.boardAddress(), target.boardAddress());
                assertEquals(lock, target.localLock());
                assertEquals(FeedbackPolarity.SHORT_WHEN_LOCKED, target.feedbackPolarity());
                assertEquals(zone.name() + lock, target.customerLabel());
                assertArrayEquals(unlockFrame(zone.boardAddress(), lock), LockerProtocol.unlockCommand(target));
            }
        }
    }

    @Test
    public void doesNotRetainCallerAvailabilityListOrExposeMutableLayoutLists() {
        List<LockerZone> online = new ArrayList<>(Collections.singletonList(LockerZone.A));
        LockerLayoutSnapshot snapshot = new LocalDemoLegacyLayoutSource().layoutFor(online);
        online.clear();
        online.add(LockerZone.C);

        assertEquals(1, snapshot.version());
        assertTrue(snapshot.areas().get(0).pages().get(0).slotAt(1, 1).enabled());
        assertFalse(snapshot.areas().get(2).pages().get(0).slotAt(1, 1).enabled());
        expectUnsupported(new Runnable() {
            @Override public void run() { snapshot.areas().clear(); }
        });
    }

    @Test
    public void rejectsNullAvailabilityAndNullAvailabilityElements() {
        final LockerLayoutSource source = new LocalDemoLegacyLayoutSource();
        expectIllegalArgument(new Runnable() {
            @Override public void run() { source.layoutFor(null); }
        });
        expectIllegalArgument(new Runnable() {
            @Override public void run() { source.layoutFor(Arrays.asList(LockerZone.A, null)); }
        });
    }

    @Test
    public void sourceHasNoInstanceFieldsAndReturnsIndependentSnapshots() {
        assertEquals(0, LocalDemoLegacyLayoutSource.class.getDeclaredFields().length);
        LockerLayoutSource source = new LocalDemoLegacyLayoutSource();
        LockerLayoutSnapshot first = source.layoutFor(Collections.singletonList(LockerZone.A));
        LockerLayoutSnapshot second = source.layoutFor(Collections.singletonList(LockerZone.A));
        assertEquals(first.version(), second.version());
        assertEquals(first.areas().size(), second.areas().size());
        assertFalse(first == second);
        assertFalse(first.areas().get(0) == second.areas().get(0));
    }

    private static void assertAvailability(List<LockerZone> online, long version,
            boolean aEnabled, boolean bEnabled, boolean cEnabled) {
        LockerLayoutSnapshot snapshot = new LocalDemoLegacyLayoutSource().layoutFor(online);
        assertEquals(version, snapshot.version());
        boolean[] expected = {aEnabled, bEnabled, cEnabled};
        for (int areaIndex = 0; areaIndex < 3; areaIndex++) {
            LockerArea area = snapshot.areas().get(areaIndex);
            assertEquals("legacy-area-" + LockerZone.values()[areaIndex].name(), area.id());
            for (LockerSlot slot : area.pages().get(0).slots()) {
                assertEquals(expected[areaIndex], slot.enabled());
            }
        }
    }

    private static byte[] unlockFrame(int boardAddress, int localLock) {
        byte header = (byte) 0x8A;
        byte feedback = 0x11;
        return new byte[] {header, (byte) boardAddress, (byte) localLock, feedback,
                (byte) (header ^ boardAddress ^ localLock ^ feedback)};
    }

    private static String twoDigits(int value) {
        return value < 10 ? "0" + value : String.valueOf(value);
    }

    private static void expectIllegalArgument(Runnable action) {
        try {
            action.run();
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Expected validation failure.
        }
    }

    private static void expectUnsupported(Runnable action) {
        try {
            action.run();
            fail("Expected UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            // Expected immutable view.
        }
    }
}
