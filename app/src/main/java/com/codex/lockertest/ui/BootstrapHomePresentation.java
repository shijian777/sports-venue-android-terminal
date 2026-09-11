package com.codex.lockertest.ui;

import com.codex.lockertest.bootstrap.BaseSettingSnapshot;
import com.codex.lockertest.bootstrap.BasicDataSnapshot;
import com.codex.lockertest.bootstrap.BootstrapSnapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Customer-safe projection of bootstrap state for the home screen. */
public final class BootstrapHomePresentation {
    private static final String LOCAL_USAGE = "使用数量：暂无本机数据";
    private static final String REMOTE_USAGE_UNAVAILABLE = "使用数量：服务器数据不可用";
    private static final String DEFAULT_WARM_TIPS =
            "请直接刷手环或扫描二维码，按页面提示完成操作。";
    private static final String DEFAULT_RETURN_TIPS =
            "请验证身份后选择要归还的柜门，按页面提示完成还柜。";

    private final String venueName;
    private final String usageText;
    private final String statusMessage;
    private final boolean customerActionsEnabled;
    private final boolean retryAvailable;
    private final boolean serverCapabilitiesKnown;
    private final List<String> recognitionTypes;
    private final String warmTipsText;
    private final String returnTipsText;

    private BootstrapHomePresentation(
            String venueName,
            String usageText,
            String statusMessage,
            boolean customerActionsEnabled,
            boolean retryAvailable,
            boolean serverCapabilitiesKnown,
            List<String> recognitionTypes,
            String warmTipsText,
            String returnTipsText) {
        this.venueName = venueName;
        this.usageText = usageText;
        this.statusMessage = statusMessage;
        this.customerActionsEnabled = customerActionsEnabled;
        this.retryAvailable = retryAvailable;
        this.serverCapabilitiesKnown = serverCapabilitiesKnown;
        this.recognitionTypes = Collections.unmodifiableList(
                new ArrayList<>(recognitionTypes));
        this.warmTipsText = warmTipsText;
        this.returnTipsText = returnTipsText;
    }

    public static BootstrapHomePresentation create(
            TerminalReadiness readiness, BootstrapSnapshot snapshot) {
        if (readiness == null || snapshot == null) {
            throw new IllegalArgumentException("Readiness and bootstrap snapshot are required");
        }
        String venue = "";
        String usage = readiness.state() == TerminalReadiness.State.READY_LOCAL_DEMO
                ? LOCAL_USAGE : REMOTE_USAGE_UNAVAILABLE;
        boolean serverCapabilitiesKnown = false;
        List<String> recognitionTypes = Collections.emptyList();
        String warmTips = DEFAULT_WARM_TIPS;
        String returnTips = DEFAULT_RETURN_TIPS;
        if (snapshot.phase() == BootstrapSnapshot.Phase.READY_READ_ONLY
                && snapshot.baseSetting() != null
                && snapshot.basicData() != null) {
            BaseSettingSnapshot base = snapshot.baseSetting();
            BasicDataSnapshot basic = snapshot.basicData();
            venue = safeLabel(base.venueName(), 80);
            usage = "空闲柜：" + nonNegative(basic.availableLockers())
                    + "　总数：" + nonNegative(basic.totalLockers());
            serverCapabilitiesKnown = true;
            recognitionTypes = base.recognitionTypes();
            String useNotice = safeMultiline(base.useNotice(), 360);
            String cozyTips = safeMultiline(base.cozyTips(), 360);
            String returnNotice = safeMultiline(base.returnNotice(), 360);
            warmTips = firstNonBlank(cozyTips, useNotice, DEFAULT_WARM_TIPS);
            returnTips = firstNonBlank(returnNotice, useNotice, DEFAULT_RETURN_TIPS);
        }
        return new BootstrapHomePresentation(
                venue,
                usage,
                safeLabel(readiness.customerMessage(), 120),
                readiness.customerActionsEnabled(),
                retryable(snapshot),
                serverCapabilitiesKnown,
                recognitionTypes,
                warmTips,
                returnTips);
    }

    /** Online presentation permission only; it never changes the legacy serial boundary. */
    public static BootstrapHomePresentation createForOnlineBrowsing(
            TerminalReadiness readiness, BootstrapSnapshot snapshot, boolean serviceReady) {
        BootstrapHomePresentation original = create(readiness, snapshot);
        if (!serviceReady || readiness.state() != TerminalReadiness.State.READY_READ_ONLY
                || !original.serverCapabilitiesKnown) return original;
        return new BootstrapHomePresentation(
                original.venueName, original.usageText,
                "已连接服务器，可验证身份并查询柜门；物理开还柜暂未开放",
                true, original.retryAvailable, true, original.recognitionTypes,
                original.warmTipsText, original.returnTipsText);
    }

    public String venueName() {
        return venueName;
    }

    public String usageText() {
        return usageText;
    }

    public String statusMessage() {
        return statusMessage;
    }

    public boolean customerActionsEnabled() {
        return customerActionsEnabled;
    }

    public boolean retryAvailable() {
        return retryAvailable;
    }

    public boolean adminAvailable() {
        return true;
    }

    public boolean serverCapabilitiesKnown() {
        return serverCapabilitiesKnown;
    }

    public List<String> recognitionTypes() {
        return recognitionTypes;
    }

    public String warmTipsText() {
        return warmTipsText;
    }

    public String returnTipsText() {
        return returnTipsText;
    }

    static boolean retryable(BootstrapSnapshot snapshot) {
        if (snapshot.phase() != BootstrapSnapshot.Phase.BLOCKED) return false;
        switch (snapshot.failureReason()) {
            case SERIAL_UNAVAILABLE:
            case CLOCK_INVALID:
            case TIMEOUT:
            case NETWORK:
            case TLS:
            case REDIRECT:
            case HTTP:
            case INVALID_RESPONSE:
                return true;
            case NONE:
            case CONFIGURATION:
            case KEY_MISSING:
            case CANCELLED:
            case REMOTE_REJECTED:
            case CONTRACT:
            default:
                return false;
        }
    }

    private static int nonNegative(int value) {
        return Math.max(0, value);
    }

    private static String safeLabel(String value, int maximumCharacters) {
        if (value == null) return "";
        StringBuilder safe = new StringBuilder();
        for (int index = 0; index < value.length()
                && safe.length() < maximumCharacters; index++) {
            char character = value.charAt(index);
            if (!Character.isISOControl(character)) safe.append(character);
        }
        return safe.toString().trim();
    }

    private static String safeMultiline(String value, int maximumCharacters) {
        if (value == null) return "";
        StringBuilder safe = new StringBuilder();
        for (int index = 0; index < value.length()
                && safe.length() < maximumCharacters; index++) {
            char character = value.charAt(index);
            if (!Character.isISOControl(character)
                    || character == '\n' || character == '\r' || character == '\t') {
                safe.append(character);
            }
        }
        return safe.toString().trim();
    }

    private static String firstNonBlank(String first, String second, String fallback) {
        if (!first.isEmpty()) return first;
        if (!second.isEmpty()) return second;
        return fallback;
    }
}
