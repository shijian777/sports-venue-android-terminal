package com.codex.lockertest.ui;

/** Density-independent conversion from the 1280x800 ZIP design canvas to screen pixels. */
public final class ZipDesignMetrics {
    private static final float DESIGN_WIDTH = 1280f;
    private static final float DESIGN_HEIGHT = 800f;

    private ZipDesignMetrics() {
    }

    public static float scale(int viewportWidth, int viewportHeight) {
        if (viewportWidth <= 0 || viewportHeight <= 0 || viewportWidth < viewportHeight) {
            throw new IllegalArgumentException("viewport must be a non-empty landscape rectangle");
        }
        return Math.min(viewportWidth / DESIGN_WIDTH, viewportHeight / DESIGN_HEIGHT);
    }

    public static int px(int viewportWidth, int viewportHeight, float designUnits) {
        if (designUnits < 0f) {
            throw new IllegalArgumentException("designUnits must not be negative");
        }
        if (designUnits == 0f) {
            return 0;
        }
        return Math.max(1, Math.round(designUnits * scale(viewportWidth, viewportHeight)));
    }
}
