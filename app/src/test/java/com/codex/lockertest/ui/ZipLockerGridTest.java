package com.codex.lockertest.ui;

import com.codex.lockertest.layout.LockerArea;
import com.codex.lockertest.layout.LockerLayoutSnapshot;
import com.codex.lockertest.layout.LockerPage;
import com.codex.lockertest.layout.LockerSlot;
import com.codex.lockertest.model.LockerTarget;
import com.codex.lockertest.model.LockerZone;
import com.codex.lockertest.protocol.FeedbackPolarity;
import com.codex.lockertest.ui.zip.ZipLockerScreenRouter;
import com.codex.lockertest.ui.zip.ZipScreenAsset;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class ZipLockerGridTest {
    @Test
    public void selectorRoutesHaveExactPrecedenceAndCoverAssetsFourteenThroughEighteen() {
        assertEquals(ZipLockerScreenRouter.SelectorState.SELECTION_ERROR,
                ZipLockerScreenRouter.selectorState(
                        LockerSelectionModel.DiscoveryState.DETECTING,
                        false, false, "操作错误"));
        assertEquals(ZipLockerScreenRouter.SelectorState.DISCOVERING,
                ZipLockerScreenRouter.selectorState(
                        LockerSelectionModel.DiscoveryState.DETECTING,
                        false, false, ""));
        assertEquals(ZipLockerScreenRouter.SelectorState.NO_AREA,
                ZipLockerScreenRouter.selectorState(
                        LockerSelectionModel.DiscoveryState.NO_ZONES,
                        false, false, null));
        assertEquals(ZipLockerScreenRouter.SelectorState.NO_AREA,
                ZipLockerScreenRouter.selectorState(
                        LockerSelectionModel.DiscoveryState.READY,
                        false, false, null));
        assertEquals(ZipLockerScreenRouter.SelectorState.UNSELECTED,
                ZipLockerScreenRouter.selectorState(
                        LockerSelectionModel.DiscoveryState.READY,
                        true, false, null));
        assertEquals(ZipLockerScreenRouter.SelectorState.SELECTED,
                ZipLockerScreenRouter.selectorState(
                        LockerSelectionModel.DiscoveryState.READY,
                        true, true, null));

        int expectedId = 14;
        Set<ZipScreenAsset> unique = new LinkedHashSet<>();
        for (ZipLockerScreenRouter.SelectorState state
                : ZipLockerScreenRouter.SelectorState.values()) {
            ZipScreenAsset asset = ZipLockerScreenRouter.assetFor(state);
            assertEquals(expectedId++, asset.id());
            assertTrue(unique.add(asset));
        }
        assertEquals(19, expectedId);

        expectIllegalArgument(() -> ZipLockerScreenRouter.assetFor(
                (ZipLockerScreenRouter.SelectorState) null));
        expectIllegalArgument(() -> ZipLockerScreenRouter.selectorState(
                null, false, false, "even an error cannot hide an unknown state"));
    }

    @Test
    public void fullServerPageUsesFourByEightColumnMajorLabelsInZipRowOrder() {
        List<LockerSlot> slots = new ArrayList<>();
        for (int number = 1; number <= 32; number++) {
            int row = (number - 1) % 4 + 1;
            int column = (number - 1) / 4 + 1;
            slots.add(slot("a-" + number, "A" + number,
                    row, column, true, number - 1));
        }
        LockerSelectionModel model = ready(snapshot(
                area("area-a", "A区", page("page-a", 1, slots))));
        LockerGridPresentation grid = LockerGridPresentation.create(model, 0);

        assertEquals(4, LockerGridPresentation.ROWS);
        assertEquals(8, LockerGridPresentation.COLUMNS);
        assertEquals(4, LockerGridPresentation.MAX_VISIBLE_AREAS);
        assertEquals(32, grid.cells().size());
        assertEquals(Arrays.asList("A1", "A5", "A9", "A13", "A17", "A21", "A25", "A29"),
                rowLabels(grid, 1));
        assertEquals(Arrays.asList("A4", "A8", "A12", "A16", "A20", "A24", "A28", "A32"),
                rowLabels(grid, 4));
    }

    @Test
    public void areaWindowsAndPageNavigationUseOnlySnapshotOrder() {
        LockerArea first = area("area-1", "一区",
                oneSlotPage("page-1-a", 1, "slot-1-a", "一-A", 0),
                oneSlotPage("page-1-b", 99, "slot-1-b", "一-B", 1));
        LockerSelectionModel model = ready(snapshot(
                first,
                oneSlotArea("area-2", "二区", 2),
                oneSlotArea("area-3", "三区", 3),
                oneSlotArea("area-4", "四区", 4),
                oneSlotArea("area-5", "五区", 5),
                oneSlotArea("area-6", "六区", 6)));

        LockerGridPresentation firstWindow = LockerGridPresentation.create(model, 0);
        assertEquals(Arrays.asList("area-1", "area-2", "area-3", "area-4"),
                areaIds(firstWindow.visibleAreas()));
        assertEquals("第 1 / 2 页", firstWindow.pageCounterText());
        assertTrue(firstWindow.canNextPage());

        assertTrue(model.nextPage());
        LockerGridPresentation pageTwo = LockerGridPresentation.create(model, 1);
        assertEquals("第 2 / 2 页", pageTwo.pageCounterText());
        assertEquals("一-B", pageTwo.cellAt(1, 1).text());
        assertEquals(Arrays.asList("area-5", "area-6"), areaIds(pageTwo.visibleAreas()));
        assertFalse(pageTwo.canNextAreaWindow());
        assertEquals(6, model.layoutSnapshot().areas().size());
    }

    @Test
    public void emptyAndDisabledCellsAreInertAndNeverProduceATarget() {
        LockerSelectionModel model = ready(snapshot(area("sparse", "稀疏区",
                new LockerPage("sparse-page", 1, 4, 8, Arrays.asList(
                        slot("enabled", "可用", 1, 1, true, 0),
                        slot("disabled", "停用", 2, 1, false, 1))))));
        LockerGridPresentation grid = LockerGridPresentation.create(model, 0);

        LockerGridPresentation.Cell empty = grid.cellAt(1, 2);
        assertEquals(LockerGridPresentation.CellState.EMPTY, empty.state());
        assertNull(empty.slotId());
        assertEquals("", empty.text());
        assertFalse(empty.clickable());

        LockerGridPresentation.Cell disabled = grid.cellAt(2, 1);
        assertEquals(LockerGridPresentation.CellState.DISABLED, disabled.state());
        assertFalse(disabled.clickable());
        assertFalse(model.selectSlot(disabled.slotId()));
        assertNull(model.selectedTarget());

        assertTrue(model.selectSlot("enabled"));
        LockerTarget exact = model.selectedTarget();
        assertTrue(model.beginSending());
        assertSame(exact, model.selectedTarget());
        assertFalse(model.selectSlot("disabled"));
        assertSame(exact, model.selectedTarget());
    }

    @Test
    public void pixelSelectorOwnsEveryDynamicRegionWithoutRestartingDiscovery() throws IOException {
        String source = read("app/src/main/java/com/codex/lockertest/ui/LockerSelectionView.java");

        assertContains(source,
                "new ZipPixelShell(context, ZipScreenAsset.LOCKER_DISCOVERING)");
        assertFalse(source.contains("new ZipKioskShell("));
        assertContains(source, "CELL_X = {222, 329, 436, 543, 650, 757, 864, 971}");
        assertContains(source, "CELL_Y = {185, 261, 337, 413}");
        assertContains(source, "place(content, button, CELL_X[column], CELL_Y[row], 100, 64)");
        assertContains(source,
                "body.addView(selectionHeader, positioned(context, 222, 493, 500, 34))");
        assertContains(source, "place(content, pageLeftButton, 770, 493, 44, 34)");
        assertContains(source, "place(content, pageCounter, 822, 493, 176, 34)");
        assertContains(source, "place(content, pageRightButton, 1006, 493, 44, 34)");
        assertContains(source, "place(content, confirmButton, 550, 535, 180, 42)");
        assertContains(source, "place(content, cancelButton, 304, 655, 192, 47)");
        assertContains(source, "AREA_X = {274, 452, 630, 808}");
        assertContains(source, "place(content, areaLeftButton, 222, 582, 44, 34)");
        assertContains(source, "place(content, areaRightButton, 986, 582, 44, 34)");

        String loading = slice(source,
                "public void showDiscoveryLoading()",
                "public void applyDiscoverySnapshot(");
        assertFalse(loading.contains("beginDiscovery"));
        assertContains(loading, "render()");

        String cellRender = slice(source,
                "private void renderCells(",
                "private void renderPrompt(");
        assertContains(cellRender, "button.setVisibility(VISIBLE)");
        assertContains(cellRender, "emptyOrUnconfigured ? \"\" : cell.text()");
        assertContains(cellRender, "button.setEnabled(cell.clickable())");
        assertFalse(cellRender.contains("button.setVisibility(INVISIBLE)"));
        assertContains(cellRender, "UiKit.roundedSolid(");

        assertContains(source, "place(overlay, promptBlocker, 0, 0, 1280, 800)");
        assertContains(source, "place(overlay, promptCard, 355, 225, 570, 330)");
        assertContains(source, "place(overlay, promptActionCover, 430, 466, 420, 118)");
        assertContains(source, "place(overlay, promptLeftButton, 470, 480, 150, 46)");
        assertContains(source, "place(overlay, promptRightButton, 660, 480, 150, 46)");
        assertContains(source, "place(overlay, promptCenterButton, 565, 480, 150, 46)");
        assertFalse(source.contains("SerialGateway"));
        assertFalse(source.contains("UnlockCoordinator"));
        assertFalse(source.contains("LockerProtocol"));
    }

    private static LockerSelectionModel ready(LockerLayoutSnapshot snapshot) {
        LockerSelectionModel model = new LockerSelectionModel(RuntimePolicyFixtures.legacyLayoutPolicy());
        assertTrue(model.applyLayoutSnapshot(snapshot));
        return model;
    }

    private static LockerLayoutSnapshot snapshot(LockerArea... areas) {
        return new LockerLayoutSnapshot(601L, Arrays.asList(areas));
    }

    private static LockerArea area(String id, String label, LockerPage... pages) {
        return new LockerArea(id, label, Arrays.asList(pages));
    }

    private static LockerArea oneSlotArea(
            String id, String label, int targetIndex) {
        return area(id, label,
                oneSlotPage("page-" + id, 1, "slot-" + id, label, targetIndex));
    }

    private static LockerPage oneSlotPage(String pageId, int pageNumber,
            String slotId, String label, int targetIndex) {
        return new LockerPage(pageId, pageNumber, 4, 8,
                Collections.singletonList(slot(
                        slotId, label, 1, 1, true, targetIndex)));
    }

    private static LockerPage page(
            String id, int pageNumber, List<LockerSlot> slots) {
        return new LockerPage(id, pageNumber, 4, 8, slots);
    }

    private static LockerSlot slot(String id, String label,
            int row, int column, boolean enabled, int targetIndex) {
        LockerZone zone = LockerZone.values()[targetIndex / 12];
        int localLock = targetIndex % 12 + 1;
        return new LockerSlot(id, label, row, column, enabled,
                new LockerTarget(zone, zone.boardAddress(), localLock,
                        FeedbackPolarity.SHORT_WHEN_LOCKED));
    }

    private static List<String> rowLabels(
            LockerGridPresentation presentation, int row) {
        List<String> labels = new ArrayList<>();
        for (int column = 1; column <= 8; column++) {
            labels.add(presentation.cellAt(row, column).text());
        }
        return labels;
    }

    private static List<String> areaIds(
            List<LockerGridPresentation.AreaItem> items) {
        List<String> ids = new ArrayList<>();
        for (LockerGridPresentation.AreaItem item : items) {
            ids.add(item.id());
        }
        return ids;
    }

    private static String read(String path) throws IOException {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    private static String slice(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from);
        assertTrue("missing start marker " + start, from >= 0);
        assertTrue("missing end marker " + end, to > from);
        return source.substring(from, to);
    }

    private static void assertContains(String source, String token) {
        assertTrue("missing token: " + token, source.contains(token));
    }

    private static void expectIllegalArgument(Runnable action) {
        try {
            action.run();
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Expected fail-closed boundary.
        }
    }
}
