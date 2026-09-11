package com.codex.lockertest.ui;

import com.codex.lockertest.model.UnlockMethod;
import com.codex.lockertest.model.LockerTarget;
import com.codex.lockertest.model.LockerZone;
import com.codex.lockertest.protocol.FeedbackPolarity;

/** Immutable customer-safe copy and deterministic action roles for ZIP prompts. */
public final class ZipPromptModel {
    public enum ActionRole {
        HOME,
        RETRY,
        CLOSE,
        CONFIRM
    }

    private static final String TITLE = "温馨提示";

    private final String title;
    private final String message;
    private final String secondaryLabel;
    private final ActionRole secondaryRole;
    private final String primaryLabel;
    private final ActionRole primaryRole;

    private ZipPromptModel(
            String message,
            String secondaryLabel,
            ActionRole secondaryRole,
            String primaryLabel,
            ActionRole primaryRole) {
        this.title = TITLE;
        this.message = message;
        this.secondaryLabel = secondaryLabel;
        this.secondaryRole = secondaryRole;
        this.primaryLabel = primaryLabel;
        this.primaryRole = primaryRole;
    }

    public static ZipPromptModel unavailable() {
        return new ZipPromptModel(
                "设备暂未接入，请联系管理员。",
                "返回首页", ActionRole.HOME,
                "再次查看", ActionRole.RETRY);
    }

    public static ZipPromptModel credentialError(UnlockMethod method) {
        final String copy;
        if (method == UnlockMethod.PHONE) {
            copy = "手机号不正确";
        } else if (method == UnlockMethod.PASSWORD) {
            copy = "密码不正确";
        } else {
            throw new IllegalArgumentException("credential method must be PHONE or PASSWORD");
        }
        return new ZipPromptModel(
                copy,
                "返回", ActionRole.CLOSE,
                "重新输入", ActionRole.RETRY);
    }

    public static ZipPromptModel lockerSuccess(LockerTarget target) {
        requireTarget(target);
        return new ZipPromptModel(
                target.customerLabel() + "号柜门已打开，请存放物品后关闭柜门。",
                "返回首页", ActionRole.HOME,
                "确定", ActionRole.CONFIRM);
    }

    public static ZipPromptModel lockerFailure(LockerTarget target) {
        requireTarget(target);
        return new ZipPromptModel(
                target.customerLabel() + "号柜门开启失败，请再次尝试。",
                "返回首页", ActionRole.HOME,
                "再次尝试", ActionRole.RETRY);
    }

    /** @deprecated Temporary A-zone bridge for pre-zone callers. */
    @Deprecated
    public static ZipPromptModel lockerSuccess(int lockerNumber) {
        return lockerSuccess(legacyTarget(lockerNumber));
    }

    /** @deprecated Temporary A-zone bridge for pre-zone callers. */
    @Deprecated
    public static ZipPromptModel lockerFailure(int lockerNumber) {
        return lockerFailure(legacyTarget(lockerNumber));
    }

    public static ZipPromptModel deviceConnectionFailure() {
        return retryPrompt("设备连接失败，请联系管理员。");
    }

    public static ZipPromptModel sendFailure() {
        return retryPrompt("开柜指令发送失败，请再次尝试。");
    }

    public static ZipPromptModel timeout() {
        return retryPrompt("设备无响应，请再次尝试。");
    }

    public String title() {
        return title;
    }

    public String message() {
        return message;
    }

    public String secondaryLabel() {
        return secondaryLabel;
    }

    public ActionRole secondaryRole() {
        return secondaryRole;
    }

    public String primaryLabel() {
        return primaryLabel;
    }

    public ActionRole primaryRole() {
        return primaryRole;
    }

    private static ZipPromptModel retryPrompt(String message) {
        return new ZipPromptModel(
                message,
                "返回首页", ActionRole.HOME,
                "再次尝试", ActionRole.RETRY);
    }

    private static LockerTarget legacyTarget(int lockerNumber) {
        if (lockerNumber < 1 || lockerNumber > 12) {
            throw new IllegalArgumentException("lockerNumber must be between 1 and 12");
        }
        return new LockerTarget(
                LockerZone.A, LockerZone.A.boardAddress(), lockerNumber,
                FeedbackPolarity.SHORT_WHEN_LOCKED);
    }

    private static void requireTarget(LockerTarget target) {
        if (target == null) {
            throw new IllegalArgumentException("target cannot be null");
        }
    }
}
