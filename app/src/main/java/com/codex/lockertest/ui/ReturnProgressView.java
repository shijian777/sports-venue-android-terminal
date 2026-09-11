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

import com.codex.lockertest.ui.zip.ReturnScreenPresentation;
import com.codex.lockertest.ui.zip.ZipPixelShell;
import com.codex.lockertest.ui.zip.ZipScreenAsset;

/** Native typed actions over ZIP return progress/result pages 35-39. */
public final class ReturnProgressView extends FrameLayout {
    /** Compatibility vocabulary retained for non-Main callers. */
    public enum State { OPENING, WAITING_FOR_CLOSE, COMMITTING, SUCCESS, ERROR }

    public interface Listener {
        void onDoorClosedConfirmed();
        void onRetryRequested();
        void onCancelRequested();
        void onNextRequested();
        void onHomeRequested();
    }

    private final ZipPixelShell shell;
    private final FrameLayout promptCard;
    private final TextView title;
    private final TextView locker;
    private final TextView detail;
    private final TextView countdown;
    private final Button leftButton;
    private final Button rightButton;
    private final Button centerButton;
    private final Button safeReturnButton;
    private ReturnScreenPresentation presentation;
    private Listener listener;

    public ReturnProgressView(Context context) {
        super(context);
        shell = new ZipPixelShell(context, ZipScreenAsset.RETURN_OPENING);
        shell.setOnSafeHomeRequested(this::notifySafeHome);
        addView(shell, match());
        FrameLayout content = shell.contentLayer();

        promptCard = new FrameLayout(context);
        promptCard.setBackground(UiKit.roundedSolid(
                context, Color.rgb(250, 252, 252), designDp(context, 12),
                Color.rgb(87, 194, 209), designDp(context, 2)));
        promptCard.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        place(content, promptCard, 355, 225, 570, 330);

        title = nativeText(context, "正在准备柜门", 27,
                UiKit.NAVY, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        place(content, title, 410, 250, 460, 54);

        locker = nativeText(context, "柜门：--", 21,
                UiKit.DARK_GREEN, Typeface.BOLD);
        locker.setGravity(Gravity.CENTER);
        locker.setBackground(UiKit.roundedSolid(
                context, Color.rgb(229, 247, 241), designDp(context, 9),
                Color.rgb(139, 214, 191), designDp(context, 1)));
        place(content, locker, 440, 316, 400, 48);

        detail = nativeText(context, "正在准备串口与柜门关闭基线…", 17,
                UiKit.TEXT, Typeface.NORMAL);
        detail.setGravity(Gravity.CENTER);
        detail.setPadding(unit(context, 15), 0, unit(context, 15), 0);
        place(content, detail, 400, 375, 480, 80);

        countdown = nativeText(context, "", 16, UiKit.MUTED, Typeface.BOLD);
        countdown.setGravity(Gravity.CENTER);
        place(content, countdown, 450, 442, 380, 32);

        leftButton = opaqueButton(context, "重新尝试", 17, UiKit.GREEN);
        leftButton.setOnClickListener(view -> notifyRetry());
        place(content, leftButton, 470, 480, 150, 46);

        rightButton = opaqueButton(context, "返回首页", 17, UiKit.NAVY);
        rightButton.setOnClickListener(view -> notifyHome());
        place(content, rightButton, 660, 480, 150, 46);

        centerButton = opaqueButton(context, "柜门已关闭", 17, UiKit.GREEN);
        centerButton.setOnClickListener(view -> notifyCenterAction());
        place(content, centerButton, 565, 480, 150, 46);

        safeReturnButton = opaqueButton(
                context, "安全返回", 16, Color.rgb(121, 137, 139));
        safeReturnButton.setOnClickListener(view -> notifyCancel());
        place(shell.overlayLayer(), safeReturnButton, 1050, 110, 176, 38);

        hideAllActions();
    }

    public void setListener(Listener listener) { this.listener = listener; }

    public void render(ReturnScreenPresentation presentation,
            CharSequence lockerLabel, CharSequence statusMessage,
            CharSequence countdownText) {
        if (presentation == null
                || presentation.surface() != ReturnScreenPresentation.Surface.PROGRESS) {
            throw new IllegalArgumentException("progress presentation is required");
        }
        this.presentation = presentation;
        shell.setScreenAsset(presentation.asset());

        CharSequence visibleLocker = normalize(lockerLabel, "--");
        locker.setText("柜门：" + visibleLocker);
        locker.setContentDescription("当前办理柜门 " + visibleLocker);
        title.setText(titleFor(presentation.asset()));
        title.setTextColor(colorFor(presentation.asset()));
        detail.setText(normalize(statusMessage, detailFor(presentation.asset())));
        detail.setContentDescription(detail.getText());
        CharSequence visibleCountdown = normalize(countdownText, "");
        countdown.setText(visibleCountdown);
        countdown.setVisibility(visibleCountdown.length() == 0 ? INVISIBLE : VISIBLE);

        hideAllActions();
        if (presentation.canDoorCloseConfirm()) {
            centerButton.setText("柜门已关闭");
            centerButton.setContentDescription("柜门已关闭，立即请求一次新的状态查询");
            centerButton.setVisibility(VISIBLE);
            setActionState(centerButton, true);
        } else if (presentation.asset() == ZipScreenAsset.RETURN_SUCCESS
                && presentation.canHome()) {
            centerButton.setText("返回首页");
            centerButton.setContentDescription("还柜完成，返回首页");
            centerButton.setVisibility(VISIBLE);
            setActionState(centerButton, true);
        } else if (presentation.canRetry() && presentation.canHome()) {
            leftButton.setVisibility(VISIBLE);
            setActionState(leftButton, true);
            rightButton.setVisibility(VISIBLE);
            setActionState(rightButton, true);
        } else if (presentation.canRetry()) {
            centerButton.setText("重新尝试");
            centerButton.setContentDescription("按当前错误类型安全重试");
            centerButton.setVisibility(VISIBLE);
            setActionState(centerButton, true);
        } else if (presentation.canHome()) {
            centerButton.setText("返回首页");
            centerButton.setContentDescription("安全返回首页");
            centerButton.setVisibility(VISIBLE);
            setActionState(centerButton, true);
        }

        safeReturnButton.setVisibility(presentation.canBack() ? VISIBLE : GONE);
        setActionState(safeReturnButton, presentation.canBack());
        promptCard.setImportantForAccessibility(
                presentation.canRetry() || presentation.canDoorCloseConfirm()
                        || presentation.canHome()
                        ? IMPORTANT_FOR_ACCESSIBILITY_YES
                        : IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    }

    /** Compatibility overload. MainActivity uses the typed overload. */
    public void render(State state, CharSequence lockerLabel,
            CharSequence statusMessage, CharSequence countdownText,
            boolean hasNext, boolean cancellable) {
        if (state == null) throw new IllegalArgumentException("state is required");
        locker.setText("柜门：" + normalize(lockerLabel, "--"));
        detail.setText(normalize(statusMessage, "离场还柜处理中"));
        countdown.setText(normalize(countdownText, ""));
    }

    private void hideAllActions() {
        for (Button button : new Button[] {leftButton, rightButton, centerButton}) {
            button.setVisibility(GONE);
            setActionState(button, false);
        }
        safeReturnButton.setVisibility(GONE);
        setActionState(safeReturnButton, false);
    }

    private void notifyCenterAction() {
        ReturnScreenPresentation current = presentation;
        if (current == null) return;
        if (current.canDoorCloseConfirm()) {
            notifyDoorClosed();
        } else if (current.canRetry()) {
            notifyRetry();
        } else if (current.canHome()) {
            notifyHome();
        }
    }

    private void notifyDoorClosed() {
        if (presentation == null || !presentation.canDoorCloseConfirm()) return;
        Listener current = listener;
        if (current != null) current.onDoorClosedConfirmed();
    }

    private void notifyRetry() {
        if (presentation == null || !presentation.canRetry()) return;
        Listener current = listener;
        if (current != null) current.onRetryRequested();
    }

    private void notifyCancel() {
        if (presentation == null || !presentation.canBack()) return;
        Listener current = listener;
        if (current != null) current.onCancelRequested();
    }

    private void notifyHome() {
        if (presentation == null || !presentation.canHome()) return;
        Listener current = listener;
        if (current != null) current.onHomeRequested();
    }

    private void notifySafeHome() {
        if (presentation != null && presentation.canBack()) notifyCancel();
        else if (presentation != null && presentation.canHome()) notifyHome();
    }

    private static String titleFor(ZipScreenAsset asset) {
        if (asset == ZipScreenAsset.RETURN_WAITING_FOR_CLOSE) return "请关好柜门";
        if (asset == ZipScreenAsset.RETURN_COMMITTING) return "正在提交还柜结果";
        if (asset == ZipScreenAsset.RETURN_SUCCESS) return "还柜完成";
        if (asset == ZipScreenAsset.RETURN_FAILED) return "还柜未完成";
        return "正在准备并打开柜门";
    }

    private static String detailFor(ZipScreenAsset asset) {
        if (asset == ZipScreenAsset.RETURN_WAITING_FOR_CLOSE) {
            return "开柜成功不代表还柜完成，请务必关好柜门；"
                    + "点击只会重新检查柜门状态，不会直接完成还柜";
        }
        if (asset == ZipScreenAsset.RETURN_COMMITTING) return "柜门关闭证明已满足，正在提交…";
        if (asset == ZipScreenAsset.RETURN_SUCCESS) return "服务器已确认本次还柜";
        if (asset == ZipScreenAsset.RETURN_FAILED) return "请按错误类型重试或联系工作人员";
        return "正在查询 CLOSED 基线并准备串口，绝不会自动重复开锁";
    }

    private static int colorFor(ZipScreenAsset asset) {
        if (asset == ZipScreenAsset.RETURN_FAILED) return UiKit.RED;
        if (asset == ZipScreenAsset.RETURN_SUCCESS) return UiKit.DARK_GREEN;
        if (asset == ZipScreenAsset.RETURN_WAITING_FOR_CLOSE) return UiKit.AMBER;
        return UiKit.NAVY;
    }

    private static void setActionState(View action, boolean enabled) {
        action.setEnabled(enabled);
        action.setClickable(enabled);
        action.setFocusable(enabled);
        action.setImportantForAccessibility(enabled
                ? IMPORTANT_FOR_ACCESSIBILITY_YES
                : IMPORTANT_FOR_ACCESSIBILITY_NO);
        action.setAlpha(1f);
    }

    private static CharSequence normalize(CharSequence value, CharSequence fallback) {
        return value == null || value.toString().trim().isEmpty() ? fallback : value;
    }

    private static Button opaqueButton(
            Context context, String label, float size, int background) {
        return ZipKioskShell.scaleButton(UiKit.button(
                context, label, size, background, Color.WHITE,
                designDp(context, 9)), size);
    }

    private static TextView nativeText(Context context, String value,
            float size, int color, int style) {
        return ZipKioskShell.scaledText(
                UiKit.text(context, value, size, color, style), size);
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

    private static int unit(Context context, float value) {
        return ZipKioskShell.unit(context, value);
    }

    private static float designDp(Context context, float value) {
        return ZipKioskShell.designDp(context, value);
    }

    private static LayoutParams match() {
        return new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
    }
}
