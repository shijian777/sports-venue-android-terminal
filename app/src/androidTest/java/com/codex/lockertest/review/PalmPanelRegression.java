package com.codex.lockertest.review;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.codex.lockertest.palm.PalmHardwareTestPanelHandle;
import com.codex.lockertest.ui.BiometricEnrollmentChoiceView;

/** Tests the real enrollment view's panel ownership without USB or native calls. */
public final class PalmPanelRegression {
    public static void check(Context context) {
        final int[] calls = new int[4];
        final Runnable[] back = new Runnable[1];
        final FrameLayout panel = new FrameLayout(context);
        panel.setContentDescription("掌静脉测试面板");
        BiometricEnrollmentChoiceView view = new BiometricEnrollmentChoiceView(context,
                (owner, returnAction) -> {
                    calls[0]++;
                    back[0] = returnAction;
                    return new PalmHardwareTestPanelHandle() {
                        public View view() { return panel; }
                        public void close() { calls[1]++; }
                    };
                });
        view.setListener(new BiometricEnrollmentChoiceView.Listener() {
            public void onFaceRequested() { calls[2]++; }
            public void onPalmRequested() { calls[3]++; }
            public void onRetryRequested() { }
            public void onBackRequested() { }
        });
        View palm = find(view, "查看掌纹设备状态");
        palm.performClick();
        if (calls[0] != 1 || calls[3] != 0 || panel.getParent() == null) {
            throw new AssertionError("Palm test did not replace unavailable route");
        }
        palm.performClick();
        if (calls[0] != 1) throw new AssertionError("Repeated click opened another panel");
        back[0].run();
        if (calls[1] != 1 || panel.getParent() != null || palm.getVisibility() != View.VISIBLE) {
            throw new AssertionError("Back did not close panel and restore choice");
        }
        find(view, "开始人脸采集").performClick();
        if (calls[2] != 1) throw new AssertionError("Palm integration broke face action");
        palm.performClick();
        view.showChoice();
        if (calls[0] != 2 || calls[1] != 2) throw new AssertionError("Choice reset leaked panel");
        back[0].run();
        if (calls[1] != 2) throw new AssertionError("Late back closed panel twice");
        palm.performClick();
        view.closePalmHardwareTest();
        view.closePalmHardwareTest();
        if (calls[0] != 3 || calls[1] != 3 || panel.getParent() != null) {
            throw new AssertionError("Activity lifecycle cleanup did not release panel exactly once");
        }

        final int[] fallback = {0};
        BiometricEnrollmentChoiceView unavailable = new BiometricEnrollmentChoiceView(context,
                (owner, returnAction) -> null);
        unavailable.setListener(new BiometricEnrollmentChoiceView.Listener() {
            public void onFaceRequested() { }
            public void onPalmRequested() { fallback[0]++; }
            public void onRetryRequested() { }
            public void onBackRequested() { }
        });
        find(unavailable, "查看掌纹设备状态").performClick();
        if (fallback[0] != 1) throw new AssertionError("Production unavailable route changed");

        checkFactoryFailure(context, false);
        checkFactoryFailure(context, true);
        checkHandleViewFailure(context);
        checkCloseFailureCannotBlockCleanup(context);
    }

    private static void checkFactoryFailure(Context context, boolean linkageFailure) {
        final int[] callbacks = new int[3];
        BiometricEnrollmentChoiceView view = new BiometricEnrollmentChoiceView(context,
                (owner, returnAction) -> {
                    if (linkageFailure) throw new UnsatisfiedLinkError("missing palm JNI");
                    throw new IllegalStateException("palm factory failed");
                });
        installBoundaryListener(view, callbacks);

        find(view, "查看掌纹设备状态").performClick();

        assertUnavailableBoundary(view, callbacks,
                linkageFailure ? "LinkageError factory" : "RuntimeException factory");
    }

