package com.codex.lockertest.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.codex.lockertest.ui.zip.ZipAdminScreenRouter;
import com.codex.lockertest.ui.zip.ZipScreenAsset;

/** Four real administrator destinations above ZIP page 42. */
public final class AdminFunctionOverlay extends FrameLayout {
    private static final int ADMIN_PANEL_LEFT = 20;
    private static final int ADMIN_PANEL_TOP = 105;
    private static final int ADMIN_PANEL_RIGHT = 1260;
    private static final int ADMIN_PANEL_BOTTOM = 710;
    public interface Listener {
        void onSerialRequested();
        void onFaceSdkRequested();
        void onEnrollmentRequested();
        void onHomeRequested();
    }

    private Listener listener;

    public AdminFunctionOverlay(Context context) {
        super(context);
        build(context);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    private void build(Context context) {
        ZipKioskShell shell = new ZipKioskShell(context, ZipScreenAsset.ADMIN_FUNCTIONS);
        addView(shell, match());

        FrameLayout nativeAdminPanel = new FrameLayout(context);
        nativeAdminPanel.setBackgroundColor(Color.WHITE);
        FrameLayout.LayoutParams nativePanelParams = new FrameLayout.LayoutParams(
                ZipKioskShell.unit(context, ADMIN_PANEL_RIGHT - ADMIN_PANEL_LEFT),
                ZipKioskShell.unit(context, ADMIN_PANEL_BOTTOM - ADMIN_PANEL_TOP));
        nativePanelParams.leftMargin = ZipKioskShell.unit(context, ADMIN_PANEL_LEFT);
        nativePanelParams.topMargin = ZipKioskShell.unit(context, ADMIN_PANEL_TOP);
        shell.pixelContent().addView(nativeAdminPanel, nativePanelParams);

        FrameLayout card = new FrameLayout(context);
        card.setBackground(UiKit.roundedSolid(
                context, Color.WHITE, ZipKioskShell.designDp(context, 18),
                Color.rgb(40, 194, 153), 1));
        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
                ZipKioskShell.unit(context, 640),
                ZipKioskShell.unit(context, 350));
        cardParams.leftMargin = ZipKioskShell.unit(context, 322);
        cardParams.topMargin = ZipKioskShell.unit(context, 185);
        shell.pixelContent().addView(card, cardParams);

        TextView guidance = UiKit.text(
                context, "请选择管理功能", 20, UiKit.TEXT, Typeface.BOLD);
        guidance.setGravity(Gravity.CENTER);
        guidance.setBackground(UiKit.roundedSolid(
                context, Color.rgb(244, 248, 249),
                ZipKioskShell.designDp(context, 8), Color.rgb(177, 199, 205), 1));
        FrameLayout.LayoutParams guidanceParams = new FrameLayout.LayoutParams(
                ZipKioskShell.unit(context, 520),
                ZipKioskShell.unit(context, 54));
        guidanceParams.leftMargin = ZipKioskShell.unit(context, 60);
        guidanceParams.topMargin = ZipKioskShell.unit(context, 40);
        card.addView(guidance, guidanceParams);

        addAction(card, action(context, "串口调试", Color.rgb(43, 141, 218),
                view -> callSerial()), context, 60, 115);
        addAction(card, action(context, "百度人脸 SDK", UiKit.GREEN,
                view -> callFace()), context, 340, 115);
        addAction(card, action(context, "人脸/掌纹录入", Color.rgb(44, 160, 183),
                view -> callEnrollment()), context, 60, 205);
        addAction(card, action(context, "返回首页", Color.rgb(132, 147, 150),
                view -> callHome()), context, 340, 205);

        shell.setScreenAsset(ZipAdminScreenRouter.adminFunctions());
    }

    private void callSerial() {
        Listener current = listener;
        if (current != null) current.onSerialRequested();
    }

    private void callFace() {
        Listener current = listener;
        if (current != null) current.onFaceSdkRequested();
    }

    private void callEnrollment() {
        Listener current = listener;
        if (current != null) current.onEnrollmentRequested();
    }

    private void callHome() {
        Listener current = listener;
        if (current != null) current.onHomeRequested();
    }

    private static Button action(Context context, CharSequence label,
            int color, View.OnClickListener listener) {
        Button button = UiKit.button(
                context, label, 18, color, Color.WHITE,
                ZipKioskShell.designDp(context, 28));
        button.setContentDescription(label);
        button.setOnClickListener(listener);
        return button;
    }

    private static void addAction(FrameLayout card, Button action,
            Context context, int left, int top) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ZipKioskShell.unit(context, 240),
                ZipKioskShell.unit(context, 60));
        params.leftMargin = ZipKioskShell.unit(context, left);
        params.topMargin = ZipKioskShell.unit(context, top);
        card.addView(action, params);
    }

    private static LayoutParams match() {
        return new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
    }
}
