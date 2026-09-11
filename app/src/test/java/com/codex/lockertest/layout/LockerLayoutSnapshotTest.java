package com.codex.lockertest.layout;

import com.codex.lockertest.model.LockerTarget;
import com.codex.lockertest.model.LockerZone;
import com.codex.lockertest.protocol.FeedbackPolarity;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

public final class LockerLayoutSnapshotTest {
    @Test
    public void acceptsZeroOneAndThirtyTwoOccupiedSlotsInCallerOrder() {
        LockerLayoutSnapshot empty = new LockerLayoutSnapshot(0, Collections.<LockerArea>emptyList());
        assertEquals(0, empty.version());
        assertEquals(Collections.emptyList(), empty.areas());

        LockerSlot only = slot("slot-1", "One", 1, 1, 0);
        LockerArea oneArea = area(" area-1 ", " Area One ", page(" page-1 ", 1, 1, 1,
                Collections.singletonList(only)));
        LockerLayoutSnapshot one = new LockerLayoutSnapshot(1, Collections.singletonList(oneArea));
        assertEquals("area-1", one.areas().get(0).id());
        assertEquals("Area One", one.areas().get(0).displayName());
        assertSame(only, one.areas().get(0).pages().get(0).slotAt(1, 1));

        List<LockerSlot> slots = new ArrayList<>();
        for (int index = 0; index < 32; index++) {
            slots.add(slot("slot-" + index, "L" + index, index / 8 + 1, index % 8 + 1, index));
        }
        LockerPage page = page("full", 1, 4, 8, slots);
        LockerLayoutSnapshot full = new LockerLayoutSnapshot(2,
                Collections.singletonList(area("a", "A", page)));
        assertEquals(32, full.areas().get(0).pages().get(0).slots().size());
        assertEquals("slot-31", full.areas().get(0).pages().get(0).slots().get(31).id());
    }

    @Test
    public void rejectsThirtyThirdSlotInFourByEightPage() {
        List<LockerSlot> slots = new ArrayList<>();
        for (int index = 0; index < 33; index++) {
            slots.add(slot("slot-" + index, "L" + index, 1, 1, index));
        }
        expectIllegalArgument(new Runnable() {
            @Override
            public void run() {
                page("full", 1, 4, 8, slots);
            }
        });
    }

    @Test
    public void rejectsOutOfBoundsRowsAndColumns() {
        expectIllegalArgument(new Runnable() {
            @Override public void run() { page("p", 1, 5, 1, Collections.<LockerSlot>emptyList()); }
        });
        expectIllegalArgument(new Runnable() {
            @Override public void run() { page("p", 1, 1, 9, Collections.<LockerSlot>emptyList()); }
        });
        expectIllegalArgument(new Runnable() {
            @Override public void run() { page("p", 1, 4, 8, Collections.singletonList(slot("s", "S", 5, 1, 0))); }
        });
        expectIllegalArgument(new Runnable() {
            @Override public void run() { page("p", 1, 4, 8, Collections.singletonList(slot("s", "S", 1, 9, 0))); }
        });
    }

    @Test
    public void rejectsDuplicateAreaPageSlotAndCoordinateIdentifiers() {
        final LockerArea duplicateAreas = area("a", "A", page("p", 1, 1, 1, Collections.<LockerSlot>emptyList()));
        expectIllegalArgument(new Runnable() {
            @Override public void run() { new LockerLayoutSnapshot(0, Arrays.asList(duplicateAreas, duplicateAreas)); }
        });
        expectIllegalArgument(new Runnable() {
            @Override public void run() {
                new LockerArea("a", "A", Arrays.asList(
                        page("p", 1, 1, 1, Collections.<LockerSlot>emptyList()),
                        page("p", 2, 1, 1, Collections.<LockerSlot>emptyList())));
            }
        });
        expectIllegalArgument(new Runnable() {
            @Override public void run() {
                new LockerArea("a", "A", Arrays.asList(
                        page("p1", 1, 1, 1, Collections.<LockerSlot>emptyList()),
                        page("p2", 1, 1, 1, Collections.<LockerSlot>emptyList())));
            }
        });
        expectIllegalArgument(new Runnable() {
            @Override public void run() {
                page("p", 1, 1, 2, Arrays.asList(slot("s", "S", 1, 1, 0), slot("s", "T", 1, 2, 1)));
            }
        });
        expectIllegalArgument(new Runnable() {
            @Override public void run() {
                page("p", 1, 1, 2, Arrays.asList(slot("s1", "S", 1, 1, 0), slot("s2", "T", 1, 1, 1)));
            }
        });
    }

