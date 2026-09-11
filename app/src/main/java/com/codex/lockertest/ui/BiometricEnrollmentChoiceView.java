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

import com.codex.lockertest.ui.zip.BiometricEnrollmentScreenRouter;
import com.codex.lockertest.ui.zip.ZipPixelShell;
import com.codex.lockertest.ui.zip.ZipScreenAsset;
import com.codex.lockertest.palm.PalmHardwareTestAssembly;
import com.codex.lockertest.palm.PalmHardwareTestPanelHandle;

/** Opaque native interaction surface for the enrollment choice and unavailable pages. */
public final class BiometricEnrollmentChoiceView extends FrameLayout {
    public interface PalmTestPanelFactory {
        PalmHardwareTestPanelHandle create(Context context, Runnable onBack);
    }
    public interface Listener {
        void onFaceRequested();
        void onPalmRequested();
        void onRetryRequested();
        void onBackRequested();
    }

    private final ZipPixelShell pixelShell;
    private final FrameLayout faceCard;
    private final FrameLayout palmCard;
    private final Button faceButton;
    private final Button palmButton;
    private final Button retryButton;
    private final Button choiceBackButton;
    private final Button palmBackButton;
    private final FrameLayout blockerCard;
    private final TextView blockerText;
    private Listener listener;
    private final PalmTestPanelFactory palmTestFactory;
    private PalmHardwareTestPanelHandle palmTestPanel;
    private View palmTestPanelView;
    private long palmPanelGeneration;

    public BiometricEnrollmentChoiceView(Context context) {
        this(context, false);
    }

    public BiometricEnrollmentChoiceView(Context context, boolean allowHardwareTest) {
        this(context, allowHardwareTest ? PalmHardwareTestAssembly::create
                : (owner, onBack) -> null);
    }

