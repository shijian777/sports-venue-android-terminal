package com.codex.lockertest.review;

import android.content.Context;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import com.codex.lockertest.bootstrap.BaseSettingSnapshot;
import com.codex.lockertest.bootstrap.BasicDataSnapshot;
import com.codex.lockertest.bootstrap.BootstrapSnapshot;
import com.codex.lockertest.bootstrap.DeviceRegistration;
import com.codex.lockertest.model.UnlockMethod;
import com.codex.lockertest.runtime.CredentialAdmission;
import com.codex.lockertest.ui.BootstrapHomePresentation;
import com.codex.lockertest.ui.TerminalReadiness;
import com.codex.lockertest.ui.ZipHomeView;
import com.codex.lockertest.ui.zip.ZipPixelShell;
import com.codex.lockertest.ui.zip.ZipScreenAsset;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;

/** Actual home keypad callback regression; no network, biometrics or lock operations. */
final class OnlineHomeRegression {
    static void check(Context context) throws Exception {
        java.lang.reflect.Method state = BootstrapSnapshot.class.getDeclaredMethod(
                "state", long.class, BootstrapSnapshot.Phase.class,
                BootstrapSnapshot.Endpoint.class, BootstrapSnapshot.FailureReason.class, String.class);
        state.setAccessible(true);
        BootstrapSnapshot idle = (BootstrapSnapshot) state.invoke(null, 1L,
                BootstrapSnapshot.Phase.IDLE, BootstrapSnapshot.Endpoint.NONE,
                BootstrapSnapshot.FailureReason.NONE, "");
        ZipHomeView home = new ZipHomeView(context,
                (method, value) -> CredentialAdmission.rejected("Fixture"), method -> true,
                BootstrapHomePresentation.create(TerminalReadiness.localDemoReady(), idle));
        int[] legacyCalls = {0};
        home.setListener(new ZipHomeView.Listener() {
            @Override public void onCredentialSubmit(UnlockMethod method, String value) {
                legacyCalls[0]++;
                if (method != UnlockMethod.PHONE || !"13800000001".equals(value))
                    throw new AssertionError("Legacy focused-field submission changed");
            }
            @Override public void onUnavailableSelected(UnlockMethod method) { }
            @Override public void onAdminRequested() { }
        });
        for (char digit : "13800000001".toCharArray()) click(home, "数字键盘" + digit);
        click(home, "取柜码输入框");
        for (char digit : "001234".toCharArray()) click(home, "数字键盘" + digit);
        click(home, "手机号输入框");
        String[] submitted = {null, null};
        home.setPairedCredentialListener((phone, code) -> {
            submitted[0] = phone; submitted[1] = code;
        });
        click(home, "确认提交当前凭据");
        if (!"13800000001".equals(submitted[0]) || !"001234".equals(submitted[1])
                || legacyCalls[0] != 0) throw new AssertionError("Paired fields were dropped or legacy path fired");
        home.setPairedCredentialListener(null);
        click(home, "确认提交当前凭据");
        if (legacyCalls[0] != 1) throw new AssertionError("Local demo callback no longer works");
        home.clearInputs();
        home.setPairedCredentialListener((phone, code) -> {
            if (!phone.isEmpty() || !code.isEmpty()) throw new AssertionError("Credentials survived clear");
        });
        click(home, "确认提交当前凭据");
        checkClockTouchRouting(home);
        checkClockAdminAndStableModal(home);
        checkPdfHomePresentation(context);
    }

    private static void checkPdfHomePresentation(Context context) throws Exception {
        ZipHomeView home = new ZipHomeView(context,
                (method, value) -> CredentialAdmission.rejected("Fixture"), method -> true,
                BootstrapHomePresentation.createForOnlineBrowsing(
                        com.codex.lockertest.ui.TerminalReadiness.readyReadOnly(),
                        readySnapshot(), true));
        home.measure(View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY));
        home.layout(0, 0, 1280, 800);

        View warmTips = findStartingWith(home, "温馨提示，服务器温馨提示");
        View runtime = findStartingWith(home, "当前模式：首页；运行状态：");
        if (warmTips == null || runtime == null)
            throw new AssertionError("Server warm tips and runtime status are not separate views");
        if (!(warmTips.getParent() instanceof android.widget.ScrollView))
            throw new AssertionError("Long server warm tips cannot be scrolled");
        assertDesignBounds(home, (View)warmTips.getParent(), 924, 522, 287, 184, "warm tips viewport");
        assertDesignBounds(home, runtime, 58, 218, 326, 36, "runtime status");
        OnlineBusinessRegression.savePreview(
                context, home, "online-home-pdf-r4.png");

        home.showOnlineStatus("正在查询可用柜门");
        if (findStartingWith(home, "当前模式：首页；运行状态：正在查询可用柜门") == null)
            throw new AssertionError("Runtime status did not update independently");
        if (findStartingWith(home, "温馨提示，服务器温馨提示") == null)
            throw new AssertionError("Runtime status overwrote server warm tips");

