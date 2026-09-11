package com.codex.lockertest.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.codex.lockertest.model.UnlockMethod;
import com.codex.lockertest.runtime.CredentialAdmissionPolicy;
import com.codex.lockertest.ui.zip.ReturnScreenPresentation;
import com.codex.lockertest.ui.zip.ZipPixelShell;
import com.codex.lockertest.ui.zip.ZipScreenAsset;

import java.util.ArrayList;
import java.util.List;

/** Opaque native controls over ZIP return-authentication pages 27-30. */
public final class ReturnAuthView extends FrameLayout {
    /** Compatibility vocabulary retained for callers outside the typed MainActivity path. */
    public enum State { READY, LOADING, ERROR, NETWORK_OFFLINE }

    public interface Listener {
        void onCredentialSubmit(UnlockMethod method, String rawValue);
        void onFaceRequested();
        void onPalmRequested();
        void onCancelRequested();
        default void onRetryRequested() { }
        default void onHomeRequested() { }
    }

    private static final String[][] KEYS = {
            {"1", "2", "3"}, {"4", "5", "6"},
            {"7", "8", "9"}, {"清空", "0", "删除"}
    };

    private final HomeCredentialModel credentialModel;
    private final ZipPixelShell shell;
    private final FrameLayout authCard;
    private final List<View> identityActions = new ArrayList<>();
    private final Button phoneField;
    private final Button passwordField;
    private final Button submitButton;
    private final Button faceButton;
    private final Button palmButton;
    private final TextView passiveScanner;
    private final LinearLayout keypad;
    private final TextView status;
    private final Button retryButton;
    private final Button homeButton;
    private final Button safeReturnButton;
    private ReturnScreenPresentation presentation;
    private Listener listener;

    public ReturnAuthView(Context context, CredentialAdmissionPolicy credentialPolicy) {
        super(context);
        credentialModel = new HomeCredentialModel(credentialPolicy);
        shell = new ZipPixelShell(context, ZipScreenAsset.RETURN_AUTH_READY);
        shell.setOnSafeHomeRequested(this::notifySafeHome);
        addView(shell, match());
        FrameLayout content = shell.contentLayer();

        authCard = new FrameLayout(context);
        authCard.setBackground(UiKit.roundedSolid(
                context, Color.rgb(247, 251, 252), designDp(context, 12),
                Color.rgb(31, 195, 146), designDp(context, 2)));
        authCard.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        TextView cardTitle = nativeText(
                context, "身份验证", 27, UiKit.TEXT, Typeface.BOLD);
        cardTitle.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        cardTitle.setPadding(0, unit(context, 15), 0, 0);
        authCard.addView(cardTitle, match());
        place(content, authCard, 386, 212, 510, 250);

        passiveScanner = nativeText(context,
                "ID卡 / 扫码器持续监听\n请直接刷手环或扫描二维码",
                16, UiKit.DARK_GREEN, Typeface.BOLD);
        passiveScanner.setGravity(Gravity.CENTER);
        passiveScanner.setBackground(UiKit.roundedSolid(
                context, Color.rgb(225, 246, 239), designDp(context, 8),
                Color.rgb(137, 215, 190), designDp(context, 1)));
        passiveScanner.setContentDescription(
                "ID卡与扫码器持续监听，请直接刷手环或扫描二维码");
        place(content, passiveScanner, 105, 293, 190, 82);

        phoneField = inputField(context, UnlockMethod.PHONE);
        place(content, phoneField, 481, 278, 320, 48);
        passwordField = inputField(context, UnlockMethod.PASSWORD);
        place(content, passwordField, 481, 332, 320, 48);

        submitButton = opaqueButton(context, "验证身份", 19, UiKit.GREEN);
        submitButton.setContentDescription("验证身份并查询本人柜门");
        submitButton.setOnClickListener(view -> submitCredential());
        identityActions.add(submitButton);
        place(content, submitButton, 551, 398, 180, 42);

        keypad = buildKeypad(context);
        place(content, keypad, 968, 245, 224, 196);

        faceButton = opaqueButton(
                context, "人脸验证", 18, Color.rgb(42, 139, 216));
        faceButton.setContentDescription("进入离场还柜人脸验证");
        faceButton.setOnClickListener(view -> {
            Listener current = listener;
            if (current != null && canIdentity()) current.onFaceRequested();
        });
        identityActions.add(faceButton);
        place(content, faceButton, 270, 520, 220, 48);

        palmButton = opaqueButton(
                context, "掌纹验证", 18, Color.rgb(31, 189, 145));
        palmButton.setContentDescription("进入离场还柜掌纹验证");
        palmButton.setOnClickListener(view -> {
            Listener current = listener;
            if (current != null && canIdentity()) current.onPalmRequested();
        });
        identityActions.add(palmButton);
        place(content, palmButton, 530, 520, 220, 48);

        status = nativeText(context, "请选择验证方式", 19,
                UiKit.TEXT, Typeface.BOLD);
        status.setGravity(Gravity.CENTER);
        status.setBackground(UiKit.roundedSolid(
                context, Color.rgb(250, 252, 252), designDp(context, 10),
                Color.rgb(154, 211, 216), designDp(context, 1)));
        status.setContentDescription("还柜身份验证状态");
        place(content, status, 355, 225, 570, 210);
        status.setVisibility(GONE);

        retryButton = opaqueButton(context, "重新检测", 17, UiKit.GREEN);
        retryButton.setOnClickListener(view -> notifyRetry());
        place(content, retryButton, 470, 480, 150, 46);
        homeButton = opaqueButton(context, "返回首页", 17, UiKit.NAVY);
        homeButton.setOnClickListener(view -> notifyHome());
        place(content, homeButton, 660, 480, 150, 46);

        safeReturnButton = opaqueButton(
                context, "安全返回", 16, Color.rgb(121, 137, 139));
        safeReturnButton.setOnClickListener(view -> notifyCancel());
        place(shell.overlayLayer(), safeReturnButton, 1050, 110, 176, 38);

        refreshFields();
        applyUnavailableUntilRendered();
    }

