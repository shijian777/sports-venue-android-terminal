package com.codex.lockertest.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.codex.lockertest.runtime.AdminCredentialPolicy;
import com.codex.lockertest.ui.zip.ZipAdminScreenRouter;
import com.codex.lockertest.ui.zip.ZipScreenAsset;

import java.util.Arrays;

/** Native administrator PIN controls above the exact ZIP pages 40 and 41. */
public final class AdminPinOverlay extends FrameLayout {
    public interface Listener {
        void onAdminAuthenticated();
        void onDismissed();
    }

    private final ZipKioskShell shell;
    private final AdminCredentialPolicy adminPolicy;
    private final int requiredLength;
    private final EditText pinInput;
    private final TextView message;
    private final Button confirm;
    private Listener listener;
    private boolean closed;
    private boolean clearingAfterFailure;
    private ZipAdminScreenRouter.PinState state = ZipAdminScreenRouter.PinState.ENTRY;

    public AdminPinOverlay(Context context, AdminCredentialPolicy adminPolicy) {
        super(context);
        if (adminPolicy == null) {
            throw new IllegalArgumentException("Admin credential policy is required");
        }
        this.adminPolicy = adminPolicy;
        requiredLength = adminPolicy.requiredLength();
        if (requiredLength <= 0) {
            throw new IllegalArgumentException("Admin credential length must be positive");
        }
        setClickable(true);

        shell = new ZipKioskShell(context, ZipScreenAsset.ADMIN_PIN_ENTRY);
        shell.setOnReturnClickListener(view -> dismiss());
        addView(shell, match());

        FrameLayout card = new FrameLayout(context);
        card.setBackground(UiKit.roundedSolid(
                context, Color.WHITE, ZipKioskShell.designDp(context, 16),
                Color.rgb(40, 194, 153), 1));
        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
                ZipKioskShell.unit(context, 510),
                ZipKioskShell.unit(context, 250));
        cardParams.leftMargin = ZipKioskShell.unit(context, 386);
        cardParams.topMargin = ZipKioskShell.unit(context, 212);
        shell.pixelContent().addView(card, cardParams);

        TextView title = UiKit.text(
                context, "请输入" + requiredLength + "位管理员密码",
                25, UiKit.GREEN, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams titleParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ZipKioskShell.unit(context, 42));
        titleParams.leftMargin = ZipKioskShell.unit(context, 24);
        titleParams.rightMargin = ZipKioskShell.unit(context, 24);
        titleParams.topMargin = ZipKioskShell.unit(context, 34);
        card.addView(title, titleParams);

        message = UiKit.text(
                context, "密码由安全原生控件输入", 14, UiKit.MUTED, Typeface.NORMAL);
        message.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams messageParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ZipKioskShell.unit(context, 28));
        messageParams.leftMargin = ZipKioskShell.unit(context, 24);
        messageParams.rightMargin = ZipKioskShell.unit(context, 24);
        messageParams.topMargin = ZipKioskShell.unit(context, 76);
        card.addView(message, messageParams);

        pinInput = new EditText(context);
        pinInput.setSingleLine(true);
        pinInput.setGravity(Gravity.CENTER);
        pinInput.setTextSize(20);
        pinInput.setTextColor(UiKit.TEXT);
        pinInput.setHint("请输入管理员密码");
        pinInput.setHintTextColor(UiKit.MUTED);
        pinInput.setInputType(InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pinInput.setTransformationMethod(PasswordTransformationMethod.getInstance());
        pinInput.setFilters(new InputFilter[] {
                new InputFilter.LengthFilter(requiredLength)
        });
        pinInput.setSaveEnabled(false);
        pinInput.setSaveFromParentEnabled(false);
        pinInput.setContentDescription("管理员密码输入框，内容已隐藏");
        pinInput.setBackground(UiKit.roundedSolid(
                context, Color.WHITE, ZipKioskShell.designDp(context, 10),
                Color.rgb(155, 190, 199), 1));
        FrameLayout.LayoutParams inputParams = new FrameLayout.LayoutParams(
                ZipKioskShell.unit(context, 320),
                ZipKioskShell.unit(context, 48));
        inputParams.leftMargin = ZipKioskShell.unit(context, 95);
        inputParams.topMargin = ZipKioskShell.unit(context, 120);
        card.addView(pinInput, inputParams);

        confirm = UiKit.button(
                context, "确认", 17, UiKit.GREEN, Color.WHITE,
                ZipKioskShell.designDp(context, 20));
        confirm.setEnabled(false);
        confirm.setContentDescription("确认管理员密码");
        FrameLayout.LayoutParams confirmParams = new FrameLayout.LayoutParams(
                ZipKioskShell.unit(context, 180),
                ZipKioskShell.unit(context, 42));
        confirmParams.leftMargin = ZipKioskShell.unit(context, 165);
        confirmParams.topMargin = ZipKioskShell.unit(context, 186);
        card.addView(confirm, confirmParams);

        pinInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value,
                    int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence value,
                    int start, int before, int count) {
                if (closed || clearingAfterFailure) return;
                if (state == ZipAdminScreenRouter.PinState.ERROR) {
                    renderPinState(ZipAdminScreenRouter.PinState.ENTRY);
                }
                confirm.setEnabled(value != null && value.length() == requiredLength);
            }
            @Override public void afterTextChanged(Editable value) { }
        });
        confirm.setOnClickListener(view -> authenticate());
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void reset() {
        closed = false;
        clearPin(false);
        renderPinState(ZipAdminScreenRouter.PinState.ENTRY);
    }

    private void authenticate() {
        if (closed || pinInput.length() != requiredLength) return;
        String unavailable = adminPolicy.unavailableMessage();
        if (unavailable != null && !unavailable.isEmpty()) {
            clearPin(true);
            renderPinFailure(unavailable);
            return;
        }

        Editable editable = pinInput.getText();
        char[] candidate = new char[editable.length()];
        boolean authenticated;
        try {
            editable.getChars(0, editable.length(), candidate, 0);
            authenticated = adminPolicy.matches(candidate);
        } finally {
            Arrays.fill(candidate, '\0');
            clearPin(false);
        }
        if (authenticated) {
            closed = true;
            confirm.setEnabled(false);
            Listener current = listener;
            if (current != null) current.onAdminAuthenticated();
            return;
        }
        renderPinState(ZipAdminScreenRouter.PinState.ERROR);
    }

    private void clearPin(boolean failure) {
        clearingAfterFailure = failure;
        try {
            pinInput.getText().clear();
            confirm.setEnabled(false);
        } finally {
            clearingAfterFailure = false;
        }
    }

    private void renderPinState(ZipAdminScreenRouter.PinState next) {
        state = next;
        shell.setScreenAsset(ZipAdminScreenRouter.assetForPin(next));
        boolean failure = next == ZipAdminScreenRouter.PinState.ERROR;
        message.setText(failure ? "密码错误，请重新输入" : "密码由安全原生控件输入");
        message.setTextColor(failure ? UiKit.RED : UiKit.MUTED);
    }

    private void renderPinFailure(String detail) {
        state = ZipAdminScreenRouter.PinState.ERROR;
        shell.setScreenAsset(ZipAdminScreenRouter.assetForPin(state));
        message.setText(detail);
        message.setTextColor(UiKit.RED);
    }

    private void dismiss() {
        if (closed) return;
        closed = true;
        clearPin(false);
        Listener current = listener;
        if (current != null) current.onDismissed();
    }

    private static LayoutParams match() {
        return new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
    }
}