    public BiometricEnrollmentChoiceView(Context context, PalmTestPanelFactory palmTestFactory) {
        super(context);
        if (palmTestFactory == null) throw new IllegalArgumentException("Palm factory is required");
        this.palmTestFactory = palmTestFactory;
        pixelShell = new ZipPixelShell(context, ZipScreenAsset.ENROLLMENT_CHOICE);
        pixelShell.setOnSafeHomeRequested(null);
        addView(pixelShell, match());
        FrameLayout content = pixelShell.contentLayer();
        FrameLayout overlay = pixelShell.overlayLayer();

        FrameLayout nativePanel = new FrameLayout(context);
        nativePanel.setBackgroundColor(Color.rgb(247, 251, 252));
        nativePanel.setContentDescription("人脸或掌纹录入方式选择区域");
        place(content, nativePanel, 20, 105, 1240, 605);

        TextView heading = text(context, "选择录入方式", 28,
                Color.rgb(27, 88, 139), Typeface.BOLD);
        heading.setGravity(Gravity.CENTER);
        heading.setContentDescription("选择录入方式");
        place(content, heading, 440, 135, 400, 58);

        faceCard = methodCard(context, "人脸录入", "使用终端摄像头进行本地人脸采集演示",
                Color.rgb(42, 139, 216));
        place(content, faceCard, 285, 260, 330, 240);

        palmCard = methodCard(context, "掌纹录入", "需要掌纹设备驱动与服务器接口",
                Color.rgb(78, 94, 218));
        place(content, palmCard, 665, 260, 330, 240);

        faceButton = action(context, "开始人脸采集", Color.rgb(42, 139, 216));
        faceButton.setContentDescription("开始人脸采集");
        faceButton.setOnClickListener(view -> notifyFace());
        place(overlay, faceButton, 335, 405, 230, 55);

        palmButton = action(context, "掌纹录入", Color.rgb(78, 94, 218));
        palmButton.setContentDescription("查看掌纹设备状态");
        palmButton.setOnClickListener(view -> notifyPalm());
        place(overlay, palmButton, 715, 405, 230, 55);

        choiceBackButton = action(context, "返回", Color.rgb(129, 143, 145));
        choiceBackButton.setContentDescription("返回上一页");
        choiceBackButton.setOnClickListener(view -> notifyBack());
        place(overlay, choiceBackButton, 550, 550, 180, 48);

        palmBackButton = action(context, "返回", Color.rgb(129, 143, 145));
        palmBackButton.setContentDescription("返回录入方式选择");
        palmBackButton.setOnClickListener(view -> notifyBack());
        place(overlay, palmBackButton, 1050, 110, 176, 38);

        blockerCard = new FrameLayout(context);
        blockerCard.setBackground(UiKit.roundedSolid(
                context, Color.WHITE, designDp(context, 12), UiKit.RED, 2));
        blockerCard.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        blockerText = text(context, "暂时无法开始人脸采集", 20,
                UiKit.RED, Typeface.BOLD);
        blockerText.setGravity(Gravity.CENTER);
        blockerText.setPadding(unit(context, 24), 0, unit(context, 24), 0);
        blockerCard.addView(blockerText, match());
        place(overlay, blockerCard, 350, 180, 580, 150);

        retryButton = action(context, "重新检查", UiKit.GREEN);
        retryButton.setContentDescription("重新检查人脸采集条件");
        retryButton.setOnClickListener(view -> notifyRetry());
        place(overlay, retryButton, 550, 345, 180, 48);

        showChoice();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void showChoice() {
        closePalmTestPanel();
        pixelShell.setScreenAsset(ZipScreenAsset.ENROLLMENT_CHOICE);
        showMethods(true);
        blockerCard.setVisibility(View.GONE);
        retryButton.setVisibility(View.GONE);
        retryButton.setEnabled(false);
        showChoiceBack(true);
        showPalmBack(false);
    }

    public void showBlocker(BiometricEnrollmentScreenRouter.Blocker blocker) {
        closePalmTestPanel();
        if (blocker == null || blocker == BiometricEnrollmentScreenRouter.Blocker.NONE) {
            showChoice();
            return;
        }
        pixelShell.setScreenAsset(ZipScreenAsset.ENROLLMENT_CHOICE);
        showMethods(false);
        CharSequence message = blockerMessage(blocker);
        blockerText.setText(message);
        blockerText.setContentDescription(message);
        blockerCard.setVisibility(View.VISIBLE);
        blockerCard.bringToFront();
        retryButton.setVisibility(View.VISIBLE);
        retryButton.setEnabled(true);
        retryButton.bringToFront();
        showChoiceBack(true);
        showPalmBack(false);
        choiceBackButton.bringToFront();
    }

    public void showPalmUnavailable() {
        closePalmTestPanel();
        pixelShell.setScreenAsset(ZipScreenAsset.PALM_ENROLLMENT_UNAVAILABLE);
        showMethods(false);
        blockerText.setText("掌纹设备暂未接入\n请联系管理员完成设备、驱动及服务器配置");
        blockerText.setContentDescription("掌纹设备暂未接入");
        blockerCard.setVisibility(View.VISIBLE);
        blockerCard.bringToFront();
        retryButton.setVisibility(View.GONE);
        retryButton.setEnabled(false);
        showChoiceBack(false);
        showPalmBack(true);
        palmBackButton.bringToFront();
    }

    private void showChoiceBack(boolean visible) {
        choiceBackButton.setVisibility(visible ? View.VISIBLE : View.GONE);
        choiceBackButton.setEnabled(visible);
    }

    private void showPalmBack(boolean visible) {
        palmBackButton.setVisibility(visible ? View.VISIBLE : View.GONE);
        palmBackButton.setEnabled(visible);
    }

    private void showMethods(boolean visible) {
        int visibility = visible ? View.VISIBLE : View.INVISIBLE;
        faceCard.setVisibility(visibility);
        palmCard.setVisibility(visibility);
        faceButton.setVisibility(visibility);
        palmButton.setVisibility(visibility);
        faceButton.setEnabled(visible);
        palmButton.setEnabled(visible);
    }

    private static CharSequence blockerMessage(
            BiometricEnrollmentScreenRouter.Blocker blocker) {
        switch (blocker) {
            case NETWORK_OFFLINE:
                return "网络异常，暂时无法开始人脸采集";
            case LICENSE_NOT_READY:
                return "百度人脸 SDK 尚未完成授权，请先在管理员页面激活";
            case RUNTIME_NOT_READY:
                return "人脸检测模型尚未就绪，请先在管理员页面初始化";
            case CAMERA_PERMISSION_DENIED:
                return "需要摄像头权限才能进行人脸采集";
            case CAMERA_UNAVAILABLE:
                return "摄像头暂时不可用，请重新检查";
            case NONE:
            default:
                return "暂时无法开始人脸采集";
        }
    }

    private static FrameLayout methodCard(Context context, String title,
            String description, int accent) {
        FrameLayout card = new FrameLayout(context);
        card.setBackground(UiKit.roundedSolid(
                context, Color.WHITE, designDp(context, 14), accent, 2));
        TextView titleView = text(context, title, 25, accent, Typeface.BOLD);
        titleView.setGravity(Gravity.CENTER);
        place(card, titleView, 25, 28, 280, 54);
        TextView descriptionView = text(context, description, 16,
                Color.rgb(75, 99, 108), Typeface.NORMAL);
        descriptionView.setGravity(Gravity.CENTER);
        descriptionView.setPadding(unit(context, 18), 0, unit(context, 18), 0);
        place(card, descriptionView, 20, 88, 290, 82);
        return card;
    }

    private static Button action(Context context, String label, int color) {
        return UiKit.button(context, label, 17, color, Color.WHITE,
                designDp(context, 22));
    }

    private static TextView text(Context context, String value,
            float size, int color, int style) {
        return ZipKioskShell.scaledText(
                UiKit.text(context, value, size, color, style), size);
    }

    private void notifyFace() {
        Listener current = listener;
        if (current != null) current.onFaceRequested();
    }

    private void notifyPalm() {
        if (palmTestPanel != null) return;
        final long generation = ++palmPanelGeneration;
        PalmHardwareTestPanelHandle panel;
        try {
            panel = palmTestFactory.create(getContext(), () -> {
                if (generation == palmPanelGeneration && palmTestPanel != null) showChoice();
            });
        } catch (RuntimeException | LinkageError unavailable) {
            showPalmUnavailable();
            return;
        }
        if (panel != null) {
            View panelView;
            try {
                panelView = panel.view();
            } catch (RuntimeException | LinkageError unavailable) {
                closePanelHandle(panel);
                showPalmUnavailable();
                return;
            }
            if (panelView == null) {
                closePanelHandle(panel);
                showPalmUnavailable();
                return;
            }
            palmTestPanel = panel;
            palmTestPanelView = panelView;
            try {
                addView(panelView, match());
            } catch (RuntimeException | LinkageError unavailable) {
                closePalmTestPanel();
                showPalmUnavailable();
                return;
            }
            showMethods(false);
            showChoiceBack(false);
            blockerCard.setVisibility(View.GONE);
            retryButton.setVisibility(View.GONE);
            showPalmBack(false);
            return;
        }
        Listener current = listener;
        if (current != null) current.onPalmRequested();
    }

    private void closePalmTestPanel() {
        ++palmPanelGeneration;
        PalmHardwareTestPanelHandle panel = palmTestPanel;
        View panelView = palmTestPanelView;
        palmTestPanel = null;
        palmTestPanelView = null;
        if (panel != null) {
            try {
                closePanelHandle(panel);
            } finally {
                removePanelView(panelView);
            }
        }
    }

    private static void closePanelHandle(PalmHardwareTestPanelHandle panel) {
        try {
            panel.close();
        } catch (RuntimeException | LinkageError ignored) {
            // Native cleanup failure must not break the Activity's remaining cleanup.
        }
    }

    private void removePanelView(View panelView) {
        if (panelView == null) return;
        try {
            removeView(panelView);
        } catch (RuntimeException | LinkageError ignored) {
            // Ownership was already revoked; lifecycle cleanup must continue.
        }
    }

    /** Called by Activity lifecycle before its enrollment view reference is cleared. */
    public void closePalmHardwareTest() { closePalmTestPanel(); }

    @Override protected void onDetachedFromWindow() {
        closePalmTestPanel();
        super.onDetachedFromWindow();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        // A backgrounded diagnostic must not retain a sample or keep collecting.
        if (visibility != View.VISIBLE && palmTestPanel != null) showChoice();
    }

    private void notifyRetry() {
        Listener current = listener;
        if (current != null) current.onRetryRequested();
    }

    private void notifyBack() {
        Listener current = listener;
        if (current != null) current.onBackRequested();
    }

    private static void place(FrameLayout parent, View child,
            int x, int y, int width, int height) {
        LayoutParams params = new LayoutParams(unit(parent.getContext(), width),
                unit(parent.getContext(), height));
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.leftMargin = unit(parent.getContext(), x);
        params.topMargin = unit(parent.getContext(), y);
        parent.addView(child, params);
    }

    private static int unit(Context context, float value) {
        return ZipKioskShell.unit(context, value);
    }

    private static float designDp(Context context, float value) {
        return ZipKioskShell.designDp(context, value);
    }

    private static LayoutParams match() {
        return new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
    }
}
