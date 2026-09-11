package com.codex.lockertest.ui.zip;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Source-level Android View contracts that run without an Android JVM runtime. */
public final class ZipPixelShellSourceTest {
    private static final String PIXEL_SHELL =
            "app/src/main/java/com/codex/lockertest/ui/zip/ZipPixelShell.java";
    private static final String KIOSK_SHELL =
            "app/src/main/java/com/codex/lockertest/ui/ZipKioskShell.java";

    @Test
    public void backgroundContentAndOverlayAreStableOrderedAccessibleLayers()
            throws Exception {
        String source = read(PIXEL_SHELL);
        assertContains(source, "public final class ZipPixelShell extends FrameLayout");
        String constructor = slice(source, "public ZipPixelShell(",
                "public void setScreenAsset(");

        int background = constructor.indexOf("addView(backgroundView");
        int content = constructor.indexOf("addView(contentLayer");
        int overlay = constructor.indexOf("addView(overlayLayer");
        assertTrue("background must be direct child index 0", background >= 0);
        assertTrue("content must be above background", content > background);
        assertTrue("overlay must be the top direct layer", overlay > content);
        assertContains(constructor, "contentLayer.setClipChildren(false)");
        assertContains(constructor, "overlayLayer.setClipChildren(false)");
        assertContains(constructor, "overlayLayer.setClickable(false)");

        assertContains(constructor, "backgroundView.setClickable(false)");
        assertContains(constructor, "backgroundView.setFocusable(false)");
        assertContains(constructor, "backgroundView.setContentDescription(null)");
        assertContains(constructor,
                "backgroundView.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO)");
        assertContains(constructor,
                "contentLayer.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES)");
        assertContains(constructor,
                "overlayLayer.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES)");
        assertContains(constructor, "contentLayer.setContentDescription(");
        assertContains(constructor, "overlayLayer.setContentDescription(");
    }

    @Test
    public void exactDesignAspectUsesFitXyAndEveryOtherLandscapeLetterboxes()
            throws Exception {
        String source = read(PIXEL_SHELL);
        assertContains(source, "setBackgroundColor(UiKit.DEEP_BLUE)");
        assertContains(source, "ZipBrand.DESIGN_WIDTH");
        assertContains(source, "ZipBrand.DESIGN_HEIGHT");
        assertContains(source, "ZipDesignMetrics.px(");
        assertTrue("aspect comparison must avoid float equality",
                Pattern.compile("\\(long\\)\\s*width\\s*\\*\\s*ZipBrand\\.DESIGN_HEIGHT\\s*"
                        + "==\\s*\\(long\\)\\s*height\\s*\\*\\s*ZipBrand\\.DESIGN_WIDTH")
                        .matcher(source).find());
        assertTrue("FIT_XY must be confined to the exact design-aspect branch",
                Pattern.compile("if\\s*\\(isExactDesignAspectRatio\\(width, height\\)\\)\\s*\\{"
                                + "[^}]*ImageView\\.ScaleType\\.FIT_XY[^}]*}\\s*else\\s*\\{"
                                + "[^}]*ImageView\\.ScaleType\\.FIT_CENTER",
                        Pattern.DOTALL).matcher(source).find());
        assertContains(source, "LayoutParams designParams = new LayoutParams(");
        assertContains(source, "designParams.gravity = Gravity.CENTER");
    }

    @Test
    public void missingDrawableShowsDiagnosticAndExposesSafeHomeAction()
            throws Exception {
        String source = read(PIXEL_SHELL);
        String setter = slice(source, "public void setScreenAsset(",
                "public ZipScreenAsset screenAsset()");
        assertContains(setter,
                "getResources().getIdentifier(drawableName, \"drawable\", getContext().getPackageName())");
        assertContains(setter, "if (resolvedDrawableId == 0)");
        assertContains(setter, "backgroundView.setImageDrawable(null)");
        assertContains(setter, "showDiagnostic(");
        assertFalse("screen changes must not rebuild business controls",
                setter.contains("removeAllViews"));

        assertContains(source, "public void setOnSafeHomeRequested(Runnable listener)");
        assertContains(source, "safeHomeRequested.run()");
        assertContains(source, "资源加载失败");
        assertContains(source, "安全返回首页");
        assertContains(source, "diagnosticTitle.setText(title)");
        assertContains(source, "Log.e(");
    }

    @Test
    public void promptDimmingLeavesTopOverlayOpaqueAndInteractive()
            throws Exception {
        String source = read(PIXEL_SHELL);
        String method = slice(source, "public void setPromptDimmed(boolean dimmed)",
                "public void setOnSafeHomeRequested(");
        assertContains(method, "backgroundView.setAlpha(");
        assertContains(method, "contentLayer.setAlpha(");
        assertFalse("overlay alpha must remain untouched", method.contains("overlayLayer.setAlpha"));
        assertFalse("overlay visibility must remain untouched",
                method.contains("overlayLayer.setVisibility"));
        assertFalse("overlay enabled state must remain untouched",
                method.contains("overlayLayer.setEnabled"));
    }

