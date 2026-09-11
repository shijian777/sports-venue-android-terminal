package com.codex.lockertest.ui;

import com.codex.lockertest.layout.LockerArea;
import com.codex.lockertest.layout.LockerLayoutSnapshot;
import com.codex.lockertest.layout.LockerPage;
import com.codex.lockertest.layout.LockerSlot;
import com.codex.lockertest.model.LockerZone;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable, Android-free rendering data for the dynamic locker selector. */
public final class LockerGridPresentation {
    public static final int MAX_VISIBLE_AREAS = 4;
    public static final int ROWS = 4;
    public static final int COLUMNS = 8;
    public static final int AREA_LABEL_CODEPOINTS = 12;
    public static final int SLOT_LABEL_CODEPOINTS = 8;

    public enum CellState {
        EMPTY,
        DISABLED,
        UNCONFIGURED,
        AVAILABLE,
        SELECTED
    }

    public static final class AreaItem {
        private final String id;
        private final String text;
        private final String contentDescription;
        private final boolean selected;
        private final boolean available;
        private final boolean clickable;

        private AreaItem(String id, String text, String contentDescription,
                boolean selected, boolean available, boolean clickable) {
            this.id = id;
            this.text = text;
            this.contentDescription = contentDescription;
            this.selected = selected;
            this.available = available;
            this.clickable = clickable;
        }

        public String id() { return id; }
        public String text() { return text; }
        public String contentDescription() { return contentDescription; }
        public boolean selected() { return selected; }
        public boolean available() { return available; }
        public boolean clickable() { return clickable; }
    }

    public static final class Cell {
        private final int row;
        private final int column;
        private final String slotId;
        private final String text;
        private final String contentDescription;
        private final CellState state;
        private final boolean clickable;

        private Cell(int row, int column, String slotId, String text,
                String contentDescription, CellState state, boolean clickable) {
            this.row = row;
            this.column = column;
            this.slotId = slotId;
            this.text = text;
            this.contentDescription = contentDescription;
            this.state = state;
            this.clickable = clickable;
        }

        public int row() { return row; }
        public int column() { return column; }
        public String slotId() { return slotId; }
        public String text() { return text; }
        public String contentDescription() { return contentDescription; }
        public CellState state() { return state; }
        public boolean clickable() { return clickable; }
    }

    private final int areaWindowPage;
    private final int areaWindowCount;
    private final boolean canPreviousAreaWindow;
    private final boolean canNextAreaWindow;
    private final List<AreaItem> visibleAreas;
    private final String pageCounterText;
    private final int pageRows;
    private final int pageColumns;
    private final boolean canPreviousPage;
    private final boolean canNextPage;
    private final List<Cell> cells;
    private final boolean interactionEnabled;

    private LockerGridPresentation(
            int areaWindowPage,
            int areaWindowCount,
            boolean canPreviousAreaWindow,
            boolean canNextAreaWindow,
            List<AreaItem> visibleAreas,
            String pageCounterText,
            int pageRows,
            int pageColumns,
            boolean canPreviousPage,
            boolean canNextPage,
            List<Cell> cells,
            boolean interactionEnabled) {
        this.areaWindowPage = areaWindowPage;
        this.areaWindowCount = areaWindowCount;
        this.canPreviousAreaWindow = canPreviousAreaWindow;
        this.canNextAreaWindow = canNextAreaWindow;
        this.visibleAreas = Collections.unmodifiableList(visibleAreas);
        this.pageCounterText = pageCounterText;
        this.pageRows = pageRows;
        this.pageColumns = pageColumns;
        this.canPreviousPage = canPreviousPage;
        this.canNextPage = canNextPage;
        this.cells = Collections.unmodifiableList(cells);
        this.interactionEnabled = interactionEnabled;
    }

