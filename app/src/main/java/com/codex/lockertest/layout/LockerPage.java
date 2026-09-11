package com.codex.lockertest.layout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** An immutable grid page of visible locker slots. */
public final class LockerPage {
    private final String id;
    private final int pageNumber;
    private final int rows;
    private final int columns;
    private final List<LockerSlot> slots;

    public LockerPage(String id, int pageNumber, int rows, int columns, List<LockerSlot> slots) {
        this.id = LockerSlot.requireName(id, "Page id", 0);
        if (pageNumber < 1) {
            throw new IllegalArgumentException("Page number must be positive");
        }
        if (rows < 1 || rows > 4) {
            throw new IllegalArgumentException("Page rows must be between 1 and 4");
        }
        if (columns < 1 || columns > 8) {
            throw new IllegalArgumentException("Page columns must be between 1 and 8");
        }
        if (slots == null) {
            throw new IllegalArgumentException("Page slots are required");
        }
        if (slots.size() > rows * columns) {
            throw new IllegalArgumentException("Page has more slots than coordinates");
        }

        List<LockerSlot> copy = new ArrayList<>(slots.size());
        Set<String> ids = new HashSet<>();
        Set<String> coordinates = new HashSet<>();
        for (LockerSlot slot : slots) {
            if (slot == null) {
                throw new IllegalArgumentException("Page slots cannot contain null");
            }
            if (slot.row() > rows || slot.column() > columns) {
                throw new IllegalArgumentException("Slot coordinate is outside the page");
            }
            if (!ids.add(slot.id())) {
                throw new IllegalArgumentException("Slot ids must be unique within a page");
            }
            if (!coordinates.add(slot.row() + ":" + slot.column())) {
                throw new IllegalArgumentException("Slot coordinates must be unique within a page");
            }
            copy.add(slot);
        }
        this.pageNumber = pageNumber;
        this.rows = rows;
        this.columns = columns;
        this.slots = Collections.unmodifiableList(copy);
    }

    public String id() { return id; }
    public String getId() { return id; }
    public int pageNumber() { return pageNumber; }
    public int getPageNumber() { return pageNumber; }
    public int rows() { return rows; }
    public int getRows() { return rows; }
    public int columns() { return columns; }
    public int getColumns() { return columns; }
    public List<LockerSlot> slots() { return slots; }
    public List<LockerSlot> getSlots() { return slots; }

    public LockerSlot slotAt(int row, int column) {
        if (row < 1 || row > rows || column < 1 || column > columns) {
            throw new IllegalArgumentException("Coordinate is outside the page");
        }
        for (LockerSlot slot : slots) {
            if (slot.row() == row && slot.column() == column) {
                return slot;
            }
        }
        return null;
    }
}
