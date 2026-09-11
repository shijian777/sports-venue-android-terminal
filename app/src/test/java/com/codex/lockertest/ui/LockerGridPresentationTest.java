package com.codex.lockertest.ui;

import com.codex.lockertest.layout.LockerArea;
import com.codex.lockertest.layout.LockerLayoutSnapshot;
import com.codex.lockertest.layout.LockerPage;
import com.codex.lockertest.layout.LockerSlot;
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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class LockerGridPresentationTest {
    @Test
    public void sixAreasStayInStableWindowsOfFourAndRequestedWindowIsClamped() {
        LockerSelectionModel model = ready(snapshot(
                area("area-a", "甲区", enabledPage("page-a", "slot-a", "A1", 1)),
                area("area-b", "乙区", enabledPage("page-b", "slot-b", "B1", 2)),
                area("area-c", "丙区", enabledPage("page-c", "slot-c", "C1", 3)),
                area("area-d", "丁区", enabledPage("page-d", "slot-d", "D1", 4)),
                area("area-e", "戊区", enabledPage("page-e", "slot-e", "E1", 5)),
                area("area-f", "己区", enabledPage("page-f", "slot-f", "F1", 6))));

        LockerGridPresentation first = LockerGridPresentation.create(model, -9);
        assertEquals(0, first.areaWindowPage());
        assertEquals(2, first.areaWindowCount());
        assertFalse(first.canPreviousAreaWindow());
        assertTrue(first.canNextAreaWindow());
        assertEquals(Arrays.asList("area-a", "area-b", "area-c", "area-d"),
                areaIds(first.visibleAreas()));
        assertTrue(first.visibleAreas().get(0).selected());
        assertTrue(first.visibleAreas().get(0).available());
        assertTrue(first.visibleAreas().get(0).clickable());

        LockerGridPresentation second = LockerGridPresentation.create(model, 1);
        assertEquals(1, second.areaWindowPage());
        assertEquals(Arrays.asList("area-e", "area-f"), areaIds(second.visibleAreas()));
        assertTrue(second.canPreviousAreaWindow());
        assertFalse(second.canNextAreaWindow());

        assertEquals(1, LockerGridPresentation.create(model, 99).areaWindowPage());
    }

    @Test
    public void unicodeShorteningKeepsStableIdsAndFullAccessibleLabels() {
        String areaLabel = "甲乙丙丁戊己庚辛壬癸😀尾巴";
        String slotLabel = "甲乙丙丁戊己😀尾巴";
        LockerSelectionModel model = ready(snapshot(area("unicode-area", areaLabel,
                page("unicode-page", 1, 1, 1,
                        slot("unicode-slot", slotLabel, 1, 1, true, 1)))));

        LockerGridPresentation presentation = LockerGridPresentation.create(model, 0);
        LockerGridPresentation.AreaItem area = presentation.visibleAreas().get(0);
        LockerGridPresentation.Cell cell = presentation.cellAt(1, 1);

        assertEquals("unicode-area", area.id());
        assertEquals("甲乙丙丁戊己庚辛壬癸😀…", area.text());
        assertEquals(areaLabel + "，已选择", area.contentDescription());
        assertEquals("unicode-slot", cell.slotId());
        assertEquals("甲乙丙丁戊己😀…", cell.text());
        assertEquals(slotLabel + "，可选择", cell.contentDescription());
        assertEquals(12, area.text().codePointCount(0, area.text().length()));
        assertEquals(8, cell.text().codePointCount(0, cell.text().length()));
        assertFalse(Character.isHighSurrogate(area.text().charAt(area.text().length() - 2)));
        assertFalse(Character.isHighSurrogate(cell.text().charAt(cell.text().length() - 2)));
    }

    @Test
    public void cellsAlwaysExposeThirtyTwoRowMajorCoordinatesWithoutCompactingSparseSlots() {
        LockerSelectionModel model = ready(snapshot(area("sparse-area", "稀疏区",
                page("sparse-page", 1, 2, 3,
                        slot("available-one", "甲一", 1, 1, true, 1),
                        slot("disabled-three", "甲三", 1, 3, false, 2),
                        slot("available-five", "乙二", 2, 2, true, 3)))));

        LockerGridPresentation presentation = LockerGridPresentation.create(model, 0);

        assertEquals(2, presentation.pageRows());
        assertEquals(3, presentation.pageColumns());
        assertEquals(32, presentation.cells().size());
        for (int index = 0; index < presentation.cells().size(); index++) {
            LockerGridPresentation.Cell cell = presentation.cells().get(index);
            assertEquals(index / 8 + 1, cell.row());
            assertEquals(index % 8 + 1, cell.column());
            assertTrue(cell == presentation.cellAt(cell.row(), cell.column()));
        }

        assertEquals(LockerGridPresentation.CellState.AVAILABLE,
                presentation.cellAt(1, 1).state());
        assertTrue(presentation.cellAt(1, 1).clickable());
        assertEquals(LockerGridPresentation.CellState.EMPTY,
                presentation.cellAt(1, 2).state());
        assertNull(presentation.cellAt(1, 2).slotId());
        assertEquals("第1行第2列，空位", presentation.cellAt(1, 2).contentDescription());
        assertFalse(presentation.cellAt(1, 2).clickable());
        assertEquals(LockerGridPresentation.CellState.DISABLED,
                presentation.cellAt(1, 3).state());
        assertEquals("disabled-three", presentation.cellAt(1, 3).slotId());
        assertEquals("甲三，暂不可用", presentation.cellAt(1, 3).contentDescription());
        assertFalse(presentation.cellAt(1, 3).clickable());
        assertEquals("available-five", presentation.cellAt(2, 2).slotId());
        assertEquals(LockerGridPresentation.CellState.EMPTY,
                presentation.cellAt(3, 1).state());
        assertEquals(4, presentation.visibleGridRowCount());

        assertTrue(model.selectSlot("available-five"));
        LockerGridPresentation selected = LockerGridPresentation.create(model, 0);
        assertEquals(LockerGridPresentation.CellState.SELECTED,
                selected.cellAt(2, 2).state());
        assertEquals("乙二，已选择", selected.cellAt(2, 2).contentDescription());
        assertTrue(selected.cellAt(2, 2).clickable());
    }

    @Test
    public void compatibilityAreaVisiblyFillsAllThirtyTwoColumnMajorLockerLabels() {
        LockerSelectionModel model = new LockerSelectionModel(
                RuntimePolicyFixtures.legacyLayoutPolicy());
        assertTrue(model.beginDiscovery());
        assertTrue(model.applyDiscoverySnapshot(Collections.singletonList(LockerZone.A)));
        LockerGridPresentation presentation = LockerGridPresentation.create(model, 0);

        assertEquals("A1", presentation.cellAt(1, 1).text());
        assertEquals("A4", presentation.cellAt(4, 1).text());
        assertEquals("A5", presentation.cellAt(1, 2).text());
        assertEquals("A12", presentation.cellAt(4, 3).text());

        LockerGridPresentation.Cell firstUnconfigured = presentation.cellAt(1, 4);
        assertEquals("A13", firstUnconfigured.text());
        assertEquals(LockerGridPresentation.CellState.UNCONFIGURED,
                firstUnconfigured.state());
        assertNull(firstUnconfigured.slotId());
        assertFalse(firstUnconfigured.clickable());
        assertEquals("A13，暂不可用", firstUnconfigured.contentDescription());

        LockerGridPresentation.Cell last = presentation.cellAt(4, 8);
        assertEquals("A32", last.text());
        assertEquals(LockerGridPresentation.CellState.UNCONFIGURED, last.state());
        assertFalse(last.clickable());

        int visibleLabels = 0;
        for (LockerGridPresentation.Cell cell : presentation.cells()) {
            if (cell.text().length() > 0) {
                visibleLabels++;
            }
        }
        assertEquals(32, visibleLabels);
    }

    @Test
    public void sendingRetainsSelectedVisualsWhileFreezingEveryPresentedControl() {
        LockerArea first = area("first-area", "一区",
                page("first-page", 1, 1, 2,
                        slot("chosen", "选中柜", 1, 1, true, 1),
                        slot("other", "其他柜", 1, 2, true, 2)),
                enabledPage("next-page", "next-slot", "下一页柜", 3));
        LockerSelectionModel model = ready(snapshot(
                first,
                area("second-area", "二区", enabledPage("p2", "s2", "二", 4)),
                area("third-area", "三区", enabledPage("p3", "s3", "三", 5)),
                area("fourth-area", "四区", enabledPage("p4", "s4", "四", 6)),
                area("fifth-area", "五区", enabledPage("p5", "s5", "五", 7))));
        assertTrue(model.selectSlot("chosen"));
        assertTrue(model.beginSending());

        LockerGridPresentation presentation = LockerGridPresentation.create(model, 0);

        assertFalse(presentation.interactionEnabled());
        assertFalse(presentation.canPreviousAreaWindow());
        assertFalse(presentation.canNextAreaWindow());
        assertFalse(presentation.canPreviousPage());
        assertFalse(presentation.canNextPage());
        for (LockerGridPresentation.AreaItem item : presentation.visibleAreas()) {
            assertFalse(item.clickable());
        }
        for (LockerGridPresentation.Cell cell : presentation.cells()) {
            assertFalse(cell.clickable());
        }
        assertEquals(LockerGridPresentation.CellState.SELECTED,
                presentation.cellAt(1, 1).state());
        assertEquals("选中柜，已选择", presentation.cellAt(1, 1).contentDescription());
    }

    @Test
    public void pageCounterUsesSourcePositionForNonContiguousServerPageNumbers() {
        LockerSelectionModel model = ready(snapshot(area("paged-area", "分页区",
                page("server-thirty", 30, 1, 1,
                        slot("page-one-slot", "一", 1, 1, true, 1)),
                page("server-ten", 10, 2, 2,
                        slot("page-two-slot", "二", 2, 2, true, 2)),
                page("server-ninety", 90, 4, 8,
                        slot("page-three-slot", "三", 4, 8, true, 3)))));

        LockerGridPresentation first = LockerGridPresentation.create(model, 0);
        assertEquals("第 1 / 3 页", first.pageCounterText());
        assertEquals(1, first.pageRows());
        assertEquals(1, first.pageColumns());
        assertFalse(first.canPreviousPage());
        assertTrue(first.canNextPage());

        assertTrue(model.nextPage());
        LockerGridPresentation second = LockerGridPresentation.create(model, 0);
        assertEquals("第 2 / 3 页", second.pageCounterText());
        assertEquals(2, second.pageRows());
        assertEquals(2, second.pageColumns());
        assertTrue(second.canPreviousPage());
        assertTrue(second.canNextPage());

        assertTrue(model.nextPage());
        LockerGridPresentation third = LockerGridPresentation.create(model, 0);
        assertEquals("第 3 / 3 页", third.pageCounterText());
        assertEquals(4, third.pageRows());
        assertEquals(8, third.pageColumns());
        assertTrue(third.canPreviousPage());
        assertFalse(third.canNextPage());
    }

    @Test
    public void detectingStateHasNoActivePageAndThirtyTwoEmptyCoordinates() {
        LockerSelectionModel model = new LockerSelectionModel(RuntimePolicyFixtures.legacyLayoutPolicy());
        assertTrue(model.beginDiscovery());

        LockerGridPresentation presentation = LockerGridPresentation.create(model, 12);

        assertTrue(presentation.interactionEnabled());
        assertEquals(0, presentation.areaWindowPage());
        assertEquals(0, presentation.areaWindowCount());
        assertTrue(presentation.visibleAreas().isEmpty());
        assertEquals("第 0 / 0 页", presentation.pageCounterText());
        assertEquals(0, presentation.pageRows());
        assertEquals(0, presentation.pageColumns());
        assertFalse(presentation.canPreviousPage());
        assertFalse(presentation.canNextPage());
        assertEquals(32, presentation.cells().size());
        for (LockerGridPresentation.Cell cell : presentation.cells()) {
            assertEquals(LockerGridPresentation.CellState.EMPTY, cell.state());
        }
    }

    @Test
    public void disabledAreasRemainVisibleButUnavailableAndNeverBecomeActive() {
        LockerSelectionModel model = ready(snapshot(
                area("disabled-a", "停用甲区", page("da", 1, 1, 1,
                        slot("disabled-a-slot", "停用甲", 1, 1, false, 1))),
                area("disabled-b", "停用乙区", page("db", 1, 1, 1,
                        slot("disabled-b-slot", "停用乙", 1, 1, false, 2)))));

        LockerGridPresentation presentation = LockerGridPresentation.create(model, 0);

        assertEquals(2, presentation.visibleAreas().size());
        assertEquals("第 0 / 0 页", presentation.pageCounterText());
        for (LockerGridPresentation.AreaItem item : presentation.visibleAreas()) {
            assertFalse(item.selected());
            assertFalse(item.available());
            assertFalse(item.clickable());
            assertTrue(item.contentDescription().endsWith("，暂不可用"));
        }
    }

    @Test
    public void returnedListsAreImmutableAndNullOrInvalidCoordinatesAreRejected() {
        LockerSelectionModel model = ready(snapshot(area("one", "一区",
                enabledPage("one-page", "one-slot", "一", 1))));
        LockerGridPresentation presentation = LockerGridPresentation.create(model, 0);

        expectUnsupported(new Runnable() {
            @Override public void run() { presentation.visibleAreas().clear(); }
        });
        expectUnsupported(new Runnable() {
            @Override public void run() { presentation.cells().clear(); }
        });
        expectIllegalArgument(new Runnable() {
            @Override public void run() { LockerGridPresentation.create(null, 0); }
        });
        expectIllegalArgument(new Runnable() {
            @Override public void run() { presentation.cellAt(0, 1); }
        });
        expectIllegalArgument(new Runnable() {
            @Override public void run() { presentation.cellAt(1, 9); }
        });
    }

    private static LockerSelectionModel ready(LockerLayoutSnapshot snapshot) {
        LockerSelectionModel model = new LockerSelectionModel(RuntimePolicyFixtures.legacyLayoutPolicy());
        assertTrue(model.applyLayoutSnapshot(snapshot));
        return model;
    }

    private static LockerLayoutSnapshot snapshot(LockerArea... areas) {
        return new LockerLayoutSnapshot(701L, Arrays.asList(areas));
    }

    private static LockerArea area(String id, String label, LockerPage... pages) {
        return new LockerArea(id, label, Arrays.asList(pages));
    }

    private static LockerPage enabledPage(
            String pageId, String slotId, String label, int targetIndex) {
        return page(pageId, targetIndex, 1, 1,
                slot(slotId, label, 1, 1, true, targetIndex));
    }

    private static LockerPage page(
            String id, int pageNumber, int rows, int columns, LockerSlot... slots) {
        return new LockerPage(id, pageNumber, rows, columns, Arrays.asList(slots));
    }

    private static LockerSlot slot(String id, String label, int row, int column,
            boolean enabled, int targetIndex) {
        int zeroBased = targetIndex - 1;
        LockerZone zone = LockerZone.values()[zeroBased / 12];
        int localLock = zeroBased % 12 + 1;
        LockerTarget target = new LockerTarget(zone, zone.boardAddress(), localLock,
                FeedbackPolarity.SHORT_WHEN_LOCKED);
        return new LockerSlot(id, label, row, column, enabled, target);
    }

    private static List<String> areaIds(List<LockerGridPresentation.AreaItem> items) {
        List<String> ids = new ArrayList<>();
        for (LockerGridPresentation.AreaItem item : items) {
            ids.add(item.id());
        }
        return ids;
    }

    private static void expectUnsupported(Runnable action) {
        try {
            action.run();
            fail("Expected UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            // Expected.
        }
    }

    private static void expectIllegalArgument(Runnable action) {
        try {
            action.run();
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }
}