    @Test
    public void kioskWrapperKeepsLegacyApiAndAddsExplicitPixelBridge()
            throws Exception {
        String source = read(KIOSK_SHELL);
        assertTrue(Pattern.compile(
                "public\\s+ZipKioskShell\\s*\\(\\s*Context\\s+context,\\s*"
                        + "CharSequence\\s+title,\\s*CharSequence\\s+subtitle,\\s*"
                        + "boolean\\s+showReturn\\s*\\)", Pattern.DOTALL)
                .matcher(source).find());
        assertTrue(Pattern.compile(
                "public\\s+ZipKioskShell\\s*\\(\\s*Context\\s+context,\\s*"
                        + "ZipScreenAsset\\s+initialAsset\\s*\\)", Pattern.DOTALL)
                .matcher(source).find());
        assertContains(source, "new ZipPixelShell(context, initialAsset)");
        assertContains(source, "public FrameLayout content()");
        assertContains(source, "public void setOnReturnClickListener(OnClickListener listener)");
        assertContains(source, "public void setReturnEnabled(boolean enabled)");
        assertContains(source, "public void setScreenAsset(ZipScreenAsset asset)");
        assertContains(source, "public FrameLayout pixelContent()");
        assertContains(source, "public FrameLayout pixelOverlay()");
        assertContains(source, "ZipBrand.FOOTER");
        assertContains(source, "ZipBrand.VERSION");
        assertFalse(source.contains("智能更衣柜管理系统"));
        assertFalse(source.contains("版本：v15.0 Demo"));
    }

    @Test
    public void pixelReturnHitTargetMatchesVisiblePngButtonAt1050x110By176x38()
            throws Exception {
        String source = read(KIOSK_SHELL);
        String constructor = slice(source,
                "public ZipKioskShell(Context context, ZipScreenAsset initialAsset)",
                "public FrameLayout content()");
        assertContains(constructor, "returnButton = new Button(context)");
        assertContains(constructor, "returnButton.setBackgroundColor(Color.TRANSPARENT)");
        assertContains(constructor, "returnButton.setContentDescription(\"返回首页\")");
        assertContains(constructor, "returnButton.setImportantForAccessibility(");
        assertContains(constructor, "returnButton.setVisibility(GONE)");
        assertContains(constructor, "returnButton.setClickable(false)");
        assertContains(constructor, "returnButton.setEnabled(false)");
        assertContains(constructor, "pixelShell.overlayLayer().addView(returnButton");
        assertContains(constructor, "unit(context, 176)");
        assertContains(constructor, "unit(context, 38)");
        assertContains(constructor, "returnParams.topMargin = unit(context, 110)");
        assertContains(constructor, "returnParams.rightMargin = unit(context, 54)");
        assertFalse(constructor.contains("unit(context, 132)"));
        assertFalse(constructor.contains("unit(context, 42)"));
        assertFalse(constructor.contains("returnParams.topMargin = unit(context, 88)"));

        String listener = slice(source,
                "public void setOnReturnClickListener(OnClickListener listener)",
                "public void setReturnEnabled(boolean enabled)");
        assertContains(listener, "returnButton.setOnClickListener(listener)");
        String enabled = slice(source,
                "public void setReturnEnabled(boolean enabled)",
                "public void setScreenAsset(ZipScreenAsset asset)");
        assertContains(enabled, "returnButton.setEnabled(enabled)");
        assertContains(enabled, "返回首页，暂不可用");

        String sync = slice(source, "private void syncPixelReturnListener()",
                "@Override\n    protected void onAttachedToWindow()");
        assertContains(sync, "boolean active = pixelReturnEnabled && listener != null");
        assertContains(sync, "returnButton.setVisibility(active ? VISIBLE : GONE)");
        assertContains(sync, "returnButton.setClickable(active)");
        assertContains(sync, "returnButton.setEnabled(active)");
        assertContains(sync, "IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS");
        assertContains(sync, "pixelShell.setOnSafeHomeRequested(null)");
    }

    private static String slice(String source, String marker, String next) {
        int start = source.indexOf(marker);
        if (start < 0) fail("missing marker: " + marker);
        int end = source.indexOf(next, start + marker.length());
        if (end < 0) fail("missing end marker: " + next);
        return source.substring(start, end);
    }

    private static void assertContains(String source, String token) {
        assertTrue("missing token: " + token, source.contains(token));
    }

    private static String read(String relative) throws IOException {
        return new String(Files.readAllBytes(projectRoot().resolve(relative)),
                StandardCharsets.UTF_8);
    }

    private static Path projectRoot() {
        Path candidate = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        for (int index = 0; index < 8 && candidate != null; index++) {
            if (Files.isRegularFile(candidate.resolve("app/build.gradle"))) return candidate;
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("project root not found");
    }
}