        invokeReturnMode(home, true);
        if (find(home, "离场还柜 · 身份验证") == null)
            throw new AssertionError("Return authentication title was not explicit");
        if (findStartingWith(home, "还柜提示，离场前请确认柜门已关闭") == null)
            throw new AssertionError("Return mode did not use the server return notice");
        if (findStartingWith(home, "当前模式：还柜身份验证；运行状态：正在查询可用柜门") == null)
            throw new AssertionError("Return mode was not identified in the runtime status");
        View phone = find(home, "手机号输入框");
        if (phone == null || !phone.isEnabled())
            throw new AssertionError("Return mode changed the existing credential form contract");
        OnlineBusinessRegression.savePreview(
                context, home, "online-home-return-auth-r4.png");

        home.showCredentialReading();
        ZipPixelShell shell = (ZipPixelShell) home.getChildAt(0);
        if (Math.abs(shell.contentLayer().getAlpha() - 0.42f) > 0.01f)
            throw new AssertionError("PDF-style loading state did not dim the home canvas");
        if (find(home, "正在读取并核验您的凭证") == null)
            throw new AssertionError("Loading card was not shown over the dimmed home");
        home.showCredentialWaiting();
        if (Math.abs(shell.contentLayer().getAlpha() - 1f) > 0.01f)
            throw new AssertionError("Leaving loading state did not restore the home canvas");
    }

    private static BootstrapSnapshot readySnapshot() throws Exception {
        DeviceRegistration registration = construct(DeviceRegistration.class,
                new Class<?>[] {String.class, String.class}, new Object[] {"M", "D"});
        BaseSettingSnapshot base = construct(BaseSettingSnapshot.class,
                new Class<?>[] {String.class, String.class, java.util.List.class,
                        String.class, String.class, String.class, String.class,
                        String.class, int.class, java.util.List.class, String.class,
                        String.class, java.util.List.class, String.class},
                new Object[] {"PDF测试场馆", "1", Arrays.asList("3", "5"),
                        "服务器使用说明", "离场前请确认柜门已关闭", "", "",
                        "服务器温馨提示\n请妥善保管物品", 0, Collections.emptyList(),
                        "", "", Collections.emptyList(), "1"});
        BasicDataSnapshot basic = construct(BasicDataSnapshot.class,
                new Class<?>[] {int.class, int.class, String.class},
                new Object[] {50, 24, ""});
        Method ready = BootstrapSnapshot.class.getDeclaredMethod("ready", long.class,
                String.class, DeviceRegistration.class, BaseSettingSnapshot.class,
                BasicDataSnapshot.class);
        ready.setAccessible(true);
        return (BootstrapSnapshot) ready.invoke(null, 1L, "SERIAL", registration, base, basic);
    }

    private static <T> T construct(
            Class<T> type, Class<?>[] parameters, Object[] arguments) throws Exception {
        Constructor<T> constructor = type.getDeclaredConstructor(parameters);
        constructor.setAccessible(true);
        return constructor.newInstance(arguments);
    }

    private static void invokeReturnMode(ZipHomeView home, boolean enabled) throws Exception {
        try {
            ZipHomeView.class.getMethod("setReturnMode", boolean.class).invoke(home, enabled);
        } catch (NoSuchMethodException missingMethod) {
            throw new AssertionError("Missing return-mode presentation API");
        }
    }

    private static void assertDesignBounds(View root, View view,
            int designLeft, int designTop, int designWidth, int designHeight, String label) {
        int actualLeft = 0;
        int actualTop = 0;
        View current = view;
        while (current != null && current != view.getRootView()) {
            actualLeft += current.getLeft();
            actualTop += current.getTop();
            current = current.getParent() instanceof View ? (View) current.getParent() : null;
        }
        android.util.DisplayMetrics metrics = root.getResources().getDisplayMetrics();
        float scale = Math.min(Math.max(metrics.widthPixels, metrics.heightPixels) / 1280f,
                Math.min(metrics.widthPixels, metrics.heightPixels) / 800f);
        int canvasWidth = Math.round(1280 * scale);
        int canvasHeight = Math.round(800 * scale);
        int left = (root.getWidth() - canvasWidth) / 2 + Math.round(designLeft * scale);
        int top = (root.getHeight() - canvasHeight) / 2 + Math.round(designTop * scale);
        int width = Math.round(designWidth * scale);
        int height = Math.round(designHeight * scale);
        // Parent and child design offsets are independently rounded on sub-1280 canvases.
        if (Math.abs(actualLeft - left) > 2 || Math.abs(actualTop - top) > 2
                || view.getWidth() != width || view.getHeight() != height) {
            throw new AssertionError(label + " bounds changed: " + actualLeft + "," + actualTop
                    + " " + view.getWidth() + "x" + view.getHeight());
        }
    }

    private static void checkClockTouchRouting(ZipHomeView home) {
        View clock = findClock(home);
        if (clock == null) throw new AssertionError("Missing home clock admin entry");
        int[] deliveredTaps = {0};
        // These detached Views cannot post Android's click runnable to a window.
        // Observe real root-to-clock touch routing here; the separate callback test
        // below exercises the actual listener and five-tap gate without replacing it.
        clock.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_UP) deliveredTaps[0]++;
            return true;
        });
        try {
            for (int[] viewport : new int[][] {{1280, 800}, {1184, 800}, {1920, 1080}}) {
                home.measure(View.MeasureSpec.makeMeasureSpec(viewport[0], View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(viewport[1], View.MeasureSpec.EXACTLY));
                home.layout(0, 0, viewport[0], viewport[1]);
                int before = deliveredTaps[0];
                dispatchClockTap(home, clock);
                if (deliveredTaps[0] != before + 1)
                    throw new AssertionError("Home clock did not receive the actual touch");
                home.showValidationError("此识别功能尚未接通，请联系管理员");
                home.measure(View.MeasureSpec.makeMeasureSpec(viewport[0], View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(viewport[1], View.MeasureSpec.EXACTLY));
                home.layout(0, 0, viewport[0], viewport[1]);
                before = deliveredTaps[0];
                dispatchClockTap(home, clock);
                if (deliveredTaps[0] != before + 1)
                    throw new AssertionError("Error modal intercepted the actual clock touch");
                home.showValidationError(null);
            }
        } finally {
            clock.setOnTouchListener(null);
            home.showValidationError(null);
        }
    }

    private static void dispatchClockTap(ZipHomeView home, View clock) {
        float[] position = {clock.getWidth() / 2f, clock.getHeight() / 2f};
        View current = clock;
        while (current != home) {
            current.getMatrix().mapPoints(position);
            position[0] += current.getLeft();
            position[1] += current.getTop();
            current = (View) current.getParent();
        }
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN,
                position[0], position[1], 0);
        MotionEvent up = MotionEvent.obtain(now, now + 20, MotionEvent.ACTION_UP,
                position[0], position[1], 0);
        try { home.dispatchTouchEvent(down); home.dispatchTouchEvent(up); }
        finally { down.recycle(); up.recycle(); }
    }

    private static void checkClockAdminAndStableModal(ZipHomeView home) {
        int[] adminCalls = {0};
        home.setListener(new ZipHomeView.Listener() {
            @Override public void onCredentialSubmit(UnlockMethod method, String value) { }
            @Override public void onUnavailableSelected(UnlockMethod method) { }
            @Override public void onAdminRequested() { adminCalls[0]++; }
        });
        View visibleAdmin = find(home, "管理员入口");
        if (visibleAdmin != null && visibleAdmin.getVisibility() == View.VISIBLE)
            throw new AssertionError("PDF requires clock-only admin entry, not a visible administrator button");
        View clock = findClock(home);
        if (clock == null) throw new AssertionError("Missing home clock admin entry");
        for (int i = 0; i < 4; i++) clock.performClick();
        if (adminCalls[0] != 0) throw new AssertionError("Fewer than five clock taps opened admin login");
        clock.performClick();
        if (adminCalls[0] != 1) throw new AssertionError("Fifth clock tap did not route to login");
        OnlineBusinessRegression.savePreview(home.getContext(), home, "online-home-v20.png");
        home.showValidationError("此识别功能尚未接通，请联系管理员");
        OnlineBusinessRegression.savePreview(home.getContext(), home, "online-home-config-error-v20.png");
        ZipPixelShell shell = (ZipPixelShell) home.getChildAt(0);
        if (shell.screenAsset() != ZipScreenAsset.HOME_WAITING)
            throw new AssertionError("Error modal replaced the home with a different baked button layout");
        View phone = find(home, "手机号输入框");
        if (phone == null || phone.getVisibility() != View.VISIBLE || phone.isEnabled())
            throw new AssertionError("Modal must retain visible home controls but disable customer input");
        if (find(home, "凭证错误，此识别功能尚未接通，请联系管理员") != null)
            throw new AssertionError("Configuration failure incorrectly labelled as bad credential");
        for (int i = 0; i < 5; i++) clock.performClick();
        if (adminCalls[0] != 2) throw new AssertionError("Modal blocked five-tap administrator entry");
        home.showValidationError(null);
        if (!phone.isEnabled()) throw new AssertionError("Dismiss did not restore customer input");
    }

    private static View findClock(View view) {
        CharSequence description = view.getContentDescription();
        if (description != null && description.toString().startsWith("当前日期时间")) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View result = findClock(group.getChildAt(i));
                if (result != null) return result;
            }
        }
        return null;
    }

    private static void click(View root, String description) {
        View target = find(root, description);
        if (target == null || !target.isEnabled()) throw new AssertionError("Missing enabled control: " + description);
        target.performClick();
    }

    private static View find(View view, String description) {
        CharSequence value = view.getContentDescription();
        if (value != null && (value.toString().equals(description)
                || value.toString().equals(description + "，已选中"))) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = find(group.getChildAt(i), description);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static View findStartingWith(View view, String descriptionPrefix) {
        CharSequence value = view.getContentDescription();
        if (value != null && value.toString().startsWith(descriptionPrefix)) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findStartingWith(group.getChildAt(i), descriptionPrefix);
                if (found != null) return found;
            }
        }
        return null;
    }
}
