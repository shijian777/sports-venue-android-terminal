package com.codex.lockertest.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class ZipDesignMetricsTest {
    @Test
    public void referenceViewportUsesOneDesignPixelPerScreenPixel() {
        assertEquals(1f, ZipDesignMetrics.scale(1280, 800), 0.0001f);
        assertEquals(53, ZipDesignMetrics.px(1280, 800, 53));
    }

    @Test
    public void compactLandscapeScalesByAvailableHeightKeepingFourRowsProportional() {
        assertEquals(0.6f, ZipDesignMetrics.scale(800, 480), 0.0001f);
        assertEquals(32, ZipDesignMetrics.px(800, 480, 53));
        assertEquals(29, ZipDesignMetrics.px(800, 480, 48));
    }

    @Test
    public void widerLandscapeStillUsesTheLimitingDimension() {
        assertEquals(1.35f, ZipDesignMetrics.scale(1920, 1080), 0.0001f);
    }

    @Test
    public void letterboxedViewportMapsAdminPanelInsideTheCenteredDesignCanvas() {
        int viewportWidth = 1366;
        int viewportHeight = 768;
        assertEquals(0.96f, ZipDesignMetrics.scale(viewportWidth, viewportHeight), 0.0001f);

        int designWidth = ZipDesignMetrics.px(viewportWidth, viewportHeight, 1280);
        int horizontalLetterbox = (viewportWidth - designWidth) / 2;
        assertEquals(1229, designWidth);
        assertEquals(68, horizontalLetterbox);
        assertEquals(87,
                horizontalLetterbox + ZipDesignMetrics.px(viewportWidth, viewportHeight, 20));
        assertEquals(101, ZipDesignMetrics.px(viewportWidth, viewportHeight, 105));
        assertEquals(1190, ZipDesignMetrics.px(viewportWidth, viewportHeight, 1260 - 20));
        assertEquals(581, ZipDesignMetrics.px(viewportWidth, viewportHeight, 710 - 105));
        assertEquals(1076,
                horizontalLetterbox + ZipDesignMetrics.px(
                        viewportWidth, viewportHeight, 1050));
        assertEquals(106, ZipDesignMetrics.px(viewportWidth, viewportHeight, 110));
        assertEquals(169, ZipDesignMetrics.px(viewportWidth, viewportHeight, 176));
        assertEquals(377,
                horizontalLetterbox + ZipDesignMetrics.px(
                        viewportWidth, viewportHeight, 322));
        assertEquals(178, ZipDesignMetrics.px(viewportWidth, viewportHeight, 185));
        assertEquals(614, ZipDesignMetrics.px(viewportWidth, viewportHeight, 640));
        assertEquals(336, ZipDesignMetrics.px(viewportWidth, viewportHeight, 350));
    }

    @Test
    public void referenceViewportPlacesAllFixedAdministratorActionsExactly() {
        assertMappedRect(1280, 800, 382, 300, 240, 60,
                382, 300, 240, 60);
        assertMappedRect(1280, 800, 662, 300, 240, 60,
                662, 300, 240, 60);
        assertMappedRect(1280, 800, 382, 390, 240, 60,
                382, 390, 240, 60);
        assertMappedRect(1280, 800, 662, 390, 240, 60,
                662, 390, 240, 60);

        assertMappedRect(1280, 800, 481, 332, 320, 48,
                481, 332, 320, 48);
        assertMappedRect(1280, 800, 551, 398, 180, 42,
                551, 398, 180, 42);
        assertMappedRect(1280, 800, 402, 342, 370, 60,
                402, 342, 370, 60);
        assertMappedRect(1280, 800, 797, 347, 72, 42,
                797, 347, 72, 42);
        assertMappedRect(1280, 800, 470, 480, 150, 46,
                470, 480, 150, 46);
        assertMappedRect(1280, 800, 660, 480, 150, 46,
                660, 480, 150, 46);
    }

    @Test
    public void letterboxPlacesAllFixedAdministratorActionsInsideTheDesignCanvas() {
        assertMappedRect(1366, 768, 382, 300, 240, 60,
                435, 288, 230, 58);
        assertMappedRect(1366, 768, 662, 300, 240, 60,
                704, 288, 230, 58);
        assertMappedRect(1366, 768, 382, 390, 240, 60,
                435, 374, 230, 58);
        assertMappedRect(1366, 768, 662, 390, 240, 60,
                704, 374, 230, 58);

        assertMappedRect(1366, 768, 481, 332, 320, 48,
                530, 319, 307, 46);
        assertMappedRect(1366, 768, 551, 398, 180, 42,
                597, 382, 173, 40);
        assertMappedRect(1366, 768, 402, 342, 370, 60,
                454, 328, 355, 58);
        assertMappedRect(1366, 768, 797, 347, 72, 42,
                833, 333, 69, 40);
        assertMappedRect(1366, 768, 470, 480, 150, 46,
                519, 461, 144, 44);
        assertMappedRect(1366, 768, 660, 480, 150, 46,
                702, 461, 144, 44);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonLandscapeOrEmptyViewport() {
        ZipDesignMetrics.scale(480, 800);
    }

    private static void assertMappedRect(int viewportWidth, int viewportHeight,
            int designLeft, int designTop, int designWidth, int designHeight,
            int expectedLeft, int expectedTop, int expectedWidth, int expectedHeight) {
        int canvasWidth = ZipDesignMetrics.px(
                viewportWidth, viewportHeight, 1280);
        int horizontalLetterbox = (viewportWidth - canvasWidth) / 2;
        assertEquals(expectedLeft, horizontalLetterbox + ZipDesignMetrics.px(
                viewportWidth, viewportHeight, designLeft));
        assertEquals(expectedTop, ZipDesignMetrics.px(
                viewportWidth, viewportHeight, designTop));
        assertEquals(expectedWidth, ZipDesignMetrics.px(
                viewportWidth, viewportHeight, designWidth));
        assertEquals(expectedHeight, ZipDesignMetrics.px(
                viewportWidth, viewportHeight, designHeight));
    }
}