    public void setListener(Listener listener) { this.listener = listener; }

    public void render(ReturnScreenPresentation presentation, CharSequence detail) {
        if (presentation == null
                || presentation.surface()
                        != ReturnScreenPresentation.Surface.AUTHENTICATION) {
            throw new IllegalArgumentException("authentication presentation is required");
        }
        this.presentation = presentation;
        shell.setScreenAsset(presentation.asset());
        boolean identity = presentation.canIdentity();
        boolean readyPage = presentation.asset() == ZipScreenAsset.RETURN_AUTH_READY;
        authCard.setVisibility(readyPage ? VISIBLE : GONE);
        phoneField.setVisibility(readyPage ? VISIBLE : GONE);
        passwordField.setVisibility(readyPage ? VISIBLE : GONE);
        submitButton.setVisibility(readyPage ? VISIBLE : GONE);
        keypad.setVisibility(readyPage ? VISIBLE : GONE);
        faceButton.setVisibility(readyPage ? VISIBLE : GONE);
        palmButton.setVisibility(readyPage ? VISIBLE : GONE);
        passiveScanner.setVisibility(readyPage ? VISIBLE : GONE);
        for (View action : identityActions) setActionState(action, identity);

        boolean prompt = !readyPage;
        status.setVisibility(prompt ? VISIBLE : GONE);
        CharSequence visible = normalize(detail, defaultDetail(presentation.asset()));
        status.setText(visible);
        status.setContentDescription("还柜状态，" + visible);
        status.setTextColor(presentation.asset() == ZipScreenAsset.RETURN_AUTH_QUERYING
                ? UiKit.NAVY : UiKit.RED);

        retryButton.setVisibility(presentation.canRetry() ? VISIBLE : GONE);
        setActionState(retryButton, presentation.canRetry());
        homeButton.setVisibility(presentation.canHome() ? VISIBLE : GONE);
        setActionState(homeButton, presentation.canHome());
        safeReturnButton.setVisibility(presentation.canBack() ? VISIBLE : GONE);
        setActionState(safeReturnButton, presentation.canBack());
        refreshFields();
    }

    /** Compatibility renderer; the active return flow uses the typed overload above. */
    public void render(State state, CharSequence detail) {
        if (state == null) throw new IllegalArgumentException("state is required");
        shell.setScreenAsset(state == State.READY
                ? ZipScreenAsset.RETURN_AUTH_READY
                : state == State.LOADING
                ? ZipScreenAsset.RETURN_AUTH_QUERYING
                : state == State.NETWORK_OFFLINE
                ? ZipScreenAsset.RETURN_AUTH_NETWORK_ERROR
                : ZipScreenAsset.RETURN_AUTH_FAILED);
        status.setText(normalize(detail, "离场还柜身份验证"));
    }

