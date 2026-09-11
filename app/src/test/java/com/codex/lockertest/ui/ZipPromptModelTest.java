package com.codex.lockertest.ui;

import com.codex.lockertest.model.LockerTarget;
import com.codex.lockertest.model.LockerZone;
import com.codex.lockertest.model.UnlockMethod;
import com.codex.lockertest.protocol.FeedbackPolarity;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public final class ZipPromptModelTest {
    private static final String[] FORBIDDEN_CUSTOMER_TEXT = {
            "board", "板01", "地址", "tty", "/dev/", "hex", "0x", "8a01", "9600"
    };

    @Test
    public void unavailablePromptHasExactSafeCopyAndNavigationRoles() {
        assertPrompt(ZipPromptModel.unavailable(),
                "温馨提示", "设备暂未接入，请联系管理员。",
                "返回首页", ZipPromptModel.ActionRole.HOME,
                "再次查看", ZipPromptModel.ActionRole.RETRY);
    }

    @Test
    public void credentialPromptsUseMethodSpecificCopyAndCloseRole() {
        assertPrompt(ZipPromptModel.credentialError(UnlockMethod.PHONE),
                "温馨提示", "手机号不正确",
                "返回", ZipPromptModel.ActionRole.CLOSE,
                "重新输入", ZipPromptModel.ActionRole.RETRY);
        assertPrompt(ZipPromptModel.credentialError(UnlockMethod.PASSWORD),
                "温馨提示", "密码不正确",
                "返回", ZipPromptModel.ActionRole.CLOSE,
                "重新输入", ZipPromptModel.ActionRole.RETRY);
    }

    @Test
    public void targetResultPromptsUseExactA1B2AndC12CustomerLabels() {
        assertPrompt(ZipPromptModel.lockerSuccess(target(LockerZone.A, 1)),
                "温馨提示", "A1号柜门已打开，请存放物品后关闭柜门。",
                "返回首页", ZipPromptModel.ActionRole.HOME,
                "确定", ZipPromptModel.ActionRole.CONFIRM);
        assertPrompt(ZipPromptModel.lockerSuccess(target(LockerZone.B, 2)),
                "温馨提示", "B2号柜门已打开，请存放物品后关闭柜门。",
                "返回首页", ZipPromptModel.ActionRole.HOME,
                "确定", ZipPromptModel.ActionRole.CONFIRM);
        assertPrompt(ZipPromptModel.lockerFailure(target(LockerZone.C, 12)),
                "温馨提示", "C12号柜门开启失败，请再次尝试。",
                "返回首页", ZipPromptModel.ActionRole.HOME,
                "再次尝试", ZipPromptModel.ActionRole.RETRY);
    }

    @Test
    public void legacyIntegerResultBridgeUsesAZoneCustomerLabel() {
        assertEquals("A12号柜门已打开，请存放物品后关闭柜门。",
                ZipPromptModel.lockerSuccess(12).message());
        assertEquals("A7号柜门开启失败，请再次尝试。",
                ZipPromptModel.lockerFailure(7).message());
    }

    @Test
    public void infrastructurePromptsUseOnlyCustomerSafeCopy() {
        assertRetryPrompt(ZipPromptModel.deviceConnectionFailure(),
                "设备连接失败，请联系管理员。");
        assertRetryPrompt(ZipPromptModel.sendFailure(),
                "开柜指令发送失败，请再次尝试。");
        assertRetryPrompt(ZipPromptModel.timeout(),
                "设备无响应，请再次尝试。");
    }

    @Test
    public void everyPromptExposesNoneOfTheForbiddenTechnicalText() {
        ZipPromptModel[] models = {
                ZipPromptModel.unavailable(),
                ZipPromptModel.credentialError(UnlockMethod.PHONE),
                ZipPromptModel.credentialError(UnlockMethod.PASSWORD),
                ZipPromptModel.lockerSuccess(target(LockerZone.A, 1)),
                ZipPromptModel.lockerFailure(target(LockerZone.B, 2)),
                ZipPromptModel.lockerSuccess(target(LockerZone.C, 12)),
                ZipPromptModel.deviceConnectionFailure(),
                ZipPromptModel.sendFailure(),
                ZipPromptModel.timeout()
        };

        for (ZipPromptModel model : models) {
            String visible = (model.title() + " " + model.message()
                    + " " + model.secondaryLabel() + " " + model.primaryLabel())
                    .toLowerCase();
            for (String forbidden : FORBIDDEN_CUSTOMER_TEXT) {
                assertFalse("forbidden customer text: " + forbidden,
                        visible.contains(forbidden));
            }
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void targetSuccessRejectsNull() {
        ZipPromptModel.lockerSuccess((LockerTarget) null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void targetFailureRejectsNull() {
        ZipPromptModel.lockerFailure((LockerTarget) null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void legacyLockerSuccessRejectsOutOfRangeLocker() {
        ZipPromptModel.lockerSuccess(13);
    }

    @Test(expected = IllegalArgumentException.class)
    public void credentialErrorRejectsNonCredentialMethod() {
        ZipPromptModel.credentialError(UnlockMethod.PALM);
    }

    private static LockerTarget target(LockerZone zone, int localLock) {
        return new LockerTarget(
                zone, zone.boardAddress(), localLock,
                FeedbackPolarity.SHORT_WHEN_LOCKED);
    }

    private static void assertRetryPrompt(ZipPromptModel model, String message) {
        assertPrompt(model,
                "温馨提示", message,
                "返回首页", ZipPromptModel.ActionRole.HOME,
                "再次尝试", ZipPromptModel.ActionRole.RETRY);
    }

    private static void assertPrompt(
            ZipPromptModel model,
            String title,
            String message,
            String secondaryLabel,
            ZipPromptModel.ActionRole secondaryRole,
            String primaryLabel,
            ZipPromptModel.ActionRole primaryRole) {
        assertEquals(title, model.title());
        assertEquals(message, model.message());
        assertEquals(secondaryLabel, model.secondaryLabel());
        assertEquals(secondaryRole, model.secondaryRole());
        assertEquals(primaryLabel, model.primaryLabel());
        assertEquals(primaryRole, model.primaryRole());
    }
}