    public static LockerGridPresentation create(
            LockerSelectionModel model, int requestedAreaWindowPage) {
        if (model == null) {
            throw new IllegalArgumentException("Locker selection model is required");
        }

        boolean interactionEnabled = model.isInteractionEnabled();
        LockerLayoutSnapshot snapshot = model.layoutSnapshot();
        List<LockerArea> areas = snapshot == null
                ? Collections.<LockerArea>emptyList() : snapshot.areas();
        int areaWindowCount = (areas.size() + MAX_VISIBLE_AREAS - 1) / MAX_VISIBLE_AREAS;
        int areaWindowPage = clampWindow(requestedAreaWindowPage, areaWindowCount);
        int windowStart = areaWindowPage * MAX_VISIBLE_AREAS;
        int windowEnd = Math.min(windowStart + MAX_VISIBLE_AREAS, areas.size());
        LockerArea activeArea = model.activeArea();
        List<AreaItem> visibleAreas = new ArrayList<>(Math.max(0, windowEnd - windowStart));
        for (int index = windowStart; index < windowEnd; index++) {
            LockerArea area = areas.get(index);
            boolean selected = activeArea != null && activeArea.id().equals(area.id());
            boolean available = model.isAreaEnabled(area.id());
            String stateCopy = selected ? "已选择" : available ? "可选择" : "暂不可用";
            visibleAreas.add(new AreaItem(
                    area.id(),
                    shorten(area.displayName(), AREA_LABEL_CODEPOINTS),
                    area.displayName() + "，" + stateCopy,
                    selected,
                    available,
                    interactionEnabled && available));
        }

        LockerPage activePage = model.activePage();
        LockerZone compatibilityZone = model.activeZone();
        int pageRows = activePage == null ? 0 : activePage.rows();
        int pageColumns = activePage == null ? 0 : activePage.columns();
        int activePageIndex = model.activePageIndex();
        int pageCount = activeArea == null ? 0 : activeArea.pages().size();
        String pageCounterText = activePage == null
                ? "第 0 / 0 页"
                : "第 " + (activePageIndex + 1) + " / " + pageCount + " 页";

        LockerSlot selectedSlot = model.selectedSlot();
        List<Cell> cells = new ArrayList<>(ROWS * COLUMNS);
        for (int row = 1; row <= ROWS; row++) {
            for (int column = 1; column <= COLUMNS; column++) {
                LockerSlot slot = null;
                if (activePage != null && row <= pageRows && column <= pageColumns) {
                    slot = activePage.slotAt(row, column);
                }
                if (slot == null && activePage != null && compatibilityZone != null) {
                    cells.add(createUnconfiguredCompatibilityCell(
                            row, column, compatibilityZone));
                } else {
                    cells.add(createCell(row, column, slot, selectedSlot, interactionEnabled));
                }
            }
        }

        return new LockerGridPresentation(
                areaWindowPage,
                areaWindowCount,
                interactionEnabled && areaWindowPage > 0,
                interactionEnabled && areaWindowPage + 1 < areaWindowCount,
                visibleAreas,
                pageCounterText,
                pageRows,
                pageColumns,
                interactionEnabled && model.hasPreviousPage(),
                interactionEnabled && model.hasNextPage(),
                cells,
                interactionEnabled);
    }

    public int areaWindowPage() { return areaWindowPage; }
    public int areaWindowCount() { return areaWindowCount; }
    public boolean canPreviousAreaWindow() { return canPreviousAreaWindow; }
    public boolean canNextAreaWindow() { return canNextAreaWindow; }
    public List<AreaItem> visibleAreas() { return visibleAreas; }
    public String pageCounterText() { return pageCounterText; }
    public int pageRows() { return pageRows; }
    public int pageColumns() { return pageColumns; }
    public int visibleGridRowCount() { return ROWS; }
    public boolean canPreviousPage() { return canPreviousPage; }
    public boolean canNextPage() { return canNextPage; }
    public List<Cell> cells() { return cells; }
    public boolean interactionEnabled() { return interactionEnabled; }

    public Cell cellAt(int row, int column) {
        if (row < 1 || row > ROWS || column < 1 || column > COLUMNS) {
            throw new IllegalArgumentException("Cell coordinate is outside the 4x8 grid");
        }
        return cells.get((row - 1) * COLUMNS + column - 1);
    }

    private static Cell createCell(int row, int column, LockerSlot slot,
            LockerSlot selectedSlot, boolean interactionEnabled) {
        if (slot == null) {
            return new Cell(row, column, null, "",
                    "第" + row + "行第" + column + "列，空位",
                    CellState.EMPTY, false);
        }
        boolean selected = selectedSlot != null && selectedSlot.id().equals(slot.id());
        CellState state = selected
                ? CellState.SELECTED : slot.enabled() ? CellState.AVAILABLE : CellState.DISABLED;
        String stateCopy = selected ? "已选择" : slot.enabled() ? "可选择" : "暂不可用";
        return new Cell(
                row,
                column,
                slot.id(),
                shorten(slot.displayLabel(), SLOT_LABEL_CODEPOINTS),
                slot.displayLabel() + "，" + stateCopy,
                state,
                interactionEnabled && slot.enabled());
    }

    private static Cell createUnconfiguredCompatibilityCell(
            int row, int column, LockerZone zone) {
        int visibleNumber = (column - 1) * ROWS + row;
        String label = zone.name() + visibleNumber;
        return new Cell(row, column, null, label, label + "，暂不可用",
                CellState.UNCONFIGURED, false);
    }

    private static int clampWindow(int requested, int count) {
        if (count == 0 || requested < 0) {
            return 0;
        }
        return Math.min(requested, count - 1);
    }

    private static String shorten(String value, int maximumCodePoints) {
        int count = value.codePointCount(0, value.length());
        if (count <= maximumCodePoints) {
            return value;
        }
        int prefixEnd = value.offsetByCodePoints(0, maximumCodePoints - 1);
        return value.substring(0, prefixEnd) + "…";
    }
}
