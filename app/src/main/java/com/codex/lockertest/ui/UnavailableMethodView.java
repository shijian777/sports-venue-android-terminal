package com.codex.lockertest.ui;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;

import com.codex.lockertest.model.UnlockMethod;
import com.codex.lockertest.ui.zip.ZipCustomerScreenRouter;
import com.codex.lockertest.ui.zip.ZipPixelShell;
import com.codex.lockertest.ui.zip.ZipScreenAsset;

/** Presentation-only palm guide and fail-closed unavailable destination. */
public final class UnavailableMethodView extends FrameLayout {
    public interface Listener {
        void onReturnHome();

        void onShowUnavailableAgain();
    }

    private final UnlockMethod method;
    private final ZipPixelShell pixelShell;
    private final Button topReturnButton;
    private final Button startButton;
    private final Button safeHomeButton;
    private Listener listener;

    public UnavailableMethodView(Context context, UnlockMethod method) {
        super(context);
        if (method != UnlockMethod.PALM) {
            throw new IllegalArgumentException("method must be PALM");
        }
        this.method = method;

        pixelShell = new ZipPixelShell(context, ZipScreenAsset.PALM_GUIDE);
        pixelShell.setOnSafeHomeRequested(this::returnHome);
        addView(pixelShell, match());
        FrameLayout overlay = pixelShell.overlayLayer();

        topReturnButton = UiKit.button(
                context, "返回", 15, Color.rgb(129, 143, 145),
                Color.WHITE, designDp(context, 20));
        topReturnButton.setContentDescription("返回首页");
        topReturnButton.setOnClickListener(view -> returnHome());
        place(overlay, topReturnButton, 1050, 110, 176, 38);

        startButton = UiKit.button(
                context, "开始识别", 18, UiKit.GREEN,
                Color.WHITE, designDp(context, 28));
        startButton.setContentDescription("检查掌纹识别设备");
        startButton.setOnClickListener(view -> {
            Listener current = listener;
            if (current != null) {
                current.onShowUnavailableAgain();
            }
            showUnavailablePrompt();
        });
        place(overlay, startButton, 520, 621, 240, 59);

        safeHomeButton = UiKit.button(
                context, "返回首页", 15, Color.rgb(129, 143, 145),
                Color.WHITE, designDp(context, 20));
        safeHomeButton.setContentDescription("安全返回首页");
        safeHomeButton.setOnClickListener(view -> returnHome());
        place(overlay, safeHomeButton, 565, 478, 150, 44);

        showPalmGuide();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public UnlockMethod method() {
        return method;
    }

    /** The check action can only reveal the fail-closed page 13. */
    public void showUnavailablePrompt() {
        pixelShell.setScreenAsset(ZipCustomerScreenRouter.assetFor(
                ZipCustomerScreenRouter.State.PALM_DEVICE_UNAVAILABLE));
        topReturnButton.setVisibility(View.GONE);
        topReturnButton.setEnabled(false);
        startButton.setVisibility(View.GONE);
        startButton.setEnabled(false);
        safeHomeButton.setVisibility(View.VISIBLE);
        safeHomeButton.setEnabled(true);
        safeHomeButton.bringToFront();
    }

    private void showPalmGuide() {
        pixelShell.setScreenAsset(ZipCustomerScreenRouter.assetFor(
                ZipCustomerScreenRouter.State.PALM_GUIDE));
        topReturnButton.setVisibility(View.VISIBLE);
        topReturnButton.setEnabled(true);
        startButton.setVisibility(View.VISIBLE);
        startButton.setEnabled(true);
        safeHomeButton.setVisibility(View.GONE);
        safeHomeButton.setEnabled(false);
    }

    private void returnHome() {
        Listener current = listener;
        if (current != null) {
            current.onReturnHome();
        }
    }

    private static void place(FrameLayout parent, View child,
            int x, int y, int width, int height) {
        LayoutParams params = new LayoutParams(
                unit(parent.getContext(), width), unit(parent.getContext(), height));
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.leftMargin = unit(parent.getContext(), x);
        params.topMargin = unit(parent.getContext(), y);
        parent.addView(child, params);
    }

    private static int unit(Context context, float designUnits) {
        return ZipKioskShell.unit(context, designUnits);
    }

    private static float designDp(Context context, float designUnits) {
        return ZipKioskShell.designDp(context, designUnits);
    }

    private static LayoutParams match() {
        return new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
    }
}