    @Test
    public void rejectsDuplicateSlotIdsAndPhysicalTargetsAcrossSnapshot() {
        final LockerArea first = area("a", "A", page("p1", 1, 1, 1,
                Collections.singletonList(slot("same", "S", 1, 1, 0))));
        final LockerArea second = area("b", "B", page("p2", 1, 1, 1,
                Collections.singletonList(slot("same", "T", 1, 1, 1))));
        expectIllegalArgument(new Runnable() {
            @Override public void run() { new LockerLayoutSnapshot(0, Arrays.asList(first, second)); }
        });

        final LockerArea targetOne = area("a", "A", page("p1", 1, 1, 1,
                Collections.singletonList(new LockerSlot("s1", "S", 1, 1, false, target(0)))));
        final LockerArea targetTwo = area("b", "B", page("p2", 1, 1, 1,
                Collections.singletonList(new LockerSlot("s2", "T", 1, 1, true, target(0)))));
        expectIllegalArgument(new Runnable() {
            @Override public void run() { new LockerLayoutSnapshot(0, Arrays.asList(targetOne, targetTwo)); }
        });
    }

    @Test
    public void acceptsCrossBoardSlotsWithinOneArea() {
        LockerPage first = page("p1", 1, 1, 1, Collections.singletonList(slot("s1", "A", 1, 1, 0)));
        LockerPage second = page("p2", 2, 1, 1, Collections.singletonList(slot("s2", "B", 1, 1, 12)));
        LockerLayoutSnapshot snapshot = new LockerLayoutSnapshot(0,
                Collections.singletonList(area("a", "A", first, second)));
        assertEquals(1, snapshot.areas().size());
        assertEquals(2, snapshot.areas().get(0).pages().size());
    }

    @Test
    public void rejectsInvalidVersionsAndBlankOrOverlongStrings() {
        expectIllegalArgument(new Runnable() {
            @Override public void run() { new LockerLayoutSnapshot(-1, Collections.<LockerArea>emptyList()); }
        });
        expectIllegalArgument(new Runnable() {
            @Override public void run() { new LockerArea(" ", "A", Collections.<LockerPage>emptyList()); }
        });
        expectIllegalArgument(new Runnable() {
            @Override public void run() { new LockerArea("a", repeat('x', 21), Collections.<LockerPage>emptyList()); }
        });
        expectIllegalArgument(new Runnable() {
            @Override public void run() { page(" ", 1, 1, 1, Collections.<LockerSlot>emptyList()); }
        });
        expectIllegalArgument(new Runnable() {
            @Override public void run() { new LockerSlot(" ", "S", 1, 1, true, target(0)); }
        });
        expectIllegalArgument(new Runnable() {
            @Override public void run() { new LockerSlot("s", repeat('x', 21), 1, 1, true, target(0)); }
        });
        expectIllegalArgument(new Runnable() {
            @Override public void run() { new LockerSlot("s", "S", 1, 1, true, null); }
        });
    }

    @Test
    public void sparseSlotAtReturnsNullAndDisabledSlotStaysVisible() {
        LockerSlot disabled = new LockerSlot("s", "Disabled", 2, 2, false, target(0));
        LockerPage page = page("p", 1, 2, 2, Collections.singletonList(disabled));
        assertNull(page.slotAt(1, 1));
        assertSame(disabled, page.slotAt(2, 2));
        assertFalse(disabled.enabled());
    }

    @Test
    public void usesDefensiveUnmodifiableCopiesAtEveryLevel() {
        List<LockerSlot> callerSlots = new ArrayList<>(Collections.singletonList(slot("s", "S", 1, 1, 0)));
        LockerPage page = page("p", 1, 1, 1, callerSlots);
        callerSlots.clear();
        List<LockerPage> callerPages = new ArrayList<>(Collections.singletonList(page));
        LockerArea area = area("a", "A", callerPages);
        callerPages.clear();
        List<LockerArea> callerAreas = new ArrayList<>(Collections.singletonList(area));
        LockerLayoutSnapshot snapshot = new LockerLayoutSnapshot(0, callerAreas);
        callerAreas.clear();

        assertEquals(1, snapshot.areas().size());
        assertEquals(1, snapshot.areas().get(0).pages().size());
        assertEquals(1, snapshot.areas().get(0).pages().get(0).slots().size());
        expectUnsupported(new Runnable() {
            @Override public void run() { snapshot.areas().clear(); }
        });
        expectUnsupported(new Runnable() {
            @Override public void run() { area.pages().clear(); }
        });
        expectUnsupported(new Runnable() {
            @Override public void run() { page.slots().clear(); }
        });
    }

    private static LockerArea area(String id, String displayName, LockerPage... pages) {
        return new LockerArea(id, displayName, Arrays.asList(pages));
    }

    private static LockerArea area(String id, String displayName, List<LockerPage> pages) {
        return new LockerArea(id, displayName, pages);
    }

    private static LockerPage page(String id, int pageNumber, int rows, int columns, List<LockerSlot> slots) {
        return new LockerPage(id, pageNumber, rows, columns, slots);
    }

    private static LockerSlot slot(String id, String label, int row, int column, int targetIndex) {
        return new LockerSlot(id, label, row, column, true, target(targetIndex));
    }

    private static LockerTarget target(int index) {
        LockerZone zone = LockerZone.values()[index / 12];
        return new LockerTarget(zone, zone.boardAddress(), index % 12 + 1,
                FeedbackPolarity.SHORT_WHEN_LOCKED);
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < count; index++) {
            result.append(value);
        }
        return result.toString();
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