    public void showReady() { render(State.READY, null); }
    public void showLoading(CharSequence detail) { render(State.LOADING, detail); }
    public void showError(CharSequence detail) { render(State.ERROR, detail); }
    public void showNetworkOffline() {
        render(State.NETWORK_OFFLINE, "网络异常，暂时无法办理离场还柜");
    }

    public void clearInputs() {
        credentialModel.clearAll();
        credentialModel.select(UnlockMethod.PHONE);
        refreshFields();
    }

    private LinearLayout buildKeypad(Context context) {
        LinearLayout result = new LinearLayout(context);
        result.setOrientation(LinearLayout.VERTICAL);
        result.setPadding(unit(context, 3), unit(context, 3),
                unit(context, 3), unit(context, 3));
        result.setBackground(UiKit.roundedSolid(
                context, Color.rgb(241, 247, 249), designDp(context, 8),
                Color.rgb(194, 214, 219), designDp(context, 1)));
        for (String[] rowLabels : KEYS) {
            LinearLayout row = new LinearLayout(context);
            for (String label : rowLabels) {
                Button key = opaqueButton(
                        context, label, 16, Color.rgb(224, 234, 237));
                key.setTextColor(UiKit.TEXT);
                key.setContentDescription("还柜数字键盘" + label);
                key.setOnClickListener(view -> handleKey(label));
                identityActions.add(key);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
                params.setMargins(unit(context, 2), unit(context, 2),
                        unit(context, 2), unit(context, 2));
                row.addView(key, params);
            }
            result.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        }
        return result;
    }

    private Button inputField(Context context, UnlockMethod method) {
        Button field = opaqueButton(context, "", 18, Color.rgb(249, 251, 252));
        field.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
        field.setPadding(unit(context, 14), 0, unit(context, 14), 0);
        field.setOnClickListener(view -> {
            if (!canIdentity()) return;
            credentialModel.select(method);
            refreshFields();
        });
        identityActions.add(field);
        return field;
    }

    private void handleKey(String label) {
        if (!canIdentity()) return;
        if ("清空".equals(label)) credentialModel.clear();
        else if ("删除".equals(label)) credentialModel.delete();
        else if (label.length() == 1) credentialModel.pressDigit(label.charAt(0));
        refreshFields();
    }

    private void submitCredential() {
        if (!canIdentity()) return;
        Listener current = listener;
        if (current != null) {
            UnlockMethod method = credentialModel.activeMethod();
            current.onCredentialSubmit(method, credentialModel.rawValue(method));
        }
    }

    private void refreshFields() {
        updateField(phoneField, UnlockMethod.PHONE, "请输入手机号");
        updateField(passwordField, UnlockMethod.PASSWORD, "请输入取柜码");
    }

    private void updateField(Button field, UnlockMethod method, String hint) {
        String display = credentialModel.displayValue(method);
        boolean active = credentialModel.activeMethod() == method;
        field.setText(display.isEmpty() ? hint : display);
        field.setTextColor(display.isEmpty() ? Color.rgb(132, 148, 152) : UiKit.TEXT);
        field.setBackground(UiKit.roundedSolid(
                getContext(), Color.rgb(249, 251, 252), designDp(getContext(), 7),
                active ? UiKit.GREEN : Color.rgb(194, 214, 219),
                designDp(getContext(), active ? 2 : 1)));
        field.setContentDescription((method == UnlockMethod.PHONE
                ? "手机号输入框" : "取柜码输入框") + (active ? "，已选择" : ""));
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

    private boolean canIdentity() {
        return presentation != null && presentation.canIdentity();
    }

    private void applyUnavailableUntilRendered() {
        for (View action : identityActions) setActionState(action, false);
        retryButton.setVisibility(GONE);
        homeButton.setVisibility(GONE);
        safeReturnButton.setVisibility(GONE);
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

    private static CharSequence defaultDetail(ZipScreenAsset asset) {
        if (asset == ZipScreenAsset.RETURN_AUTH_QUERYING) return "正在验证并查询本人柜门…";
        if (asset == ZipScreenAsset.RETURN_AUTH_NETWORK_ERROR) return "网络异常，请重新检测";
        if (asset == ZipScreenAsset.RETURN_AUTH_FAILED) return "身份验证失败，请重试";
        return "请选择验证方式";
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
