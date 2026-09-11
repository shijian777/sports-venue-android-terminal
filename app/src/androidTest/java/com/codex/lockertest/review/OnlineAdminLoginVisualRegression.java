package com.codex.lockertest.review;

import android.app.Instrumentation;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.EditText;
import android.widget.TextView;

import com.codex.lockertest.business.AdminLogin;
import com.codex.lockertest.business.SessionToken;
import com.codex.lockertest.server.ApiResult;
import com.codex.lockertest.server.CallToken;
import com.codex.lockertest.ui.ZipKioskShell;
import com.codex.lockertest.ui.business.OnlineAdminPanel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Isolated administrator-login layout and credential-entry regression. */
public final class OnlineAdminLoginVisualRegression extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void onStart() {
        Throwable[] failure = {null};
        runOnMainSync(() -> {
            try { check(false); check(true); }
            catch (Throwable value) { failure[0] = value; }
        });
        Bundle result = new Bundle();
        result.putString("stream", failure[0] == null
                ? "ONLINE_ADMIN_LOGIN_VISUAL_REGRESSION=PASS\n"
                : "ONLINE_ADMIN_LOGIN_VISUAL_REGRESSION=FAIL " + failure[0] + "\n");
        finish(failure[0] == null ? -1 : 0, result);
    }

    private void check(boolean dynamicRequired) {
        CapturingService service = new CapturingService();
        OnlineAdminPanel panel = new OnlineAdminPanel(getTargetContext(), service,
                new OnlineReaderRegression.Sources(), "terminal", dynamicRequired, () -> { });
        try {
            int width = unit(1280), height = unit(800);
            panel.measure(exact(width), exact(height));
            panel.layout(0, 0, width, height);

            TextView title = requireText(panel, "登录管理后台");
            require(title.getTypeface() != null && title.getTypeface().getStyle() == Typeface.BOLD,
                    "Administrator login title is not visually prominent");

            View card = requireDescription(panel, "管理员登录表单");
            require(card.getBackground() != null, "Administrator login form is not framed");
            require(Math.abs(card.getLeft() + card.getWidth() / 2 - unit(640)) <= 1,
                    "Administrator login form is not centered on the 1280 design canvas");
            require(card.getWidth() == unit(600) && card.getWidth() < width,
                    "Administrator login form does not use the narrow PDF-style composition");

            EditText username = requireInput(panel, "服务器管理员账号");
            EditText password = requireInput(panel, "服务器管理员密码");
            assertAccountIme(username);
            assertSecretIme(password, "Administrator password");
            require(username.getLeft() == password.getLeft() && username.getWidth() == password.getWidth(),
                    "Administrator credential fields are not aligned");
            require(username.getLeft() > card.getLeft()
                            && username.getRight() < card.getRight(),
                    "Administrator credential fields are not contained by the login card");

            OnlineBusinessRegression.savePreview(this, panel, dynamicRequired
                    ? "online-admin-login-pdf-required.png"
                    : "online-admin-login-pdf-optional.png");
            String account = "admin.A-7_用户+tag@example";
            username.setText(account);
            require(account.contentEquals(username.getText()),
                    "Administrator account field filtered or normalized valid characters");
            password.setText("p@ss-9");
            if (dynamicRequired) {
                EditText dynamic = requireInput(panel, "管理员动态验证码");
                assertSecretIme(dynamic, "Administrator dynamic code");
                dynamic.setText("otp-A7");
            } else {
                require(findDescription(panel, "管理员动态验证码") == null,
                        "Optional dynamic code remained visible");
            }
            requireDescription(panel, "服务器管理员登录").performClick();
            awaitLogin(service);
            require(account.equals(service.username),
                    "Administrator account characters changed before reaching the service");
            require("p@ss-9".equals(service.password), "Administrator password symbols changed");
            require(dynamicRequired ? "otp-A7".equals(service.dynamicCode) : service.dynamicCode == null,
                    "Dynamic-code optionality or value changed");
        } finally {
            panel.close();
        }
    }

    private void awaitLogin(CapturingService service) {
        try {
            require(service.completed.await(4, TimeUnit.SECONDS),
                    "Administrator login did not reach the server service once");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for administrator login", failure);
        }
        require(service.calls == 1, "Administrator login did not reach the server service once");
    }

    private static void assertAccountIme(EditText input) {
        EditorInfo info = editorInfo(input, "Administrator account");
        require((info.inputType & InputType.TYPE_MASK_VARIATION)
                        == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                "Administrator account did not request literal visible credential input");
        require((info.inputType & InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0,
                "Administrator account enabled IME suggestions");
        require((info.inputType & InputType.TYPE_TEXT_FLAG_AUTO_CORRECT) == 0,
                "Administrator account enabled auto-correction");
        require((info.inputType & (InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                        | InputType.TYPE_TEXT_FLAG_CAP_WORDS
                        | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)) == 0,
                "Administrator account enabled automatic capitalization");
        require(!(input.getTransformationMethod() instanceof PasswordTransformationMethod),
                "Administrator account became visually masked");
    }

    private static void assertSecretIme(EditText input, String label) {
        EditorInfo info = editorInfo(input, label);
        require((info.inputType & InputType.TYPE_MASK_VARIATION)
                        == InputType.TYPE_TEXT_VARIATION_PASSWORD,
                label + " is not a secure password editor");
        require(input.getTransformationMethod() instanceof PasswordTransformationMethod,
                label + " is no longer visually masked");
    }

    private static EditorInfo editorInfo(EditText input, String label) {
        require((input.getInputType() & InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT,
                label + " was constrained to a numeric keypad");
        require(input.onCheckIsTextEditor(), label + " no longer accepts IME input");
        EditorInfo info = new EditorInfo();
        InputConnection connection = input.onCreateInputConnection(info);
        require(connection != null, label + " did not expose an Android input connection");
        require((info.imeOptions & EditorInfo.IME_FLAG_FORCE_ASCII) != 0,
                label + " did not request an ASCII-capable keyboard");
        return info;
    }

    private static TextView requireText(View root, String text) {
        TextView found = findText(root, text);
        if (found == null) throw new AssertionError("Missing visible text: " + text);
        return found;
    }

    private static TextView findText(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return (TextView) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            TextView found = findText(((ViewGroup) view).getChildAt(i), text);
            if (found != null) return found;
        }
        return null;
    }

    private static EditText requireInput(View root, String description) {
        View found = requireDescription(root, description);
        if (!(found instanceof EditText)) throw new AssertionError(description + " is not an editable field");
        return (EditText) found;
    }

    private static View requireDescription(View root, String description) {
        View found = findDescription(root, description);
        if (found == null) throw new AssertionError("Missing control: " + description);
        return found;
    }

    private static View findDescription(View view, String description) {
        CharSequence actual = view.getContentDescription();
        if (actual != null && description.contentEquals(actual)) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View found = findDescription(((ViewGroup) view).getChildAt(i), description);
            if (found != null) return found;
        }
        return null;
    }

    private int unit(float value) { return ZipKioskShell.unit(getTargetContext(), value); }
    private static int exact(int value) { return View.MeasureSpec.makeMeasureSpec(value, View.MeasureSpec.EXACTLY); }
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static final class CapturingService extends OnlineReaderRegression.Service {
        volatile String username;
        volatile String password;
        volatile String dynamicCode;
        final CountDownLatch completed = new CountDownLatch(1);

        @Override public ApiResult<AdminLogin> adminLogin(
                String username, String password, String dynamicCode, CallToken token) {
            this.username = username;
            this.password = password;
            this.dynamicCode = dynamicCode;
            calls++;
            completed.countDown();
            return ApiResult.success(new AdminLogin(SessionToken.of("test-token"),
                    "venue", "device", "terminal", "area", "user", "name"));
        }
    }
}
