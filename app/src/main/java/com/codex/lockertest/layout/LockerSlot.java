package com.codex.lockertest.layout;

import com.codex.lockertest.model.LockerTarget;

/** A visible logical locker at a page coordinate. */
public final class LockerSlot {
    private final String id;
    private final String displayLabel;
    private final int row;
    private final int column;
    private final boolean enabled;
    private final LockerTarget target;

    public LockerSlot(String id, String displayLabel, int row, int column,
            boolean enabled, LockerTarget target) {
        this.id = requireName(id, "Slot id", 0);
        this.displayLabel = requireName(displayLabel, "Slot display label", 20);
        if (row < 1 || column < 1) {
            throw new IllegalArgumentException("Slot row and column must be positive");
        }
        if (target == null) {
            throw new IllegalArgumentException("Slot target is required");
        }
        this.row = row;
        this.column = column;
        this.enabled = enabled;
        this.target = target;
    }

    public String id() { return id; }
    public String getId() { return id; }
    public String displayLabel() { return displayLabel; }
    public String getDisplayLabel() { return displayLabel; }
    public int row() { return row; }
    public int getRow() { return row; }
    public int column() { return column; }
    public int getColumn() { return column; }
    public boolean enabled() { return enabled; }
    public boolean isEnabled() { return enabled; }
    public LockerTarget target() { return target; }
    public LockerTarget getTarget() { return target; }

    static String requireName(String value, String fieldName, int maximumLength) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must be nonblank");
        }
        if (maximumLength > 0 && trimmed.length() > maximumLength) {
            throw new IllegalArgumentException(fieldName + " must be at most " + maximumLength + " characters");
        }
        return trimmed;
    }
}
