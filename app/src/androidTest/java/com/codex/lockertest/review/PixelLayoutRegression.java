package com.codex.lockertest.review;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Resources;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.view.MotionEvent;
import android.widget.FrameLayout;

import com.codex.lockertest.bootstrap.BootstrapSnapshot;
import com.codex.lockertest.model.UnlockMethod;
import com.codex.lockertest.runtime.CredentialAdmission;
import com.codex.lockertest.ui.BootstrapHomePresentation;
import com.codex.lockertest.ui.TerminalReadiness;
import com.codex.lockertest.ui.ZipHomeView;
import com.codex.lockertest.ui.ZipKioskShell;
import com.codex.lockertest.ui.zip.ZipPixelShell;
import com.codex.lockertest.ui.zip.ZipScreenAsset;

/** Runs Android View measurement and transforms, not a mock of the layout algorithm. */
public final class PixelLayoutRegression extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        StringBuilder failures = new StringBuilder();
        runOnMainSync(() -> {
            // First draw after navigation-bar removal; re-layout must also stay aligned.
            checkCase(failures, 1184, 800, 1280, 800);
            checkCase(failures, 1280, 800, 1280, 800);
            checkCase(failures, 1184, 800, 1206, 800);
            checkCase(failures, 1920, 1080, 1920, 1080);
            checkHomeActions(failures);
            try { OnlineHomeRegression.check(getTargetContext()); }
            catch (Throwable failure) { failures.append("Online home: ").append(failure).append('\n'); }
            try { PalmPanelRegression.check(getTargetContext()); }
            catch (Throwable failure) { failures.append("Palm panel: ").append(failure).append('\n'); }
            try { DetailUiRegression.check(getTargetContext()); }
            catch (Throwable failure) { failures.append("UI details: ").append(failure).append('\n'); }
        });
        try { OnlineBusinessRegression.check(this); }
        catch (Throwable failure) { failures.append("Online business: ").append(failure).append('\n'); }
        try { OnlineFaceHostRegression.check(this); }
        catch (Throwable failure) { failures.append("Online face: ").append(failure).append('\n'); }
        try { OnlineReturnRegression.check(this); }
        catch (Throwable failure) { failures.append("Online return: ").append(failure).append('\n'); }
        try { OnlineUnlockRegression.check(this); }
        catch (Throwable failure) { failures.append("Online unlock: ").append(failure).append('\n'); }
        try { OnlineAuthorizedSerialGatewayRegression.check(); }
        catch (Throwable failure) { failures.append("Authorized serial: ").append(failure).append('\n'); }
        try { OnlineReaderRegression.check(this); }
        catch (Throwable failure) { failures.append("Online readers: ").append(failure).append('\n'); }
        try { OnlineMqttRegression.check(this); }
        catch (Throwable failure) { failures.append("Online MQTT: ").append(failure).append('\n'); }
        try { OnlineMaintenanceActivityRegression.check(this); }
        catch (Throwable failure) { failures.append("Online maintenance lifecycle: ").append(failure).append('\n'); }
        result.putString("stream", failures.length() == 0
                ? "\nPIXEL_LAYOUT_REGRESSION=PASS (4 metrics configurations, resize cycles + both layers + touch; home callbacks and offline admin gate; palm panel lifecycle; online paired credentials, async browsing/paging/owned-list and cancellation; isolated production maintenance guard lifecycle)\n"
                : "\nPIXEL_LAYOUT_REGRESSION=FAIL\n" + failures);
        finish(failures.length() == 0 ? -1 : 0, result);
    }

    private void checkHomeActions(StringBuilder failures) {
        try {
            // Fixture state only. This runner never invokes a network, SDK or lock controller.
            java.lang.reflect.Method state = BootstrapSnapshot.class.getDeclaredMethod(
                    "state", long.class, BootstrapSnapshot.Phase.class,
                    BootstrapSnapshot.Endpoint.class, BootstrapSnapshot.FailureReason.class,
                    String.class);
            state.setAccessible(true);
            BootstrapSnapshot blocked = (BootstrapSnapshot) state.invoke(null, 1L,
                    BootstrapSnapshot.Phase.BLOCKED, BootstrapSnapshot.Endpoint.CHECK_DEVICE,
                    BootstrapSnapshot.FailureReason.NETWORK, "********");
            int[] calls = new int[4];
            ZipHomeView.Listener listener = new ZipHomeView.Listener() {
                @Override public void onCredentialSubmit(UnlockMethod method, String value) {
                    throw new AssertionError("Unexpected credential submission");
                }
                @Override public void onUnavailableSelected(UnlockMethod method) {
                    throw new AssertionError("Unexpected unavailable callback");
                }
                @Override public void onEnrollmentRequested() { calls[0]++; }
                @Override public void onFaceRequested() { calls[1]++; }
                @Override public void onReturnRequested() { calls[2]++; }
                @Override public void onAdminRequested() { calls[3]++; }
            };
            ZipHomeView home = new ZipHomeView(getTargetContext(),
                    (method, value) -> CredentialAdmission.rejected("Test fixture only"),
                    method -> true,
                    BootstrapHomePresentation.create(TerminalReadiness.localDemoReady(), blocked));
            home.setListener(listener);
            String[] descriptions = {"掌纹录入入口", "人脸识别入口", "离场还柜，进入身份验证"};
            for (int index = 0; index < descriptions.length; index++) {
                View action = requireDescription(home, descriptions[index], false);
                if (!action.isEnabled() || !action.isClickable()) {
                    throw new AssertionError("Local home action disabled: " + descriptions[index]);
                }
                action.performClick();
                if (calls[index] != 1) throw new AssertionError("Wrong home action callback");
            }
            View clock = requireDescription(home, "当前日期时间", true);
            for (int tap = 0; tap < 4; tap++) clock.performClick();
            if (calls[3] != 0) throw new AssertionError("Administrator entry opened before fifth tap");
            clock.performClick();
            if (calls[3] != 1) throw new AssertionError("Fifth clock tap did not open administrator entry");
            clock.performClick();
            if (calls[3] != 1) throw new AssertionError("Clock tap sequence did not reset");

            ZipHomeView offline = new ZipHomeView(getTargetContext(),
                    (method, value) -> CredentialAdmission.rejected("Test fixture only"),
                    method -> true,
                    BootstrapHomePresentation.create(TerminalReadiness.networkUnavailable(), blocked));
            offline.setListener(listener);
            for (String description : descriptions) {
                View action = requireDescription(offline, description, false);
                if (action.isEnabled() || action.isClickable()) {
                    throw new AssertionError("Offline production action remained enabled");
                }
            }
            View offlineClock = requireDescription(offline, "当前日期时间", true);
            for (int tap = 0; tap < 5; tap++) offlineClock.performClick();
            if (calls[3] != 2) throw new AssertionError("Offline diagnostic entry is inaccessible");

            ZipHomeView unavailable = new ZipHomeView(getTargetContext(),
                    (method, value) -> CredentialAdmission.rejected("Test fixture only"),
                    method -> false,
                    BootstrapHomePresentation.create(TerminalReadiness.localDemoReady(), blocked));
            if (findDescription(unavailable, descriptions[0], false) != null
                    || findDescription(unavailable, descriptions[1], false) != null) {
                throw new AssertionError("Unsupported face/palm entry was rendered");
            }
            requireDescription(unavailable, descriptions[2], false);
        } catch (Throwable failure) {
            failures.append("final home: ").append(failure).append('\n');
        }
    }

    private static View requireDescription(View root, String description, boolean prefix) {
        View found = findDescription(root, description, prefix);
        if (found == null) throw new AssertionError("Missing control: " + description);
        return found;
    }

    private static View findDescription(View root, String description, boolean prefix) {
        CharSequence current = root.getContentDescription();
        if (current != null && (prefix ? current.toString().startsWith(description)
                : current.toString().equals(description))) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int index = 0; index < group.getChildCount(); index++) {
                View found = findDescription(group.getChildAt(index), description, prefix);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void checkCase(StringBuilder failures, int resourceWidth, int resourceHeight,
            int actualWidth, int actualHeight) {
        try {
            Context base = getTargetContext();
            DisplayMetrics metrics = new DisplayMetrics();
            metrics.setTo(base.getResources().getDisplayMetrics());
            metrics.widthPixels = resourceWidth;
            metrics.heightPixels = resourceHeight;
            Resources resources = new Resources(base.getAssets(), metrics,
                    base.getResources().getConfiguration()) {
                @Override public DisplayMetrics getDisplayMetrics() { return metrics; }
            };
            Context context = new ContextWrapper(base) {
                @Override public Resources getResources() { return resources; }
            };
            ZipPixelShell shell = new ZipPixelShell(context, ZipScreenAsset.HOME_WAITING);
            addMarker(context, shell.contentLayer());
            addMarker(context, shell.overlayLayer());
            int[][] viewports = {{resourceWidth, resourceHeight}, {actualWidth, actualHeight},
                    {actualWidth, actualHeight}, {resourceWidth, resourceHeight},
                    {actualWidth, actualHeight}};
            for (int[] viewport : viewports) {
                shell.measure(View.MeasureSpec.makeMeasureSpec(viewport[0], View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(viewport[1], View.MeasureSpec.EXACTLY));
                shell.layout(0, 0, viewport[0], viewport[1]);
                assertAligned(shell.contentLayer(), viewport[0], viewport[1]);
                assertAligned(shell.overlayLayer(), viewport[0], viewport[1]);
                assertTouch(shell, shell.overlayLayer());
                shell.overlayLayer().setVisibility(View.INVISIBLE);
                assertTouch(shell, shell.contentLayer());
                shell.overlayLayer().setVisibility(View.VISIBLE);
            }
        } catch (Throwable failure) {
            failures.append(resourceWidth).append('x').append(resourceHeight).append(" -> ")
                    .append(actualWidth).append('x').append(actualHeight).append(": ")
                    .append(failure).append('\n');
        }
    }

    private static void addMarker(Context context, FrameLayout layer) {
        View marker = new View(context) {
            @Override public boolean onTouchEvent(MotionEvent event) {
                // A detached View queues performClick until attached; record delivery
                // here so this test checks framework hit-routing without fake timing.
                if (event.getActionMasked() == MotionEvent.ACTION_UP) setActivated(true);
                return super.onTouchEvent(event);
            }
        };
        marker.setTag("marker");
        marker.setClickable(true);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ZipKioskShell.unit(context, 444), ZipKioskShell.unit(context, 342));
        params.leftMargin = ZipKioskShell.unit(context, 433);
        params.topMargin = ZipKioskShell.unit(context, 289);
        layer.addView(marker, params);
    }

    private static void assertAligned(FrameLayout layer, int width, int height) {
        View marker = layer.findViewWithTag("marker");
        RectF rect = new RectF(marker.getLeft(), marker.getTop(), marker.getRight(), marker.getBottom());
        layer.getMatrix().mapRect(rect);
        rect.offset(layer.getLeft(), layer.getTop());
        float scale = Math.min(width / 1280f, height / 800f);
        float x = (width - 1280f * scale) / 2f;
        float y = (height - 800f * scale) / 2f;
        close("left", x + 433 * scale, rect.left);
        close("top", y + 289 * scale, rect.top);
        close("right", x + 877 * scale, rect.right);
        close("bottom", y + 631 * scale, rect.bottom);
    }

    private static void close(String edge, float expected, float actual) {
        if (Math.abs(expected - actual) > 1.5f) {
            throw new AssertionError(edge + " expected " + expected + " but was " + actual);
        }
    }

    private static void assertTouch(ZipPixelShell shell, FrameLayout layer) {
        View marker = layer.findViewWithTag("marker");
        marker.setActivated(false);
        RectF rect = new RectF(marker.getLeft(), marker.getTop(), marker.getRight(), marker.getBottom());
        layer.getMatrix().mapRect(rect);
        rect.offset(layer.getLeft(), layer.getTop());
        long now = SystemClock.uptimeMillis();
        // Near the bottom-right corner detects a drawn-but-not-clickable scaled fringe.
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN,
                rect.right - 4, rect.bottom - 4, 0);
        MotionEvent up = MotionEvent.obtain(now, now + 20, MotionEvent.ACTION_UP,
                rect.right - 4, rect.bottom - 4, 0);
        try {
            shell.dispatchTouchEvent(down);
            shell.dispatchTouchEvent(up);
            if (!marker.isActivated()) throw new AssertionError("Transformed button did not receive tap");
        } finally { down.recycle(); up.recycle(); }
    }
}