    private static void checkHandleViewFailure(Context context) {
        final int[] callbacks = new int[3];
        final int[] closes = {0};
        BiometricEnrollmentChoiceView view = new BiometricEnrollmentChoiceView(context,
                (owner, returnAction) -> new PalmHardwareTestPanelHandle() {
                    public View view() { throw new IllegalStateException("view failed"); }
                    public void close() { closes[0]++; }
                });
        installBoundaryListener(view, callbacks);

        find(view, "查看掌纹设备状态").performClick();

        if (closes[0] != 1) throw new AssertionError("Failed panel view was not closed");
        assertUnavailableBoundary(view, callbacks, "handle view failure");
    }

    private static void checkCloseFailureCannotBlockCleanup(Context context) {
        final int[] callbacks = new int[3];
        final int[] calls = new int[3];
        final FrameLayout panel = new FrameLayout(context);
        panel.setContentDescription("抛错掌静脉测试面板");
        BiometricEnrollmentChoiceView view = new BiometricEnrollmentChoiceView(context,
                (owner, returnAction) -> new PalmHardwareTestPanelHandle() {
                    public View view() {
                        calls[0]++;
                        if (calls[0] > 1) throw new UnsatisfiedLinkError("view called after attach");
                        return panel;
                    }
                    public void close() {
                        calls[1]++;
                        throw new IllegalStateException("close failed");
                    }
                });
        installBoundaryListener(view, callbacks);
        View palm = find(view, "查看掌纹设备状态");
        palm.performClick();
        if (panel.getParent() == null) throw new AssertionError("Throwing panel did not attach");

        view.closePalmHardwareTest();
        calls[2]++;

        if (calls[0] != 1) throw new AssertionError("Cleanup called handle.view again");
        if (calls[1] != 1) throw new AssertionError("Cleanup did not close exactly once");
        if (calls[2] != 1) throw new AssertionError("Panel close blocked caller cleanup");
        if (panel.getParent() != null) throw new AssertionError("Close failure retained panel view");
        if (palm.getVisibility() != View.INVISIBLE) {
            throw new AssertionError("Explicit lifecycle close unexpectedly changed page state");
        }
        view.showChoice();
        if (palm.getVisibility() != View.VISIBLE) {
            throw new AssertionError("Choice could not recover after close failure");
        }
        view.closePalmHardwareTest();
        if (calls[1] != 1) throw new AssertionError("Close failure broke idempotence");
        if (callbacks[0] != 0 || callbacks[1] != 0) {
            throw new AssertionError("Close failure invoked a business enrollment callback");
        }
    }

    private static void installBoundaryListener(BiometricEnrollmentChoiceView view,
            final int[] callbacks) {
        view.setListener(new BiometricEnrollmentChoiceView.Listener() {
            public void onFaceRequested() { callbacks[0]++; }
            public void onPalmRequested() { callbacks[1]++; }
            public void onRetryRequested() { }
            public void onBackRequested() { callbacks[2]++; }
        });
    }

    private static void assertUnavailableBoundary(BiometricEnrollmentChoiceView view,
            int[] callbacks, String source) {
        View status = find(view, "掌纹设备暂未接入");
        View back = find(view, "返回录入方式选择");
        if (status.getVisibility() != View.VISIBLE || back.getVisibility() != View.VISIBLE
                || !back.isEnabled()) {
            throw new AssertionError(source + " did not show readable unavailable/back state");
        }
        if (callbacks[0] != 0 || callbacks[1] != 0) {
            throw new AssertionError(source + " invoked face or palm business fallback");
        }
        back.performClick();
        if (callbacks[2] != 1) throw new AssertionError(source + " back was not usable");
    }

    private static View find(View root, String description) {
        View found = findOrNull(root, description);
        if (found != null) return found;
        throw new AssertionError("Missing action: " + description);
    }

    private static View findOrNull(View root, String description) {
        if (description.contentEquals(root.getContentDescription() == null
                ? "" : root.getContentDescription())) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int index = 0; index < group.getChildCount(); index++) {
                View found = findOrNull(group.getChildAt(index), description);
                if (found != null) return found;
            }
        }
        return null;
    }
}
